package aggregate

import (
	"math/rand/v2"
	"sync"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/ldp"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/protocol"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/tiles"
)

var t0 = time.Date(2026, 10, 26, 8, 0, 0, 0, time.UTC) // window start

func report(tile tiles.Tile, class protocol.RoadClass, bucket int, window time.Time) protocol.Report {
	return protocol.Report{
		Window:      window.Unix(),
		Tile:        tile,
		Entry:       protocol.Edge{Side: tiles.West, Offset: 100},
		Exit:        protocol.Edge{Side: tiles.East, Offset: 200},
		RoadClass:   class,
		SpeedBucket: bucket,
	}
}

func repeat(r protocol.Report, n int) []protocol.Report {
	res := make([]protocol.Report, n)
	for i := range res {
		res[i] = r
	}
	return res
}

// finalizeAt is the earliest time at which the window starting at w is evaluated.
func finalizeAt(cfg Config, w time.Time) time.Time { return w.Add(cfg.Window + cfg.Grace) }

func TestKThreshold(t *testing.T) {
	cfg := DefaultConfig()
	a := New(cfg, t0)
	below := tiles.Tile{X: 70400, Y: 43000}
	at := tiles.Tile{X: 70401, Y: 43000}
	a.Add(repeat(report(below, protocol.Motorway, 0, t0), cfg.K-1))
	a.Add(repeat(report(at, protocol.Motorway, 0, t0), cfg.K))
	a.Tick(finalizeAt(cfg, t0))

	pubs := a.Publications(finalizeAt(cfg, t0))
	if len(pubs) != 1 || pubs[0].Key.Tile != at || pubs[0].Reports != cfg.K {
		t.Fatalf("publications = %+v", pubs)
	}
	// The segment below k leaves no trace: no counters, no reference, no publication.
	for k := range a.refs {
		if k.Tile == below {
			t.Fatal("reference for segment below k")
		}
	}
	if fp := a.Footprint(); fp.OpenWindows != 0 || fp.OpenSegments != 0 || fp.References != 1 || fp.Publications != 1 {
		t.Fatalf("footprint = %+v", fp)
	}
	if st := a.Stats(); st.SegmentsBelowK != 1 || st.SegmentsAboveK != 1 || st.ReportsAccepted != uint64(2*cfg.K-1) ||
		st.WindowsFinalized != 1 {
		t.Fatalf("stats = %+v", st)
	}
}

func TestWindowLifecycle(t *testing.T) {
	cfg := DefaultConfig()
	a := New(cfg, t0)
	tile := tiles.Tile{X: 70400, Y: 43000}
	a.Add(repeat(report(tile, protocol.Motorway, 0, t0), 20))

	// Not evaluated before the grace period has passed.
	a.Tick(finalizeAt(cfg, t0).Add(-time.Second))
	if len(a.Publications(finalizeAt(cfg, t0))) != 0 || a.Footprint().OpenSegments != 1 {
		t.Fatal("window evaluated too early")
	}
	// Reports during the grace period still count.
	a.Add(repeat(report(tile, protocol.Motorway, 0, t0), 5))
	fin := finalizeAt(cfg, t0)
	a.Tick(fin)
	pubs := a.Publications(fin)
	if len(pubs) != 1 || pubs[0].Reports != 25 {
		t.Fatalf("publications = %+v", pubs)
	}
	p := pubs[0]
	if !p.WindowStart.Equal(t0) || !p.WindowEnd.Equal(t0.Add(cfg.Window)) || !p.Published.Equal(fin) ||
		!p.Expires.Equal(fin.Add(cfg.Window+cfg.Grace)) {
		t.Fatalf("times = %+v", p)
	}
	// Late reports for the finalized window are rejected and counted.
	if n := a.Add(repeat(report(tile, protocol.Motorway, 0, t0), 3)); n != 0 {
		t.Fatal("late reports accepted")
	}
	if a.Stats().ReportsLate != 3 || a.Footprint().OpenSegments != 0 {
		t.Fatalf("stats = %+v", a.Stats())
	}
	// The publication expires when no new window replaces it.
	a.Tick(p.Expires.Add(-time.Second))
	if len(a.Publications(p.Expires.Add(-time.Second))) != 1 {
		t.Fatal("publication expired early")
	}
	a.Tick(p.Expires)
	if a.Footprint().Publications != 0 || len(a.Publications(p.Expires)) != 0 {
		t.Fatal("publication did not expire")
	}
	// The reference lives longer, but not forever.
	if a.Footprint().References != 1 {
		t.Fatal("reference missing")
	}
	a.Tick(p.WindowEnd.Add(cfg.RefTTL))
	if a.Footprint().References != 0 {
		t.Fatal("reference did not expire")
	}
}

