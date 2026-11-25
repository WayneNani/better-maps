package venues

import (
	"bytes"
	"compress/gzip"
	"errors"
	"fmt"
	"io"
	"math"
	"slices"
	"strings"
	"testing"

	"github.com/WayneNani/better-maps/services/indoor-server/internal/geo"
)

// Synthetic features around a base point; x/y in metres east/north.
const baseLon, baseLat = 7.0, 50.0

func pt(x, y float64) geo.Point {
	return geo.Point{Lon: baseLon + x/geo.MetersPerDegLon(baseLat), Lat: baseLat + y/geo.MetersPerDegLat}
}

func rect(x, y, w, h float64) geo.Polygon {
	return geo.Polygon{geo.Ring{pt(x, y), pt(x+w, y), pt(x+w, y+h), pt(x, y+h)}}
}

func area(id string, x, y, w, h float64, tags ...string) *Raw {
	return &Raw{ID: id, Kind: GeomArea, Polys: []geo.Polygon{rect(x, y, w, h)}, Tags: tagMap(tags)}
}

func point(id string, x, y float64, tags ...string) *Raw {
	return &Raw{ID: id, Kind: GeomPoint, Point: pt(x, y), Tags: tagMap(tags)}
}

func line(id string, tags []string, xy ...float64) *Raw {
	var l []geo.Point
	for i := 0; i+1 < len(xy); i += 2 {
		l = append(l, pt(xy[i], xy[i+1]))
	}
	return &Raw{ID: id, Kind: GeomLine, Lines: [][]geo.Point{l}, Tags: tagMap(tags)}
}

func tagMap(kv []string) map[string]string {
	m := map[string]string{}
	for _, s := range kv {
		k, v, _ := strings.Cut(s, "=")
		m[k] = v
	}
	return m
}

var nextID = 1000

// rooms adds n rooms of 20 m x 20 m in a row starting at (x, y), 2 m apart, on the given level.
func rooms(b *Builder, n int, x, y float64, level string) {
	for i := 0; i < n; i++ {
		nextID++
		b.Add(area(fmt.Sprintf("w%d", nextID), x+float64(i)*22, y, 20, 20, "indoor=room", "level="+level))
	}
}

func TestOsmID(t *testing.T) {
	cases := map[string]string{"n1": "n1", "w22": "w22", "r3": "r3", "a4": "w2", "a7": "r3", "a1": "r0"}
	for in, want := range cases {
		if got, ok := osmID(in); !ok || got != want {
			t.Errorf("osmID(%q) = %q, %v", in, got, ok)
		}
	}
	for _, bad := range []string{"", "n", "x1", "n-1", "n0", "n+1", "w1.5", "nabc"} {
		if got, ok := osmID(bad); ok {
			t.Errorf("osmID(%q) = %q", bad, got)
		}
	}
}

