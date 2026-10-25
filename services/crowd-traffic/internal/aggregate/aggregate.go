// Package aggregate counts crowd-traffic reports per segment and window, de-noises the speed
// distribution once a window is closed and derives a congestion state for segments with at
// least K reports.
//
// Data lifecycle (see docs/CROWD_TRAFFIC_PRIVACY.md, "Aufbewahrung"):
//   - Reports are never stored. Add only increments counters of the open window.
//   - A window is finalized Grace after its end. Its counters are then evaluated and deleted.
//     Segments with fewer than K reports leave no trace at all.
//   - Segments with at least K reports get a publication (valid for about one window) and their
//     counters are merged into an exponentially decaying reference histogram, from which the
//     free-flow speed is estimated. References expire after RefTTL without new data.
package aggregate

import (
	"math"
	"sort"
	"sync"
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/ldp"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/protocol"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/tiles"
)

// Config holds the aggregation parameters.
type Config struct {
	Window time.Duration // window length
	Grace  time.Duration // delay between the end of a window and its evaluation
	K      int           // minimum number of reports per segment and window

	RefHalfLife   time.Duration // half-life of the reference histogram
	RefTTL        time.Duration // a reference without new data is deleted after this time
	RefMinReports float64       // decayed report count needed to trust the observed V85

	FreeFlowRatio float64 // speed ratio at or above which traffic is free-flowing
	Z             float64 // confidence factor for congestion (standard errors)
	MaxOpenKeys   int     // memory limit for open windows
}

// DefaultConfig returns the production parameters.
func DefaultConfig() Config {
	return Config{
		Window:        protocol.WindowSeconds * time.Second,
		Grace:         5 * time.Minute,
		K:             10,
		RefHalfLife:   7 * 24 * time.Hour,
		RefTTL:        14 * 24 * time.Hour,
		RefMinReports: 50,
		FreeFlowRatio: 0.75,
		Z:             1,
		MaxOpenKeys:   1_000_000,
	}
}

// State is the traffic state of a segment.
type State int

// Traffic states, from free flow to standstill.
const (
	FreeFlow State = iota
	Heavy
	Slow
	Queue
	Stationary
)

var stateNames = [...]string{"FREE_FLOW", "HEAVY_TRAFFIC", "SLOW_TRAFFIC", "QUEUE", "STATIONARY_TRAFFIC"}

func (s State) String() string { return stateNames[s] }

// TraffEvent returns the TraFF event type of a congestion state ("" for free flow). The CoMaps
// client maps them to speed groups G4 (heavy), G3 (slow), G2 (queue) and G1 (stationary).
func (s State) TraffEvent() string {
	if s == FreeFlow {
		return ""
	}
	return "CONGESTION_" + stateNames[s]
}

// Classify maps the ratio of the median speed to the free-flow speed to a state. The thresholds
// lie between the relative speeds of the CoMaps speed groups (G5 100 %, G4 83 %, G3 58 %,
// G2 33 %, G1 16 %); free flow starts below G4 because the median of free-flowing traffic is
// typically 85–95 % of the V85 reference.
func Classify(ratio, speed, freeFlowRatio float64) State {
	switch {
	case ratio >= freeFlowRatio:
		return FreeFlow
	case ratio >= 0.55:
		return Heavy
	case ratio >= 0.35:
		return Slow
	case speed < 10:
		return Stationary
	default:
		return Queue
	}
}

// DefaultFreeFlow is the free-flow speed (km/h) assumed per road class while too few reports
// are known. The observed V85 is clamped to [0.6, 1.3] times this value.
var DefaultFreeFlow = map[protocol.RoadClass]float64{
	protocol.Motorway:  110,
	protocol.Trunk:     90,
	protocol.Primary:   60,
	protocol.Secondary: 50,
	protocol.Tertiary:  40,
}

const (
	hintBins     = 32
	hintBinWidth = tiles.Size / hintBins
)

type hist [hintBins]float64

func (h *hist) mode() int {
	best := 0
	for i, v := range h {
		if v > h[best] {
			best = i
		}
	}
	return best*hintBinWidth + hintBinWidth/2
}

// windowAgg holds the counters of one segment in one open window. It contains no report.
type windowAgg struct {
	counts      [protocol.NumBuckets]uint32
	entry, exit [hintBins]uint32
}

func (w *windowAgg) n() int {
	n := 0
	for _, c := range w.counts {
		n += int(c)
	}
	return n
}

// reference is the long-term, exponentially decaying histogram of a segment.
type reference struct {
	counts      [protocol.NumBuckets]float64
	entry, exit hist
	updated     time.Time
}

func (r *reference) total() float64 {
	s := 0.0
	for _, c := range r.counts {
		s += c
	}
	return s
}

// LatLon is a WGS84 position.
type LatLon struct {
	Lat float64 `json:"lat"`
	Lon float64 `json:"lon"`
}

