package autobahn

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

var update = flag.Bool("update", false, "rewrite golden files")

// fixtureNow is shortly after the fixtures were recorded (2026-10-25, ~15:50 UTC).
var fixtureNow = time.Date(2026, 10, 25, 16, 0, 0, 0, time.UTC)

func loadFixture(t *testing.T, name string) (road, service string, items []Item) {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", name+".json"))
	if err != nil {
		t.Fatal(err)
	}
	road, service, _ = strings.Cut(name, "_")
	items, err = DecodeItems(data, service)
	if err != nil {
		t.Fatalf("%s: %v", name, err)
	}
	return road, service, items
}

func mapAll(t *testing.T, name string, now time.Time) []traff.Message {
	t.Helper()
	road, service, items := loadFixture(t, name)
	tz := berlin(t)
	var res []traff.Message
	for i := range items {
		m, err := MapItem(road, service, &items[i], now, tz)
		if err != nil {
			t.Errorf("%s item %d (%s): %v", name, i, items[i].Identifier, err)
			continue
		}
		res = append(res, m)
	}
	return res
}

func fixtureNames(t *testing.T) []string {
	t.Helper()
	files, err := filepath.Glob("testdata/A*_*.json")
	if err != nil || len(files) == 0 {
		t.Fatalf("no fixtures: %v", err)
	}
	var names []string
	for _, f := range files {
		names = append(names, strings.TrimSuffix(filepath.Base(f), ".json"))
	}
	return names
}

func hasEvent(m traff.Message, typ string) *traff.Event {
	for i := range m.Events {
		if m.Events[i].Type == typ {
			return &m.Events[i]
		}
	}
	return nil
}

// TestMapFixturesInvariants maps every recorded API response and checks properties every
// message must have.
func TestMapFixturesInvariants(t *testing.T) {
	for _, name := range fixtureNames(t) {
		t.Run(name, func(t *testing.T) {
			road, service, items := loadFixture(t, name)
			msgs := mapAll(t, name, fixtureNow)
			if len(msgs) != len(items) {
				t.Errorf("mapped %d of %d items", len(msgs), len(items))
			}
			ids := map[string]bool{}
			for _, m := range msgs {
				if !strings.HasPrefix(m.ID, IDPrefix+service+":") || ids[m.ID] {
					t.Errorf("bad or duplicate ID %q", m.ID)
				}
				ids[m.ID] = true
				l := m.Location
				if l == nil || l.From == nil || l.To == nil || l.At != nil {
					t.Fatalf("%s: location needs from and to: %+v", m.ID, l)
				}
				if l.Country != "DE" || l.RoadRef != RoadRef(road) || l.RoadClass != traff.RoadClassMotorway || l.Directionality != traff.OneDirection {
					t.Errorf("%s: location attributes %+v", m.ID, l)
				}
				b, _ := m.BBox()
				if !(traff.BBox{MinLat: 47, MinLon: 5.5, MaxLat: 55.5, MaxLon: 15.5}).Contains(b) {
					t.Errorf("%s: location %v outside Germany (lat/lon swapped?)", m.ID, b)
				}
				if len(m.Events) == 0 {
					t.Errorf("%s: no events", m.ID)
				}
				for _, e := range m.Events {
					if !strings.HasPrefix(e.Type, e.Class+"_") {
						t.Errorf("%s: event type %s does not belong to class %s", m.ID, e.Type, e.Class)
					}
				}
				if m.StartTime != nil && m.EndTime != nil && !m.EndTime.After(m.StartTime.Time) {
					t.Errorf("%s: end %v not after start %v", m.ID, m.EndTime, m.StartTime)
				}
				if m.EndTime != nil && !m.EndTime.After(fixtureNow) {
					t.Errorf("%s: ended message was not skipped", m.ID)
				}
				switch service {
				case ServiceWarning:
					if m.StartTime == nil || m.EndTime != nil || l.Origin == "" && !strings.Contains(name, "A831") {
						t.Errorf("%s: warning times/direction %+v %+v", m.ID, m.StartTime, l)
					}
				case ServiceRoadworks:
					if hasEvent(m, constructionRoadwork) == nil || m.StartTime == nil || m.EndTime == nil {
						t.Errorf("%s: roadworks without CONSTRUCTION_ROADWORKS or validity", m.ID)
					}
				case ServiceClosure:
					if hasEvent(m, restrictionClosed) == nil && hasEvent(m, restrictionRamp) == nil {
						t.Errorf("%s: closure without closure event", m.ID)
					}
				}
			}
		})
	}
}