func TestReadGeoJSONSeq(t *testing.T) {
	long := strings.Repeat(`[7.0,50.0],`, 200000) // > the 1 MB read buffer
	in := "\x1e" + `{"type":"Feature","id":"n1","geometry":{"type":"Point","coordinates":[7.1,50.1]},"properties":{"amenity":"toilets","level":"0","x":1}}` + "\n" +
		"\n" +
		`{"type":"Feature","id":"w2","geometry":{"type":"MultiLineString","coordinates":[[[7,50],[7.1,50]],[[7,50]]]},"properties":{}}` + "\n" +
		`{"type":"Feature","id":"a6","geometry":{"type":"Polygon","coordinates":[[[7,50],[7.1,50],[7.1,50.1],[7,50]],[[7,50],[7,50]]]},"properties":{}}` + "\n" +
		`{"type":"Feature","id":"w4","geometry":{"type":"LineString","coordinates":[` + long + `[7.1,50.1]]},"properties":{}}` + "\n" +
		`{"type":"Feature","id":"w5","geometry":{"type":"GeometryCollection","coordinates":[]},"properties":{}}` + "\n" +
		`{"type":"Feature","id":"a8","geometry":{"type":"MultiPolygon","coordinates":[[[[7,50],[7.1,50],[7,50]]]]},"properties":{}}` + "\n" +
		`{"type":"Feature","id":"q9","geometry":{"type":"Point","coordinates":[7,50]},"properties":{}}` + "\n" +
		`{"type":"Feature","id":"n10","geometry":{"type":"Point","coordinates":"x"},"properties":{}}` + "\n" +
		`{"type":"Feature","id":"w11","geometry":{"type":"LineString","coordinates":[[7,50]]},"properties":{}}` // no final newline
	var got []*Raw
	skipped, err := ReadGeoJSONSeq(strings.NewReader(in), func(r *Raw) error { got = append(got, r); return nil })
	if err != nil {
		t.Fatal(err)
	}
	if skipped != 5 || len(got) != 4 {
		t.Fatalf("skipped %d, got %d", skipped, len(got))
	}
	if got[0].ID != "n1" || got[0].Kind != GeomPoint || got[0].Point != (geo.Point{Lon: 7.1, Lat: 50.1}) ||
		got[0].Tags["amenity"] != "toilets" || len(got[0].Tags) != 2 {
		t.Errorf("point %+v", got[0])
	}
	if got[1].Kind != GeomLine || len(got[1].Lines) != 1 {
		t.Errorf("multilinestring %+v", got[1])
	}
	if got[2].ID != "w3" || got[2].Kind != GeomArea || len(got[2].Polys) != 1 || len(got[2].Polys[0]) != 1 || len(got[2].Polys[0][0]) != 3 {
		t.Errorf("polygon %+v", got[2])
	}
	if got[3].ID != "w4" || len(got[3].Lines[0]) != 200001 {
		t.Errorf("long line %d", len(got[3].Lines[0]))
	}

	if _, err := ReadGeoJSONSeq(strings.NewReader("{not json\n"), func(*Raw) error { return nil }); err == nil {
		t.Error("malformed JSON accepted")
	}
	stop := errors.New("stop")
	if _, err := ReadGeoJSONSeq(strings.NewReader(in), func(*Raw) error { return stop }); !errors.Is(err, stop) {
		t.Errorf("callback error: %v", err)
	}
	if _, err := ReadGeoJSONSeq(errReader{}, func(*Raw) error { return nil }); err == nil {
		t.Error("read error swallowed")
	}
}

type errReader struct{}

func (errReader) Read([]byte) (int, error) { return 0, io.ErrUnexpectedEOF }