func TestStartupTreatsOldWindowsAsClosed(t *testing.T) {
	cfg := DefaultConfig()
	now := t0.Add(cfg.Window + cfg.Grace) // the window at t0 is already due
	a := New(cfg, now)
	if n := a.Add([]protocol.Report{report(tiles.Tile{X: 1, Y: 1}, protocol.Primary, 0, t0)}); n != 0 {
		t.Fatal("report for a closed window accepted after start")
	}
	// The previous window is still open during its grace period.
	a = New(cfg, t0.Add(cfg.Window+cfg.Grace-time.Second))
	if n := a.Add([]protocol.Report{report(tiles.Tile{X: 1, Y: 1}, protocol.Primary, 0, t0)}); n != 1 {
		t.Fatal("report within grace rejected after start")
	}
}

func TestClassify(t *testing.T) {
	cases := []struct {
		ratio, speed float64
		want         State
	}{
		{1.0, 100, FreeFlow},
		{0.75, 80, FreeFlow},
		{0.7, 77, Heavy},
		{0.5, 55, Slow},
		{0.3, 33, Queue},
		{0.05, 5, Stationary},
	}
	for _, c := range cases {
		if got := Classify(c.ratio, c.speed, 0.75); got != c.want {
			t.Errorf("Classify(%v, %v) = %v, want %v", c.ratio, c.speed, got, c.want)
		}
	}
	if FreeFlow.TraffEvent() != "" || Queue.TraffEvent() != "CONGESTION_QUEUE" ||
		Stationary.TraffEvent() != "CONGESTION_STATIONARY_TRAFFIC" || Heavy.String() != "HEAVY_TRAFFIC" {
		t.Fatal("state names")
	}
}

// noisyWindow adds n perturbed reports per tile, with true speeds drawn by speed().
func noisyWindow(a *Aggregator, rnd *rand.Rand, tilesN, n int, class protocol.RoadClass, w time.Time, speed func() float64) {
	rr := ldp.New(protocol.NumBuckets, protocol.Epsilon)
	for i := range tilesN {
		tile := tiles.Tile{X: 60000 + i, Y: 40000}
		for range n {
			a.Add([]protocol.Report{report(tile, class, rr.Perturb(protocol.BucketOf(speed()), rnd), w)})
		}
	}
}

func congestedShare(a *Aggregator, now time.Time) (float64, int) {
	pubs := a.Publications(now)
	c := 0
	for _, p := range pubs {
		if p.Congested() {
			c++
		}
	}
	return float64(c) / float64(len(pubs)), len(pubs)
}

