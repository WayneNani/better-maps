// Package protocol defines the wire format of crowd-traffic reports (version 1) and the rules
// the gateway applies to them. docs/CROWD_TRAFFIC_PRIVACY.md ("Client-Vertrag") is the normative
// description; the constants here must stay in sync with it.
package protocol

import (
	"bytes"
	"encoding/json"
	"fmt"
	"math"
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/tiles"
)

// Protocol constants shared by client and server.
const (
	// Version is the bundle format version.
	Version = 1
	// WindowSeconds is the length of an aggregation window. Windows start at multiples of it
	// (Unix time, UTC).
	WindowSeconds = 900
	// MaxReports is the maximum number of reports in a bundle (and per client and window).
	MaxReports = 12
	// NumBuckets is the number of speed buckets.
	NumBuckets = 7
	// Epsilon is the local differential privacy parameter of the randomized response applied
	// to the speed bucket of every report.
	Epsilon = 3.0
	// PadTo is the size to which clients pad the binary HTTP request (RFC 9292 padding), so that
	// all encapsulated requests have the same length.
	PadTo = 4096
	// MaxBodyBytes limits the JSON body of a bundle.
	MaxBodyBytes = 8192
	// ReportPath is the target path of bundles inside the encapsulated request.
	ReportPath = "/v1/reports"
	// ContentType of a bundle.
	ContentType = "application/json"
)

// SpeedBucketEdges are the bucket boundaries in km/h: bucket i covers
// [SpeedBucketEdges[i], SpeedBucketEdges[i+1]). The last bucket is open-ended; the upper value
// is only used as interpolation limit by the server.
var SpeedBucketEdges = [NumBuckets + 1]float64{0, 10, 20, 35, 55, 80, 110, 140}

// BucketOf returns the speed bucket of a speed in km/h.
func BucketOf(kmh float64) int {
	for i := NumBuckets - 1; i > 0; i-- {
		if kmh >= SpeedBucketEdges[i] {
			return i
		}
	}
	return 0
}

// RoadClass is the class of the road a segment belongs to. Only these classes are reported;
// links (ramps), residential and smaller roads are never reported.
type RoadClass uint8

// Road classes; the names match TraFF road classes.
const (
	Motorway RoadClass = iota + 1
	Trunk
	Primary
	Secondary
	Tertiary
)

var roadClassNames = [...]string{"", "MOTORWAY", "TRUNK", "PRIMARY", "SECONDARY", "TERTIARY"}

// Valid reports whether c is a reportable road class.
func (c RoadClass) Valid() bool { return c >= Motorway && c <= Tertiary }

// String returns the TraFF name of the road class.
func (c RoadClass) String() string {
	if !c.Valid() {
		return fmt.Sprintf("RoadClass(%d)", uint8(c))
	}
	return roadClassNames[c]
}

// MarshalText implements encoding.TextMarshaler.
func (c RoadClass) MarshalText() ([]byte, error) {
	if !c.Valid() {
		return nil, fmt.Errorf("protocol: invalid road class %d", uint8(c))
	}
	return []byte(roadClassNames[c]), nil
}

// UnmarshalText implements encoding.TextUnmarshaler.
func (c *RoadClass) UnmarshalText(b []byte) error {
	for i := Motorway; i <= Tertiary; i++ {
		if roadClassNames[i] == string(b) {
			*c = i
			return nil
		}
	}
	return fmt.Errorf("protocol: invalid road class %q", b)
}

// Edge is a crossing point on a tile edge.
type Edge struct {
	Side   tiles.Side `json:"side"`
	Offset int        `json:"offset"`
}

// Report is a single observation: a vehicle crossed tile Tile, entering at Entry and leaving at
// Exit, on a road of class RoadClass, with an average speed whose (noisy) bucket is
// SpeedBucket. Window is the start of the aggregation window in which the vehicle left the tile.
type Report struct {
	Window      int64      `json:"window"`
	Tile        tiles.Tile `json:"tile"`
	Entry       Edge       `json:"entry"`
	Exit        Edge       `json:"exit"`
	RoadClass   RoadClass  `json:"road_class"`
	SpeedBucket int        `json:"speed_bucket"`
}