func TestMapSpecificFixtureItems(t *testing.T) {
	byID := map[string]traff.Message{}
	for _, name := range fixtureNames(t) {
		for _, m := range mapAll(t, name, fixtureNow) {
			byID[m.ID] = m
		}
	}
	get := func(id string) traff.Message {
		t.Helper()
		m, ok := byID[id]
		if !ok {
			t.Fatalf("message %s not found", id)
		}
		return m
	}

	// A7 Würzburg -> Fulda: QUEUING_TRAFFIC with "Im Stillstand" and 12 minutes delay.
	m := get("de.autobahn.warning:INRIX--vi-avl.2026-10-25_15-08-00-000_009.de0")
	if m.Events[0].Type != congestionStationary || hasEvent(m, delayDelay).QDuration != "00:12" {
		t.Errorf("A7 warning events = %+v", m.Events)
	}
	if m.Location.Origin != "Würzburg" || m.Location.Destination != "Fulda" || m.Location.RoadRef != "A 7" {
		t.Errorf("A7 warning location = %+v", m.Location)
	}
	if m.Location.From.Coord != (traff.LatLon{Lat: 50.302025, Lon: 9.848275}) || m.Location.To.Coord != (traff.LatLon{Lat: 50.319385, Lon: 9.838167}) {
		t.Errorf("A7 warning points = %v -> %v", m.Location.From.Coord, m.Location.To.Coord)
	}
	if !m.StartTime.Equal(time.Date(2026, 10, 25, 15, 8, 0, 0, time.UTC)) {
		t.Errorf("A7 warning start = %v", m.StartTime)
	}

	// A7 Hannover -> Hamburg with average speed 20 km/h.
	m = get("de.autobahn.warning:INRIX--vi-avl.2026-10-25_14-51-00-000_011.de0")
	if *m.Events[0].Speed != 20 {
		t.Errorf("average speed not mapped: %+v", m.Events[0])
	}

	// A2 accident without congestion type.
	m = get("de.autobahn.warning:INRIX--vi-unf.2026-10-25_15-04-17-000.de0")
	if len(m.Events) != 1 || m.Events[0].Type != incidentAccident {
		t.Errorf("A2 accident events = %+v", m.Events)
	}

	// A3 "Fahrbahnmanagement" with display type CLOSURE in the warning list.
	m = get("de.autobahn.warning:INRIX--vi-fbm.2026-10-25_09-29-17-000.de0")
	if hasEvent(m, restrictionClosed) == nil {
		t.Errorf("A3 closure events = %+v", m.Events)
	}

	// A8: accident, people on the road, lane closed.
	m = get("de.autobahn.warning:INRIX--vi-zus.2026-10-25_14-29-29-000.de0")
	for _, typ := range []string{incidentAccident, hazardPeople, restrictionLane, delayDelay} {
		if hasEvent(m, typ) == nil {
			t.Errorf("A8 warning lacks %s: %+v", typ, m.Events)
		}
	}

	// A7 closure in the Füssen tunnel: future window that starts on 29.10. 20:00 local time.
	m = get("de.autobahn.closure:2026-053039--vi-fbm.2026-10-29_19-00-00-000.devi-zus.2027-10-29_19-00-00-000.de2")
	if !m.StartTime.Equal(time.Date(2026, 10, 29, 19, 0, 0, 0, time.UTC)) || !m.EndTime.Equal(time.Date(2026, 10, 30, 4, 0, 0, 0, time.UTC)) {
		t.Errorf("tunnel closure window = %v - %v", m.StartTime, m.EndTime)
	}

	// A9 ramp closure: "Auffahrt auf die A92" -> entry ramp.
	m = get("de.autobahn.closure:" + firstIdentifier(t, "A9_closure"))
	if m.Location.Ramps != traff.RampsEntry || m.Events[0].Type != restrictionRamp {
		t.Errorf("A9 ramp closure = %+v %+v", m.Location, m.Events)
	}
	if !m.StartTime.Equal(time.Date(2026, 10, 26, 19, 0, 0, 0, time.UTC)) || !m.EndTime.Equal(time.Date(2026, 10, 27, 4, 0, 0, 0, time.UTC)) {
		t.Errorf("A9 ramp closure window = %v - %v", m.StartTime, m.EndTime)
	}
}

