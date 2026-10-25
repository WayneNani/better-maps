// Package crowd implements the source for crowd-sourced congestion data from
// services/crowd-traffic.
//
// The source downloads the JSON export GET /v1/segments (version 1) of the crowd-traffic server
// as a complete snapshot, keeps the congested segments (one per zoom-17 tile passage), joins
// adjacent segments of the same road class and direction into chains and turns every chain into
// one TraFF message. Segments that disappear from the export are cancelled by the store.
//
// The JSON export is used instead of the TraFF feed GET /v1/traff because it carries the
// structure that is needed for merging (tile, entry and exit edge, road class) as separate
// fields instead of inside an opaque message ID, the unrounded speed and the expiry of every
// segment, and an explicit format version.
package crowd

import (
	"fmt"
	"math"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

// IDPrefix is the prefix of all message IDs of this source.
const IDPrefix = "de.crowd:"

// FormatVersion is the version of the JSON export this package understands.
const FormatVersion = 1

// Zoom is the zoom level of the tiles that make up the segment keys.
const Zoom = 17

// Document is the JSON export of the crowd-traffic server (GET /v1/segments). Only the fields
// used here are declared; unknown fields are ignored.
type Document struct {
	Version   int       `json:"version"`
	Generated time.Time `json:"generated"`
	Segments  []Segment `json:"segments"`
}

// LatLon is a coordinate pair of the export.
type LatLon struct {
	Lat float64 `json:"lat"`
	Lon float64 `json:"lon"`
}

// Segment is one published segment: the passage through a tile from one edge to another.
type Segment struct {
	ID        string    `json:"id"`
	Tile      []int     `json:"tile"` // zoom, x, y
	Entry     string    `json:"entry"`
	Exit      string    `json:"exit"`
	RoadClass string    `json:"road_class"`
	From      LatLon    `json:"from"`
	To        LatLon    `json:"to"`
	Reports   int       `json:"reports"`
	SpeedKmh  float64   `json:"speed_kmh"`
	State     string    `json:"state"`
	Confident bool      `json:"confident"`
	Congested bool      `json:"congested"`
	Expires   time.Time `json:"expires"`
}

// side is a tile edge.
type side uint8

const (
	north side = iota
	east
	south
	west
)

var sideNames = [...]string{"N", "E", "S", "W"}

func (s side) String() string { return sideNames[s] }

func parseSide(v string) (side, bool) {
	for i, n := range sideNames {
		if n == v {
			return side(i), true
		}
	}
	return 0, false
}

func (s side) opposite() side { return (s + 2) % 4 }

// perpendicular returns the two edges at right angles to s.
func (s side) perpendicular() [2]side { return [2]side{(s + 1) % 4, (s + 3) % 4} }

// tile is a zoom-17 tile.
type tile struct{ x, y int }

// neighbor returns the tile across edge s (y grows southwards).
func (t tile) neighbor(s side) tile {
	switch s {
	case north:
		t.y--
	case east:
		t.x++
	case south:
		t.y++
	default:
		t.x--
	}
	return t
}

// Congestion states in increasing severity; the index is the severity.
var stateNames = [...]string{"", "HEAVY_TRAFFIC", "SLOW_TRAFFIC", "QUEUE", "STATIONARY_TRAFFIC"}

func severityOf(state string) int {
	for i, n := range stateNames {
		if i > 0 && n == state {
			return i
		}
	}
	return 0
}

// segment is a validated congested segment.
type segment struct {
	key        string // "17/x/y/E-A/CLASS", unique per segment
	tile       tile
	entry      side
	exit       side
	class      traff.RoadClass
	from, to   traff.LatLon
	severity   int     // 1 (heavy traffic) … 4 (stationary traffic)
	speed      float64 // km/h
	length     float64 // metres, straight line from → to
	expires    time.Time
	successors []*segment
	preds      []*segment
}

// Reasons for skipping a segment of the export.
const (
	skipFreeFlow = "skipped_free_flow"
	skipInvalid  = "skipped_invalid"
	skipExpired  = "skipped_expired"
)

// parseSegment validates a segment of the export. It returns the reason if the segment is not
// used.
func parseSegment(s *Segment, now time.Time) (*segment, string) {
	if !s.Congested || !s.Confident {
		return nil, skipFreeFlow
	}
	if len(s.Tile) != 3 || s.Tile[0] != Zoom || s.Tile[1] < 0 || s.Tile[2] < 0 || s.Tile[1] >= 1<<Zoom || s.Tile[2] >= 1<<Zoom {
		return nil, skipInvalid
	}
	entry, ok1 := parseSide(s.Entry)
	exit, ok2 := parseSide(s.Exit)
	if !ok1 || !ok2 || entry == exit {
		return nil, skipInvalid
	}
	class, err := traff.ParseRoadClass(s.RoadClass)
	if err != nil || class == traff.RoadClassOther {
		return nil, skipInvalid
	}
	from, to := traff.LatLon{Lat: s.From.Lat, Lon: s.From.Lon}, traff.LatLon{Lat: s.To.Lat, Lon: s.To.Lon}
	sev := severityOf(s.State)
	if !from.Valid() || !to.Valid() || sev == 0 || math.IsNaN(s.SpeedKmh) || s.SpeedKmh < 0 {
		return nil, skipInvalid
	}
	if !s.Expires.IsZero() && !s.Expires.After(now) {
		return nil, skipExpired
	}
	t := tile{s.Tile[1], s.Tile[2]}
	return &segment{
		key:      segmentKey(t, entry, exit, class),
		tile:     t,
		entry:    entry,
		exit:     exit,
		class:    class,
		from:     from,
		to:       to,
		severity: sev,
		speed:    s.SpeedKmh,
		length:   distance(from, to),
		expires:  s.Expires,
	}, ""
}

// segmentKey returns the key of a segment as used by the crowd-traffic server, e.g.
// "17/70420/43010/W-E/MOTORWAY".
func segmentKey(t tile, entry, exit side, class traff.RoadClass) string {
	return fmt.Sprintf("%d/%d/%d/%s-%s/%s", Zoom, t.x, t.y, entry, exit, class)
}

// distance returns the great-circle distance between two points in metres.
func distance(a, b traff.LatLon) float64 {
	const r = 6371008.8
	rad := math.Pi / 180
	dLat := (b.Lat - a.Lat) * rad
	dLon := (b.Lon - a.Lon) * rad
	h := math.Sin(dLat/2)*math.Sin(dLat/2) + math.Cos(a.Lat*rad)*math.Cos(b.Lat*rad)*math.Sin(dLon/2)*math.Sin(dLon/2)
	return 2 * r * math.Asin(math.Min(1, math.Sqrt(h)))
}
