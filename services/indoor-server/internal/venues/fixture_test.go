package venues

import (
	"encoding/json"
	"os"
	"slices"
	"strings"
	"testing"

	"github.com/WayneNani/better-maps/services/indoor-server/internal/geo"
)

// testdata/export-fixture.geojsonseq is the output of scripts/check-export.sh: osmium export
// (with config/osmium-export.json) of testdata/export-fixture.osm, a hand-written small terminal.
func buildFixture(t *testing.T) (*Builder, []*Venue) {
	t.Helper()
	f, err := os.Open("../../testdata/export-fixture.geojsonseq")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	b := NewBuilder(DefaultOptions())
	skipped, err := ReadGeoJSONSeq(f, func(r *Raw) error { b.Add(r); return nil })
	if err != nil || skipped != 0 {
		t.Fatal(skipped, err)
	}
	return b, b.Build()
}

func TestExportFixtureAreasAndLines(t *testing.T) {
	// What the osmium export config does with closed ways (the reason for the config).
	f, err := os.Open("../../testdata/export-fixture.geojsonseq")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	got := map[string]GeomKind{}
	count := map[string]int{}
	ReadGeoJSONSeq(f, func(r *Raw) error {
		got[r.ID] = r.Kind
		count[r.ID]++
		return nil
	})
	want := map[string]GeomKind{
		"w1": GeomArea, "w2": GeomArea, "w10": GeomArea, "r1": GeomArea, // terminal, level, room, multipolygon room
		"w20":   GeomArea, // closed indoor=corridor: area only
		"w31":   GeomArea, // closed indoor=wall (pillar): area
		"w30":   GeomLine, // open indoor=wall
		"w42":   GeomLine, // closed highway=footway + indoor=yes: line
		"w43":   GeomLine, // closed highway=footway: line
		"w50":   GeomArea, // closed railway=platform: area only
		"w51":   GeomArea, // highway=pedestrian + area=yes: area only
		"n1014": GeomPoint,
	}
	for id, k := range want {
		if got[id] != k || count[id] != 1 {
			t.Errorf("%s: kind %c, exported %d times", id, got[id], count[id])
		}
	}
	for _, id := range []string{"w17", "w18"} { // untagged multipolygon members
		if _, ok := got[id]; ok {
			t.Errorf("%s exported", id)
		}
	}
}

