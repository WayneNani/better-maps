// Package client is the reference implementation of the reporting client (AP5b): it cuts a
// map-matched trace into tile segments, applies the privacy rules of the client contract
// (trimming, delayed release, sampling, randomized response, batching) and sends bundles via
// Oblivious HTTP.
//
// The app implements the same rules incrementally on the device; this package processes whole
// trips offline and is used by the simulator and the end-to-end tests.
package client

import (
	"math"
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/protocol"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/tiles"
)

// Point is a map-matched position. Class is the class of the road between this point and the
// next one (0 for roads that are never reported: links, residential roads, unmatched stretches).
type Point struct {
	Lat, Lon float64
	Time     time.Time
	Class    protocol.RoadClass
}

// Traversal is one pass through a tile from one edge to another.
type Traversal struct {
	Tile        tiles.Tile
	Entry, Exit protocol.Edge
	Class       protocol.RoadClass // 0 if the road class changed inside the tile
	TIn, TOut   time.Time
	LengthM     float64
	// Route distance from the start of the trip at entry and exit, and the coordinates of the
	// entry and exit points (used for trimming only, never sent).
	DistIn, DistOut float64
	InLat, InLon    float64
	OutLat, OutLon  float64
}

// SpeedKmh returns the average speed in the tile.
func (t Traversal) SpeedKmh() float64 {
	d := t.TOut.Sub(t.TIn).Hours()
	if d <= 0 {
		return math.Inf(1)
	}
	return t.LengthM / 1000 / d
}

// Key returns the segment key.
func (t Traversal) Key() protocol.Key {
	return protocol.Key{Tile: t.Tile, Entry: t.Entry.Side, Exit: t.Exit.Side, Class: t.Class}
}

type crossing struct {
	t        float64
	vertical bool // crossing of a line x = const
	forward  bool // moving towards larger pixel coordinates
}

// crossings returns the grid line crossings between pixel positions a and b, ordered along the
// way. A point exactly on a grid line belongs to the tile east/south of it (floor semantics).
func crossings(ax, ay, bx, by float64) []crossing {
	var res []crossing
	add := func(a, b float64, vertical bool) {
		ka, kb := math.Floor(a/tiles.Size), math.Floor(b/tiles.Size)
		if ka == kb {
			return
		}
		step := 1.0
		first := ka + 1 // line k*Size is crossed when moving from tile k-1 to tile k
		if kb < ka {
			step = -1
			first = ka
		}
		for k := first; ; k += step {
			res = append(res, crossing{t: (k*tiles.Size - a) / (b - a), vertical: vertical, forward: step > 0})
			if (step > 0 && k >= kb) || (step < 0 && k <= kb+1) {
				break
			}
		}
	}
	add(ax, bx, true)
	add(ay, by, false)
	// Insertion sort: there are only a few crossings per GPS step. Ties: vertical lines first.
	for i := 1; i < len(res); i++ {
		for j := i; j > 0 && (res[j].t < res[j-1].t || (res[j].t == res[j-1].t && res[j].vertical && !res[j-1].vertical)); j-- {
			res[j], res[j-1] = res[j-1], res[j]
		}
	}
	return res
}

func clampOffset(v float64) int {
	return max(0, min(tiles.Size-1, int(math.Floor(v))))
}

// Segment cuts a map-matched trace into tile traversals. Only complete traversals (entered and
// left through an edge) are returned; the first and the last tile of the trace are not.
func Segment(points []Point) []Traversal {
	if len(points) < 2 {
		return nil
	}
	var res []Traversal
	px, py := tiles.Project(points[0].Lat, points[0].Lon)
	cur := tiles.Tile{X: int(math.Floor(px / tiles.Size)), Y: int(math.Floor(py / tiles.Size))}
	var (
		entered           bool
		entry             protocol.Edge
		tIn               time.Time
		length, dist, dIn float64
		class             = points[0].Class
		mixed             bool
		inLat, inLon      float64
		lastLat, lastLon  = points[0].Lat, points[0].Lon
	)
	addLength := func(lat, lon float64) {
		d := tiles.DistanceM(lastLat, lastLon, lat, lon)
		length += d
		dist += d
		lastLat, lastLon = lat, lon
	}
	for i := 1; i < len(points); i++ {
		a, b := points[i-1], points[i]
		ax, ay := tiles.Project(a.Lat, a.Lon)
		bx, by := tiles.Project(b.Lat, b.Lon)
		if a.Class != class {
			mixed = true
		}
		for _, c := range crossings(ax, ay, bx, by) {
			cx, cy := ax+c.t*(bx-ax), ay+c.t*(by-ay)
			lat, lon := tiles.Unproject(cx, cy)
			tm := a.Time.Add(time.Duration(c.t * float64(b.Time.Sub(a.Time))))
			addLength(lat, lon)
			var exit protocol.Edge
			switch {
			case c.vertical && c.forward:
				exit = protocol.Edge{Side: tiles.East, Offset: clampOffset(cy - float64(cur.Y*tiles.Size))}
			case c.vertical:
				exit = protocol.Edge{Side: tiles.West, Offset: clampOffset(cy - float64(cur.Y*tiles.Size))}
			case c.forward:
				exit = protocol.Edge{Side: tiles.South, Offset: clampOffset(cx - float64(cur.X*tiles.Size))}
			default:
				exit = protocol.Edge{Side: tiles.North, Offset: clampOffset(cx - float64(cur.X*tiles.Size))}
			}
			if entered {
				cl := class
				if mixed {
					cl = 0
				}
				res = append(res, Traversal{
					Tile: cur, Entry: entry, Exit: exit, Class: cl, TIn: tIn, TOut: tm, LengthM: length,
					DistIn: dIn, DistOut: dist, InLat: inLat, InLon: inLon, OutLat: lat, OutLon: lon,
				})
			}
			cur = cur.Neighbor(exit.Side)
			entered = true
			entry = protocol.Edge{Side: exit.Side.Opposite(), Offset: exit.Offset}
			tIn, dIn, inLat, inLon = tm, dist, lat, lon
			length, class, mixed = 0, a.Class, false
		}
		addLength(b.Lat, b.Lon)
	}
	return res
}
