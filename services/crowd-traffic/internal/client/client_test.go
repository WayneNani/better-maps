package client

import (
	"math"
	"math/rand/v2"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/ldp"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/protocol"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/tiles"
)

var t0 = time.Date(2026, 10, 26, 8, 0, 0, 0, time.UTC)

// line returns points every step metres from (lat, lon) along a bearing (0 = north, 90 = east)
// at constant speed.
func line(lat, lon, bearing, lengthM, step, kmh float64, start time.Time, class protocol.RoadClass) []Point {
	var pts []Point
	dLat := math.Cos(bearing*math.Pi/180) / 111195
	dLon := math.Sin(bearing*math.Pi/180) / (111195 * math.Cos(lat*math.Pi/180))
	for d := 0.0; d <= lengthM+1e-9; d += step {
		pts = append(pts, Point{
			Lat: lat + d*dLat, Lon: lon + d*dLon,
			Time:  start.Add(time.Duration(d / (kmh / 3.6) * float64(time.Second))),
			Class: class,
		})
	}
	return pts
}

func TestSegmentEastbound(t *testing.T) {
	pts := line(52.47, 13.40, 90, 2000, 10, 72, t0, protocol.Motorway)
	tr := Segment(pts)
	if len(tr) < 8 {
		t.Fatalf("%d traversals", len(tr))
	}
	for i, x := range tr {
		if x.Entry.Side != tiles.West || x.Exit.Side != tiles.East || x.Class != protocol.Motorway {
			t.Fatalf("traversal %d: %+v", i, x)
		}
		// Constant latitude: entry and exit offsets equal and equal to the next entry offset.
		if math.Abs(float64(x.Entry.Offset-x.Exit.Offset)) > 1 {
			t.Errorf("traversal %d: offsets %d/%d", i, x.Entry.Offset, x.Exit.Offset)
		}
		if i > 0 && (tr[i-1].Tile.Neighbor(tiles.East) != x.Tile || tr[i-1].Exit.Offset != x.Entry.Offset) {
			t.Errorf("traversal %d does not continue the previous one", i)
		}
		if v := x.SpeedKmh(); math.Abs(v-72) > 1 {
			t.Errorf("traversal %d: speed %.2f", i, v)
		}
		// Tile width in Berlin ≈ 186 m.
		if x.LengthM < 180 || x.LengthM > 192 {
			t.Errorf("traversal %d: length %.1f", i, x.LengthM)
		}
		if x.DistOut-x.DistIn-x.LengthM > 0.01 {
			t.Errorf("traversal %d: distances", i)
		}
	}
	// The offsets match the projected latitude.
	_, py := tiles.Project(52.47, 13.40)
	if want := int(py) % tiles.Size; tr[0].Entry.Offset != want {
		t.Fatalf("offset %d, want %d", tr[0].Entry.Offset, want)
	}
}

func TestSegmentDirectionsAndCorners(t *testing.T) {
	cases := []struct {
		bearing     float64
		entry, exit tiles.Side
	}{
		{0, tiles.South, tiles.North},
		{180, tiles.North, tiles.South},
		{270, tiles.East, tiles.West},
	}
	for _, c := range cases {
		tr := Segment(line(52.47, 13.40, c.bearing, 1000, 7, 50, t0, protocol.Primary))
		if len(tr) < 3 {
			t.Fatalf("bearing %v: %d traversals", c.bearing, len(tr))
		}
		for _, x := range tr {
			if x.Entry.Side != c.entry || x.Exit.Side != c.exit {
				t.Fatalf("bearing %v: %v→%v", c.bearing, x.Entry.Side, x.Exit.Side)
			}
		}
	}
	// A step exactly through a tile corner.
	tl := tiles.Tile{X: 70420, Y: 43010}
	x0, y0 := float64(tl.X*tiles.Size), float64(tl.Y*tiles.Size)
	aLat, aLon := tiles.Unproject(x0-10, y0-10)
	bLat, bLon := tiles.Unproject(x0+10, y0+10)
	cLat, cLon := tiles.Unproject(x0+300, y0+10)
	tr := Segment([]Point{
		{Lat: aLat, Lon: aLon, Time: t0, Class: protocol.Primary},
		{Lat: bLat, Lon: bLon, Time: t0.Add(2 * time.Second), Class: protocol.Primary},
		{Lat: cLat, Lon: cLon, Time: t0.Add(30 * time.Second), Class: protocol.Primary},
	})
	// Crossing the corner passes a diagonal neighbour for zero length (which one depends on
	// rounding), then tile tl.
	if len(tr) != 2 || tr[0].LengthM > 0.01 || tr[1].Tile != tl || tr[1].Exit.Side != tiles.East ||
		(tr[1].Entry.Side != tiles.North && tr[1].Entry.Side != tiles.West) {
		t.Fatalf("corner traversals: %+v", tr)
	}
	if Segment(nil) != nil || Segment(line(52.47, 13.40, 90, 0, 10, 50, t0, protocol.Primary)) != nil {
		t.Fatal("degenerate traces")
	}
}

func TestSegmentRoadClassChange(t *testing.T) {
	pts := line(52.47, 13.40, 90, 1000, 10, 50, t0, protocol.Primary)
	for i := 40; i < len(pts); i++ {
		pts[i].Class = protocol.Secondary
	}
	mixed := 0
	for _, x := range Segment(pts) {
		if x.Class == 0 {
			mixed++
		}
	}
	if mixed != 1 {
		t.Fatalf("%d mixed traversals, want 1", mixed)
	}
}

