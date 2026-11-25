// Package regions reads the region outlines Geofabrik publishes next to every extract
// (Osmosis polygon filter format, "<region>.poly"). The importer uses them only for its
// statistics (venues per region).
package regions

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"strconv"
	"strings"

	"github.com/WayneNani/better-maps/services/indoor-server/internal/geo"
)

// Region is a named area made of outer rings and holes.
type Region struct {
	Name  string
	outer []geo.Ring
	holes []geo.Ring
	bbox  geo.BBox
}

// ParsePoly reads a .poly file:
//
//	<name>
//	<section name>        (a name starting with "!" is a hole)
//	   <lon> <lat>
//	   ...
//	END
//	...
//	END
func ParsePoly(name string, r io.Reader) (*Region, error) {
	sc := bufio.NewScanner(r)
	reg := &Region{Name: name, bbox: geo.EmptyBBox()}
	line := 0
	next := func() (string, bool) {
		for sc.Scan() {
			line++
			if s := strings.TrimSpace(sc.Text()); s != "" {
				return s, true
			}
		}
		return "", false
	}
	if _, ok := next(); !ok { // file name line
		return nil, errors.New("empty poly file")
	}
	for {
		sect, ok := next()
		if !ok {
			return nil, errors.New("missing final END")
		}
		if sect == "END" {
			break
		}
		var ring geo.Ring
		for {
			s, ok := next()
			if !ok {
				return nil, fmt.Errorf("line %d: section without END", line)
			}
			if s == "END" {
				break
			}
			f := strings.Fields(s)
			if len(f) != 2 {
				return nil, fmt.Errorf("line %d: expected \"lon lat\"", line)
			}
			lon, err1 := strconv.ParseFloat(f[0], 64)
			lat, err2 := strconv.ParseFloat(f[1], 64)
			if err1 != nil || err2 != nil || lon < -180 || lon > 180 || lat < -90 || lat > 90 {
				return nil, fmt.Errorf("line %d: bad coordinate", line)
			}
			ring = append(ring, geo.Point{Lon: lon, Lat: lat})
		}
		if len(ring) > 1 && ring[0] == ring[len(ring)-1] {
			ring = ring[:len(ring)-1]
		}
		if len(ring) < 3 {
			continue
		}
		if strings.HasPrefix(sect, "!") {
			reg.holes = append(reg.holes, ring)
		} else {
			reg.outer = append(reg.outer, ring)
			for _, p := range ring {
				reg.bbox.Extend(p)
			}
		}
	}
	if err := sc.Err(); err != nil {
		return nil, err
	}
	if len(reg.outer) == 0 {
		return nil, errors.New("no outer ring")
	}
	return reg, nil
}

// Contains reports whether p lies in the region.
func (r *Region) Contains(p geo.Point) bool {
	if !r.bbox.Contains(p) {
		return false
	}
	in := false
	for _, o := range r.outer {
		if geo.PointInRing(p, o) {
			in = true
			break
		}
	}
	if !in {
		return false
	}
	for _, h := range r.holes {
		if geo.PointInRing(p, h) {
			return false
		}
	}
	return true
}