// Key identifies a segment independently of the map version: a movement through a tile from one
// edge to another on a road of a given class.
type Key struct {
	Tile  tiles.Tile
	Entry tiles.Side
	Exit  tiles.Side
	Class RoadClass
}

// Key returns the segment key of the report.
func (r Report) Key() Key {
	return Key{Tile: r.Tile, Entry: r.Entry.Side, Exit: r.Exit.Side, Class: r.RoadClass}
}

// String returns a stable textual form, e.g. "17/70406/42977/W-E/MOTORWAY".
func (k Key) String() string {
	return fmt.Sprintf("%s/%s-%s/%s", k.Tile, k.Entry, k.Exit, k.Class)
}

// Bundle is the body of a report submission.
type Bundle struct {
	Version int      `json:"version"`
	Reports []Report `json:"reports"`
}

// Encode returns the JSON encoding of the bundle.
func (b *Bundle) Encode() ([]byte, error) { return json.Marshal(b) }

// Decode parses a bundle strictly: unknown fields, trailing data and oversized input are errors.
func Decode(data []byte) (*Bundle, error) {
	if len(data) > MaxBodyBytes {
		return nil, &ValidationError{Reason: ReasonTooLarge}
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	var b Bundle
	if err := dec.Decode(&b); err != nil {
		return nil, &ValidationError{Reason: ReasonSyntax, Detail: err.Error()}
	}
	if dec.More() {
		return nil, &ValidationError{Reason: ReasonSyntax, Detail: "trailing data"}
	}
	return &b, nil
}

// Reason is a machine-readable rejection reason. Reasons are used as counter names; they never
// contain report data.
type Reason string

// Rejection reasons.
const (
	ReasonTooLarge     Reason = "too_large"
	ReasonSyntax       Reason = "syntax"
	ReasonVersion      Reason = "version"
	ReasonEmpty        Reason = "empty"
	ReasonTooMany      Reason = "too_many"
	ReasonMixedWindows Reason = "mixed_windows"
	ReasonMisaligned   Reason = "window_misaligned"
	ReasonLate         Reason = "window_late"
	ReasonFuture       Reason = "window_future"
	ReasonTile         Reason = "tile"
	ReasonSide         Reason = "side"
	ReasonOffset       Reason = "offset"
	ReasonRoadClass    Reason = "road_class"
	ReasonSpeedBucket  Reason = "speed_bucket"
	ReasonDuplicate    Reason = "duplicate_key"
	ReasonSameEdge     Reason = "same_edge_position"
	ReasonCoverage     Reason = "coverage"
	ReasonSpread       Reason = "spread"
)

// ValidationError reports why a bundle was rejected.
type ValidationError struct {
	Reason Reason
	Detail string
}

func (e *ValidationError) Error() string {
	if e.Detail == "" {
		return "invalid bundle: " + string(e.Reason)
	}
	return "invalid bundle: " + string(e.Reason) + ": " + e.Detail
}

// BBox is a coverage area in WGS84 degrees.
type BBox struct {
	MinLat, MinLon, MaxLat, MaxLon float64
}

// Contains reports whether the point lies inside the box.
func (b BBox) Contains(lat, lon float64) bool {
	return lat >= b.MinLat && lat <= b.MaxLat && lon >= b.MinLon && lon <= b.MaxLon
}

// Germany is the default coverage area (Germany with a margin of a few kilometres).
var Germany = BBox{MinLat: 47.2, MinLon: 5.8, MaxLat: 55.1, MaxLon: 15.1}

// Rules are the acceptance rules of the server.
type Rules struct {
	// Window is the window length (WindowSeconds in production; shorter for tests).
	Window time.Duration
	// Grace is how long after the end of a window reports for it are still accepted.
	Grace time.Duration
	// Coverage limits the accepted tiles; nil accepts the whole world.
	Coverage *BBox
	// MaxSpread is the maximum distance between two tiles of a bundle, in tiles per axis.
	// A client covers at most ~60 km in one window, i.e. ~320 tiles in Germany.
	MaxSpread int
}

// DefaultRules returns the production rules.
func DefaultRules() Rules {
	c := Germany
	return Rules{Window: WindowSeconds * time.Second, Grace: 5 * time.Minute, Coverage: &c, MaxSpread: 400}
}

// WindowStart returns the start of the window containing t, in Unix seconds.
func (r Rules) WindowStart(t time.Time) int64 {
	w := int64(r.Window / time.Second)
	s := t.Unix()
	return s - ((s%w)+w)%w
}

// Validate checks a bundle received at time now. Bundles are accepted or rejected as a whole:
// honest clients never send invalid reports, so one invalid report makes the bundle suspect.
//
// The speed bucket is checked only for its value range. Plausibility checks must never depend
// on the noisy bucket (e.g. "130 km/h on a tertiary road"), because dropping noisy values
// selectively would bias the de-noising estimator.
func (r Rules) Validate(b *Bundle, now time.Time) error {
	if b.Version != Version {
		return &ValidationError{Reason: ReasonVersion}
	}
	if len(b.Reports) == 0 {
		return &ValidationError{Reason: ReasonEmpty}
	}
	if len(b.Reports) > MaxReports {
		return &ValidationError{Reason: ReasonTooMany}
	}
	w := int64(r.Window / time.Second)
	window := b.Reports[0].Window
	cur := r.WindowStart(now)
	switch {
	case window%w != 0:
		return &ValidationError{Reason: ReasonMisaligned}
	case window > cur:
		return &ValidationError{Reason: ReasonFuture}
	case window < cur-w || (window == cur-w && !now.Before(time.Unix(cur, 0).Add(r.Grace))):
		return &ValidationError{Reason: ReasonLate}
	}
	seen := make(map[Key]bool, len(b.Reports))
	minX, minY, maxX, maxY := math.MaxInt, math.MaxInt, math.MinInt, math.MinInt
	for _, rep := range b.Reports {
		if rep.Window != window {
			return &ValidationError{Reason: ReasonMixedWindows}
		}
		if !rep.Tile.Valid() {
			return &ValidationError{Reason: ReasonTile}
		}
		if !rep.Entry.Side.Valid() || !rep.Exit.Side.Valid() {
			return &ValidationError{Reason: ReasonSide}
		}
		if rep.Entry.Offset < 0 || rep.Entry.Offset >= tiles.Size || rep.Exit.Offset < 0 || rep.Exit.Offset >= tiles.Size {
			return &ValidationError{Reason: ReasonOffset}
		}
		if rep.Entry == rep.Exit {
			return &ValidationError{Reason: ReasonSameEdge}
		}
		if !rep.RoadClass.Valid() {
			return &ValidationError{Reason: ReasonRoadClass}
		}
		if rep.SpeedBucket < 0 || rep.SpeedBucket >= NumBuckets {
			return &ValidationError{Reason: ReasonSpeedBucket}
		}
		if r.Coverage != nil && !r.Coverage.Contains(rep.Tile.Center()) {
			return &ValidationError{Reason: ReasonCoverage}
		}
		k := rep.Key()
		if seen[k] {
			return &ValidationError{Reason: ReasonDuplicate}
		}
		seen[k] = true
		minX, maxX = min(minX, rep.Tile.X), max(maxX, rep.Tile.X)
		minY, maxY = min(minY, rep.Tile.Y), max(maxY, rep.Tile.Y)
	}
	if r.MaxSpread > 0 && (maxX-minX > r.MaxSpread || maxY-minY > r.MaxSpread) {
		return &ValidationError{Reason: ReasonSpread}
	}
	return nil
}
