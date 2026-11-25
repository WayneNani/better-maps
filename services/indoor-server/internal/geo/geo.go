// Package geo holds the small amount of planar geometry the venue builder needs: bounding
// boxes, a local metric projection, Douglas-Peucker simplification, areas, point-in-polygon and
// the Google encoded polyline format (precision 6).
//
// Coordinates are WGS84 degrees. Distances use an equirectangular projection around the
// latitude of the geometry, which is exact enough for buildings (errors well below 1 % over a
// few kilometres).
package geo

import (
	"math"
	"strings"
)

// Point is a WGS84 coordinate.
type Point struct{ Lon, Lat float64 }

// Ring is a polygon ring without the closing point (last != first).
type Ring []Point

// Polygon is an outer ring followed by its holes.
type Polygon []Ring

// MetersPerDegLat is the length of one degree of latitude (mean).
const MetersPerDegLat = 111_195.0

// MetersPerDegLon returns the length of one degree of longitude at lat.
func MetersPerDegLon(lat float64) float64 { return MetersPerDegLat * math.Cos(lat*math.Pi/180) }

// BBox is a bounding box in degrees.
type BBox struct{ MinLon, MinLat, MaxLon, MaxLat float64 }

// EmptyBBox returns a box that contains nothing; Extend makes it grow.
func EmptyBBox() BBox {
	return BBox{math.Inf(1), math.Inf(1), math.Inf(-1), math.Inf(-1)}
}

// Valid reports whether the box contains at least one point.
func (b BBox) Valid() bool { return b.MinLon <= b.MaxLon && b.MinLat <= b.MaxLat }

// Extend grows b to contain p.
func (b *BBox) Extend(p Point) {
	b.MinLon = math.Min(b.MinLon, p.Lon)
	b.MinLat = math.Min(b.MinLat, p.Lat)
	b.MaxLon = math.Max(b.MaxLon, p.Lon)
	b.MaxLat = math.Max(b.MaxLat, p.Lat)
}

// Union returns the box containing b and o.
func (b BBox) Union(o BBox) BBox {
	return BBox{math.Min(b.MinLon, o.MinLon), math.Min(b.MinLat, o.MinLat),
		math.Max(b.MaxLon, o.MaxLon), math.Max(b.MaxLat, o.MaxLat)}
}

// Intersects reports whether b and o share at least one point (touching counts).
func (b BBox) Intersects(o BBox) bool {
	return b.MinLon <= o.MaxLon && o.MinLon <= b.MaxLon && b.MinLat <= o.MaxLat && o.MinLat <= b.MaxLat
}

// Contains reports whether p lies in b (boundary included).
func (b BBox) Contains(p Point) bool {
	return p.Lon >= b.MinLon && p.Lon <= b.MaxLon && p.Lat >= b.MinLat && p.Lat <= b.MaxLat
}

// Center returns the centre of b.
func (b BBox) Center() Point { return Point{(b.MinLon + b.MaxLon) / 2, (b.MinLat + b.MaxLat) / 2} }

// Expand grows b by m metres on every side.
func (b BBox) Expand(m float64) BBox {
	dLat := m / MetersPerDegLat
	dLon := m / MetersPerDegLon(math.Max(math.Abs(b.MinLat), math.Abs(b.MaxLat)))
	return BBox{b.MinLon - dLon, b.MinLat - dLat, b.MaxLon + dLon, b.MaxLat + dLat}
}

// SizeMeters returns width and height of b in metres.
func (b BBox) SizeMeters() (w, h float64) {
	lat := (b.MinLat + b.MaxLat) / 2
	return (b.MaxLon - b.MinLon) * MetersPerDegLon(lat), (b.MaxLat - b.MinLat) * MetersPerDegLat
}

// AreaM2 returns the area of b in square metres.
func (b BBox) AreaM2() float64 {
	w, h := b.SizeMeters()
	return w * h
}