// Statistical properties of the whole pipeline (noise, de-noising, classification) at the
// publication threshold, with a fixed seed.
func TestDetectionAndFalsePositives(t *testing.T) {
	cfg := DefaultConfig()
	const segments = 2000
	cases := []struct {
		name     string
		n        int
		speed    func(r *rand.Rand) float64
		min, max float64 // allowed range of the congested share
	}{
		// Free-flowing motorway, 95–130 km/h: false positives below 1 %.
		{"free flow n=k", cfg.K, func(r *rand.Rand) float64 { return 95 + 35*r.Float64() }, 0, 0.01},
		{"free flow n=30", 30, func(r *rand.Rand) float64 { return 95 + 35*r.Float64() }, 0, 0.001},
		// Stop-and-go, 3–25 km/h: detected in at least 95 % of the windows at n = k.
		{"jam n=k", cfg.K, func(r *rand.Rand) float64 { return 3 + 22*r.Float64() }, 0.95, 1},
		{"jam n=30", 30, func(r *rand.Rand) float64 { return 3 + 22*r.Float64() }, 0.999, 1},
	}
	for i, c := range cases {
		rnd := rand.New(rand.NewPCG(uint64(i), 42))
		a := New(cfg, t0)
		noisyWindow(a, rnd, segments, c.n, protocol.Motorway, t0, func() float64 { return c.speed(rnd) })
		fin := finalizeAt(cfg, t0)
		a.Tick(fin)
		share, n := congestedShare(a, fin)
		if n != segments {
			t.Fatalf("%s: %d publications", c.name, n)
		}
		if share < c.min || share > c.max {
			t.Errorf("%s: congested share %.4f, want [%v, %v]", c.name, share, c.min, c.max)
		} else {
			t.Logf("%s: congested share %.4f", c.name, share)
		}
	}
}

// The median speed estimate is close to the truth on average.
func TestSpeedEstimate(t *testing.T) {
	cfg := DefaultConfig()
	rnd := rand.New(rand.NewPCG(7, 7))
	a := New(cfg, t0)
	noisyWindow(a, rnd, 500, 40, protocol.Motorway, t0, func() float64 { return 2 + 7*rnd.Float64() })
	fin := finalizeAt(cfg, t0)
	a.Tick(fin)
	sum, stationary := 0.0, 0
	pubs := a.Publications(fin)
	for _, p := range pubs {
		sum += p.Speed
		if p.State == Stationary {
			stationary++
		}
	}
	// The true speeds all lie in bucket 0 ([0, 10) km/h); the interpolated median lies inside it.
	if mean := sum / float64(len(pubs)); mean < 3 || mean > 9 {
		t.Fatalf("mean median speed %.1f, want within bucket 0", mean)
	}
	if float64(stationary)/float64(len(pubs)) < 0.9 {
		t.Fatalf("only %d of %d stationary", stationary, len(pubs))
	}
}

// A minority of fake fast reports cannot hide a jam, and a minority of fake slow reports cannot
// create one (robustness of the median).
func TestOutlierRobustness(t *testing.T) {
	cfg := DefaultConfig()
	rnd := rand.New(rand.NewPCG(9, 9))
	rr := ldp.New(protocol.NumBuckets, protocol.Epsilon)
	a := New(cfg, t0)
	jam := tiles.Tile{X: 1000, Y: 1000}
	free := tiles.Tile{X: 1001, Y: 1000}
	for range 30 { // honest
		a.Add([]protocol.Report{report(jam, protocol.Motorway, rr.Perturb(0, rnd), t0)})
		a.Add([]protocol.Report{report(free, protocol.Motorway, rr.Perturb(6, rnd), t0)})
	}
	for range 10 { // attackers do not apply noise
		a.Add([]protocol.Report{report(jam, protocol.Motorway, 6, t0)})
		a.Add([]protocol.Report{report(free, protocol.Motorway, 0, t0)})
	}
	fin := finalizeAt(cfg, t0)
	a.Tick(fin)
	for _, p := range a.Publications(fin) {
		if p.Key.Tile == jam && !p.Congested() {
			t.Errorf("jam hidden: %+v", p)
		}
		if p.Key.Tile == free && p.Congested() {
			t.Errorf("fake jam: %+v", p)
		}
	}
}

