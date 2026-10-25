package crowd

import (
	"encoding/json"
	"math"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/clientcheck"
	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

func TestParseSegment(t *testing.T) {
	base := straight(0)
	cases := []struct {
		name   string
		f      func(*Segment)
		reason string
	}{
		{"valid", func(*Segment) {}, ""},
		{"no expiry", func(s *Segment) { s.Expires = time.Time{} }, ""},
		{"free flow", func(s *Segment) { s.Congested, s.State = false, "FREE_FLOW" }, skipFreeFlow},
		{"not confident", func(s *Segment) { s.Confident = false }, skipFreeFlow},
		{"tile without zoom", func(s *Segment) { s.Tile = s.Tile[1:] }, skipInvalid},
		{"zoom 16", func(s *Segment) { s.Tile[0] = 16 }, skipInvalid},
		{"negative x", func(s *Segment) { s.Tile[1] = -1 }, skipInvalid},
		{"y out of range", func(s *Segment) { s.Tile[2] = 1 << Zoom }, skipInvalid},
		{"unknown edge", func(s *Segment) { s.Entry = "X" }, skipInvalid},
		{"entry equals exit", func(s *Segment) { s.Exit = "W" }, skipInvalid},
		{"road class OTHER", func(s *Segment) { s.RoadClass = "OTHER" }, skipInvalid},
		{"unknown road class", func(s *Segment) { s.RoadClass = "RESIDENTIAL" }, skipInvalid},
		{"invalid coordinates", func(s *Segment) { s.To.Lat = 95 }, skipInvalid},
		{"congested but free-flow state", func(s *Segment) { s.State = "FREE_FLOW" }, skipInvalid},
		{"negative speed", func(s *Segment) { s.SpeedKmh = -1 }, skipInvalid},
		{"NaN speed", func(s *Segment) { s.SpeedKmh = math.NaN() }, skipInvalid},
		{"expired", func(s *Segment) { s.Expires = t0 }, skipExpired},
	}
	for _, c := range cases {
		s := opts(base, c.f)
		got, reason := parseSegment(&s, t0)
		if reason != c.reason || (got == nil) != (c.reason != "") {
			t.Errorf("%s: reason %q, segment %v", c.name, reason, got)
		}
	}
	got, _ := parseSegment(&base, t0)
	if got.key != "17/70400/43000/W-E/MOTORWAY" || got.severity != 4 || got.length < 180 || got.length > 190 {
		t.Errorf("segment = %+v", got)
	}

	// Duplicate keys and skipped segments are counted.
	_, st := mapSegs(t, base, base, opts(base, func(s *Segment) { s.Congested = false }), opts(base, func(s *Segment) { s.Expires = t0.Add(-time.Minute) }))
	if st.Segments != 4 || st.Congested != 1 || st.Skipped[skipInvalid] != 1 || st.Skipped[skipFreeFlow] != 1 || st.Skipped[skipExpired] != 1 {
		t.Errorf("stats = %+v", st)
	}
}

func TestSides(t *testing.T) {
	for s := north; s <= west; s++ {
		if p, ok := parseSide(s.String()); !ok || p != s || s.opposite().opposite() != s || s.opposite() == s {
			t.Errorf("side %v", s)
		}
		for _, q := range s.perpendicular() {
			if q == s || q == s.opposite() {
				t.Errorf("perpendicular(%v) = %v", s, s.perpendicular())
			}
		}
		if n := (tile{5, 5}).neighbor(s).neighbor(s.opposite()); n != (tile{5, 5}) {
			t.Errorf("neighbor %v", s)
		}
	}
	if (tile{5, 5}).neighbor(north) != (tile{5, 4}) || (tile{5, 5}).neighbor(east) != (tile{6, 5}) {
		t.Error("neighbor orientation")
	}
}

func TestAggregate(t *testing.T) {
	stat := seg(0, 0, "W", 0.5, "E", 0.5, "STATIONARY_TRAFFIC", 5)
	heavy := seg(1, 0, "W", 0.5, "E", 0.5, "HEAVY_TRAFFIC", 50)
	slow := seg(2, 0, "W", 0.5, "E", 0.5, "SLOW_TRAFFIC", 30)
	cases := []struct {
		name  string
		segs  []Segment
		event string
		speed int
	}{
		{"single segment", []Segment{opts(stat, func(s *Segment) { s.SpeedKmh = 6.4 })}, "CONGESTION_STATIONARY_TRAFFIC", 6},
		// Equal lengths: severity (4+1)/2 = 2.5 → queue; speed 2/(1/5+1/50) = 9.1 km/h.
		{"stationary and heavy", []Segment{stat, heavy}, "CONGESTION_QUEUE", 9},
		// (4+1+2)/3 = 2.33 → slow traffic; 3/(1/5+1/50+1/30) = 11.8 km/h.
		{"three states", []Segment{stat, heavy, slow}, "CONGESTION_SLOW_TRAFFIC", 12},
		{"standstill", []Segment{opts(stat, func(s *Segment) { s.SpeedKmh = 0.2 })}, "CONGESTION_STATIONARY_TRAFFIC", 1},
	}
	for _, c := range cases {
		msgs, _ := mapSegs(t, c.segs...)
		if len(msgs) != 1 || len(msgs[0].Events) != 1 {
			t.Fatalf("%s: %+v", c.name, msgs)
		}
		e := msgs[0].Events[0]
		if e.Class != "CONGESTION" || e.Type != c.event || e.Speed == nil || *e.Speed != c.speed {
			t.Errorf("%s: event %s speed %v, want %s %d", c.name, e.Type, *e.Speed, c.event, c.speed)
		}
	}
	// Weighting by length: a long stationary segment outweighs a short heavy one.
	long := &segment{length: 300, severity: 4, speed: 5}
	short := &segment{length: 100, severity: 1, speed: 80}
	if sev, speed := aggregate(chain{short, long}); sev != 3 || speed != 7 {
		t.Errorf("weighted = %d, %d km/h", sev, speed)
	}
	// Degenerate segments of zero length still count.
	if sev, _ := aggregate(chain{{length: 0, severity: 1, speed: 50}, {length: 0, severity: 3, speed: 15}}); sev != 2 {
		t.Errorf("zero length severity = %d", sev)
	}
}

func TestViaIndex(t *testing.T) {
	c := chain{{length: 100}, {length: 100}, {length: 500}, {length: 100}}
	if i := viaIndex(c); i != 1 { // junctions at 100, 200, 700; half = 400
		t.Errorf("via index = %d", i)
	}
}

// loadFixture reads a recorded export.
func loadFixture(t *testing.T, name string) *Document {
	t.Helper()
	data, err := os.ReadFile("testdata/" + name)
	if err != nil {
		t.Fatal(err)
	}
	var doc Document
	if err := json.Unmarshal(data, &doc); err != nil {
		t.Fatal(err)
	}
	return &doc
}

// clientAccepts wraps the messages into a feed and checks it with the client rules.
func clientAccepts(t *testing.T, msgs []traff.Message) {
	t.Helper()
	for i := range msgs {
		msgs[i].ReceiveTime, msgs[i].UpdateTime, msgs[i].ExpirationTime = traff.NewTime(t0), traff.NewTime(t0), traff.NewTime(t0.Add(30*time.Minute))
	}
	data, err := (&traff.Response{Status: "OK", Feed: &traff.Feed{Messages: msgs}}).Marshal()
	if err != nil {
		t.Fatal(err)
	}
	res, err := clientcheck.ParseResponse(data)
	if err != nil {
		t.Fatalf("client rejects the feed: %v", err)
	}
	if len(res.Rejected) != 0 || len(res.Accepted) != len(msgs) || len(res.SkippedEvents) != 0 {
		t.Errorf("client: accepted %d of %d, rejected %v, skipped events %v", len(res.Accepted), len(msgs), res.Rejected, res.SkippedEvents)
	}
}

// TestRecordedExport maps an export recorded from crowd-traffic after a run of crowd-sim (A 100
// jam scenario, window 60 s, seed 7): 105 published segments, 12 of them congested in a row on
// the A 100 eastbound, two of the joins across tile corners.
func TestRecordedExport(t *testing.T) {
	doc := loadFixture(t, "a100_segments.json")
	msgs, st := Map(doc, doc.Generated, MergeOptions{Tolerance: DefaultMergeDistance, MaxLength: DefaultMaxLength})
	if st.Segments != 105 || st.Congested != 12 || st.Skipped[skipFreeFlow] != 93 || st.Merge.Joins != 11 || st.Merge.CornerJoins != 2 {
		t.Errorf("stats = %+v", st)
	}
	if len(msgs) != 1 || msgs[0].ID != "de.crowd:17/70416/43018/W-E/MOTORWAY" {
		t.Fatalf("messages = %v", ids(msgs))
	}
	m := msgs[0]
	if m.Location.From.Coord != (traff.LatLon{Lat: 52.47107, Lon: 13.376246}) || m.Location.To.Coord != (traff.LatLon{Lat: 52.464818, Lon: 13.406067}) ||
		m.Location.Via == nil || m.Events[0].Type != "CONGESTION_STATIONARY_TRAFFIC" || *m.Events[0].Speed != 6 {
		t.Errorf("message = %+v %+v %+v", m.Location, m.Location.Via, m.Events)
	}
	clientAccepts(t, msgs)

	// Without merging: one message per congested tile, all accepted by the client.
	single, _ := Map(doc, doc.Generated, MergeOptions{})
	if len(single) != 12 {
		t.Errorf("%d messages without merging", len(single))
	}
	for _, m := range single {
		if !strings.HasPrefix(m.ID, IDPrefix+"17/") || m.Location.Via != nil {
			t.Errorf("message %+v", m)
		}
	}
	clientAccepts(t, single)

	// 20 minutes after the evaluation everything has expired.
	if gone, st := Map(doc, doc.Generated.Add(21*time.Minute), MergeOptions{Tolerance: 25}); len(gone) != 0 || st.Skipped[skipExpired] != 12 {
		t.Errorf("after expiry: %v, %+v", ids(gone), st)
	}
}
