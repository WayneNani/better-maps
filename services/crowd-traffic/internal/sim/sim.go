// Package sim generates synthetic trips on a few roads, runs them through the reference client
// and returns the bundles the clients would send. It is used by cmd/crowd-sim and by the
// end-to-end tests.
package sim

import (
	"math"
	"math/rand/v2"
	"sort"
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/client"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/ldp"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/protocol"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/tiles"
)

// Jam is a stretch of a road (in metres from its start) with a fixed speed.
type Jam struct {
	FromM, ToM float64
	Kmh        float64
}

// Road is a road driven in one direction.
type Road struct {
	Name              string
	Class             protocol.RoadClass
	Path              [][2]float64 // lat, lon in driving direction
	FreeKmhMin        float64
	FreeKmhMax        float64
	Jams              []Jam
	VehiclesPerWindow int
}

// Scenario is a set of roads and the number of windows with departures.
type Scenario struct {
	Roads   []Road
	Windows int
}

// Berlin returns the reference scenario: a jam on the A100 eastbound between Tempelhofer Damm
// and Oberlandstraße, free flow in the opposite direction and on the B96 (Tempelhofer Damm),
// and a slow village road with too few vehicles to be published.
func Berlin() Scenario {
	a100 := [][2]float64{
		{52.4782, 13.3420}, {52.4772, 13.3530}, {52.4755, 13.3640}, {52.4712, 13.3760},
		{52.4669, 13.3853}, {52.4652, 13.3990}, {52.4645, 13.4130}, {52.4638, 13.4436},
	}
	rev := make([][2]float64, len(a100))
	for i, p := range a100 {
		rev[len(a100)-1-i] = p
	}
	return Scenario{
		Windows: 2,
		Roads: []Road{
			{Name: "A100 Ost", Class: protocol.Motorway, Path: a100, FreeKmhMin: 80, FreeKmhMax: 95,
				Jams: []Jam{{FromM: 2600, ToM: 4600, Kmh: 8}}, VehiclesPerWindow: 60},
			{Name: "A100 West", Class: protocol.Motorway, Path: rev, FreeKmhMin: 80, FreeKmhMax: 95, VehiclesPerWindow: 60},
			{Name: "B96 Tempelhofer Damm Süd", Class: protocol.Primary,
				Path:       [][2]float64{{52.4900, 13.3860}, {52.4750, 13.3855}, {52.4600, 13.3845}, {52.4520, 13.3840}},
				FreeKmhMin: 40, FreeKmhMax: 50, VehiclesPerWindow: 40},
			{Name: "Dorfstraße Schönerlinde", Class: protocol.Tertiary,
				Path:       [][2]float64{{52.6500, 13.4300}, {52.6550, 13.4450}, {52.6600, 13.4600}},
				FreeKmhMin: 5, FreeKmhMax: 8, VehiclesPerWindow: 3},
		},
	}
}

// polyline with cumulative distances and a class per segment.
type polyline struct {
	pts   [][2]float64
	cum   []float64
	class []protocol.RoadClass // class of segment i (pts[i] → pts[i+1])
}

func newPolyline(pts [][2]float64, class []protocol.RoadClass) *polyline {
	p := &polyline{pts: pts, class: class, cum: make([]float64, len(pts))}
	for i := 1; i < len(pts); i++ {
		p.cum[i] = p.cum[i-1] + tiles.DistanceM(pts[i-1][0], pts[i-1][1], pts[i][0], pts[i][1])
	}
	return p
}

func (p *polyline) length() float64 { return p.cum[len(p.cum)-1] }

// at returns the position and class at distance d.
func (p *polyline) at(d float64) (lat, lon float64, class protocol.RoadClass) {
	i := sort.SearchFloat64s(p.cum, d)
	if i <= 0 {
		return p.pts[0][0], p.pts[0][1], p.class[0]
	}
	if i >= len(p.pts) {
		n := len(p.pts) - 1
		return p.pts[n][0], p.pts[n][1], p.class[n-1]
	}
	f := (d - p.cum[i-1]) / (p.cum[i] - p.cum[i-1])
	a, b := p.pts[i-1], p.pts[i]
	return a[0] + f*(b[0]-a[0]), a[1] + f*(b[1]-a[1]), p.class[i-1]
}