func firstIdentifier(t *testing.T, name string) string {
	_, _, items := loadFixture(t, name)
	return items[0].Identifier
}

func TestWindowAdvancesOverTime(t *testing.T) {
	_, _, items := loadFixture(t, "A9_closure")
	tz := berlin(t)
	// The first item is valid 26.10. 20:00-27.10. 05:00 and 27.10. 20:00-28.10. 05:00 (local).
	later := time.Date(2026, 10, 27, 12, 0, 0, 0, time.UTC)
	m, err := MapItem("A9", ServiceClosure, &items[0], later, tz)
	if err != nil {
		t.Fatal(err)
	}
	if !m.StartTime.Equal(time.Date(2026, 10, 27, 19, 0, 0, 0, time.UTC)) {
		t.Errorf("second window expected, got start %v", m.StartTime)
	}
	if _, err := MapItem("A9", ServiceClosure, &items[0], time.Date(2026, 10, 28, 5, 0, 0, 0, time.UTC), tz); !errors.Is(err, ErrEnded) {
		t.Errorf("after the last window: %v", err)
	}
}

// TestGoldenFeeds compares the mapping of selected fixtures with reviewed golden files.
// Run "go test ./internal/source/autobahn -update" to regenerate them.
func TestGoldenFeeds(t *testing.T) {
	for _, name := range []string{"A7_warning", "A8_warning", "A9_closure"} {
		t.Run(name, func(t *testing.T) {
			msgs := mapAll(t, name, fixtureNow)
			for i := range msgs {
				// The store sets these; use fixed values for a stable golden file.
				msgs[i].ReceiveTime = traff.NewTime(fixtureNow)
				msgs[i].UpdateTime = traff.NewTime(fixtureNow)
				msgs[i].ExpirationTime = traff.NewTime(fixtureNow.Add(30 * time.Minute))
			}
			got, err := traff.MarshalFeed(&traff.Feed{Messages: msgs})
			if err != nil {
				t.Fatal(err)
			}
			golden := filepath.Join("testdata", "golden", name+".xml")
			if *update {
				if err := os.MkdirAll(filepath.Dir(golden), 0o755); err != nil {
					t.Fatal(err)
				}
				if err := os.WriteFile(golden, got, 0o644); err != nil {
					t.Fatal(err)
				}
			}
			want, err := os.ReadFile(golden)
			if err != nil {
				t.Fatalf("%v (run with -update to create)", err)
			}
			if !bytes.Equal(got, want) {
				t.Errorf("feed differs from %s; run with -update after reviewing the change", golden)
			}
		})
	}
}

func item(js string) *Item {
	var it Item
	if err := json.Unmarshal([]byte(js), &it); err != nil {
		panic(err)
	}
	return &it
}

const line = `"geometry":{"type":"LineString","coordinates":[[9.84,50.30],[9.845,50.31],[9.838,50.32]]}`

