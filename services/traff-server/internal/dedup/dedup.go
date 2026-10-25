// Package dedup recognizes messages from different sources that describe the same traffic event,
// e.g. a closure reported both by the Autobahn API and by a DATEX II feed of a traffic
// information centre.
//
// The heuristic is deliberately simple and conservative. Two messages are duplicates if all of
// the following hold:
//
//  1. Same road: both have a road reference and the references are equal after normalization
//     ("A 8", "A8" and "a8" are equal). Messages without road reference are never duplicates.
//  2. Same kind of event: the event categories overlap (see Category).
//  3. Spatial proximity: the shortest distance between the two locations (polylines through
//     from, via and to, or the point at) is at most MaxDistance.
//  4. Same direction: if both messages affect one direction only and both have a measurable
//     extent, their directions differ by at most 90°.
//  5. Overlapping time: the validity intervals [start_time, end_time] overlap; a missing start
//     or end is open.
//
// The runner applies the heuristic in a merge step before the messages of a source are stored:
// a message is suppressed if it duplicates an active message of a source with higher priority.
package dedup

import (
	"math"
	"strings"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

// DefaultMaxDistance is the default value of Config.MaxDistance in metres.
const DefaultMaxDistance = 500

// Config holds the parameters of the heuristic.
type Config struct {
	// MaxDistance is the largest distance in metres between two locations that are considered
	// the same place.
	MaxDistance float64
}

// Matcher compares messages.
type Matcher struct {
	cfg Config
}

// New creates a matcher. A non-positive MaxDistance is replaced by DefaultMaxDistance.
func New(cfg Config) *Matcher {
	if cfg.MaxDistance <= 0 {
		cfg.MaxDistance = DefaultMaxDistance
	}
	return &Matcher{cfg: cfg}
}

// Event categories.
const (
	CatIncident   = "incident"   // INCIDENT_*, HAZARD_*
	CatCongestion = "congestion" // CONGESTION_*, DELAY_*
	CatWorks      = "works"      // CONSTRUCTION_*, and RESTRICTION_* without other events
	CatWeather    = "weather"    // WEATHER_*, ENVIRONMENT_*
)

// Categories returns the event categories of a message. Restrictions (closures, lane closures,
// speed limits) are consequences of other events and are only taken into account if the
// message has no other events: a lane closure caused by an accident must not match roadworks
// with a lane closure nearby.
func Categories(m *traff.Message) map[string]bool {
	res := map[string]bool{}
	restriction := false
	for _, e := range m.Events {
		switch e.Class {
		case "INCIDENT", "HAZARD":
			res[CatIncident] = true
		case "CONGESTION", "DELAY":
			res[CatCongestion] = true
		case "CONSTRUCTION":
			res[CatWorks] = true
		case "WEATHER", "ENVIRONMENT":
			res[CatWeather] = true
		case "RESTRICTION":
			restriction = true
		default:
			res[strings.ToLower(e.Class)] = true
		}
	}
	if len(res) == 0 && restriction {
		res[CatWorks] = true
	}
	return res
}

// NormalizeRoad normalizes a road reference for comparison ("A 8" -> "A8").
func NormalizeRoad(ref string) string {
	return strings.Map(func(r rune) rune {
		if r == ' ' || r == '-' || r == '\t' {
			return -1
		}
		return r
	}, strings.ToUpper(strings.TrimSpace(ref)))
}

// features are the precomputed properties of a message.
type features struct {
	road       string
	cats       map[string]bool
	line       []xy // local planar coordinates in metres
	both       bool
	start, end time.Time
}

type xy struct{ x, y float64 }

// Reference latitude for the planar projection. Germany spans 47°–55° N; the error of a fixed
// reference latitude is far below the distances that matter here.
const refLat = 51.0

var cosRef = math.Cos(refLat * math.Pi / 180)

func project(p traff.LatLon) xy {
	return xy{x: p.Lon * 111320 * cosRef, y: p.Lat * 110574}
}

func featuresOf(m *traff.Message) *features {
	if m.Location == nil {
		return nil
	}
	f := &features{road: NormalizeRoad(m.Location.RoadRef), cats: Categories(m), both: m.Location.Directionality == traff.BothDirections}
	for _, p := range []*traff.Point{m.Location.From, m.Location.Via, m.Location.To, m.Location.At} {
		if p != nil {
			f.line = append(f.line, project(p.Coord))
		}
	}
	if m.StartTime != nil {
		f.start = m.StartTime.Time
	}
	if m.EndTime != nil {
		f.end = m.EndTime.Time
	}
	if f.road == "" || len(f.line) == 0 {
		return nil
	}
	return f
}

// Duplicate reports whether a and b describe the same event.
func (mt *Matcher) Duplicate(a, b *traff.Message) bool {
	fa, fb := featuresOf(a), featuresOf(b)
	return fa != nil && fb != nil && mt.dup(fa, fb)
}

func (mt *Matcher) dup(a, b *features) bool {
	if a.road != b.road || !overlap(a.cats, b.cats) || !timesOverlap(a, b) {
		return false
	}
	if lineDistance(a.line, b.line) > mt.cfg.MaxDistance {
		return false
	}
	return a.both || b.both || sameDirection(a.line, b.line)
}

func overlap(a, b map[string]bool) bool {
	for k := range a {
		if b[k] {
			return true
		}
	}
	return false
}

func timesOverlap(a, b *features) bool {
	if !a.start.IsZero() && !b.end.IsZero() && !a.start.Before(b.end) {
		return false
	}
	if !b.start.IsZero() && !a.end.IsZero() && !b.start.Before(a.end) {
		return false
	}
	return true
}

// minExtent is the minimum length (metres) a location needs for its direction to be compared.
const minExtent = 50

func sameDirection(a, b []xy) bool {
	da, db := sub(a[len(a)-1], a[0]), sub(b[len(b)-1], b[0])
	la, lb := math.Hypot(da.x, da.y), math.Hypot(db.x, db.y)
	if la < minExtent || lb < minExtent {
		return true
	}
	return (da.x*db.x+da.y*db.y)/(la*lb) >= 0 // angle <= 90°
}

func sub(a, b xy) xy { return xy{a.x - b.x, a.y - b.y} }

// lineDistance returns the shortest distance between two polylines (a single point is a
// degenerate polyline).
func lineDistance(a, b []xy) float64 {
	best := math.Inf(1)
	for i := range max(1, len(a)-1) {
		a1, a2 := a[i], a[min(i+1, len(a)-1)]
		for j := range max(1, len(b)-1) {
			b1, b2 := b[j], b[min(j+1, len(b)-1)]
			best = min(best, segmentDistance(a1, a2, b1, b2))
		}
	}
	return best
}

func segmentDistance(a1, a2, b1, b2 xy) float64 {
	if segmentsIntersect(a1, a2, b1, b2) {
		return 0
	}
	return min(pointSegment(a1, b1, b2), pointSegment(a2, b1, b2), pointSegment(b1, a1, a2), pointSegment(b2, a1, a2))
}

func pointSegment(p, s1, s2 xy) float64 {
	d := sub(s2, s1)
	l2 := d.x*d.x + d.y*d.y
	t := 0.0
	if l2 > 0 {
		t = max(0, min(1, ((p.x-s1.x)*d.x+(p.y-s1.y)*d.y)/l2))
	}
	return math.Hypot(p.x-(s1.x+t*d.x), p.y-(s1.y+t*d.y))
}

func cross(o, a, b xy) float64 { return (a.x-o.x)*(b.y-o.y) - (a.y-o.y)*(b.x-o.x) }

func segmentsIntersect(a1, a2, b1, b2 xy) bool {
	d1, d2 := cross(b1, b2, a1), cross(b1, b2, a2)
	d3, d4 := cross(a1, a2, b1), cross(a1, a2, b2)
	return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
}

// Filter removes the messages from msgs that duplicate one of ref (the active messages of
// sources with higher priority). It returns the remaining messages and, for every suppressed
// message, the ID of the message it duplicates.
func (mt *Matcher) Filter(msgs, ref []traff.Message) (kept []traff.Message, suppressed map[string]string) {
	byRoad := map[string][]refFeature{}
	for i := range ref {
		if f := featuresOf(&ref[i]); f != nil {
			byRoad[f.road] = append(byRoad[f.road], refFeature{id: ref[i].ID, f: f})
		}
	}
	suppressed = map[string]string{}
	kept = make([]traff.Message, 0, len(msgs))
	for i := range msgs {
		f := featuresOf(&msgs[i])
		if f != nil {
			if id, ok := mt.match(f, byRoad[f.road]); ok {
				suppressed[msgs[i].ID] = id
				continue
			}
		}
		kept = append(kept, msgs[i])
	}
	return kept, suppressed
}

type refFeature struct {
	id string
	f  *features
}

func (mt *Matcher) match(f *features, cands []refFeature) (string, bool) {
	for _, c := range cands {
		if mt.dup(c.f, f) {
			return c.id, true
		}
	}
	return "", false
}