func TestCrossingsOrder(t *testing.T) {
	c := crossings(250, 250, 520, 262)
	if len(c) != 3 || !c[0].vertical || c[1].vertical || !c[2].vertical || c[0].t > c[1].t || c[1].t > c[2].t {
		t.Fatalf("crossings = %+v", c)
	}
	c = crossings(520, 10, 250, 10)
	if len(c) != 2 || c[0].forward || c[0].t > c[1].t {
		t.Fatalf("backward crossings = %+v", c)
	}
}

// trip builds a trip: 1.6 km approach on an unclassified road, main road, 1.6 km exit.
func trip(mainLen, kmh float64, start time.Time) []Point {
	pts := line(52.47, 13.30, 90, 1600, 10, 50, start, 0)
	last := pts[len(pts)-1]
	main := line(last.Lat, last.Lon, 90, mainLen, 10, kmh, last.Time, protocol.Motorway)[1:]
	pts = append(pts, main...)
	last = pts[len(pts)-1]
	return append(pts, line(last.Lat, last.Lon, 90, 1600, 10, 50, last.Time, 0)[1:]...)
}

func TestPlanTripRules(t *testing.T) {
	cfg := DefaultConfig()
	rr := ldp.New(protocol.NumBuckets, 50) // no effective noise: check the true buckets
	rnd := rand.New(rand.NewPCG(1, 2))
	pts := trip(3000, 72, t0)
	plans := PlanTrip(pts, cfg, rr, rnd)
	if len(plans) == 0 {
		t.Fatal("no bundles")
	}
	start, dest := pts[0], pts[len(pts)-1]
	n := 0
	for _, p := range plans {
		if len(p.Reports) > cfg.MaxPerWindow {
			t.Fatalf("%d reports in one bundle", len(p.Reports))
		}
		ws := time.Unix(p.Window, 0)
		if p.SendAt.Before(ws.Add(cfg.Window+cfg.SendDelayMin)) || p.SendAt.After(ws.Add(cfg.Window+cfg.SendDelayMax)) {
			t.Fatalf("send time %v for window %v", p.SendAt, ws)
		}
		if err := protocol.DefaultRules().Validate(&protocol.Bundle{Version: 1, Reports: p.Reports}, p.SendAt); err != nil {
			t.Fatalf("planned bundle invalid: %v", err)
		}
		for _, r := range p.Reports {
			n++
			if r.RoadClass != protocol.Motorway || r.SpeedBucket != protocol.BucketOf(72) {
				t.Fatalf("report %+v", r)
			}
			lat, lon := r.Tile.Center()
			if tiles.DistanceM(lat, lon, start.Lat, start.Lon) < 900 || tiles.DistanceM(lat, lon, dest.Lat, dest.Lon) < 900 {
				t.Fatalf("report too close to start or destination: %+v", r)
			}
		}
	}
	// 3 km main road ≈ 16 tiles; trimming and windows may cut some, but most are reported.
	if n < 8 || n > 17 {
		t.Fatalf("%d reports", n)
	}
}

func TestPlanTripShortTripReportsNothing(t *testing.T) {
	cfg := DefaultConfig()
	rnd := rand.New(rand.NewPCG(1, 2))
	// 2 km on a motorway without approach: everything lies within the trimmed zones.
	if p := PlanTrip(line(52.47, 13.30, 90, 2000, 10, 72, t0, protocol.Motorway), cfg, ldp.New(7, 3), rnd); len(p) != 0 {
		t.Fatalf("short trip produced %+v", p)
	}
	if p := PlanTrip(nil, cfg, ldp.New(7, 3), rnd); p != nil {
		t.Fatal("empty trip")
	}
}

func TestPlanTripSamplingAndRelease(t *testing.T) {
	cfg := DefaultConfig()
	cfg.MaxPerWindow = 3
	rr := ldp.New(protocol.NumBuckets, 3)
	rnd := rand.New(rand.NewPCG(3, 4))
	plans := PlanTrip(trip(20000, 100, t0), cfg, rr, rnd)
	for _, p := range plans {
		if len(p.Reports) > 3 {
			t.Fatalf("sampling cap ignored: %d", len(p.Reports))
		}
	}
	// Release: with a stop right after the main road (no exit stretch), the last segments are
	// never released.
	pts := trip(3000, 72, t0)
	cut := pts[:len(pts)-150] // trip ends 100 m after the main road… nothing near the end is sent
	cfg = DefaultConfig()
	cfg.DestRoute, cfg.DestRadius = 0, 0 // isolate the release rule
	end := cut[len(cut)-1]
	for _, p := range PlanTrip(cut, cfg, ldp.New(7, 50), rnd) {
		for _, r := range p.Reports {
			lat, lon := r.Tile.EdgePoint(r.Exit.Side, r.Exit.Offset)
			if tiles.DistanceM(lat, lon, end.Lat, end.Lon) < cfg.ReleaseDistance-1 {
				// Released by time only if the trip continued for ReleaseTime.
				t.Fatalf("segment %v released although the trip ended %v m later", r.Tile,
					tiles.DistanceM(lat, lon, end.Lat, end.Lon))
			}
		}
	}
}

func TestPlanTripSlowTrafficReleasedByTime(t *testing.T) {
	cfg := DefaultConfig()
	rnd := rand.New(rand.NewPCG(5, 6))
	// 1.5 km at 6 km/h: 500 m take 5 minutes, so segments are released by time (3 min).
	pts := trip(1500, 6, t0)
	n := 0
	for _, p := range PlanTrip(pts, cfg, ldp.New(7, 50), rnd) {
		for _, r := range p.Reports {
			n++
			if r.SpeedBucket != 0 {
				t.Fatalf("bucket %d for 6 km/h", r.SpeedBucket)
			}
		}
	}
	if n == 0 {
		t.Fatal("no report from slow traffic")
	}
}