func TestMapItemSynthetic(t *testing.T) {
	tz := berlin(t)
	now := fixtureNow
	tests := []struct {
		name    string
		service string
		js      string
		wantErr error
		check   func(t *testing.T, m traff.Message)
	}{
		{name: "no id", service: ServiceWarning, js: `{"display_type":"WARNING",` + line + `}`, wantErr: ErrNoID},
		{name: "no coordinates", service: ServiceWarning, js: `{"identifier":"x","display_type":"WARNING","coordinate":{"lat":"","long":"abc"}}`, wantErr: ErrNoLocation},
		{name: "zero coordinates", service: ServiceWarning, js: `{"identifier":"x","geometry":{"type":"LineString","coordinates":[[0,0]]},"coordinate":{"lat":0,"long":0}}`, wantErr: ErrNoLocation},
		{name: "ended", service: ServiceRoadworks, js: `{"identifier":"x","display_type":"ROADWORKS","description":["Beginn: 01.01.26 um 08:00 Uhr","Ende: 02.01.26 um 08:00 Uhr"],` + line + `}`, wantErr: ErrEnded},
		{name: "overall end passed", service: ServiceRoadworks, js: `{"identifier":"x","display_type":"ROADWORKS","description":["(Ende der Gesamtmaßnahme: 01.10.26)"],` + line + `}`, wantErr: ErrEnded},
		{name: "future without start", service: ServiceRoadworks, js: `{"identifier":"x","future":true,"display_type":"ROADWORKS",` + line + `}`, wantErr: ErrNoStart},
		{
			name: "point only, string coordinates", service: ServiceWarning,
			js: `{"identifier":"p","display_type":"WARNING","coordinate":{"lat":"50.1","long":"8.6"},"startTimestamp":"2026-10-25T15:00:00.000+0100","isBlocked":"true"}`,
			check: func(t *testing.T, m traff.Message) {
				if m.Location.At == nil || m.Location.From != nil || m.Location.At.Coord != (traff.LatLon{Lat: 50.1, Lon: 8.6}) {
					t.Errorf("point location = %+v", m.Location)
				}
				if m.Events[0].Type != congestionProblem || hasEvent(m, restrictionClosed) == nil {
					t.Errorf("events = %+v", m.Events)
				}
				if !m.StartTime.Equal(time.Date(2026, 10, 25, 14, 0, 0, 0, time.UTC)) {
					t.Errorf("start = %v", m.StartTime)
				}
			},
		},
		{
			name: "multilinestring, duplicates removed", service: ServiceClosure,
			js: `{"identifier":"m","display_type":"CLOSURE","geometry":{"type":"MultiLineString","coordinates":[[[9.0,50.0],[9.0,50.0]],[[9.1,50.1]]]}}`,
			check: func(t *testing.T, m traff.Message) {
				if m.Location.From.Coord.Lon != 9.0 || m.Location.To.Coord.Lon != 9.1 || m.Location.Via != nil {
					t.Errorf("location = %+v", m.Location)
				}
				if m.StartTime != nil || m.EndTime != nil {
					t.Error("no validity expected")
				}
			},
		},
		{
			name: "geometry point", service: ServiceClosure,
			js: `{"identifier":"g","geometry":{"type":"Point","coordinates":[9.2,50.2]}}`,
			check: func(t *testing.T, m traff.Message) {
				if m.Location.At == nil || hasEvent(m, restrictionClosed) == nil {
					t.Errorf("point closure = %+v %+v", m.Location, m.Events)
				}
			},
		},
		{
			name: "weight limit", service: ServiceRoadworks,
			js: `{"identifier":"w","display_type":"WEIGHT_LIMIT_35",` + line + `}`,
			check: func(t *testing.T, m traff.Message) {
				if e := hasEvent(m, restrictionWeight); e == nil || e.QWeight != "3.5" {
					t.Errorf("events = %+v", m.Events)
				}
			},
		},
		{
			name: "unknown display type in roadworks list", service: ServiceRoadworks,
			js: `{"identifier":"r","display_type":"NEW_TYPE","description":["Länge: 1 km | Max. 60 km/h | Maximale Durchfahrtsbreite: 3,25 m"],"impact":{"symbols":["SEPARATE","CLOSED","CLOSED"]},` + line + `}`,
			check: func(t *testing.T, m traff.Message) {
				if hasEvent(m, constructionRoadwork) == nil || *hasEvent(m, restrictionSpeed).Speed != 60 ||
					hasEvent(m, restrictionWidth).QDimension != "3.25" || hasEvent(m, restrictionClosed) == nil {
					t.Errorf("events = %+v", m.Events)
				}
			},
		},
		{
			name: "unknown display type in closure list", service: ServiceClosure,
			js: `{"identifier":"c","display_type":"NEW_TYPE",` + line + `}`,
			check: func(t *testing.T, m traff.Message) {
				if hasEvent(m, restrictionClosed) == nil {
					t.Errorf("events = %+v", m.Events)
				}
			},
		},
		{
			name: "unknown display type in warning list", service: ServiceWarning,
			js: `{"identifier":"u","display_type":"","abnormalTrafficType":"STATIONARY_TRAFFIC","delayTimeValue":75,"averageSpeed":"abc",` + line + `}`,
			check: func(t *testing.T, m traff.Message) {
				if m.Events[0].Type != congestionStationary || m.Events[0].Speed != nil || hasEvent(m, delayDelay).QDuration != "01:15" {
					t.Errorf("events = %+v", m.Events)
				}
			},
		},
		{
			name: "warning bullets", service: ServiceWarning,
			js: `{"identifier":"b","display_type":"WARNING","abnormalTrafficType":"HEAVY_TRAFFIC","subtitle":" Köln -> Dortmund","description":[
				"Beginn: 25.10.26 um 16:14 Uhr","","Unfall, seit 25.10.2026, 15:14","Ereignismeldung:","- Langsamer Verkehr","- Falschfahrer",
				"- Tiere auf der Fahrbahn","- Gegenstände auf der Fahrbahn","- Vollsperrung","- Etwas Neues"],` + line + `}`,
			check: func(t *testing.T, m traff.Message) {
				if m.Events[0].Type != congestionSlow {
					t.Errorf("most severe congestion should win: %+v", m.Events)
				}
				for _, typ := range []string{incidentAccident, hazardWrongWay, hazardAnimals, hazardObject, restrictionClosed} {
					if hasEvent(m, typ) == nil {
						t.Errorf("missing %s in %+v", typ, m.Events)
					}
				}
				if !m.StartTime.Equal(time.Date(2026, 10, 25, 15, 14, 0, 0, time.UTC)) {
					t.Errorf("start = %v", m.StartTime)
				}
			},
		},
		{
			name: "contraflow", service: ServiceRoadworks,
			js: `{"identifier":"cf","display_type":"ROADWORKS","impact":{"symbols":["ARROW_DOWN","SEPARATE","ARROW_UP","SEPARATE","CLOSED","CLOSED","BORDER_RIGHT","CLOSED"]},` + line + `}`,
			check: func(t *testing.T, m traff.Message) {
				if hasEvent(m, restrictionContra) == nil || hasEvent(m, restrictionClosed) != nil {
					t.Errorf("events = %+v", m.Events)
				}
			},
		},
		{
			name: "lane closed", service: ServiceRoadworks,
			js: `{"identifier":"lc","display_type":"SHORT_TERM_ROADWORKS","impact":{"symbols":["SEPARATE","CLOSED","ARROW_UP","BORDER_RIGHT","CLOSED"]},` + line + `}`,
			check: func(t *testing.T, m traff.Message) {
				if e := hasEvent(m, restrictionLane); e == nil || *e.QInt != 1 {
					t.Errorf("events = %+v", m.Events)
				}
			},
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			m, err := MapItem("A5", tc.service, item(tc.js), now, tz)
			if tc.wantErr != nil {
				if !errors.Is(err, tc.wantErr) {
					t.Fatalf("err = %v, want %v", err, tc.wantErr)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if m.Location.RoadRef != "A 5" {
				t.Errorf("road ref = %q", m.Location.RoadRef)
			}
			tc.check(t, m)
		})
	}
}

func TestRampsFor(t *testing.T) {
	cases := map[string]string{
		"Auffahrt auf die A7: AS Göttingen-Nord":                         traff.RampsEntry,
		"Abfahrt von der A7: AS Göttingen-Nord":                          traff.RampsExit,
		"Von Abfahrt von der A7: AK Memmingen nach Auffahrt auf die A96": traff.RampsAll,
		"AS Göttingen-Nord gesperrt":                                     traff.RampsAll,
	}
	for desc, want := range cases {
		if got := rampsFor(&Item{Description: []string{desc}}); got != want {
			t.Errorf("rampsFor(%q) = %s, want %s", desc, got, want)
		}
	}
}

func TestLanes(t *testing.T) {
	cases := []struct {
		sym                       []string
		closed, open              int
		contraflow, allClosedOurs bool
	}{
		{nil, 0, 0, false, false},
		{[]string{"ARROW_DOWN", "CLOSED", "SEPARATE", "CLOSED", "ARROW_UP"}, 1, 1, false, false},
		{[]string{"CLOSED", "CLOSED", "SEPARATE", "CLOSED", "CLOSED"}, 2, 0, false, true},
		{[]string{"SEPARATE", "ARROW_UP", "ARROW_UP", "BORDER_RIGHT", "CLOSED"}, 0, 2, false, false},
		{[]string{"ARROW_DOWN", "SEPARATE", "ARROW_UP", "SEPARATE", "CLOSED", "BORDER_RIGHT", "CLOSED"}, 1, 0, true, true},
		{[]string{"CLOSED", "ARROW_UP"}, 1, 1, false, false},
		{[]string{"SEPARATE", "BORDER_RIGHT", "CLOSED"}, 0, 0, false, false},
	}
	for _, c := range cases {
		var imp *Impact
		if c.sym != nil {
			imp = &Impact{Symbols: c.sym}
		}
		ls := lanes(imp)
		if ls.closed != c.closed || ls.open != c.open || ls.contraflow != c.contraflow || ls.allClosedUs != c.allClosedOurs {
			t.Errorf("lanes(%v) = %+v", c.sym, ls)
		}
	}
}

func TestRoadHelpers(t *testing.T) {
	for in, want := range map[string]string{"A7": "A 7", "A60 ": "A 60", "a100": "A 100", "B 2": "B 2", "A1/A2": "A1/A2"} {
		if got := RoadRef(in); got != want {
			t.Errorf("RoadRef(%q) = %q, want %q", in, got, want)
		}
	}
	if RoadClassFor("A7") != traff.RoadClassMotorway || RoadClassFor("B2") != "" || RoadClassFor("A") != "" {
		t.Error("RoadClassFor")
	}
	if MessageID("warning", " x ") != "de.autobahn.warning:x" {
		t.Error("MessageID")
	}
}

func TestFlexTypes(t *testing.T) {
	var v struct {
		F  FlexFloat  `json:"f"`
		F2 FlexFloat  `json:"f2"`
		F3 FlexFloat  `json:"f3"`
		B  FlexBool   `json:"b"`
		B2 FlexBool   `json:"b2"`
		S  FlexString `json:"s"`
		S2 FlexString `json:"s2"`
		S3 FlexString `json:"s3"`
	}
	if err := json.Unmarshal([]byte(`{"f":"1.5","f2":null,"f3":"x","b":"true","b2":false,"s":12,"s2":null,"s3":"7.6"}`), &v); err != nil {
		t.Fatal(err)
	}
	if !v.F.Valid || v.F.Value != 1.5 || v.F2.Valid || v.F3.Valid || !bool(v.B) || bool(v.B2) || v.S != "12" || v.S2 != "" {
		t.Errorf("decoded %+v", v)
	}
	if i, ok := v.S3.Int(); !ok || i != 8 {
		t.Errorf("Int(7.6) = %d %v", i, ok)
	}
	if _, ok := FlexString("").Int(); ok {
		t.Error("empty string is not an int")
	}
	if _, ok := FlexString("x").Int(); ok {
		t.Error("x is not an int")
	}
}

func TestDecodeItems(t *testing.T) {
	if items, err := DecodeItems([]byte(`{}`), ServiceWarning); err != nil || items == nil || len(items) != 0 {
		t.Errorf("missing key: %v %v", items, err)
	}
	if items, err := DecodeItems([]byte(`{"warning":null}`), ServiceWarning); err != nil || items == nil {
		t.Errorf("null list: %v %v", items, err)
	}
	if items, err := DecodeItems([]byte(`{"warning":[]}`), ServiceWarning); err != nil || items == nil {
		t.Errorf("empty list: %v %v", items, err)
	}
	if _, err := DecodeItems([]byte(`[`), ServiceWarning); err == nil {
		t.Error("invalid JSON accepted")
	}
	if _, err := DecodeItems([]byte(`{"warning":{"a":1}}`), ServiceWarning); err == nil {
		t.Error("object instead of list accepted")
	}
	g := &Geometry{Coordinates: json.RawMessage(`"nonsense"`)}
	if g.lonLat() != nil {
		t.Error("nonsense geometry decoded")
	}
}