func TestReferenceSpeedAndEpisodes(t *testing.T) {
	cfg := DefaultConfig()
	rnd := rand.New(rand.NewPCG(1, 1))
	rr := ldp.New(protocol.NumBuckets, protocol.Epsilon)
	a := New(cfg, t0)
	tile := tiles.Tile{X: 5000, Y: 5000}
	add := func(w time.Time, n int, speed float64) time.Time {
		for range n {
			a.Add([]protocol.Report{report(tile, protocol.Primary, rr.Perturb(protocol.BucketOf(speed), rnd), w)})
		}
		fin := finalizeAt(cfg, w)
		a.Tick(fin)
		return fin
	}
	// First window: reference is the road class default.
	fin := add(t0, 20, 45)
	p := a.Publications(fin)[0]
	if p.RefObserved || p.RefSpeed != DefaultFreeFlow[protocol.Primary] {
		t.Fatalf("first reference = %v (observed %v)", p.RefSpeed, p.RefObserved)
	}
	// After enough free-flow data (45 km/h on a primary road), the observed V85 is used.
	w := t0
	for range 5 {
		w = w.Add(cfg.Window)
		fin = add(w, 20, 45)
	}
	p = a.Publications(fin)[0]
	if !p.RefObserved || p.RefSpeed < 36 || p.RefSpeed > 55 || p.Congested() {
		t.Fatalf("observed reference = %.1f (observed %v), state %v", p.RefSpeed, p.RefObserved, p.State)
	}
	// Two congested windows in a row form one episode.
	w = w.Add(cfg.Window)
	fin1 := add(w, 20, 5)
	p1 := a.Publications(fin1)[0]
	w = w.Add(cfg.Window)
	fin2 := add(w, 20, 5)
	p2 := a.Publications(fin2)[0]
	if !p1.Congested() || !p2.Congested() || !p2.FirstSeen.Equal(p1.FirstSeen) || !p2.Published.Equal(fin2) {
		t.Fatalf("episode: %+v / %+v", p1, p2)
	}
	// From/To lie on the reported edge positions (bin centre within 8 px ≈ 6 m).
	wantLat, wantLon := tile.EdgePoint(tiles.West, 100)
	if d := tiles.DistanceM(p2.From.Lat, p2.From.Lon, wantLat, wantLon); d > 10 {
		t.Fatalf("from is %.1f m off", d)
	}
	wantLat, wantLon = tile.EdgePoint(tiles.East, 200)
	if d := tiles.DistanceM(p2.To.Lat, p2.To.Lon, wantLat, wantLon); d > 10 {
		t.Fatalf("to is %.1f m off", d)
	}
	if fp := a.Footprint(); fp.Congested != 1 {
		t.Fatalf("footprint %+v", fp)
	}
}

func TestOverload(t *testing.T) {
	cfg := DefaultConfig()
	cfg.MaxOpenKeys = 2
	a := New(cfg, t0)
	for i := range 3 {
		a.Add([]protocol.Report{report(tiles.Tile{X: i, Y: 0}, protocol.Primary, 0, t0)})
	}
	// Existing keys still count.
	a.Add([]protocol.Report{report(tiles.Tile{X: 0, Y: 0}, protocol.Primary, 0, t0)})
	if st := a.Stats(); st.ReportsDropped != 1 || st.ReportsAccepted != 3 {
		t.Fatalf("stats = %+v", st)
	}
}

func TestConcurrentAdd(t *testing.T) {
	cfg := DefaultConfig()
	a := New(cfg, t0)
	var wg sync.WaitGroup
	for g := range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := range 100 {
				a.Add([]protocol.Report{report(tiles.Tile{X: i % 10, Y: g}, protocol.Primary, i%7, t0)})
				if i%25 == 0 {
					a.Tick(t0)
					a.Publications(t0)
					a.Footprint()
				}
			}
		}()
	}
	wg.Wait()
	if a.Stats().ReportsAccepted != 800 {
		t.Fatal(a.Stats())
	}
}
