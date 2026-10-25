package datex2

import (
	"bufio"
	"bytes"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/clientcheck"
	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

var update = flag.Bool("update", false, "rewrite golden files")

// t0 is the reference time of all fixtures.
var t0 = time.Date(2026, 10, 25, 16, 0, 0, 0, time.UTC)

const horizon = 7 * 24 * time.Hour

func mapFile(t *testing.T, name, feed string) ([]traff.Message, Stats) {
	t.Helper()
	return Map(parseFile(t, name), Options{Feed: feed, Now: t0, Horizon: horizon})
}

// stamp sets the timestamps the store would set, so that golden files and client checks see
// complete messages.
func stamp(msgs []traff.Message) []traff.Message {
	out := make([]traff.Message, len(msgs))
	for i, m := range msgs {
		m.ReceiveTime, m.UpdateTime, m.ExpirationTime = traff.NewTime(t0), traff.NewTime(t0), traff.NewTime(t0.Add(30*time.Minute))
		out[i] = m
	}
	return out
}

func golden(t *testing.T, name string, got []byte) {
	t.Helper()
	path := filepath.Join("testdata", "golden", name)
	if *update {
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, got, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	want, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("%v (run go test -update to create)", err)
	}
	if !bytes.Equal(got, want) {
		t.Errorf("mapping differs from golden file %s (run go test -update after review):\n%s", path, got)
	}
}

// statsText renders stats in a stable order for golden files.
func statsText(st Stats) string {
	keys := make([]string, 0, len(st))
	for k := range st {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var b strings.Builder
	for _, k := range keys {
		fmt.Fprintf(&b, "%s=%d\n", k, st[k])
	}
	return b.String()
}

// clientView wraps msgs into a response and parses it like the CoMaps client.
func clientView(t *testing.T, msgs []traff.Message) *clientcheck.Result {
	t.Helper()
	data, err := (&traff.Response{Status: traff.StatusOK, Feed: &traff.Feed{Messages: msgs}}).Marshal()
	if err != nil {
		t.Fatal(err)
	}
	res, err := clientcheck.ParseResponse(data)
	if err != nil {
		t.Fatalf("client rejects response: %v", err)
	}
	return res
}

func TestMappingGolden(t *testing.T) {
	for _, name := range []string{"bw_incidents_v2", "bw_roadworks_v2", "synthetic_v2", "synthetic_v3", "container_v3", "latin1_v2"} {
		t.Run(name, func(t *testing.T) {
			msgs, st := mapFile(t, name+".xml", "test")
			msgs = stamp(msgs)
			data, err := traff.MarshalFeed(&traff.Feed{Messages: msgs})
			if err != nil {
				t.Fatal(err)
			}
			golden(t, name+".xml", data)
			golden(t, name+".stats", []byte(statsText(st)))

			// Every message is well-formed for the client. Rejections are only allowed for the
			// documented client limitations: point locations (at only) and event types the
			// client does not know yet.
			res := clientView(t, msgs)
			if len(res.Accepted)+len(res.Rejected) != len(msgs) {
				t.Errorf("client saw %d+%d of %d messages", len(res.Accepted), len(res.Rejected), len(msgs))
			}
			for id, reason := range res.Rejected {
				if reason != "only 1 of from/to/at" && reason != "no event known to the client" {
					t.Errorf("client rejects %s: %s", id, reason)
				}
			}
			for _, m := range msgs {
				if !strings.HasPrefix(m.ID, "de.datex2.test:") || m.Location == nil || len(m.Events) == 0 {
					t.Errorf("message %+v", m)
				}
				for _, e := range m.Events {
					if !strings.HasPrefix(e.Type, e.Class+"_") {
						t.Errorf("%s: event %s/%s", m.ID, e.Class, e.Type)
					}
				}
			}
		})
	}
}

func byID(msgs []traff.Message) map[string]traff.Message {
	m := map[string]traff.Message{}
	for _, msg := range msgs {
		_, id, _ := strings.Cut(msg.ID, ":")
		m[id] = msg
	}
	return m
}

func eventTypes(m traff.Message) string {
	var s []string
	for _, e := range m.Events {
		s = append(s, e.Type)
	}
	return strings.Join(s, ",")
}

func TestMappingSyntheticV2(t *testing.T) {
	msgs, st := mapFile(t, "synthetic_v2.xml", "test")
	want := Stats{
		"situations": 21, "records": 24, "messages": 12,
		"skipped_" + SkipAlertCOnly: 1, "skipped_" + SkipLinearRefOnly: 1, "skipped_" + SkipNoLocation: 1, "skipped_" + SkipNoID: 1,
		"skipped_" + SkipEnded: 2, "skipped_" + SkipFuture: 1, "skipped_" + SkipCancelled: 1, "skipped_" + SkipUnsupported: 2,
		"skipped_duplicate_id": 1,
	}
	if statsText(st) != statsText(want) {
		t.Errorf("stats:\n%s\nwant:\n%s", statsText(st), statsText(want))
	}
	m := byID(msgs)
	speed := m["syn-speed.0"]
	if eventTypes(speed) != "RESTRICTION_SPEED_LIMIT" || *speed.Events[0].Speed != 60 || speed.Location.RoadRef != "A 9" ||
		speed.Location.RoadClass != traff.RoadClassMotorway || speed.Location.Via == nil || speed.StartTime == nil || speed.EndTime != nil {
		t.Errorf("speed = %+v %+v", speed, speed.Location)
	}
	if a := m["syn-animals.0"]; a.Urgency != "X_URGENT" || a.Location.At == nil || a.Location.From != nil || eventTypes(a) != "HAZARD_LARGE_ANIMALS_ON_ROAD" {
		t.Errorf("animals = %+v", a)
	}
	if a := m["syn-infra.0"]; a.Urgency != "URGENT" || eventTypes(a) != "HAZARD_GAS_LEAK" {
		t.Errorf("infra = %+v", a)
	}
	if a := m["syn-ice.0"]; a.Location.RoadRef != "B 2" || a.Location.RoadClass != traff.RoadClassPrimary || !a.EndTime.Equal(time.Date(2026, 10, 26, 9, 0, 0, 0, time.UTC)) {
		t.Errorf("ice = %+v", a.Location)
	}
	if a := m["syn-oil.0"]; a.Location.RoadRef != "K 1234" || a.Location.Origin != "Musterdorf" || a.Location.Destination != "Beispielstadt" ||
		a.Location.RoadClass != traff.RoadClassTertiary {
		t.Errorf("oil = %+v", a.Location)
	}
	// The current valid period wins over the overall validity.
	if p := m["syn-periods.0"]; !p.StartTime.Equal(time.Date(2026, 10, 25, 8, 0, 0, 0, time.UTC)) || !p.EndTime.Equal(time.Date(2026, 10, 25, 20, 0, 0, 0, time.UTC)) ||
		p.Location.RoadClass != traff.RoadClassMotorway || p.Location.RoadRef != "" {
		t.Errorf("periods = %+v %+v", p, p.Location)
	}
	g := m["syn-group.0"]
	if g.Location.Directionality != traff.BothDirections || eventTypes(g) != "RESTRICTION_LANE_CLOSED,DELAY_DELAY" ||
		*g.Events[0].QInt != 2 || g.Events[1].QDuration != "00:15" || g.Location.RoadRef != "A 3" {
		t.Errorf("group = %+v %+v", g, g.Location)
	}
	if b := m["syn-bypoints.0"]; b.Location.RoadName != "Hauptstraße" || b.Location.RoadRef != "" || eventTypes(b) != "RESTRICTION_CONTRAFLOW" {
		t.Errorf("bypoints = %+v", b.Location)
	}
	w := m["syn-wrongway.0"]
	if w.Location.RoadRef != "A 3" || w.Location.Origin != "Frankfurt" || w.Location.Destination != "Köln" || w.Urgency != "" ||
		eventTypes(w) != "HAZARD_WRONG_WAY_DRIVER" || w.Location.From.Coord.Lat != 50.37 {
		t.Errorf("wrong way = %+v %+v", w, w.Location)
	}
	// Two records at the same point are merged into one message with the first record's ID.
	if d := m["syn-defaults.0"]; eventTypes(d) != "HAZARD_OBSTRUCTION,CONGESTION_TRAFFIC_PROBLEM" {
		t.Errorf("defaults = %s", eventTypes(d))
	}
	if d := m["syn-dupid.0"]; eventTypes(d) != "INCIDENT_MULTI_VEHICLE_ACCIDENT" {
		t.Errorf("first record with duplicate ID not kept: %s", eventTypes(d))
	}
	for _, gone := range []string{"syn-speed.1", "syn-alertc.0", "syn-cancel.0", "syn-future.0", "syn-ended.0", "syn-periods-ended.0", "syn-unsupported.0"} {
		if _, ok := m[gone]; ok {
			t.Errorf("%s mapped", gone)
		}
	}
	// Without horizon, future records are kept.
	msgs, _ = Map(parseFile(t, "synthetic_v2.xml"), Options{Feed: "test", Now: t0})
	if _, ok := byID(msgs)["syn-future.0"]; !ok {
		t.Error("future record dropped without horizon")
	}
}

func TestMappingSyntheticV3(t *testing.T) {
	msgs, st := mapFile(t, "synthetic_v3.xml", "test")
	m := byID(msgs)
	if st["messages"] != 7 || st["skipped_"+SkipArea] != 1 || st["skipped_"+SkipSuspended] != 1 || st["skipped_"+SkipUnsupported] != 1 {
		t.Errorf("stats = %s", statsText(st))
	}
	if a := m["v3-accident_1"]; eventTypes(a) != "INCIDENT_SERIOUS_ACCIDENT" || a.Urgency != "URGENT" || a.Location.RoadRef != "A 7" || a.Location.RoadName != "" {
		t.Errorf("accident = %+v", a)
	}
	if q := m["v3-accident_2"]; eventTypes(q) != "CONGESTION_STATIONARY_TRAFFIC,DELAY_DELAY" || q.Events[1].QDuration != "00:25" {
		t.Errorf("queue = %s", eventTypes(q))
	}
	if c := m["v3-closure_1"]; eventTypes(c) != "RESTRICTION_CARRIAGEWAY_CLOSED" || c.Location.RoadRef != "B 27" ||
		!c.StartTime.Equal(time.Date(2026, 10, 25, 19, 0, 0, 0, time.UTC)) || !c.EndTime.Equal(time.Date(2026, 10, 26, 4, 0, 0, 0, time.UTC)) {
		t.Errorf("closure = %+v", c)
	}
	if w := m["v3-works_1"]; eventTypes(w) != "CONSTRUCTION_RESURFACING_WORK,RESTRICTION_LANE_CLOSED" || *w.Events[1].QInt != 1 ||
		!w.StartTime.Equal(time.Date(2026, 10, 25, 18, 0, 0, 0, time.UTC)) {
		t.Errorf("works = %+v", w)
	}
	if _, ok := m["v3-works_2"]; ok {
		t.Error("lane closure not merged into roadworks")
	}
	if f := m["v3-fire_1"]; eventTypes(f) != "INCIDENT_VEHICLE_FIRE" || f.Location.RoadClass != traff.RoadClassTrunk || f.Urgency != "X_URGENT" {
		t.Errorf("fire = %+v", f)
	}
	if g := m["v3-weather_2"]; eventTypes(g) != "WEATHER_GUSTY_WIND" || g.Location.At == nil {
		t.Errorf("gusts = %+v", g)
	}
	if s := m["v3-misc_1"]; eventTypes(s) != "RESTRICTION_SPEED_LIMIT,HAZARD_PEOPLE_ON_ROAD" || *s.Events[0].Speed != 80 {
		t.Errorf("misc = %s", eventTypes(s))
	}
}

func TestMappingBWIncidents(t *testing.T) {
	msgs, st := mapFile(t, "bw_incidents_v2.xml", "bw")
	if st["situations"] != 12 || st["messages"] == 0 {
		t.Fatalf("stats = %s", statsText(st))
	}
	var acc, auth *traff.Message
	for i := range msgs {
		switch msgs[i].ID {
		case "de.datex2.bw:LMS-BW/r_LMS-BW/669322_D  BW LMS-BW.0":
			acc = &msgs[i]
		case "de.datex2.bw:LMS-BW/r_LMS-BW/669307_D  BW LMS-BW.0":
			auth = &msgs[i]
		}
	}
	if acc == nil || eventTypes(*acc) != "INCIDENT_ACCIDENT,CONGESTION_TRAFFIC_PROBLEM" || acc.Location.RoadRef != "A 5" ||
		acc.Location.Origin != "Karlsruhe" || acc.Location.Destination != "Heidelberg" || acc.Location.RoadClass != traff.RoadClassMotorway {
		t.Errorf("accident = %+v", acc)
	}
	// Accident investigation plus lane closure at the same place: one message.
	if auth == nil || eventTypes(*auth) != "INCIDENT_INVESTIGATION,RESTRICTION_LANE_CLOSED" || auth.Location.RoadRef != "A 6" {
		t.Errorf("authority = %+v", auth)
	}
}

func TestEventTypesInTraFFList(t *testing.T) {
	f, err := os.Open("testdata/traff_event_types.txt")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	known := map[string]bool{}
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		if l := strings.TrimSpace(sc.Text()); l != "" && !strings.HasPrefix(l, "#") {
			known[l] = true
		}
	}
	if len(known) < 500 {
		t.Fatalf("only %d known event types", len(known))
	}
	for typ, m := range recordEvents {
		for _, ev := range append([]string{m.def}, valuesOf(m.values)...) {
			if !known[ev] {
				t.Errorf("%s: %s is not in the TraFF event list", typ, ev)
			}
			if eventClass(ev) == "" {
				t.Errorf("%s: no class for %s", typ, ev)
			}
		}
	}
	if !known["DELAY_DELAY"] {
		t.Error("DELAY_DELAY unknown")
	}
	if eventClass("SECURITY_X") != "" {
		t.Error("unexpected class")
	}
}

func valuesOf(m map[string]string) []string {
	var res []string
	for _, v := range m {
		res = append(res, v)
	}
	return res
}

func TestRoadRefAndClass(t *testing.T) {
	cases := []struct {
		in, ref string
		class   traff.RoadClass
	}{
		{"A5", "A 5", traff.RoadClassMotorway},
		{" A 81 ", "A 81", traff.RoadClassMotorway},
		{"B31N", "B 31N", traff.RoadClassPrimary},
		{"b27", "B 27", traff.RoadClassPrimary},
		{"L1100", "L 1100", traff.RoadClassSecondary},
		{"St2345", "ST 2345", traff.RoadClassSecondary},
		{"K 6122", "K 6122", traff.RoadClassTertiary},
		{"E 35", "E 35", ""},
		{"Hauptstraße", "Hauptstraße", ""},
	}
	for _, c := range cases {
		ref := RoadRef(c.in)
		if ref != c.ref || RoadClassFor(ref, "") != c.class {
			t.Errorf("%q -> %q %q", c.in, ref, RoadClassFor(ref, ""))
		}
	}
	frc := map[string]traff.RoadClass{"FRC0": "MOTORWAY", "FRC1": "TRUNK", "FRC2": "PRIMARY", "FRC3": "SECONDARY", "FRC4": "TERTIARY", "FRC5": "OTHER", "FRC7": "OTHER", "": ""}
	for in, want := range frc {
		if got := RoadClassFor("", in); got != want {
			t.Errorf("FRC %q -> %q", in, got)
		}
	}
	if MessageID("bw", " r1 ") != "de.datex2.bw:r1" {
		t.Error(MessageID("bw", " r1 "))
	}
}

func TestWindow(t *testing.T) {
	at := func(h int) time.Time { return t0.Add(time.Duration(h) * time.Hour) }
	cases := []struct {
		name       string
		r          Record
		start, end time.Time
		reason     string
	}{
		{"open", Record{}, time.Time{}, time.Time{}, ""},
		{"cancelled", Record{Cancelled: true}, time.Time{}, time.Time{}, SkipCancelled},
		{"suspended", Record{ValidityStatus: "suspended"}, time.Time{}, time.Time{}, SkipSuspended},
		{"overall", Record{OverallStart: at(-2), OverallEnd: at(2)}, at(-2), at(2), ""},
		{"ended exactly now", Record{OverallEnd: t0}, time.Time{}, t0, SkipEnded},
		{"open period", Record{OverallStart: at(-5), Periods: []Period{{Start: at(-1)}}}, at(-1), time.Time{}, ""},
		{"period bounded by overall end", Record{OverallEnd: at(3), Periods: []Period{{Start: at(1), End: at(5)}}}, at(1), at(3), ""},
		{"period starting before overall start", Record{OverallStart: at(1), Periods: []Period{{Start: at(-1), End: at(5)}}}, at(1), at(5), ""},
		{"all periods ended", Record{Periods: []Period{{End: at(-1)}}}, time.Time{}, time.Time{}, SkipEnded},
	}
	for _, c := range cases {
		start, end, reason := c.r.window(t0)
		if reason != c.reason || (reason == "" && (!start.Equal(c.start) || !end.Equal(c.end))) {
			t.Errorf("%s: %v %v %q", c.name, start, end, reason)
		}
	}
}

func TestMergeValidity(t *testing.T) {
	loc := Location{Points: []traff.LatLon{{Lat: 50, Lon: 8}, {Lat: 50.1, Lon: 8.1}}, Kind: GeometryEndpoints, RoadNumber: "A5"}
	pub := &Publication{Country: "Germany", Situations: []Situation{{ID: "s", Records: []Record{
		{ID: "r1", Type: "MaintenanceWorks", OverallStart: t0.Add(time.Hour), OverallEnd: t0.Add(2 * time.Hour), Location: loc},
		{ID: "r2", Type: "RoadOrCarriagewayOrLaneManagement", SubType: "roadClosed", OverallStart: t0.Add(-time.Hour), OverallEnd: t0.Add(3 * time.Hour), Location: loc},
		{ID: "r3", Type: "MaintenanceWorks", Location: loc},
	}}, {ID: "t", Records: []Record{
		{ID: "t1", Type: "MaintenanceWorks", OverallEnd: t0.Add(time.Hour), Location: loc},
		{ID: "t2", Type: "MaintenanceWorks", SubType: "repairWork", OverallStart: t0.Add(-time.Hour), OverallEnd: t0.Add(4 * time.Hour), Location: loc},
	}}}}
	msgs, _ := Map(pub, Options{Feed: "f", Now: t0})
	if len(msgs) != 2 {
		t.Fatalf("%d messages", len(msgs))
	}
	// r3 has no end: the merged message is open-ended. Duplicate event types are merged.
	if m := msgs[0]; m.ID != "de.datex2.f:r1" || !m.StartTime.Equal(t0.Add(-time.Hour)) || m.EndTime != nil ||
		eventTypes(m) != "CONSTRUCTION_ROADWORKS,RESTRICTION_CLOSED" || m.Location.Country != "DE" {
		t.Errorf("merged = %+v", m)
	}
	if m := msgs[1]; m.StartTime == nil || !m.StartTime.Equal(t0.Add(-time.Hour)) || !m.EndTime.Equal(t0.Add(4*time.Hour)) ||
		eventTypes(m) != "CONSTRUCTION_ROADWORKS,CONSTRUCTION_REPAIR" {
		t.Errorf("merged = %+v", m)
	}
}