func TestClassify(t *testing.T) {
	type c struct {
		raw    *Raw
		kind   string
		seed   bool
		levels []float64
	}
	cases := []c{
		{area("w1", 0, 0, 5, 5, "indoor=room", "level=1"), "room", true, []float64{1}},
		{area("w1", 0, 0, 5, 5, "indoor=room"), "room", true, []float64{0}},
		{area("w1", 0, 0, 5, 5, "indoor=area", "repeat_on=1-2", "level=0"), "area", true, []float64{0, 1, 2}},
		{area("w1", 0, 0, 5, 5, "indoor=corridor", "level=0", "shop=kiosk"), "corridor", true, []float64{0}},
		{area("w1", 0, 0, 5, 5, "indoor=level", "level=-1"), "level", true, []float64{-1}},
		{area("w1", 0, 0, 5, 5, "indoor=wall", "level=0"), "wall", false, []float64{0}},
		{area("w1", 0, 0, 5, 5, "indoor=room", "level=0", "shop=bakery"), "food", true, []float64{0}},
		{area("w1", 0, 0, 5, 5, "indoor=room", "level=0", "amenity=cafe"), "food", true, []float64{0}},
		{area("w1", 0, 0, 5, 5, "indoor=room", "level=0", "shop=clothes"), "shop", true, []float64{0}},
		{area("w1", 0, 0, 5, 5, "indoor=room", "level=0", "shop=vacant"), "room", true, []float64{0}},
		{area("w1", 0, 0, 5, 5, "indoor=room", "level=0", "room=toilets"), "toilets", true, []float64{0}},
		{area("w1", 0, 0, 5, 5, "indoor=room", "level=0", "room=elevator"), "elevator", true, []float64{0}},
		{area("w1", 0, 0, 5, 5, "indoor=room", "level=0", "stairs=yes"), "stairs", true, []float64{0}},
		{area("w1", 0, 0, 5, 5, "indoor=area", "level=0", "aeroway=gate"), "gate", true, []float64{0}},
		{area("w1", 0, 0, 5, 5, "indoor=room", "level=0", "access=no"), "restricted", true, []float64{0}},
		{area("w1", 0, 0, 5, 5, "indoor=room", "level=0", "office=company"), "service", true, []float64{0}},
		{area("w1", 0, 0, 5, 5, "indoor=room", "level=0", "amenity=bench"), "room", true, []float64{0}},
		{area("w1", 0, 0, 5, 5, "shop=shoes", "level=1"), "shop", false, []float64{1}},
		{area("w1", 0, 0, 5, 5, "railway=platform", "level=-2"), "platform", false, []float64{-2}},
		{area("w1", 0, 0, 5, 5, "highway=pedestrian", "area=yes", "level=0"), "corridor", false, []float64{0}},
		{area("w1", 0, 0, 5, 5, "railway=platform"), "", false, nil},               // no level, not indoor
		{area("w1", 0, 0, 5, 5, "shop=mall", "level=0", "name=M"), "", false, nil}, // container
		{area("w1", 0, 0, 5, 5, "building=yes", "level=0", "shop=x"), "", false, nil},
		{area("w1", 0, 0, 5, 5, "access=private", "level=0"), "", false, nil},
		{area("w1", 0, 0, 5, 5, "indoor=room", "level=EG"), "", false, nil}, // bad level
		{area("w1", 0, 0, 5, 5, "indoor=room", "level=0", "repeat_on=x"), "", false, nil},
		{line("w1", []string{"highway=steps", "level=0;1"}, 0, 0, 1, 1), "steps", false, []float64{0, 1}},
		{line("w1", []string{"highway=steps", "conveying=yes", "level=0"}, 0, 0, 1, 1), "escalator", false, []float64{0}},
		{line("w1", []string{"highway=footway", "level=0"}, 0, 0, 1, 1), "footway", false, []float64{0}},
		{line("w1", []string{"highway=corridor", "indoor=yes"}, 0, 0, 1, 1), "footway", false, []float64{0}},
		{line("w1", []string{"highway=footway"}, 0, 0, 1, 1), "", false, nil},
		{line("w1", []string{"highway=elevator", "level=0;1"}, 0, 0, 1, 1), "elevator", false, []float64{0, 1}},
		{line("w1", []string{"indoor=wall", "level=0"}, 0, 0, 1, 1), "wall", false, []float64{0}},
		{line("w1", []string{"indoor=corridor"}, 0, 0, 1, 1), "footway", false, []float64{0}},
		{line("w1", []string{"railway=rail", "level=0"}, 0, 0, 1, 1), "", false, nil},
		{point("n1", 0, 0, "amenity=toilets", "level=0"), "toilets", false, []float64{0}},
		{point("n1", 0, 0, "highway=elevator", "repeat_on=-1;0"), "elevator", false, []float64{-1, 0}},
		{point("n1", 0, 0, "conveying=forward", "level=0"), "escalator", false, []float64{0}},
		{point("n1", 0, 0, "stairs=yes", "level=0"), "stairs", false, []float64{0}},
		{point("n1", 0, 0, "aeroway=gate", "ref=A1", "level=0"), "gate", false, []float64{0}},
		{point("n1", 0, 0, "shop=ticket", "level=0"), "ticket", false, []float64{0}},
		{point("n1", 0, 0, "amenity=vending_machine", "vending=public_transport_tickets", "level=0"), "ticket", false, []float64{0}},
		{point("n1", 0, 0, "amenity=pharmacy", "level=0"), "pharmacy", false, []float64{0}},
		{point("n1", 0, 0, "amenity=atm", "level=0"), "atm", false, []float64{0}},
		{point("n1", 0, 0, "amenity=luggage_locker", "level=0"), "luggage", false, []float64{0}},
		{point("n1", 0, 0, "amenity=fast_food", "level=0"), "food", false, []float64{0}},
		{point("n1", 0, 0, "tourism=information", "level=0"), "info", false, []float64{0}},
		{point("n1", 0, 0, "shop=books", "level=0"), "shop", false, []float64{0}},
		{point("n1", 0, 0, "entrance=main", "door=hinged", "level=0"), "entrance", false, []float64{0}},
		{point("n1", 0, 0, "indoor=door"), "door", false, []float64{0}},
		{point("n1", 0, 0, "name=Lounge", "level=1"), "poi", false, []float64{1}},
		{point("n1", 0, 0, "amenity=post_box", "level=1"), "poi", false, []float64{1}},
		{point("n1", 0, 0, "amenity=bench", "level=1"), "", false, nil},
		{point("n1", 0, 0, "railway=station", "name=X", "level=1"), "", false, nil},
		{point("n1", 0, 0, "shop=books"), "", false, nil}, // no level
		{point("n1", 0, 0, "level=0"), "", false, nil},
	}
	for i, tc := range cases {
		res := classify(tc.raw)
		f := res.feature
		if tc.kind == "" {
			if f != nil {
				t.Errorf("%d %v: classified as %s", i, tc.raw.Tags, f.Kind)
			}
			continue
		}
		if f == nil || f.Kind != tc.kind || f.Seed != tc.seed || !slices.Equal(f.Levels, tc.levels) {
			t.Errorf("%d %v: %+v, want %s seed=%v %v", i, tc.raw.Tags, f, tc.kind, tc.seed, tc.levels)
		}
	}
	// Door without level: pending until the outline check.
	if f := classify(point("n1", 0, 0, "door=yes")).feature; f == nil || !f.LevelFromOutline || len(f.Levels) != 0 {
		t.Errorf("door %+v", f)
	}
	if !classify(area("w1", 0, 0, 5, 5, "indoor=room", "level=EG")).badLevel {
		t.Error("bad level not counted")
	}
	// Tag subset, clipping, level label.
	long := strings.Repeat("ä", 250)
	f := classify(area("w1", 0, 0, 5, 5, "indoor=room", "level=0", "shop=kiosk", "description="+long, "phone=123", "name=N", "ref=R")).feature
	if f.Tags["description"] != strings.Repeat("ä", 200) || f.Tags["phone"] != "" || f.Name != "N" || f.Ref != "R" || len(f.Tags) != 2 {
		t.Errorf("tags %v", f.Tags)
	}
	if math.Abs(f.AreaM2-25) > 0.1 {
		t.Errorf("area %v", f.AreaM2)
	}
	for tags, label := range map[string]string{"level:ref=EG,name=Erdgeschoss": "EG", "level_ref=U1": "U1",
		"name=Ebene 0": "Ebene 0", "name=Erdgeschoss": ""} {
		f := classify(area("w1", 0, 0, 5, 5, append([]string{"indoor=level", "level=0"}, strings.Split(tags, ",")...)...)).feature
		if f.LevelLabel != label {
			t.Errorf("%s: label %q", tags, f.LevelLabel)
		}
	}
}