// extend returns the point at distance m from a, continuing the direction from b to a.
func extend(a, b [2]float64, m float64) [2]float64 {
	d := tiles.DistanceM(a[0], a[1], b[0], b[1])
	f := m / d
	return [2]float64{a[0] + (a[0]-b[0])*f, a[1] + (a[1]-b[1])*f}
}

// ApproachM is the length of the unclassified approach and exit roads of every trip.
const ApproachM = 1600

// trip returns the full path of a trip on road r (approach, road, exit) and the offset of the
// road start in it.
func (r Road) trip() *polyline {
	n := len(r.Path)
	start := extend(r.Path[0], r.Path[1], ApproachM)
	end := extend(r.Path[n-1], r.Path[n-2], ApproachM)
	pts := append([][2]float64{start}, r.Path...)
	pts = append(pts, end)
	class := make([]protocol.RoadClass, len(pts)-1)
	for i := 1; i < len(class)-1; i++ {
		class[i] = r.Class
	}
	return newPolyline(pts, class)
}

// Length returns the length of the road in metres.
func (r Road) Length() float64 {
	return newPolyline(r.Path, make([]protocol.RoadClass, len(r.Path))).length()
}

func (r Road) speedAt(roadM, free float64) float64 {
	for _, j := range r.Jams {
		if roadM >= j.FromM && roadM < j.ToM {
			return j.Kmh
		}
	}
	return free
}

// Drive returns the map-matched points of one vehicle departing at dep, sampled every second.
func (r Road) Drive(dep time.Time, free float64) []client.Point {
	pl := r.trip()
	var pts []client.Point
	d, t := 0.0, dep
	for {
		lat, lon, class := pl.at(d)
		pts = append(pts, client.Point{Lat: lat, Lon: lon, Time: t, Class: class})
		if d >= pl.length() {
			return pts
		}
		v := 50.0 // approach and exit roads
		if roadM := d - ApproachM; roadM >= 0 && roadM < pl.length()-2*ApproachM {
			v = r.speedAt(roadM, free)
		}
		d = math.Min(pl.length(), d+v/3.6)
		t = t.Add(time.Second)
	}
}

// Trip is one simulated vehicle.
type Trip struct {
	Road   string
	Points []client.Point
}

// Trips generates the trips of the scenario. Departures are spread uniformly over each of the
// first Windows windows after start.
func (s Scenario) Trips(start time.Time, window time.Duration, rnd *rand.Rand) []Trip {
	var res []Trip
	for _, r := range s.Roads {
		for w := range s.Windows {
			for i := range r.VehiclesPerWindow {
				dep := start.Add(time.Duration(w)*window + time.Duration((float64(i)+rnd.Float64())/float64(r.VehiclesPerWindow)*float64(window)))
				free := r.FreeKmhMin + (r.FreeKmhMax-r.FreeKmhMin)*rnd.Float64()
				res = append(res, Trip{Road: r.Name, Points: r.Drive(dep, free)})
			}
		}
	}
	return res
}

// Bundle is a planned bundle together with the road it came from (for statistics only).
type Bundle struct {
	client.Planned
	Road string
}

// Plan runs all trips through the reference client and returns the bundles ordered by send time.
func Plan(trips []Trip, cfg client.Config, rnd *rand.Rand) []Bundle {
	rr := ldp.New(protocol.NumBuckets, protocol.Epsilon)
	var res []Bundle
	for _, t := range trips {
		for _, p := range client.PlanTrip(t.Points, cfg, rr, rnd) {
			res = append(res, Bundle{Planned: p, Road: t.Road})
		}
	}
	sort.SliceStable(res, func(i, j int) bool { return res[i].SendAt.Before(res[j].SendAt) })
	return res
}

// SegmentZones classifies the segment keys of a road: keys whose traversal lies completely
// inside a jam, keys that touch a jam, and all keys of the road.
func (r Road) SegmentZones() (inside, touching, all map[protocol.Key]bool) {
	inside, touching, all = map[protocol.Key]bool{}, map[protocol.Key]bool{}, map[protocol.Key]bool{}
	pts := r.Drive(time.Unix(0, 0), 50)
	for _, t := range client.Segment(pts) {
		if t.Class != r.Class {
			continue
		}
		k := t.Key()
		all[k] = true
		from, to := t.DistIn-ApproachM, t.DistOut-ApproachM
		for _, j := range r.Jams {
			if from < j.ToM && to > j.FromM {
				touching[k] = true
			}
			if from >= j.FromM && to <= j.ToM {
				inside[k] = true
			}
		}
	}
	return inside, touching, all
}
