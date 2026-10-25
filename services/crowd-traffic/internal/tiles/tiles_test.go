package tiles

import (
	"encoding/json"
	"math"
	"testing"
)

func TestProjectRoundTrip(t *testing.T) {
	for _, p := range [][2]float64{{52.4782, 13.3420}, {0, 0}, {-33.9, 151.2}, {47.2, 5.8}, {84, -179}} {
		px, py := Project(p[0], p[1])
		lat, lon := Unproject(px, py)
		if math.Abs(lat-p[0]) > 1e-9 || math.Abs(lon-p[1]) > 1e-9 {
			t.Errorf("round trip %v -> %v %v", p, lat, lon)
		}
	}
	// Clamping at the poles.
	if _, py := Project(90, 0); py < -1e-6 {
		t.Error("projection of the pole outside the world")
	}
}

func TestKnownTile(t *testing.T) {
	// Brandenburger Tor; tile from the standard slippy-map formula.
	got := At(52.516275, 13.377704)
	want := Tile{70406, 42987}
	if got != want {
		t.Fatalf("At = %v, want %v", got, want)
	}
	if !got.Valid() || got.String() != "17/70406/42987" {
		t.Fatal(got.String())
	}
	if (Tile{-1, 0}).Valid() || (Tile{0, N}).Valid() {
		t.Fatal("invalid tile accepted")
	}
	lat, lon := got.Center()
	if At(lat, lon) != got {
		t.Fatal("centre outside tile")
	}
	// Tile size in Berlin: about 186 m.
	nLat, nLon := got.EdgePoint(North, 0)
	sLat, sLon := got.EdgePoint(South, 0)
	if d := DistanceM(nLat, nLon, sLat, sLon); d < 180 || d < 0 || d > 195 {
		t.Fatalf("tile height = %.1f m", d)
	}
}

func TestEdgePoints(t *testing.T) {
	tl := Tile{70406, 42987}
	for _, s := range []Side{North, East, South, West} {
		for _, o := range []int{0, 128, 255} {
			lat, lon := tl.EdgePoint(s, o)
			px, py := Project(lat, lon)
			x0, y0 := float64(tl.X*Size), float64(tl.Y*Size)
			var along, across float64
			switch s {
			case North:
				along, across = px-x0, py-y0
			case South:
				along, across = px-x0, py-y0-Size
			case West:
				along, across = py-y0, px-x0
			case East:
				along, across = py-y0, px-x0-Size
			}
			if math.Abs(along-float64(o)-0.5) > 1e-6 || math.Abs(across) > 1e-6 {
				t.Errorf("%v/%d: along %.3f across %.3f", s, o, along, across)
			}
		}
	}
}

func TestSides(t *testing.T) {
	tl := Tile{10, 10}
	for _, s := range []Side{North, East, South, West} {
		if s.Opposite().Opposite() != s || s.Opposite() == s {
			t.Errorf("opposite of %v", s)
		}
		if tl.Neighbor(s).Neighbor(s.Opposite()) != tl {
			t.Errorf("neighbour across %v", s)
		}
		b, err := json.Marshal(s)
		if err != nil {
			t.Fatal(err)
		}
		var back Side
		if err := json.Unmarshal(b, &back); err != nil || back != s {
			t.Errorf("json %s -> %v, %v", b, back, err)
		}
	}
	var s Side
	if err := json.Unmarshal([]byte(`"X"`), &s); err == nil {
		t.Error("invalid side accepted")
	}
	if _, err := json.Marshal(Side(0)); err == nil {
		t.Error("zero side marshalled")
	}
	if Side(9).String() != "Side(9)" || Side(9).Opposite() != Side(9) || tl.Neighbor(Side(0)) != tl {
		t.Error("invalid side handling")
	}
}

func TestDistance(t *testing.T) {
	// One degree of latitude ≈ 111.2 km.
	if d := DistanceM(52, 13, 53, 13); math.Abs(d-111195) > 100 {
		t.Fatalf("distance = %.0f", d)
	}
	if DistanceM(1, 1, 1, 1) != 0 {
		t.Fatal("zero distance")
	}
}