// DistanceMeters returns the distance from p to b (0 inside).
func (b BBox) DistanceMeters(p Point) float64 {
	dx := math.Max(0, math.Max(b.MinLon-p.Lon, p.Lon-b.MaxLon)) * MetersPerDegLon(p.Lat)
	dy := math.Max(0, math.Max(b.MinLat-p.Lat, p.Lat-b.MaxLat)) * MetersPerDegLat
	return math.Hypot(dx, dy)
}

// BBoxOf returns the box of pts.
func BBoxOf(pts []Point) BBox {
	b := EmptyBBox()
	for _, p := range pts {
		b.Extend(p)
	}
	return b
}

// proj is an equirectangular projection to metres around a reference latitude.
type proj struct{ kx, ky float64 }

func projAt(lat float64) proj { return proj{MetersPerDegLon(lat), MetersPerDegLat} }

func (p proj) xy(pt Point) (float64, float64) { return pt.Lon * p.kx, pt.Lat * p.ky }

// Simplify reduces a line with the Douglas-Peucker algorithm; points closer than tol metres to
// the simplified line are dropped. The first and last point are kept.
func Simplify(pts []Point, tol float64) []Point {
	if len(pts) < 3 || tol <= 0 {
		return append([]Point(nil), pts...)
	}
	pr := projAt(pts[0].Lat)
	keep := make([]bool, len(pts))
	keep[0], keep[len(pts)-1] = true, true
	type span struct{ a, b int }
	stack := []span{{0, len(pts) - 1}}
	for len(stack) > 0 {
		s := stack[len(stack)-1]
		stack = stack[:len(stack)-1]
		if s.b-s.a < 2 {
			continue
		}
		ax, ay := pr.xy(pts[s.a])
		bx, by := pr.xy(pts[s.b])
		best, bestI := -1.0, -1
		for i := s.a + 1; i < s.b; i++ {
			px, py := pr.xy(pts[i])
			if d := segDist(px, py, ax, ay, bx, by); d > best {
				best, bestI = d, i
			}
		}
		if best > tol {
			keep[bestI] = true
			stack = append(stack, span{s.a, bestI}, span{bestI, s.b})
		}
	}
	out := make([]Point, 0, len(pts))
	for i, k := range keep {
		if k {
			out = append(out, pts[i])
		}
	}
	return out
}

// SimplifyRing simplifies a ring (without closing point). It splits the ring at the point
// farthest from the first one so that both halves keep their shape.
func SimplifyRing(r Ring, tol float64) Ring {
	if len(r) < 4 {
		return append(Ring(nil), r...)
	}
	pr := projAt(r[0].Lat)
	x0, y0 := pr.xy(r[0])
	far, farD := 0, -1.0
	for i, p := range r {
		x, y := pr.xy(p)
		if d := math.Hypot(x-x0, y-y0); d > farD {
			far, farD = i, d
		}
	}
	first := Simplify(r[:far+1], tol)
	closed := append(append([]Point(nil), r[far:]...), r[0])
	second := Simplify(closed, tol)
	out := append(Ring(nil), first...)
	out = append(out, second[1:len(second)-1]...)
	return out
}

// segDist is the distance from (px,py) to the segment (ax,ay)-(bx,by).
func segDist(px, py, ax, ay, bx, by float64) float64 {
	dx, dy := bx-ax, by-ay
	l2 := dx*dx + dy*dy
	if l2 == 0 {
		return math.Hypot(px-ax, py-ay)
	}
	t := math.Max(0, math.Min(1, ((px-ax)*dx+(py-ay)*dy)/l2))
	return math.Hypot(px-(ax+t*dx), py-(ay+t*dy))
}

// RingArea returns the area of r in square metres (always >= 0).
func RingArea(r Ring) float64 {
	if len(r) < 3 {
		return 0
	}
	pr := projAt(r[0].Lat)
	ox, oy := pr.xy(r[0])
	var s float64
	for i := range r {
		x1, y1 := pr.xy(r[i])
		x2, y2 := pr.xy(r[(i+1)%len(r)])
		s += (x1-ox)*(y2-oy) - (x2-ox)*(y1-oy)
	}
	return math.Abs(s) / 2
}

