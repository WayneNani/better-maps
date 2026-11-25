package geo

import (
	"math"
	"testing"
)

func near(a, b, eps float64) bool { return math.Abs(a-b) <= eps }

func TestEncodeKnownValue(t *testing.T) {
	// The example of the polyline algorithm documentation, scaled to precision 6.
	pts := []Point{{-120.2, 38.5}, {-120.95, 40.7}, {-126.453, 43.252}}
	got := Encode(pts)
	if got != "_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI" {
		t.Fatalf("Encode = %q", got)
	}
	back, ok := Decode(got)
	if !ok || len(back) != 3 {
		t.Fatal(back, ok)
	}
	for i := range pts {
		if !near(back[i].Lat, pts[i].Lat, 1e-9) || !near(back[i].Lon, pts[i].Lon, 1e-9) {
			t.Fatalf("%d: %v != %v", i, back[i], pts[i])
		}
	}
	if s := Encode(nil); s != "" {
		t.Fatal(s)
	}
	for _, bad := range []string{"_", "~", "\x01\x02", "_izlhA"} {
		if _, ok := Decode(bad); ok {
			t.Errorf("Decode(%q) ok", bad)
		}
	}
}

func TestRoundTripSmallSteps(t *testing.T) {
	pts := Round6([]Point{{8.5612341, 50.0459}, {8.5612344, 50.0459001}, {8.56124, 50.04591}, {-0.000001, -0.000002}})
	if len(pts) != 3 {
		t.Fatalf("duplicates after rounding not dropped: %v", pts)
	}
	back, ok := Decode(Encode(pts))
	if !ok {
		t.Fatal("decode")
	}
	for i := range pts {
		if back[i] != pts[i] {
			t.Fatalf("%v != %v", back[i], pts[i])
		}
	}
}

func TestSimplify(t *testing.T) {
	// A 100 m straight line with a 5 cm bump and a 1 m bump.
	d := 1 / MetersPerDegLat
	line := []Point{{8, 50}, {8, 50 + 10*d}, {8 + 0.05/MetersPerDegLon(50), 50 + 20*d}, {8, 50 + 30*d},
		{8 + 1/MetersPerDegLon(50), 50 + 60*d}, {8, 50 + 100*d}}
	got := Simplify(line, 0.15)
	// The 5 cm bump and the point at 10 m go; the 1 m bump stays and so does the point at 30 m
	// (0.5 m off the chord to the bump).
	if len(got) != 4 || got[1] != line[3] || got[2] != line[4] {
		t.Fatalf("Simplify = %v", got)
	}
	if got := Simplify(line[:2], 0.15); len(got) != 2 {
		t.Fatal(got)
	}
	if got := Simplify(line, 0); len(got) != len(line) {
		t.Fatal(got)
	}
}

func TestSimplifyRing(t *testing.T) {
	d := 1 / MetersPerDegLat
	dl := 1 / MetersPerDegLon(50)
	// 20 m square with collinear points on every side.
	r := Ring{{8, 50}, {8 + 10*dl, 50}, {8 + 20*dl, 50}, {8 + 20*dl, 50 + 10*d}, {8 + 20*dl, 50 + 20*d},
		{8 + 10*dl, 50 + 20*d}, {8, 50 + 20*d}, {8, 50 + 10*d}}
	got := SimplifyRing(r, 0.15)
	if len(got) != 4 {
		t.Fatalf("SimplifyRing = %v", got)
	}
	if a := RingArea(got); !near(a, 400, 1) {
		t.Fatalf("area %v", a)
	}
	if got := SimplifyRing(r[:3], 0.15); len(got) != 3 {
		t.Fatal(got)
	}
}

func TestAreaAndContainment(t *testing.T) {
	d := 1 / MetersPerDegLat
	dl := 1 / MetersPerDegLon(50)
	outer := Ring{{8, 50}, {8 + 100*dl, 50}, {8 + 100*dl, 50 + 50*d}, {8, 50 + 50*d}}
	hole := Ring{{8 + 10*dl, 50 + 10*d}, {8 + 20*dl, 50 + 10*d}, {8 + 20*dl, 50 + 20*d}, {8 + 10*dl, 50 + 20*d}}
	poly := Polygon{outer, hole}
	if a := RingArea(outer); !near(a, 5000, 5) {
		t.Fatalf("outer %v", a)
	}
	if a := PolygonArea(poly); !near(a, 4900, 5) {
		t.Fatalf("polygon %v", a)
	}
	if PolygonArea(nil) != 0 || RingArea(outer[:2]) != 0 {
		t.Fatal("degenerate area")
	}
	in := Point{8 + 50*dl, 50 + 25*d}
	inHole := Point{8 + 15*dl, 50 + 15*d}
	out := Point{8 + 150*dl, 50 + 25*d}
	if !PointInPolygon(in, poly) || PointInPolygon(inHole, poly) || PointInPolygon(out, poly) || PointInPolygon(in, nil) {
		t.Fatal("PointInPolygon")
	}
	if dist := DistanceToRing(Point{8 + 50*dl, 50 + 0.3*d}, outer); !near(dist, 0.3, 0.01) {
		t.Fatalf("distance %v", dist)
	}
	if !math.IsInf(DistanceToRing(in, nil), 1) {
		t.Fatal("empty ring")
	}
}

func TestBBox(t *testing.T) {
	b := EmptyBBox()
	if b.Valid() {
		t.Fatal("empty box valid")
	}
	b.Extend(Point{8, 50})
	b.Extend(Point{8.01, 50.01})
	if c := b.Center(); !b.Valid() || !near(c.Lon, 8.005, 1e-12) || !near(c.Lat, 50.005, 1e-12) {
		t.Fatal(b)
	}
	w, h := b.SizeMeters()
	if !near(w, 715, 5) || !near(h, 1112, 2) || !near(b.AreaM2(), w*h, 1e-6) {
		t.Fatal(w, h)
	}
	o := BBox{8.02, 50, 8.03, 50.01}
	if b.Intersects(o) || !b.Union(o).Contains(Point{8.015, 50.005}) {
		t.Fatal("intersects/union")
	}
	if !b.Expand(800).Intersects(o) || b.Expand(600).Intersects(o) {
		t.Fatal("expand")
	}
	if d := b.DistanceMeters(Point{8.005, 50.02}); !near(d, 1112, 2) || b.DistanceMeters(b.Center()) != 0 {
		t.Fatal(d)
	}
	if bb := BBoxOf([]Point{{1, 2}, {-1, 3}}); bb != (BBox{-1, 2, 1, 3}) {
		t.Fatal(bb)
	}
}