func TestContainersAndStations(t *testing.T) {
	cases := map[string]int{
		"aeroway=terminal":          classTerminal,
		"building=train_station":    classStation,
		"building=transportation":   classStation,
		"railway=station":           classStation,
		"public_transport=station":  classStation,
		"shop=mall":                 classMall,
		"shop=department_store":     classMall,
		"building=yes":              classBuilding,
		"aeroway=aerodrome":         classAerodrome,
		"amenity=university":        -1,
		"building=no":               -1,
		"aeroway=terminal,noname=1": -1,
	}
	for tags, class := range cases {
		kv := strings.Split(tags, ",")
		if !strings.Contains(tags, "noname") {
			kv = append(kv, "name=X")
		}
		c := asContainer(area("w1", 0, 0, 10, 10, kv...))
		if (c == nil) != (class < 0) || (c != nil && c.class != class) {
			t.Errorf("%s: %+v", tags, c)
		}
	}
	if classify(point("n1", 0, 0, "railway=station", "name=Hbf")).station == nil ||
		classify(point("n1", 0, 0, "railway=station")).station != nil {
		t.Error("station nodes")
	}
}

func TestClusteringByGap(t *testing.T) {
	b := NewBuilder(DefaultOptions())
	rooms(b, 8, 0, 0, "0")    // 0..174 m
	rooms(b, 8, 200, 0, "0")  // 26 m gap: same venue
	rooms(b, 8, 1000, 0, "0") // far away: own venue
	vs := b.Build()
	if len(vs) != 2 || len(vs[0].Features) != 16 || len(vs[1].Features) != 8 {
		t.Fatalf("%d venues", len(vs))
	}
	b = NewBuilder(DefaultOptions())
	rooms(b, 8, 0, 0, "0")
	rooms(b, 8, 210, 0, "0") // 36 m gap
	if vs := b.Build(); len(vs) != 2 || b.Stats.Clusters != 2 {
		t.Fatalf("%d venues", len(vs))
	}
}