// Publication is the evaluated state of a segment in a closed window with at least K reports.
type Publication struct {
	Key         protocol.Key
	WindowStart time.Time
	WindowEnd   time.Time
	Reports     int
	Speed       float64 // estimated median speed (km/h)
	RefSpeed    float64 // free-flow reference (km/h)
	RefObserved bool    // RefSpeed is the observed V85 (otherwise the road class default)
	Ratio       float64
	SlowShare   float64 // estimated share of vehicles slower than FreeFlowRatio·RefSpeed
	SlowShareSE float64 // its standard error
	State       State
	Confident   bool
	From, To    LatLon
	FirstSeen   time.Time // start of the current congestion episode (or of this publication)
	Published   time.Time
	Expires     time.Time
}

// Congested reports whether the publication describes a congestion that should be announced.
func (p *Publication) Congested() bool { return p.State != FreeFlow && p.Confident }

// Stats are aggregate counters. They contain no information about single reports.
type Stats struct {
	ReportsAccepted  uint64 `json:"reports_accepted"`
	ReportsLate      uint64 `json:"reports_late"`
	ReportsDropped   uint64 `json:"reports_dropped_overload"`
	WindowsFinalized uint64 `json:"windows_finalized"`
	SegmentsBelowK   uint64 `json:"segments_below_k"`
	SegmentsAboveK   uint64 `json:"segments_published"`
}

// Footprint describes what the aggregator currently holds in memory.
type Footprint struct {
	OpenWindows  int `json:"open_windows"`
	OpenSegments int `json:"open_segments"`
	References   int `json:"references"`
	Publications int `json:"publications"`
	Congested    int `json:"congested"`
}

// Aggregator is safe for concurrent use.
type Aggregator struct {
	cfg Config
	rr  ldp.RR

	mu              sync.Mutex
	windows         map[int64]map[protocol.Key]*windowAgg
	openKeys        int
	finalizedBefore int64 // all windows starting before this are finalized
	refs            map[protocol.Key]*reference
	pubs            map[protocol.Key]*Publication
	stats           Stats
}

// New creates an aggregator. Windows that would already be finalized at now are treated as
// closed.
func New(cfg Config, now time.Time) *Aggregator {
	a := &Aggregator{
		cfg:     cfg,
		rr:      ldp.New(protocol.NumBuckets, protocol.Epsilon),
		windows: map[int64]map[protocol.Key]*windowAgg{},
		refs:    map[protocol.Key]*reference{},
		pubs:    map[protocol.Key]*Publication{},
	}
	a.finalizedBefore = a.windowStart(now.Add(-cfg.Grace))
	return a
}

func (a *Aggregator) windowStart(t time.Time) int64 {
	w := int64(a.cfg.Window / time.Second)
	s := t.Unix()
	return s - ((s%w)+w)%w
}

// Add counts validated reports. Reports for windows that are already finalized are dropped and
// counted as late. It returns the number of reports counted.
func (a *Aggregator) Add(reports []protocol.Report) int {
	a.mu.Lock()
	defer a.mu.Unlock()
	n := 0
	for _, r := range reports {
		if r.Window < a.finalizedBefore {
			a.stats.ReportsLate++
			continue
		}
		win := a.windows[r.Window]
		if win == nil {
			win = map[protocol.Key]*windowAgg{}
			a.windows[r.Window] = win
		}
		k := r.Key()
		agg := win[k]
		if agg == nil {
			if a.openKeys >= a.cfg.MaxOpenKeys {
				a.stats.ReportsDropped++
				continue
			}
			agg = &windowAgg{}
			win[k] = agg
			a.openKeys++
		}
		agg.counts[r.SpeedBucket]++
		agg.entry[r.Entry.Offset/hintBinWidth]++
		agg.exit[r.Exit.Offset/hintBinWidth]++
		a.stats.ReportsAccepted++
		n++
	}
	return n
}

// Tick finalizes all windows that ended at least Grace before now and removes expired
// publications and references.
func (a *Aggregator) Tick(now time.Time) {
	a.mu.Lock()
	defer a.mu.Unlock()
	cutoff := a.windowStart(now.Add(-a.cfg.Grace))
	if cutoff > a.finalizedBefore {
		a.finalizedBefore = cutoff
	}
	starts := make([]int64, 0, len(a.windows))
	for w := range a.windows {
		if w < a.finalizedBefore {
			starts = append(starts, w)
		}
	}
	sort.Slice(starts, func(i, j int) bool { return starts[i] < starts[j] })
	for _, w := range starts {
		a.finalize(w, now)
	}
	for k, p := range a.pubs {
		if !now.Before(p.Expires) {
			delete(a.pubs, k)
		}
	}
	for k, r := range a.refs {
		if now.Sub(r.updated) >= a.cfg.RefTTL {
			delete(a.refs, k)
		}
	}
}

