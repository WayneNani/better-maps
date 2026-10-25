package dedup

import (
	"math"
	"os"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/source/datex2"
	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

var t0 = time.Date(2026, 10, 25, 16, 0, 0, 0, time.UTC)

type msgOpt func(*traff.Message)

func msg(id, road string, events []string, pts ...[2]float64) traff.Message {
	m := traff.Message{ID: id, Location: &traff.Location{RoadRef: road, Directionality: traff.OneDirection}}
	for _, e := range events {
		class := e[:indexAfterClass(e)-1]
		m.Events = append(m.Events, traff.Event{Class: class, Type: e})
	}
	switch len(pts) {
	case 1:
		m.Location.At = traff.NewPoint(pts[0][0], pts[0][1])
	case 2:
		m.Location.From, m.Location.To = traff.NewPoint(pts[0][0], pts[0][1]), traff.NewPoint(pts[1][0], pts[1][1])
	case 3:
		m.Location.From, m.Location.Via, m.Location.To = traff.NewPoint(pts[0][0], pts[0][1]), traff.NewPoint(pts[1][0], pts[1][1]), traff.NewPoint(pts[2][0], pts[2][1])
	}
	return m
}

// indexAfterClass returns the index of the type part after the event class.
func indexAfterClass(typ string) int {
	for _, c := range []string{"CONGESTION", "CONSTRUCTION", "DELAY", "HAZARD", "INCIDENT", "RESTRICTION", "WEATHER", "ACTIVITY"} {
		if len(typ) > len(c) && typ[:len(c)+1] == c+"_" {
			return len(c) + 1
		}
	}
	return 0
}

// with returns a modified deep copy of m.
func with(m traff.Message, opts ...msgOpt) traff.Message {
	m = m.Clone()
	for _, o := range opts {
		o(&m)
	}
	return m
}

func times(start, end time.Time) msgOpt {
	return func(m *traff.Message) {
		if !start.IsZero() {
			m.StartTime = traff.TimePtr(start)
		}
		if !end.IsZero() {
			m.EndTime = traff.TimePtr(end)
		}
	}
}

func bothDirections(m *traff.Message) { m.Location.Directionality = traff.BothDirections }

// The Autobahn warning seen live on 2026-10-25 on the A5 near Walldorf (INRIX).
var autobahnA5 = with(msg("de.autobahn.warning:INRIX--vi-avl.2026-10-25_16-17-00-000_007.de0", "A 5",
	[]string{"CONGESTION_STATIONARY_TRAFFIC", "DELAY_DELAY"},
	[2]float64{49.271567, 8.614388}, [2]float64{49.298928, 8.626139}, [2]float64{49.329635, 8.630273}),
	times(time.Date(2026, 10, 25, 16, 17, 0, 0, time.UTC), time.Time{}))

func TestDuplicateCases(t *testing.T) {
	m := New(Config{MaxDistance: 500})
	h := time.Hour
	a7 := msg("a", "A 7", []string{"CONGESTION_QUEUE"}, [2]float64{50.30, 9.848}, [2]float64{50.31, 9.845}, [2]float64{50.32, 9.838})
	cases := []struct {
		name string
		a, b traff.Message
		want bool
	}{
		{"same road, overlapping queue", a7,
			msg("b", "A7", []string{"CONGESTION_STATIONARY_TRAFFIC"}, [2]float64{50.305, 9.847}, [2]float64{50.325, 9.836}), true},
		{"accident point near congestion of an accident warning",
			msg("a", "A 7", []string{"CONGESTION_QUEUE", "INCIDENT_ACCIDENT"}, [2]float64{50.30, 9.848}, [2]float64{50.32, 9.838}),
			msg("b", "A7", []string{"INCIDENT_ACCIDENT"}, [2]float64{50.321, 9.8385}), true},
		{"closure vs. roadworks with closure",
			msg("a", "A 9", []string{"RESTRICTION_CLOSED"}, [2]float64{48.3158, 11.6216}, [2]float64{48.3173, 11.6207}),
			msg("b", "A 9", []string{"RESTRICTION_CLOSED", "CONSTRUCTION_ROADWORKS"}, [2]float64{48.3160, 11.6215}, [2]float64{48.3175, 11.6206}), true},
		{"both directions ignore direction", a7,
			with(msg("b", "A 7", []string{"CONGESTION_QUEUE"}, [2]float64{50.32, 9.8385}, [2]float64{50.30, 9.8485}), bothDirections), true},
		{"short location has no direction", a7,
			msg("b", "A 7", []string{"CONGESTION_QUEUE"}, [2]float64{50.3101, 9.8451}, [2]float64{50.3100, 9.8451}), true},
		{"open time intervals overlap", with(a7, times(t0, time.Time{})), with(a7, times(time.Time{}, t0.Add(h))), true},

		{"different road", a7, msg("b", "A 70", []string{"CONGESTION_QUEUE"}, [2]float64{50.30, 9.848}, [2]float64{50.32, 9.838}), false},
		{"no road reference", a7, msg("b", "", []string{"CONGESTION_QUEUE"}, [2]float64{50.30, 9.848}, [2]float64{50.32, 9.838}), false},
		{"different event category", a7, msg("b", "A 7", []string{"CONSTRUCTION_ROADWORKS"}, [2]float64{50.30, 9.848}, [2]float64{50.32, 9.838}), false},
		{"accident with lane closure vs. roadworks with lane closure",
			msg("a", "A 7", []string{"CONSTRUCTION_ROADWORKS", "RESTRICTION_LANE_CLOSED"}, [2]float64{50.30, 9.848}, [2]float64{50.32, 9.838}),
			msg("b", "A 7", []string{"INCIDENT_INVESTIGATION", "RESTRICTION_LANE_CLOSED"}, [2]float64{50.30, 9.848}, [2]float64{50.32, 9.838}), false},
		{"too far apart", a7, msg("b", "A 7", []string{"CONGESTION_QUEUE"}, [2]float64{50.33, 9.836}, [2]float64{50.34, 9.83}), false},
		{"opposite direction", a7, msg("b", "A 7", []string{"CONGESTION_QUEUE"}, [2]float64{50.32, 9.8385}, [2]float64{50.30, 9.8485}), false},
		{"b starts when a ends", with(a7, times(t0.Add(-h), t0)), with(a7, times(t0, t0.Add(h))), false},
		{"a starts after b ends", with(a7, times(t0.Add(2*h), time.Time{})), with(a7, times(time.Time{}, t0)), false},
		{"no location", a7, traff.Message{ID: "b", Events: a7.Events}, false},
		{"location without points", a7, traff.Message{ID: "b", Location: &traff.Location{RoadRef: "A 7"}, Events: a7.Events}, false},
	}
	for _, c := range cases {
		if got := m.Duplicate(&c.a, &c.b); got != c.want {
			t.Errorf("%s: Duplicate = %v", c.name, got)
		}
		// The relation is symmetric.
		if got := m.Duplicate(&c.b, &c.a); got != c.want {
			t.Errorf("%s (swapped): Duplicate = %v", c.name, got)
		}
	}
	// A larger distance makes the far-away queue a duplicate.
	far := msg("b", "A 7", []string{"CONGESTION_QUEUE"}, [2]float64{50.33, 9.836}, [2]float64{50.34, 9.83})
	if !New(Config{MaxDistance: 2000}).Duplicate(&a7, &far) {
		t.Error("MaxDistance not applied")
	}
}

// TestRealLMSAccident checks the heuristic with the A5 accident from the LMS-BW fixture against
// the Autobahn warning for the same queue.
func TestRealLMSAccident(t *testing.T) {
	f, err := os.Open("../source/datex2/testdata/bw_incidents_v2.xml")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	pub, err := datex2.Parse(f)
	if err != nil {
		t.Fatal(err)
	}
	msgs, _ := datex2.Map(pub, datex2.Options{Feed: "bw", Now: t0})
	kept, suppressed := New(Config{}).Filter(msgs, []traff.Message{autobahnA5})
	const accident = "de.datex2.bw:LMS-BW/r_LMS-BW/669322_D  BW LMS-BW.0"
	if len(suppressed) != 1 || suppressed[accident] != autobahnA5.ID || len(kept) != len(msgs)-1 {
		t.Errorf("suppressed = %v, kept %d of %d", suppressed, len(kept), len(msgs))
	}
	// The same warning in the opposite direction (Heidelberg -> Karlsruhe) is no duplicate.
	rev := autobahnA5
	rev.Location = &traff.Location{RoadRef: "A 5", Directionality: traff.OneDirection, From: autobahnA5.Location.To, Via: autobahnA5.Location.Via, To: autobahnA5.Location.From}
	if _, suppressed := New(Config{}).Filter(msgs, []traff.Message{rev}); len(suppressed) != 0 {
		t.Errorf("opposite direction suppressed %v", suppressed)
	}
}

func TestFilter(t *testing.T) {
	a := msg("ref", "A 1", []string{"RESTRICTION_CLOSED"}, [2]float64{53.5, 10.1}, [2]float64{53.52, 10.15})
	ref := []traff.Message{a, {ID: "no-location"}, msg("no-road", "", []string{"RESTRICTION_CLOSED"}, [2]float64{53.5, 10.1})}
	in := []traff.Message{
		msg("dup", "A1", []string{"CONSTRUCTION_ROADWORKS"}, [2]float64{53.501, 10.101}, [2]float64{53.519, 10.149}),
		msg("other-road", "A 7", []string{"CONSTRUCTION_ROADWORKS"}, [2]float64{53.501, 10.101}, [2]float64{53.519, 10.149}),
		{ID: "no-location"},
	}
	kept, suppressed := New(Config{}).Filter(in, ref)
	if len(kept) != 2 || kept[0].ID != "other-road" || kept[1].ID != "no-location" || len(suppressed) != 1 || suppressed["dup"] != "ref" {
		t.Errorf("kept %v, suppressed %v", kept, suppressed)
	}
	kept, suppressed = New(Config{}).Filter(in, nil)
	if len(kept) != 3 || len(suppressed) != 0 {
		t.Errorf("without reference: kept %d, suppressed %v", len(kept), suppressed)
	}
}

func TestCategories(t *testing.T) {
	cases := []struct {
		events []string
		want   []string
	}{
		{[]string{"RESTRICTION_CLOSED"}, []string{CatWorks}},
		{[]string{"RESTRICTION_LANE_CLOSED", "INCIDENT_ACCIDENT"}, []string{CatIncident}},
		{[]string{"HAZARD_OIL_ON_ROAD", "DELAY_DELAY"}, []string{CatIncident, CatCongestion}},
		{[]string{"WEATHER_FOG"}, []string{CatWeather}},
		{[]string{"ACTIVITY_MARKET"}, []string{"activity"}},
		{nil, nil},
	}
	for _, c := range cases {
		m := msg("x", "A 1", c.events, [2]float64{50, 8})
		got := Categories(&m)
		if len(got) != len(c.want) {
			t.Errorf("%v -> %v", c.events, got)
			continue
		}
		for _, w := range c.want {
			if !got[w] {
				t.Errorf("%v -> %v", c.events, got)
			}
		}
	}
	if NormalizeRoad(" a 8-x ") != "A8X" || NormalizeRoad("B\t27") != "B27" {
		t.Error(NormalizeRoad(" a 8-x "))
	}
	if New(Config{}).cfg.MaxDistance != DefaultMaxDistance {
		t.Error("default distance")
	}
}

func TestGeometry(t *testing.T) {
	p := func(x, y float64) xy { return xy{x, y} }
	// Crossing segments.
	if d := lineDistance([]xy{p(0, 0), p(10, 10)}, []xy{p(0, 10), p(10, 0)}); d != 0 {
		t.Errorf("crossing = %v", d)
	}
	// Parallel segments 3 m apart.
	if d := lineDistance([]xy{p(0, 0), p(10, 0)}, []xy{p(0, 3), p(10, 3)}); math.Abs(d-3) > 1e-9 {
		t.Errorf("parallel = %v", d)
	}
	// Point to point, and point to a degenerate segment.
	if d := lineDistance([]xy{p(0, 0)}, []xy{p(3, 4)}); math.Abs(d-5) > 1e-9 {
		t.Errorf("points = %v", d)
	}
	if d := pointSegment(p(3, 4), p(0, 0), p(0, 0)); math.Abs(d-5) > 1e-9 {
		t.Errorf("degenerate = %v", d)
	}
	// Projection: 0.01° latitude are about 1.1 km.
	a, b := project(traff.LatLon{Lat: 51, Lon: 9}), project(traff.LatLon{Lat: 51.01, Lon: 9})
	if d := math.Hypot(a.x-b.x, a.y-b.y); d < 1100 || d > 1112 {
		t.Errorf("0.01° = %v m", d)
	}
}

func class(rc traff.RoadClass) msgOpt { return func(m *traff.Message) { m.Location.RoadClass = rc } }

// TestSpatialDuplicate covers the comparison of congestion messages without road reference
// (crowd-sourced data) with official messages.
func TestSpatialDuplicate(t *testing.T) {
	m := New(Config{})
	mw := class(traff.RoadClassMotorway)
	// Official jam on the A 100 in Berlin, about 2.4 km long, from west to east.
	official := with(msg("de.autobahn.warning:a100", "A 100", []string{"CONGESTION_STATIONARY_TRAFFIC", "DELAY_DELAY"},
		[2]float64{52.4710, 13.3762}, [2]float64{52.4674, 13.3841}, [2]float64{52.4648, 13.4061}), mw)
	// Crowd chain on the same stretch, a little shorter.
	crowd := with(msg("de.crowd:x", "", []string{"CONGESTION_STATIONARY_TRAFFIC"},
		[2]float64{52.4700, 13.3786}, [2]float64{52.4664, 13.3868}, [2]float64{52.4650, 13.4033}), mw)
	h := time.Hour
	cases := []struct {
		name string
		a, b traff.Message
		want bool
	}{
		{"crowd covered by official jam", official, crowd, true},
		{"message with road reference covered by official jam without", with(official, func(m *traff.Message) { m.Location.RoadRef = "" }),
			with(crowd, func(m *traff.Message) { m.Location.RoadRef = "A100" }), true},
		{"official without road reference, both directions", with(official, func(m *traff.Message) { m.Location.RoadRef = "" }, bothDirections),
			with(msg("c", "", []string{"CONGESTION_QUEUE"}, [2]float64{52.4650, 13.4033}, [2]float64{52.4700, 13.3786}), mw), true},
		{"official is a pure delay", with(msg("a", "A 100", []string{"DELAY_DELAY"}, [2]float64{52.4710, 13.3762}, [2]float64{52.4674, 13.3841}, [2]float64{52.4648, 13.4061}), mw), crowd, true},

		{"crowd longer than official jam", with(msg("a", "A 100", []string{"CONGESTION_QUEUE"}, [2]float64{52.4674, 13.3841}, [2]float64{52.4664, 13.3868}), mw), crowd, false},
		{"opposite direction", official,
			with(msg("c", "", []string{"CONGESTION_QUEUE"}, [2]float64{52.4650, 13.4033}, [2]float64{52.4700, 13.3786}), mw), false},
		{"different road class", official, with(crowd, class(traff.RoadClassPrimary)), false},
		{"crowd without road class", official, with(crowd, class("")), false},
		{"official without road class", with(official, class("")), crowd, false},
		{"official accident without congestion", with(msg("a", "A 100", []string{"INCIDENT_ACCIDENT"}, [2]float64{52.4710, 13.3762}, [2]float64{52.4648, 13.4061}), mw), crowd, false},
		{"crowd message with incident", official, with(crowd, func(m *traff.Message) {
			m.Events = append(m.Events, traff.Event{Class: "INCIDENT", Type: "INCIDENT_ACCIDENT"})
		}), false},
		{"official jam ended", with(official, times(t0.Add(-2*h), t0.Add(-h))), with(crowd, times(t0, time.Time{})), false},
		{"far away", official, with(msg("c", "", []string{"CONGESTION_QUEUE"}, [2]float64{52.50, 13.38}, [2]float64{52.50, 13.40}), mw), false},
	}
	for _, c := range cases {
		if got := m.Duplicate(&c.a, &c.b); got != c.want {
			t.Errorf("%s: Duplicate = %v", c.name, got)
		}
	}
	// The rule is asymmetric: a short crowd message does not suppress the official one.
	short := with(msg("c", "", []string{"CONGESTION_QUEUE"}, [2]float64{52.4674, 13.3841}, [2]float64{52.4664, 13.3868}), mw)
	if !m.Duplicate(&official, &short) || m.Duplicate(&short, &official) {
		t.Error("official message duplicates the crowd message")
	}
	// The distance is configurable; a negative value disables the rule.
	shifted := with(msg("c", "", []string{"CONGESTION_QUEUE"}, [2]float64{52.4721, 13.3786}, [2]float64{52.4671, 13.4033}), mw) // ~200 m north
	if !m.Duplicate(&official, &shifted) || New(Config{SpatialDistance: 100}).Duplicate(&official, &shifted) {
		t.Error("SpatialDistance not applied")
	}
	if New(Config{SpatialDistance: -1}).Duplicate(&official, &crowd) {
		t.Error("negative SpatialDistance does not disable the rule")
	}
	if New(Config{}).cfg.SpatialDistance != DefaultSpatialDistance {
		t.Error("default spatial distance")
	}
}

func TestFilterSpatial(t *testing.T) {
	mw := class(traff.RoadClassMotorway)
	ref := []traff.Message{
		with(msg("official", "A 100", []string{"CONGESTION_QUEUE"}, [2]float64{52.4710, 13.3762}, [2]float64{52.4648, 13.4061}), mw),
		with(msg("official-noref", "", []string{"CONGESTION_QUEUE"}, [2]float64{52.50, 13.30}, [2]float64{52.50, 13.32}), mw),
		with(msg("roadworks", "", []string{"CONSTRUCTION_ROADWORKS"}, [2]float64{52.52, 13.30}, [2]float64{52.52, 13.32}), mw),
	}
	in := []traff.Message{
		with(msg("crowd-1", "", []string{"CONGESTION_QUEUE"}, [2]float64{52.4700, 13.3786}, [2]float64{52.4650, 13.4033}), mw),
		with(msg("crowd-2", "", []string{"CONGESTION_QUEUE"}, [2]float64{52.50, 13.302}, [2]float64{52.50, 13.318}), mw),
		with(msg("crowd-3", "", []string{"CONGESTION_QUEUE"}, [2]float64{52.52, 13.302}, [2]float64{52.52, 13.318}), mw),
		// With road reference: compared by road with "official", spatially with "official-noref".
		with(msg("ref-1", "A 100", []string{"CONGESTION_SLOW_TRAFFIC"}, [2]float64{52.4700, 13.3786}, [2]float64{52.4650, 13.4033}), mw),
		with(msg("ref-2", "B 1", []string{"CONGESTION_SLOW_TRAFFIC"}, [2]float64{52.50, 13.302}, [2]float64{52.50, 13.318}), mw),
		with(msg("ref-3", "B 1", []string{"CONGESTION_SLOW_TRAFFIC", "INCIDENT_ACCIDENT"}, [2]float64{52.50, 13.302}, [2]float64{52.50, 13.318}), mw),
	}
	kept, suppressed := New(Config{}).Filter(in, ref)
	want := map[string]string{"crowd-1": "official", "crowd-2": "official-noref", "ref-1": "official", "ref-2": "official-noref"}
	if len(suppressed) != len(want) || len(kept) != 2 || kept[0].ID != "crowd-3" || kept[1].ID != "ref-3" {
		t.Errorf("kept %v, suppressed %v", kept, suppressed)
	}
	for id, of := range want {
		if suppressed[id] != of {
			t.Errorf("%s suppressed as duplicate of %q, want %q", id, suppressed[id], of)
		}
	}
}
