package traff

import (
	"fmt"
	"strconv"
	"strings"
)

// BBox is a bounding box in WGS84 coordinates.
type BBox struct {
	MinLat, MinLon, MaxLat, MaxLon float64
}

// ParseBBox parses a TraFF bbox attribute. The CoMaps client and the examples in the TraFF
// specification send "minLat minLon maxLat maxLon". Commas are accepted as separators as well.
// If minimum and maximum are swapped, they are put in order.
func ParseBBox(s string) (BBox, error) {
	f := strings.FieldsFunc(s, func(r rune) bool { return r == ' ' || r == ',' || r == '\t' || r == '\n' || r == '\r' })
	if len(f) != 4 {
		return BBox{}, fmt.Errorf("traff: bbox needs 4 values, got %d", len(f))
	}
	var v [4]float64
	for i, x := range f {
		var err error
		if v[i], err = strconv.ParseFloat(x, 64); err != nil {
			return BBox{}, fmt.Errorf("traff: invalid bbox value %q", x)
		}
	}
	b := BBox{MinLat: min(v[0], v[2]), MinLon: min(v[1], v[3]), MaxLat: max(v[0], v[2]), MaxLon: max(v[1], v[3])}
	if !(LatLon{b.MinLat, b.MinLon}).Valid() || !(LatLon{b.MaxLat, b.MaxLon}).Valid() {
		return BBox{}, fmt.Errorf("traff: bbox out of range")
	}
	return b, nil
}

// String formats the bbox as a TraFF attribute value.
func (b BBox) String() string {
	return fmt.Sprintf("%+.6f %+.6f %+.6f %+.6f", b.MinLat, b.MinLon, b.MaxLat, b.MaxLon)
}

// Intersects reports whether the two boxes have at least one point in common.
func (b BBox) Intersects(o BBox) bool {
	return b.MinLat <= o.MaxLat && o.MinLat <= b.MaxLat && b.MinLon <= o.MaxLon && o.MinLon <= b.MaxLon
}

// Contains reports whether o lies completely within b.
func (b BBox) Contains(o BBox) bool {
	return b.MinLat <= o.MinLat && b.MaxLat >= o.MaxLat && b.MinLon <= o.MinLon && b.MaxLon >= o.MaxLon
}

// Extend returns the smallest box containing b and p.
func (b BBox) Extend(p LatLon) BBox {
	return BBox{MinLat: min(b.MinLat, p.Lat), MinLon: min(b.MinLon, p.Lon), MaxLat: max(b.MaxLat, p.Lat), MaxLon: max(b.MaxLon, p.Lon)}
}

// Union returns the smallest box containing both boxes.
func (b BBox) Union(o BBox) BBox {
	return b.Extend(LatLon{o.MinLat, o.MinLon}).Extend(LatLon{o.MaxLat, o.MaxLon})
}

// Filter is a single TraFF filter item. A nil BBox or empty MinRoadClass does not constrain the
// result.
type Filter struct {
	BBox         *BBox
	MinRoadClass RoadClass
}

// MatchesArea reports whether a message with bounding box mb matches the area criterion.
func (f Filter) MatchesArea(mb BBox) bool { return f.BBox == nil || f.BBox.Intersects(mb) }

// MatchesRoadClass reports whether a message with road class rc matches the road class criterion.
// Messages without a road class always match (TraFF 0.8, section 2.2).
func (f Filter) MatchesRoadClass(rc RoadClass) bool {
	if f.MinRoadClass == "" || rc.Rank() < 0 {
		return true
	}
	return rc.Rank() >= f.MinRoadClass.Rank()
}

// FilterList is a list of filter items. A message matches if it matches at least one item.
type FilterList []Filter

// Matches reports whether a message with bounding box mb and road class rc matches the list. An
// empty list matches nothing.
func (fl FilterList) Matches(mb BBox, rc RoadClass) bool {
	for _, f := range fl {
		if f.MatchesArea(mb) && f.MatchesRoadClass(rc) {
			return true
		}
	}
	return false
}

// Coverage classifies a filter list against a coverage area. It returns "covered" if every filter
// area lies within one of the coverage boxes, "partial" if at least one filter area intersects the
// coverage, and "none" otherwise. An empty coverage means global coverage.
func (fl FilterList) Coverage(coverage []BBox) string {
	if len(coverage) == 0 {
		return "covered"
	}
	anyHit, allIn := false, true
	for _, f := range fl {
		if f.BBox == nil {
			anyHit, allIn = true, false
			continue
		}
		in, hit := false, false
		for _, c := range coverage {
			if c.Contains(*f.BBox) {
				in = true
			}
			if c.Intersects(*f.BBox) {
				hit = true
			}
		}
		anyHit = anyHit || hit
		allIn = allIn && in
	}
	switch {
	case !anyHit:
		return "none"
	case allIn:
		return "covered"
	default:
		return "partial"
	}
}