func (a *Aggregator) finalize(w int64, now time.Time) {
	start := time.Unix(w, 0).UTC()
	end := start.Add(a.cfg.Window)
	for k, agg := range a.windows[w] {
		if agg.n() < a.cfg.K {
			a.stats.SegmentsBelowK++
			continue
		}
		a.stats.SegmentsAboveK++
		ref := a.mergeReference(k, agg, end)
		pub := a.evaluate(k, agg, ref, start, end, now)
		if prev := a.pubs[k]; prev != nil && prev.Congested() && pub.Congested() {
			pub.FirstSeen = prev.FirstSeen
		}
		a.pubs[k] = pub
	}
	a.openKeys -= len(a.windows[w])
	delete(a.windows, w)
	a.stats.WindowsFinalized++
}

func (a *Aggregator) mergeReference(k protocol.Key, agg *windowAgg, end time.Time) *reference {
	ref := a.refs[k]
	if ref == nil {
		ref = &reference{updated: end}
		a.refs[k] = ref
	}
	decay := math.Pow(0.5, end.Sub(ref.updated).Hours()/a.cfg.RefHalfLife.Hours())
	if decay > 1 {
		decay = 1 // windows are finalized in order; keep it robust anyway
	}
	for i, c := range agg.counts {
		ref.counts[i] = ref.counts[i]*decay + float64(c)
	}
	for i := range hintBins {
		ref.entry[i] = ref.entry[i]*decay + float64(agg.entry[i])
		ref.exit[i] = ref.exit[i]*decay + float64(agg.exit[i])
	}
	if end.After(ref.updated) {
		ref.updated = end
	}
	return ref
}

var edges = protocol.SpeedBucketEdges[:]

// referenceSpeed returns the free-flow speed of a segment: the V85 of its de-noised long-term
// distribution, clamped to a range around the road class default, or the default while fewer
// than RefMinReports (decayed) reports are known.
func (a *Aggregator) referenceSpeed(k protocol.Key, ref *reference) (float64, bool) {
	def := DefaultFreeFlow[k.Class]
	if ref.total() < a.cfg.RefMinReports {
		return def, false
	}
	shares := ldp.NormSub(a.rr.Estimate(ref.counts[:]))
	v85 := ldp.Quantile(shares, edges, 0.85)
	return math.Max(0.6*def, math.Min(1.3*def, v85)), true
}

func (a *Aggregator) evaluate(k protocol.Key, agg *windowAgg, ref *reference, start, end, now time.Time) *Publication {
	counts := make([]float64, protocol.NumBuckets)
	for i, c := range agg.counts {
		counts[i] = float64(c)
	}
	shares := ldp.NormSub(a.rr.Estimate(counts))
	speed := ldp.Quantile(shares, edges, 0.5)
	refSpeed, observed := a.referenceSpeed(k, ref)
	threshold := a.cfg.FreeFlowRatio * refSpeed
	slow := ldp.MassBelow(shares, edges, threshold)
	se := a.rr.SetStdErr(counts, func(j int) bool { return edges[j] < threshold })
	ratio := speed / refSpeed
	state := Classify(ratio, speed, a.cfg.FreeFlowRatio)
	p := &Publication{
		Key:         k,
		WindowStart: start,
		WindowEnd:   end,
		Reports:     agg.n(),
		Speed:       speed,
		RefSpeed:    refSpeed,
		RefObserved: observed,
		Ratio:       ratio,
		SlowShare:   slow,
		SlowShareSE: se,
		State:       state,
		Confident:   state == FreeFlow || slow-a.cfg.Z*se >= 0.5,
		FirstSeen:   now,
		Published:   now,
		Expires:     now.Add(a.cfg.Window + a.cfg.Grace),
	}
	p.From.Lat, p.From.Lon = k.Tile.EdgePoint(k.Entry, ref.entry.mode())
	p.To.Lat, p.To.Lon = k.Tile.EdgePoint(k.Exit, ref.exit.mode())
	return p
}

// Publications returns the current publications ordered by key.
func (a *Aggregator) Publications(now time.Time) []Publication {
	a.mu.Lock()
	defer a.mu.Unlock()
	res := make([]Publication, 0, len(a.pubs))
	for _, p := range a.pubs {
		if now.Before(p.Expires) {
			res = append(res, *p)
		}
	}
	sort.Slice(res, func(i, j int) bool { return res[i].Key.String() < res[j].Key.String() })
	return res
}

// Stats returns the counters.
func (a *Aggregator) Stats() Stats {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.stats
}

// Footprint returns what the aggregator currently holds in memory.
func (a *Aggregator) Footprint() Footprint {
	a.mu.Lock()
	defer a.mu.Unlock()
	f := Footprint{OpenWindows: len(a.windows), OpenSegments: a.openKeys, References: len(a.refs), Publications: len(a.pubs)}
	for _, p := range a.pubs {
		if p.Congested() {
			f.Congested++
		}
	}
	return f
}