func TestThresholds(t *testing.T) {
	cases := []struct {
		name  string
		setup func(b *Builder)
		keep  bool
	}{
		{"8 rooms, 3200 m²", func(b *Builder) { rooms(b, 8, 0, 0, "0") }, true},
		{"7 rooms", func(b *Builder) { rooms(b, 7, 0, 0, "0") }, false},
		{"8 small rooms", func(b *Builder) {
			for i := 0; i < 8; i++ {
				nextID++
				b.Add(area(fmt.Sprintf("w%d", nextID), float64(i)*6, 0, 5, 5, "indoor=room", "level=0"))
			}
		}, false},
		{"2 levels with 4 features", func(b *Builder) {
			for i := 0; i < 4; i++ {
				nextID++
				b.Add(area(fmt.Sprintf("w%d", nextID), float64(i)*6, 0, 5, 5, "indoor=room", "level=0;1"))
			}
		}, true},
		{"2 levels, one with 3 features", func(b *Builder) {
			for i := 0; i < 4; i++ {
				nextID++
				lv := "0"
				if i < 3 {
					lv = "0;1"
				}
				b.Add(area(fmt.Sprintf("w%d", nextID), float64(i)*6, 0, 5, 5, "indoor=room", "level="+lv))
			}
		}, false},
		{"level outlines do not count as room area", func(b *Builder) {
			for i := 0; i < 8; i++ {
				nextID++
				b.Add(area(fmt.Sprintf("w%d", nextID), float64(i)*6, 0, 100, 100, "indoor=level", "level=0"))
			}
		}, false},
	}
	for _, tc := range cases {
		b := NewBuilder(DefaultOptions())
		tc.setup(b)
		vs := b.Build()
		if (len(vs) == 1) != tc.keep || b.Stats.SkippedSmall != map[bool]int{true: 0, false: 1}[tc.keep] {
			t.Errorf("%s: %d venues, stats %+v", tc.name, len(vs), b.Stats)
		}
	}
}

func TestNaming(t *testing.T) {
	type tc struct {
		name, setupDesc string
		extra           []*Raw
		wantID, want    string
		kind            string
	}
	cases := []tc{
		{"terminal beats aerodrome and building", "", []*Raw{
			area("w1", -500, -500, 2000, 2000, "aeroway=aerodrome", "name=Flughafen"),
			area("w2", -10, -10, 400, 50, "aeroway=terminal", "name=Terminal 1"),
			area("w3", -5, -5, 300, 40, "building=yes", "name=Gebäude"),
		}, "w2", "Terminal 1", "airport"},
		{"smallest building of a class", "", []*Raw{
			area("w3", -5, -5, 300, 40, "building=yes", "name=Klein"),
			area("w4", -50, -50, 500, 200, "building=yes", "name=Groß"),
		}, "w3", "Klein", "building"},
		{"station building", "", []*Raw{area("r9", -5, -5, 300, 40, "building=train_station", "name=Hbf")}, "r9", "Hbf", "station"},
		{"mall", "", []*Raw{area("w5", -5, -5, 300, 40, "shop=mall", "name=Center")}, "w5", "Center", "mall"},
		{"aerodrome only", "", []*Raw{area("w1", -500, -500, 2000, 2000, "aeroway=aerodrome", "name=Flughafen")}, "w1", "Flughafen", "airport"},
		{"container covering too little", "", []*Raw{area("w6", -5, -5, 60, 40, "building=yes", "name=Ecke")}, "", "", "other"},
		{"station node", "", []*Raw{point("n7", 100, 10, "railway=station", "name=Bahnhof"),
			point("n8", 5000, 10, "railway=station", "name=Fern")}, "n7", "Bahnhof", "station"},
		{"level name", "", []*Raw{
			area("w11", 0, 30, 100, 20, "indoor=level", "level=0", "name=Halle"),
			area("w12", 0, 30, 100, 20, "indoor=level", "level=1", "name=Halle"),
			area("w13", 0, 30, 100, 20, "indoor=level", "level=2", "name=Andere"),
		}, "", "Halle", "other"},
	}
	for _, c := range cases {
		b := NewBuilder(DefaultOptions())
		rooms(b, 8, 0, 0, "0")
		for _, r := range c.extra {
			b.Add(r)
		}
		vs := b.Build()
		if len(vs) != 1 {
			t.Fatalf("%s: %d venues", c.name, len(vs))
		}
		v := vs[0]
		wantID := c.wantID
		if wantID == "" {
			wantID = "c" + smallestID(v.seeds)
		}
		if v.ID != wantID || v.Name != c.want || v.Kind != c.kind {
			t.Errorf("%s: %s %q %s", c.name, v.ID, v.Name, v.Kind)
		}
		if !strings.HasPrefix(v.ID, "c") && !ValidID(v.ID) {
			t.Errorf("%s: invalid id %q", c.name, v.ID)
		}
	}
	// No name at all.
	b := NewBuilder(DefaultOptions())
	rooms(b, 8, 0, 0, "0")
	v := b.Build()[0]
	if v.Name != "" || v.Kind != "other" || v.ID != "c"+smallestID(v.seeds) || !strings.HasPrefix(v.ID, "cw") {
		t.Errorf("unnamed: %s %q %s", v.ID, v.Name, v.Kind)
	}
}