func TestFixtureVenue(t *testing.T) {
	b, vs := buildFixture(t)
	if len(vs) != 1 {
		t.Fatalf("%d venues", len(vs))
	}
	v := vs[0]
	if v.ID != "w1" || v.Name != "Terminal 9" || v.Kind != "airport" || v.DefaultLevel != 0 {
		t.Fatalf("venue %s %q %s %v", v.ID, v.Name, v.Kind, v.DefaultLevel)
	}
	wantLevels := []Level{{-1, "-1"}, {0, "EG"}, {1, "Abflug"}}
	if !slices.Equal(v.Levels, wantLevels) {
		t.Fatalf("levels %v", v.Levels)
	}
	if b.Stats.BadLevel != 1 || b.Stats.Unattached != 1 || b.Stats.Venues != 1 || b.Stats.SkippedSmall != 0 ||
		b.Stats.Stations != 1 || b.Stats.Containers != 2 {
		t.Fatalf("stats %+v", b.Stats)
	}

	doc, entry := Encode(v, "2026-11-24T21:21:02Z", DefaultSimplifyM)
	byID := map[string]DocFeature{}
	for _, f := range doc.Features {
		byID[f.ID] = f
	}
	want := map[string]string{ // id -> t/k
		"w2": "a/level", "w3": "a/level", "w20": "a/corridor", "w16": "a/area", "w12": "a/restricted",
		"w15": "a/service", "w10": "a/shop", "r1": "a/shop", "w14": "a/food", "w11": "a/toilets",
		"w13": "a/stairs", "w21": "a/gate", "w31": "a/wall", "w50": "a/platform", "w51": "a/corridor",
		"w42": "l/footway", "w40": "l/steps", "w41": "l/escalator", "w30": "l/wall",
		"n1014": "p/door", "n1096": "p/entrance", "n1088": "p/toilets", "n1090": "p/elevator", "n1089": "p/gate",
		"n1094": "p/info", "n1095": "p/atm", "n1092": "p/food", "n1091": "p/shop",
	}
	for id, tk := range want {
		f, ok := byID[id]
		if !ok || f.T+"/"+f.K != tk {
			t.Errorf("%s: %+v, want %s", id, f, tk)
		}
	}
	if len(doc.Features) != len(want) || entry.Features != len(want) {
		t.Errorf("%d features: %v", len(doc.Features), byID)
	}
	for _, gone := range []string{"n1093", "n1098", "w43", "w1", "w60", "n1097"} {
		if _, ok := byID[gone]; ok {
			t.Errorf("%s included", gone)
		}
	}
	// Levels: door from the room outline, ranges and lists, indoor without level.
	for id, lv := range map[string][]float64{"n1014": {0}, "w41": {0, 1}, "w13": {0, 1}, "n1090": {-1, 0, 1}, "w50": {-1}} {
		if !slices.Equal(byID[id].Levels, lv) {
			t.Errorf("%s levels %v, want %v", id, byID[id].Levels, lv)
		}
	}
	if f := byID["w21"]; f.R != "B12" || f.Tags["aeroway"] != "gate" {
		t.Errorf("gate area %+v", f)
	}
	if f := byID["n1089"]; f.R != "A5" {
		t.Errorf("gate %+v", f)
	}
	if f := byID["w10"]; f.N != "Relay" || f.Tags["opening_hours"] != "Mo-Su 05:00-23:00" || f.Tags["shop"] != "kiosk" {
		t.Errorf("shop %+v", f)
	}
	if f := byID["n1088"]; f.Tags["wheelchair"] != "yes" || f.Tags["changing_table"] != "yes" {
		t.Errorf("toilets %+v", f)
	}
	// Drawing order: areas (level first), lines, points.
	if doc.Features[0].K != "level" || doc.Features[len(doc.Features)-1].T != "p" {
		t.Errorf("order: first %+v last %+v", doc.Features[0], doc.Features[len(doc.Features)-1])
	}
	last := ""
	order := map[string]int{"a": 0, "l": 1, "p": 2}
	for _, f := range doc.Features {
		if last != "" && order[f.T] < order[last] {
			t.Fatalf("order broken at %s", f.ID)
		}
		last = f.T
	}
	// Geometry: the multipolygon room keeps its hole, rings are open, coordinates in the bbox.
	rings := byID["r1"].G.([][]string)
	if len(rings) != 1 || len(rings[0]) != 2 {
		t.Fatalf("r1 rings %v", rings)
	}
	bb := geo.BBox{MinLon: doc.BBox[0], MinLat: doc.BBox[1], MaxLon: doc.BBox[2], MaxLat: doc.BBox[3]}
	for _, enc := range rings[0] {
		pts, ok := geo.Decode(enc)
		if !ok || len(pts) != 4 || pts[0] == pts[len(pts)-1] {
			t.Fatalf("ring %v", pts)
		}
		for _, p := range pts {
			if !bb.Contains(p) {
				t.Fatalf("%v outside %v", p, bb)
			}
		}
	}
	if pts, ok := geo.Decode(byID["n1090"].G.(string)); !ok || len(pts) != 1 || !bb.Contains(pts[0]) {
		t.Fatalf("point %v", pts)
	}
	if l := byID["w30"].G.([]string); len(l) != 1 {
		t.Fatalf("line %v", l)
	}

	// JSON shape.
	plain, gz, err := Marshal(doc)
	if err != nil || len(gz) == 0 || len(gz) >= len(plain) {
		t.Fatal(len(plain), len(gz), err)
	}
	var m map[string]any
	if err := json.Unmarshal(plain, &m); err != nil {
		t.Fatal(err)
	}
	for _, k := range []string{"id", "name", "kind", "bbox", "levels", "default_level", "data_timestamp", "attribution", "license", "features"} {
		if _, ok := m[k]; !ok {
			t.Errorf("document lacks %q", k)
		}
	}
	s := string(plain)
	for _, want := range []string{`"attribution":"© OpenStreetMap-Mitwirkende"`, `"license":"ODbL-1.0"`,
		`{"level":0,"label":"EG"}`, `"default_level":0`, `"id":"w41","t":"l","k":"escalator","lv":[0,1],"g":[`} {
		if !strings.Contains(s, want) {
			t.Errorf("document lacks %s", want)
		}
	}
	// Empty fields are omitted.
	if strings.Contains(s, `"n":""`) || strings.Contains(s, `"r":""`) || strings.Contains(s, `"tg":{}`) || strings.Contains(s, `"tg":null`) {
		t.Error("empty fields in the document")
	}
}