// PolygonArea returns the area of the outer ring minus the holes, in square metres.
func PolygonArea(p Polygon) float64 {
	if len(p) == 0 {
		return 0
	}
	a := RingArea(p[0])
	for _, h := range p[1:] {
		a -= RingArea(h)
	}
	return math.Max(0, a)
}

// PointInRing reports whether p lies inside r (even-odd rule; boundary points may go either way).
func PointInRing(p Point, r Ring) bool {
	in := false
	for i, j := 0, len(r)-1; i < len(r); j, i = i, i+1 {
		a, b := r[i], r[j]
		if (a.Lat > p.Lat) != (b.Lat > p.Lat) &&
			p.Lon < (b.Lon-a.Lon)*(p.Lat-a.Lat)/(b.Lat-a.Lat)+a.Lon {
			in = !in
		}
	}
	return in
}

// PointInPolygon reports whether p lies inside the outer ring of poly and outside its holes.
func PointInPolygon(p Point, poly Polygon) bool {
	if len(poly) == 0 || !PointInRing(p, poly[0]) {
		return false
	}
	for _, h := range poly[1:] {
		if PointInRing(p, h) {
			return false
		}
	}
	return true
}

// DistanceToRing returns the distance in metres from p to the outline of r.
func DistanceToRing(p Point, r Ring) float64 {
	if len(r) == 0 {
		return math.Inf(1)
	}
	pr := projAt(p.Lat)
	px, py := pr.xy(p)
	best := math.Inf(1)
	for i := range r {
		ax, ay := pr.xy(r[i])
		bx, by := pr.xy(r[(i+1)%len(r)])
		best = math.Min(best, segDist(px, py, ax, ay, bx, by))
	}
	return best
}

// Round6 rounds pts to 1e-6 degrees (the precision of the encoding) and drops consecutive
// duplicates.
func Round6(pts []Point) []Point {
	out := make([]Point, 0, len(pts))
	for _, p := range pts {
		q := Point{math.Round(p.Lon*1e6) / 1e6, math.Round(p.Lat*1e6) / 1e6}
		if len(out) > 0 && out[len(out)-1] == q {
			continue
		}
		out = append(out, q)
	}
	return out
}

// Encode returns pts in the Google encoded polyline format with precision 6 (1e-6 degrees),
// latitude first.
func Encode(pts []Point) string {
	var sb strings.Builder
	var plat, plon int64
	for _, p := range pts {
		lat := int64(math.Round(p.Lat * 1e6))
		lon := int64(math.Round(p.Lon * 1e6))
		encodeValue(&sb, lat-plat)
		encodeValue(&sb, lon-plon)
		plat, plon = lat, lon
	}
	return sb.String()
}

func encodeValue(sb *strings.Builder, v int64) {
	u := uint64(v) << 1
	if v < 0 {
		u = ^u
	}
	for u >= 0x20 {
		sb.WriteByte(byte((0x20 | (u & 0x1f)) + 63))
		u >>= 5
	}
	sb.WriteByte(byte(u + 63))
}

// Decode is the inverse of Encode (for tests and tools). It returns false for malformed input.
func Decode(s string) ([]Point, bool) {
	var pts []Point
	var lat, lon int64
	i := 0
	next := func() (int64, bool) {
		var result uint64
		shift := uint(0)
		for {
			if i >= len(s) || shift > 60 {
				return 0, false
			}
			b := uint64(s[i]) - 63
			i++
			if b > 0x3f {
				return 0, false
			}
			result |= (b & 0x1f) << shift
			shift += 5
			if b < 0x20 {
				break
			}
		}
		if result&1 != 0 {
			return int64(^(result >> 1)), true
		}
		return int64(result >> 1), true
	}
	for i < len(s) {
		dlat, ok := next()
		if !ok {
			return nil, false
		}
		dlon, ok := next()
		if !ok {
			return nil, false
		}
		lat += dlat
		lon += dlon
		pts = append(pts, Point{float64(lon) / 1e6, float64(lat) / 1e6})
	}
	return pts, true
}