func TestSharedContainerGivesUniqueIDs(t *testing.T) {
	// Two separate clusters inside one aerodrome: the larger keeps the aerodrome's id.
	b := NewBuilder(DefaultOptions())
	b.Add(area("w1", -500, -500, 3000, 1000, "aeroway=aerodrome", "name=Flughafen"))
	rooms(b, 9, 0, 0, "0")
	rooms(b, 8, 1000, 0, "0")
	vs := b.Build()
	if len(vs) != 2 || vs[0].ID != "w1" || vs[1].ID == "w1" || !strings.HasPrefix(vs[1].ID, "cw") ||
		vs[0].Name != "Flughafen" || vs[1].Name != "Flughafen" {
		t.Fatalf("%s %s", vs[0].ID, vs[1].ID)
	}
}

func TestSplitByTerminals(t *testing.T) {
	b := NewBuilder(DefaultOptions())
	// Two terminals joined by a corridor (one cluster), plus a few rooms outside both.
	b.Add(area("w1", -10, -10, 200, 40, "aeroway=terminal", "name=Terminal 1"))
	b.Add(area("w2", 250, -10, 200, 40, "aeroway=terminal", "name=Terminal 2"))
	b.Add(area("w3", -500, -500, 1500, 1000, "aeroway=aerodrome", "name=Flughafen"))
	rooms(b, 8, 0, 0, "0")
	rooms(b, 8, 260, 0, "0")
	b.Add(area("w9", 170, 5, 100, 10, "indoor=corridor", "level=0")) // centre between the terminals
	rooms(b, 1, 0, 40, "0")                                          // just outside terminal 1: joins it
	vs := b.Build()
	if b.Stats.Clusters != 1 || b.Stats.SplitClusters != 1 || len(vs) != 2 {
		t.Fatalf("clusters %d split %d venues %d", b.Stats.Clusters, b.Stats.SplitClusters, len(vs))
	}
	names := []string{vs[0].Name, vs[1].Name}
	slices.Sort(names)
	if names[0] != "Terminal 1" || names[1] != "Terminal 2" {
		t.Fatal(names)
	}
	total := len(vs[0].seeds) + len(vs[1].seeds)
	if total != 18 {
		t.Fatalf("%d seeds", total)
	}
}

func TestSplitHugeCluster(t *testing.T) {
	opt := DefaultOptions()
	opt.SplitExtentM = 500
	b := NewBuilder(opt)
	b.Add(area("w1", -5, -5, 200, 30, "building=yes", "name=West"))
	b.Add(area("w2", 395, -5, 200, 30, "building=yes", "name=Ost"))
	rooms(b, 28, 0, 0, "0") // 0..614 m, one chain
	vs := b.Build()
	if b.Stats.SplitClusters != 1 || len(vs) < 2 {
		t.Fatalf("split %d venues %d", b.Stats.SplitClusters, len(vs))
	}
	names := map[string]bool{}
	for _, v := range vs {
		names[v.Name] = true
	}
	if !names["West"] || !names["Ost"] {
		t.Fatal(names)
	}
	// Below the limits: no split.
	b = NewBuilder(DefaultOptions())
	b.Add(area("w1", -5, -5, 200, 30, "building=yes", "name=West"))
	b.Add(area("w2", 395, -5, 200, 30, "building=yes", "name=Ost"))
	rooms(b, 28, 0, 0, "0")
	if vs := b.Build(); len(vs) != 1 || b.Stats.SplitClusters != 0 {
		t.Fatal(len(vs))
	}
}

func TestAttach(t *testing.T) {
	b := NewBuilder(DefaultOptions())
	rooms(b, 8, 0, 0, "0")
	rooms(b, 8, 300, 0, "1")
	b.Add(point("n1", 10, 30, "shop=books", "level=0"))       // 10 m north of venue A
	b.Add(point("n2", 290, 10, "amenity=toilets", "level=1")) // between, nearer to B
	b.Add(point("n3", 10, 50, "shop=books", "level=0"))       // 30 m away: ignored
	b.Add(line("w1", []string{"indoor=wall", "level=0"}, 0, 5, 10, 5))
	b.Add(line("w1", []string{"indoor=wall", "level=0"}, 0, 5, 10, 5))
	vs := b.Build()
	if len(vs) != 2 {
		t.Fatal(len(vs))
	}
	has := func(v *Venue, id string) bool {
		for _, f := range v.Features {
			if f.ID == id {
				return true
			}
		}
		return false
	}
	a, bv := vs[0], vs[1]
	if has(bv, "n1") {
		a, bv = bv, a
	}
	if !has(a, "n1") || !has(bv, "n2") || has(a, "n3") || has(bv, "n3") || !has(a, "w1") {
		t.Fatal("attachment")
	}
	if b.Stats.Unattached != 1 {
		t.Fatalf("unattached %d", b.Stats.Unattached)
	}
}

