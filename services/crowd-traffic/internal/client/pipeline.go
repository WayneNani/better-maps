package client

import (
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/ldp"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/protocol"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/tiles"
)

// Config holds the client-side privacy rules (docs/CROWD_TRAFFIC_PRIVACY.md, "Client-Vertrag").
type Config struct {
	// Start trimming: segments are dropped until the vehicle has driven a random distance in
	// [StartTrimMin, StartTrimMax] metres (drawn once per trip) and while they are closer than
	// StartRadius (straight line) to the start point.
	StartTrimMin, StartTrimMax, StartRadius float64
	// Destination trimming: segments that end less than DestRoute metres (route distance) or
	// DestRadius metres (straight line) before the destination are dropped.
	DestRoute, DestRadius float64
	// Release: a segment becomes sendable once the vehicle has driven ReleaseDistance metres
	// beyond it or ReleaseTime has passed since leaving it, while the trip continues. Segments
	// not released when the trip ends are dropped (protection against unplanned stops).
	ReleaseDistance float64
	ReleaseTime     time.Duration
	// Plausibility of a measurement.
	MinLengthM, MaxSpeedKmh float64
	// Batching: one bundle per window, sent at a random time in [SendDelayMin, SendDelayMax]
	// after the end of the window, with at most MaxPerWindow randomly chosen segments.
	SendDelayMin, SendDelayMax time.Duration
	MaxPerWindow               int
	Window                     time.Duration
}

// DefaultConfig returns the parameters of the client contract.
func DefaultConfig() Config {
	return Config{
		StartTrimMin: 1000, StartTrimMax: 1500, StartRadius: 1000,
		DestRoute: 1500, DestRadius: 1000,
		ReleaseDistance: 500, ReleaseTime: 3 * time.Minute,
		MinLengthM: 50, MaxSpeedKmh: 250,
		SendDelayMin: 30 * time.Second, SendDelayMax: 240 * time.Second,
		MaxPerWindow: protocol.MaxReports,
		Window:       protocol.WindowSeconds * time.Second,
	}
}

// Planned is a bundle ready to be sent at SendAt.
type Planned struct {
	Window  int64
	SendAt  time.Time
	Reports []protocol.Report
}

func uniform(rnd ldp.Rand, lo, hi float64) float64 { return lo + (hi-lo)*rnd.Float64() }

func (c Config) windowStart(t time.Time) int64 {
	w := int64(c.Window / time.Second)
	s := t.Unix()
	return s - ((s%w)+w)%w
}

// Eligible applies the per-segment rules that do not depend on time: reportable road class,
// complete and plausible measurement, start and destination trimming. totalDist is the route
// length of the trip, start and dest its first and last point, startTrim the drawn trim distance.
func (c Config) eligible(t Traversal, totalDist, startTrim float64, start, dest Point) bool {
	if !t.Class.Valid() || t.LengthM < c.MinLengthM || t.SpeedKmh() > c.MaxSpeedKmh || t.Entry == t.Exit {
		return false
	}
	if t.DistIn < startTrim ||
		tiles.DistanceM(start.Lat, start.Lon, t.InLat, t.InLon) < c.StartRadius ||
		tiles.DistanceM(start.Lat, start.Lon, t.OutLat, t.OutLon) < c.StartRadius {
		return false
	}
	if totalDist-t.DistOut < c.DestRoute ||
		tiles.DistanceM(dest.Lat, dest.Lon, t.InLat, t.InLon) < c.DestRadius ||
		tiles.DistanceM(dest.Lat, dest.Lon, t.OutLat, t.OutLon) < c.DestRadius {
		return false
	}
	return true
}

// releaseTimes returns, for every traversal, the time at which it may be sent, or the zero time
// if the trip ends before.
func (c Config) releaseTimes(points []Point, trav []Traversal) []time.Time {
	// Cumulative route distance per point.
	cum := make([]float64, len(points))
	for i := 1; i < len(points); i++ {
		cum[i] = cum[i-1] + tiles.DistanceM(points[i-1].Lat, points[i-1].Lon, points[i].Lat, points[i].Lon)
	}
	end := points[len(points)-1].Time
	res := make([]time.Time, len(trav))
	for i, t := range trav {
		rel := t.TOut.Add(c.ReleaseTime)
		for j := range points {
			if cum[j] >= t.DistOut+c.ReleaseDistance {
				if points[j].Time.Before(rel) {
					rel = points[j].Time
				}
				break
			}
		}
		if rel.After(end) {
			continue // the trip ended before the segment was released
		}
		res[i] = rel
	}
	return res
}

// PlanTrip applies all client rules to a complete trip and returns the bundles to send. The
// last point of the trip is the navigation destination.
func PlanTrip(points []Point, cfg Config, rr ldp.RR, rnd ldp.Rand) []Planned {
	trav := Segment(points)
	if len(trav) == 0 {
		return nil
	}
	total := 0.0
	for i := 1; i < len(points); i++ {
		total += tiles.DistanceM(points[i-1].Lat, points[i-1].Lon, points[i].Lat, points[i].Lon)
	}
	startTrim := uniform(rnd, cfg.StartTrimMin, cfg.StartTrimMax)
	release := cfg.releaseTimes(points, trav)

	type window struct {
		sendAt time.Time
		cand   []Traversal
		seen   map[protocol.Key]bool
	}
	windows := map[int64]*window{}
	var order []int64
	for i, t := range trav {
		if release[i].IsZero() || !cfg.eligible(t, total, startTrim, points[0], points[len(points)-1]) {
			continue
		}
		ws := cfg.windowStart(t.TOut)
		w := windows[ws]
		if w == nil {
			delay := time.Duration(uniform(rnd, float64(cfg.SendDelayMin), float64(cfg.SendDelayMax)))
			w = &window{sendAt: time.Unix(ws, 0).Add(cfg.Window + delay), seen: map[protocol.Key]bool{}}
			windows[ws] = w
			order = append(order, ws)
		}
		if release[i].After(w.sendAt) || w.seen[t.Key()] {
			continue // not released in time for this window's bundle, or already reported
		}
		w.seen[t.Key()] = true
		w.cand = append(w.cand, t)
	}

	var res []Planned
	for _, ws := range order {
		w := windows[ws]
		if len(w.cand) == 0 {
			continue
		}
		// Uniform random subset of at most MaxPerWindow segments in random order
		// (Fisher–Yates shuffle, then truncate).
		for i := len(w.cand) - 1; i > 0; i-- {
			j := rnd.IntN(i + 1)
			w.cand[i], w.cand[j] = w.cand[j], w.cand[i]
		}
		if len(w.cand) > cfg.MaxPerWindow {
			w.cand = w.cand[:cfg.MaxPerWindow]
		}
		p := Planned{Window: ws, SendAt: w.sendAt}
		for _, t := range w.cand {
			p.Reports = append(p.Reports, protocol.Report{
				Window:      ws,
				Tile:        t.Tile,
				Entry:       t.Entry,
				Exit:        t.Exit,
				RoadClass:   t.Class,
				SpeedBucket: rr.Perturb(protocol.BucketOf(t.SpeedKmh()), rnd),
			})
		}
		res = append(res, p)
	}
	return res
}
