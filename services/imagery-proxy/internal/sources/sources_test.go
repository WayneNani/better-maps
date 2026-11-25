package sources

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// An L-shaped polygon with a hole: outer (0,0)-(10,0)-(10,5)-(5,5)-(5,10)-(0,10), hole (1,1)-(2,1)-(2,2)-(1,2).
const lShape = `{"type": "Feature", "geometry": {"type": "Polygon", "coordinates": [
  [[0,0],[10,0],[10,5],[5,5],[5,10],[0,10],[0,0]],
  [[1,1],[2,1],[2,2],[1,2],[1,1]]]}}`

func mustCoverage(t *testing.T, s string) *Coverage {
	t.Helper()
	c, err := ParseGeoJSON(strings.NewReader(s))
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func TestContainsPoint(t *testing.T) {
	c := mustCoverage(t, lShape)
	if c.BBox != (BBox{0, 0, 10, 10}) {
		t.Fatal(c.BBox)
	}
	cases := []struct {
		p    Point
		want bool
	}{
		{Point{3, 3}, true}, {Point{8, 2}, true}, {Point{2, 8}, true},
		{Point{8, 8}, false},     // the notch of the L
		{Point{1.5, 1.5}, false}, // the hole
		{Point{-1, 5}, false}, {Point{11, 1}, false},
	}
	for _, c2 := range cases {
		if got := c.ContainsPoint(c2.p); got != c2.want {
			t.Errorf("%v: %v", c2.p, got)
		}
	}
	var world *Coverage
	if !world.ContainsPoint(Point{170, -80}) || !world.IntersectsBBox(BBox{1, 1, 2, 2}) {
		t.Fatal("nil coverage must cover the world")
	}
}

func TestIntersectsBBox(t *testing.T) {
	c := mustCoverage(t, lShape)
	cases := []struct {
		b    BBox
		want bool
	}{
		{BBox{2.5, 2.5, 3, 3}, true},      // inside
		{BBox{-5, -5, 20, 20}, true},      // contains the polygon
		{BBox{7, 7, 9, 9}, false},         // in the notch
		{BBox{4, 7, 6, 8}, true},          // overlaps the vertical edge x=5
		{BBox{20, 20, 21, 21}, false},     // far away
		{BBox{-1, 4.5, 11, 4.8}, true},    // a thin strip crossing, no corner inside
		{BBox{1.2, 1.2, 1.8, 1.8}, false}, // inside the hole
	}
	for _, c2 := range cases {
		if got := c.IntersectsBBox(c2.b); got != c2.want {
			t.Errorf("%v: %v", c2.b, got)
		}
	}
}

func TestParseGeoJSONVariants(t *testing.T) {
	multi := `{"type": "FeatureCollection", "features": [
	  {"type": "Feature", "geometry": null},
	  {"type": "Feature", "geometry": {"type": "MultiPolygon", "coordinates": [
	    [[[0,0],[1,0],[1,1],[0,0]]], [[[5,5],[6,5],[6,6],[5,5]]]]}},
	  {"type": "Feature", "geometry": {"type": "GeometryCollection", "geometries": [
	    {"type": "Polygon", "coordinates": [[[8,8],[9,8],[9,9],[8,8]]]}]}}]}`
	c := mustCoverage(t, multi)
	if len(c.Polygons) != 3 || c.BBox != (BBox{0, 0, 9, 9}) {
		t.Fatal(len(c.Polygons), c.BBox)
	}
	for _, bad := range []string{
		``, `{`, `{"type": "Point", "coordinates": [1,2]}`, `{"type": "FeatureCollection", "features": []}`,
		`{"type": "Polygon", "coordinates": [[[0,0],[1,1]]]}`, `{"type": "Polygon", "coordinates": [[[0,0],[1,1],[200,1]]]}`,
		`{"type": "Polygon", "coordinates": "x"}`, `{"type": "MultiPolygon", "coordinates": [1]}`,
	} {
		if _, err := ParseGeoJSON(strings.NewReader(bad)); err == nil {
			t.Errorf("accepted %q", bad)
		}
	}
	deep := `{"type": "Feature", "geometry": ` + strings.Repeat(`{"type": "GeometryCollection", "geometries": [`, 6) +
		`{"type": "Polygon", "coordinates": [[[0,0],[1,0],[1,1],[0,0]]]}` + strings.Repeat(`]}`, 6) + `}`
	if _, err := ParseGeoJSON(strings.NewReader(deep)); err == nil {
		t.Error("deep nesting accepted")
	}
}

func TestParseBBox(t *testing.T) {
	b, err := ParseBBox(" 7.0, 50.7 ,7.2,50.8")
	if err != nil || b != (BBox{7, 50.7, 7.2, 50.8}) {
		t.Fatal(b, err)
	}
	for _, bad := range []string{"", "1,2,3", "1,2,3,x", "7.2,50,7.0,51", "0,0,181,1", "0,-91,1,1", "1,1,1,1"} {
		if _, err := ParseBBox(bad); err == nil {
			t.Errorf("accepted %q", bad)
		}
	}
}

func TestParseValidation(t *testing.T) {
	open := func(string) (io.ReadCloser, error) { return io.NopCloser(strings.NewReader(lShape)), nil }
	for _, bad := range []string{
		`{"sources": []}`,
		`{"sources": [{"id": "Bad ID", "name": "n", "attribution": "a", "license": "l"}]}`,
		`{"sources": [{"id": "a", "name": "n", "attribution": "a", "license": "l"}, {"id": "a", "name": "n", "attribution": "a", "license": "l"}]}`,
		`{"sources": [{"id": "a", "name": "", "attribution": "a", "license": "l"}]}`,
		`{"sources": [{"id": "a", "name": "n", "attribution": "a", "license": "l", "min_zoom": 30}]}`,
		`{"sources": [{"id": "a", "name": "n", "attribution": "a", "license": "l", "extra": 1}]}`,
	} {
		if _, err := Parse(strings.NewReader(bad), open); err == nil {
			t.Errorf("accepted %s", bad)
		}
	}
	failing := func(string) (io.ReadCloser, error) { return nil, errors.New("nope") }
	if _, err := Parse(strings.NewReader(`{"sources": [{"id": "a", "name": "n", "attribution": "a", "license": "l", "coverage": "x"}]}`), failing); err == nil {
		t.Error("missing coverage accepted")
	}
	broken := func(string) (io.ReadCloser, error) { return io.NopCloser(strings.NewReader("{}")), nil }
	if _, err := Parse(strings.NewReader(`{"sources": [{"id": "a", "name": "n", "attribution": "a", "license": "l", "coverage": "x"}]}`), broken); err == nil {
		t.Error("broken coverage accepted")
	}
}

func TestForAndAll(t *testing.T) {
	open := func(ref string) (io.ReadCloser, error) {
		switch ref {
		case "west.json":
			return io.NopCloser(strings.NewReader(`{"type": "Polygon", "coordinates": [[[0,0],[5,0],[5,10],[0,10],[0,0]]]}`)), nil
		case "east.json":
			return io.NopCloser(strings.NewReader(`{"type": "Polygon", "coordinates": [[[5,0],[10,0],[10,10],[5,10],[5,0]]]}`)), nil
		}
		return nil, errors.New("unknown")
	}
	set, err := Parse(strings.NewReader(`{"sources": [
	  {"id": "world", "name": "W", "attribution": "W {year}", "license": "CC BY 4.0", "min_zoom": 0},
	  {"id": "west", "name": "West", "attribution": "West", "license": "x", "min_zoom": 12, "coverage": "west.json"},
	  {"id": "east", "name": "East", "attribution": "East", "license": "x", "min_zoom": 12, "coverage": "east.json"}]}`), open)
	if err != nil {
		t.Fatal(err)
	}
	ids := func(as []Attribution) string {
		var s []string
		for _, a := range as {
			s = append(s, a.ID)
		}
		return strings.Join(s, ",")
	}
	if got := ids(set.All(2026)); got != "east,west,world" {
		t.Fatal(got)
	}
	if a := set.All(2026); a[2].Attribution != "W 2026" || a[0].BBox != [4]float64{5, 0, 10, 10} || a[2].BBox[0] != -180 {
		t.Fatal(a)
	}
	cases := []struct {
		b    BBox
		z    int
		want string
	}{
		{BBox{1, 1, 2, 2}, 14, "west"},
		{BBox{6, 1, 7, 2}, 14, "east"},
		{BBox{4, 1, 6, 2}, 14, "east,west"},   // across the inner border, fully covered
		{BBox{8, 8, 12, 9}, 14, "east,world"}, // partly outside
		{BBox{1, 1, 2, 2}, 11, "world"},       // below min zoom of the states
		{BBox{20, 20, 21, 21}, 14, "world"},
	}
	for _, c := range cases {
		if got := ids(set.For(c.b, c.z, 2026)); got != c.want {
			t.Errorf("%v z%d: %s, want %s", c.b, c.z, got, c.want)
		}
	}
}

func TestLoad(t *testing.T) {
	dir := t.TempDir()
	os.MkdirAll(filepath.Join(dir, "coverage"), 0o755)
	os.WriteFile(filepath.Join(dir, "coverage", "l.geojson"), []byte(lShape), 0o644)
	os.WriteFile(filepath.Join(dir, "sources.json"), []byte(`{"sources": [
	  {"id": "l", "name": "L", "attribution": "L", "license": "x", "coverage": "coverage/l.geojson"}]}`), 0o644)
	set, err := Load(filepath.Join(dir, "sources.json"))
	if err != nil || set.Sources[0].Coverage == nil {
		t.Fatal(err)
	}
	for _, ref := range []string{"../x.geojson", "/etc/passwd"} {
		os.WriteFile(filepath.Join(dir, "bad.json"), []byte(`{"sources": [
		  {"id": "l", "name": "L", "attribution": "L", "license": "x", "coverage": "`+ref+`"}]}`), 0o644)
		if _, err := Load(filepath.Join(dir, "bad.json")); err == nil {
			t.Errorf("%s accepted", ref)
		}
	}
	if _, err := Load(filepath.Join(dir, "missing.json")); err == nil {
		t.Error("missing file accepted")
	}
}

// The shipped configuration must load: every coverage file exists and parses.
func TestShippedConfig(t *testing.T) {
	set, err := Load("../../config/sources.json")
	if err != nil {
		t.Fatal(err)
	}
	if set.Sources[0].Coverage != nil {
		t.Fatal("the bottom source must cover the world (Sentinel-2)")
	}
	bonn := BBox{7.05, 50.70, 7.15, 50.75}
	got := set.For(bonn, 16, 2026)
	if len(got) != 1 || got[0].ID != "nw" {
		t.Fatalf("Bonn: %v", got)
	}
	munich := BBox{11.5, 48.1, 11.6, 48.2}
	if got := set.For(munich, 16, 2026); len(got) != 1 || got[0].ID != "by" {
		t.Fatalf("Munich: %v", got)
	}
	paris := BBox{2.3, 48.8, 2.4, 48.9}
	if got := set.For(paris, 16, 2026); len(got) != 1 || got[0].ID != "s2cloudless2016" {
		t.Fatalf("Paris: %v", got)
	}
	for _, a := range set.All(2026) {
		if strings.Contains(a.Attribution, "{") {
			t.Errorf("%s: unreplaced placeholder", a.ID)
		}
	}
}