func TestDuplicateLines(t *testing.T) {
	b := NewBuilder(DefaultOptions())
	rooms(b, 8, 0, 0, "0")
	b.Add(area("w5", 0, 30, 20, 20, "indoor=corridor"))
	b.Add(&Raw{ID: "w5", Kind: GeomLine, Lines: [][]geo.Point{rect(0, 30, 20, 20)[0]}, Tags: tagMap([]string{"indoor=corridor"})})
	b.Build()
	if b.Stats.DuplicateLines != 1 {
		t.Fatal(b.Stats)
	}
}

func TestDoorLevels(t *testing.T) {
	b := NewBuilder(DefaultOptions())
	rooms(b, 8, 0, 0, "1")
	b.Add(area("w1", 0, 0, 200, 20, "indoor=level", "level=1"))
	b.Add(area("w2", 0, 0, 200, 20, "indoor=level", "level=2"))
	b.Add(point("n1", 20.2, 10, "door=yes"))    // on room walls (x=20, 22): level 1
	b.Add(point("n2", 100, 10, "door=yes"))     // inside a room, not on a wall: dropped
	b.Add(point("n3", 200, 10, "entrance=yes")) // on the level outlines only: 1 and 2
	vs := b.Build()
	got := map[string][]float64{}
	for _, f := range vs[0].Features {
		if f.T == GeomPoint {
			got[f.ID] = f.Levels
		}
	}
	if !slices.Equal(got["n1"], []float64{1}) || got["n2"] != nil || !slices.Equal(got["n3"], []float64{1, 2}) ||
		b.Stats.DoorsWithoutLevel != 1 {
		t.Fatalf("%v %+v", got, b.Stats)
	}
}

func TestLevelsAndDefault(t *testing.T) {
	cases := []struct {
		per  map[float64]int
		want float64
	}{
		{map[float64]int{-1: 5, 0: 1, 1: 9}, 0},
		{map[float64]int{-1: 5, 1: 9}, 1},
		{map[float64]int{-2: 5, -1: 5, 3: 2}, -1},
		{map[float64]int{-1: 5, 1: 5}, 1},
		{map[float64]int{}, 0},
	}
	for _, c := range cases {
		if got := defaultLevel(c.per); got != c.want {
			t.Errorf("%v: %v", c.per, got)
		}
	}
	b := NewBuilder(DefaultOptions())
	rooms(b, 8, 0, 0, "-1")
	b.Add(area("w1", 0, 0, 100, 20, "indoor=level", "level=-1", "level:ref=UG"))
	b.Add(area("w2", 0, 0, 100, 20, "indoor=level", "repeat_on=-1;0", "level:ref=XX")) // several levels: no label
	b.Add(area("w3", 0, 0, 100, 20, "indoor=level", "level=0.5", "level:ref=Zwischengeschoss"))
	v := b.Build()[0]
	want := []Level{{-1, "UG"}, {0, "0"}, {0.5, "0.5"}}
	if !slices.Equal(v.Levels, want) || v.DefaultLevel != 0 {
		t.Fatalf("%v default %v", v.Levels, v.DefaultLevel)
	}
}

func TestEncodeDropsDegenerateGeometry(t *testing.T) {
	v := &Venue{ID: "w1", Kind: "other", BBox: geo.BBox{MinLon: 7.00000049, MinLat: 50.0000001, MaxLon: 7.1000001, MaxLat: 50.1}}
	tiny := geo.Polygon{geo.Ring{pt(0, 0), pt(0.01, 0), pt(0.01, 0.01)}}
	v.Features = []*Feature{
		{ID: "w2", T: GeomArea, Kind: "room", Levels: []float64{0}, Polys: []geo.Polygon{tiny}},
		{ID: "w3", T: GeomLine, Kind: "wall", Levels: []float64{0}, Lines: [][]geo.Point{{pt(0, 0), pt(0.01, 0)}}},
		{ID: "w4", T: GeomArea, Kind: "room", Levels: []float64{0}, Polys: []geo.Polygon{append(rect(0, 0, 10, 10), geo.Ring{pt(1, 1), pt(1.01, 1), pt(1, 1.01)})}},
		{ID: "n5", T: GeomPoint, Kind: "door", Levels: []float64{0}, Point: pt(0, 0)},
		{ID: "x6", T: 'x'},
	}
	doc, e := Encode(v, "ts", DefaultSimplifyM)
	if len(doc.Features) != 2 || e.Features != 2 || doc.Features[0].ID != "w4" || len(doc.Features[0].G.([][]string)[0]) != 1 {
		t.Fatalf("%+v", doc.Features)
	}
	if doc.BBox != [4]float64{7, 50, 7.100001, 50.1} || doc.Levels == nil {
		t.Fatalf("bbox %v", doc.BBox)
	}
	if outward(geo.EmptyBBox()) != [4]float64{} {
		t.Fatal("empty bbox")
	}
	_, gz, err := Marshal(doc)
	if err != nil {
		t.Fatal(err)
	}
	r, err := gzip.NewReader(bytes.NewReader(gz))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := io.ReadAll(r); err != nil {
		t.Fatal(err)
	}
	if _, _, err := Marshal(math.NaN()); err == nil {
		t.Fatal("NaN marshalled")
	}
}

