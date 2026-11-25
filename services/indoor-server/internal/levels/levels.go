// Package levels parses the "level" and "repeat_on" values of OSM Simple Indoor Tagging.
//
// Accepted forms (whitespace around values and separators is ignored):
//
//	"0", "-1", "0.5", "1.0"      single level (decimal point, not comma)
//	"0;1", "-1;0;1"              list, ";" separated (empty items such as a trailing ";" are ignored)
//	"0-2", "-2--1", "-1-1"       range: both ends plus every integer between them
//	"0.5-2"                      fractional end: {0.5, 1, 2} (the ends are kept, the steps are integers)
//	"2-0"                        reversed range: same as "0-2"
//	"1,2", "-1,0,1"              comma list, only when unambiguous: all items integers and consecutive
//	                             ascending; "0,5" (probably a decimal comma) and "1,3" are errors
//	"0;2-3"                      lists may contain ranges
//
// Everything else ("EG", "1a", "roof", "", "1,5") is an error. A result may hold at most
// MaxLevels levels, each within ±MaxAbs. The result is sorted ascending without duplicates.
package levels

import (
	"errors"
	"fmt"
	"math"
	"regexp"
	"slices"
	"strconv"
	"strings"
)

// MaxLevels caps the number of levels of one value (a range "0-100" is an error, not 101 levels).
const MaxLevels = 20

// MaxAbs bounds a single level number.
const MaxAbs = 100

var (
	reNumber = regexp.MustCompile(`^-?\d+(?:\.\d+)?$`)
	reRange  = regexp.MustCompile(`^(-?\d+(?:\.\d+)?)\s*-\s*(-?\d+(?:\.\d+)?)$`)
	reInt    = regexp.MustCompile(`^-?\d+$`)
)

// ErrEmpty is returned for empty values.
var ErrEmpty = errors.New("empty level")

// Parse parses a level or repeat_on value.
func Parse(s string) ([]float64, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return nil, ErrEmpty
	}
	var parts []string
	switch {
	case strings.Contains(s, ";"):
		parts = strings.Split(s, ";")
	case strings.Contains(s, ","):
		var err error
		if parts, err = commaList(s); err != nil {
			return nil, err
		}
	default:
		parts = []string{s}
	}
	var out []float64
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		vs, err := parsePart(p)
		if err != nil {
			return nil, err
		}
		out = append(out, vs...)
		if len(out) > 4*MaxLevels {
			slices.Sort(out)
			if out = slices.Compact(out); len(out) > MaxLevels {
				return nil, fmt.Errorf("level %q: more than %d levels", s, MaxLevels)
			}
		}
	}
	if len(out) == 0 {
		return nil, ErrEmpty
	}
	slices.Sort(out)
	out = slices.Compact(out)
	if len(out) > MaxLevels {
		return nil, fmt.Errorf("level %q: more than %d levels", s, MaxLevels)
	}
	return out, nil
}

// commaList accepts "1,2" and "-1,0,1" (consecutive ascending integers) and rejects everything
// else with a comma, because "0,5" is more likely a decimal comma than the levels 0 and 5.
func commaList(s string) ([]string, error) {
	parts := strings.Split(s, ",")
	prev := 0
	for i, p := range parts {
		p = strings.TrimSpace(p)
		if !reInt.MatchString(p) {
			return nil, fmt.Errorf("level %q: ambiguous comma", s)
		}
		v, err := strconv.Atoi(p)
		if err != nil {
			return nil, fmt.Errorf("level %q: ambiguous comma", s)
		}
		if i > 0 && v != prev+1 {
			return nil, fmt.Errorf("level %q: ambiguous comma", s)
		}
		prev = v
		parts[i] = p
	}
	return parts, nil
}

func parsePart(p string) ([]float64, error) {
	if reNumber.MatchString(p) {
		v, err := number(p)
		if err != nil {
			return nil, err
		}
		return []float64{v}, nil
	}
	m := reRange.FindStringSubmatch(p)
	if m == nil {
		return nil, fmt.Errorf("level %q: not a number or range", p)
	}
	lo, err := number(m[1])
	if err != nil {
		return nil, err
	}
	hi, err := number(m[2])
	if err != nil {
		return nil, err
	}
	if lo > hi {
		lo, hi = hi, lo
	}
	if hi-lo > MaxLevels {
		return nil, fmt.Errorf("level range %q: more than %d levels", p, MaxLevels)
	}
	out := []float64{lo}
	for v := math.Ceil(lo); v <= hi; v++ {
		if v != lo {
			out = append(out, v)
		}
	}
	if out[len(out)-1] != hi {
		out = append(out, hi)
	}
	return out, nil
}

func number(s string) (float64, error) {
	v, err := strconv.ParseFloat(s, 64)
	if err != nil || math.IsNaN(v) || math.IsInf(v, 0) {
		return 0, fmt.Errorf("level %q: not a number", s)
	}
	if math.Abs(v) > MaxAbs {
		return 0, fmt.Errorf("level %q: out of range ±%d", s, MaxAbs)
	}
	if v == 0 {
		v = 0 // no negative zero
	}
	return v, nil
}

// Union returns the sorted union of a and b (both sorted).
func Union(a, b []float64) []float64 {
	out := make([]float64, 0, len(a)+len(b))
	out = append(out, a...)
	out = append(out, b...)
	slices.Sort(out)
	return slices.Compact(out)
}

// Format formats a level without trailing zeros: "0", "-1", "0.5".
func Format(v float64) string {
	if v == 0 {
		return "0"
	}
	return strconv.FormatFloat(v, 'f', -1, 64)
}