func TestIDs(t *testing.T) {
	ids := []string{"r1", "w10", "n5", "w9", "n40", "x1", ""}
	slices.SortFunc(ids, compareIDs)
	if strings.Join(ids, ",") != "n5,n40,w9,w10,r1,x1," {
		t.Fatal(ids)
	}
	for id, ok := range map[string]bool{"w1": true, "cw123": true, "a-b_C": true, "": false, "w/1": false,
		strings.Repeat("a", 40): true, strings.Repeat("a", 41): false, "ü": false} {
		if ValidID(id) != ok {
			t.Errorf("ValidID(%q)", id)
		}
	}
}

func TestBuildingVenues(t *testing.T) {
	b := NewBuilder(DefaultOptions())
	// A terminal without indoor areas, with shops and gates on two levels.
	b.Add(area("w1", 0, 0, 300, 100, "aeroway=terminal", "name=Flugsteig X"))
	for i := 0; i < 4; i++ {
		b.Add(point(fmt.Sprintf("n%d", 10+i), float64(20+i*50), 50, "shop=gift", "level=0"))
		b.Add(point(fmt.Sprintf("n%d", 20+i), float64(20+i*50), 60, "aeroway=gate", "ref=X"+fmt.Sprint(i), "level=1"))
	}
	b.Add(point("n30", 150, 115, "aeroway=gate", "ref=X9", "level=1")) // 15 m outside: joins
	b.Add(point("n31", 150, 140, "aeroway=gate", "level=1"))           // 40 m outside: ignored
	// A named building that is not a terminal/station/mall does not form a venue.
	b.Add(area("w2", 1000, 0, 100, 100, "building=office", "name=Büro"))
	for i := 0; i < 8; i++ {
		b.Add(point(fmt.Sprintf("n%d", 40+i), 1010+float64(i)*10, 50, "shop=gift", "level="+fmt.Sprint(i%2)))
	}
	// A terminal with too little.
	b.Add(area("w3", 2000, 0, 100, 100, "aeroway=terminal", "name=Klein"))
	b.Add(point("n60", 2050, 50, "shop=gift", "level=0"))
	vs := b.Build()
	if len(vs) != 1 || b.Stats.BuildingVenues != 1 || b.Stats.SkippedSmall != 1 {
		t.Fatalf("%d venues, stats %+v", len(vs), b.Stats)
	}
	v := vs[0]
	if v.ID != "w1" || v.Name != "Flugsteig X" || v.Kind != "airport" || len(v.Features) != 10 {
		t.Fatalf("%s %q %s %d", v.ID, v.Name, v.Kind, len(v.Features))
	}
	if f := v.Features[0]; f.ID != "w1" || f.Kind != "level" || !slices.Equal(f.Levels, []float64{0, 1}) {
		t.Fatalf("outline %+v", f)
	}
}

func TestClaimedBuilding(t *testing.T) {
	b := NewBuilder(DefaultOptions())
	b.Add(area("w1", -10, -10, 600, 60, "building=train_station", "name=Hbf"))
	rooms(b, 8, 0, 0, "0")                               // all inside the station: it names and claims the venue
	b.Add(point("n1", 500, 20, "shop=books", "level=0")) // far from the rooms, inside the station
	vs := b.Build()
	if len(vs) != 1 || vs[0].ID != "w1" || vs[0].Kind != "station" || b.Stats.BuildingVenues != 0 {
		t.Fatalf("%d venues", len(vs))
	}
	ids := map[string]bool{}
	for _, f := range vs[0].Features {
		ids[f.ID] = true
	}
	if !ids["n1"] || !ids["w1"] { // the POI and the building outline (no indoor=level areas)
		t.Fatal(ids)
	}
}
