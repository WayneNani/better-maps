// Package tiles implements the map-version-independent segment grid of the crowd-traffic
// protocol: Web Mercator ("slippy map") tiles at zoom level 17 and positions on their edges.
//
// A tile at zoom 17 is about 305 m × cos(latitude) wide and high (≈ 188 m in Berlin). Positions
// on a tile edge are given in pixels of a 256-pixel tile (≈ 0.7 m in Germany), counted from the
// north-west corner: from west to east on the north and south edges, from north to south on the
// west and east edges.
package tiles

import (
	"fmt"
	"math"
)

// Zoom is the zoom level of the segment grid.
const Zoom = 17

// Size is the number of pixels along a tile edge; edge offsets are in [0, Size).
const Size = 256

// N is the number of tiles along each axis.
const N = 1 << Zoom

// MaxLat is the latitude limit of the Web Mercator projection.
const MaxLat = 85.05112877980659

// Tile is a tile at zoom level Zoom.
type Tile struct {
	X int `json:"x"`
	Y int `json:"y"`
}

// Valid reports whether the tile exists at zoom level Zoom.
func (t Tile) Valid() bool { return t.X >= 0 && t.X < N && t.Y >= 0 && t.Y < N }

// String formats the tile as "z/x/y".
func (t Tile) String() string { return fmt.Sprintf("%d/%d/%d", Zoom, t.X, t.Y) }

// Side is a tile edge.
type Side uint8

// Tile edges. The zero value is invalid.
const (
	North Side = iota + 1
	East
	South
	West
)

var sideNames = [...]string{"", "N", "E", "S", "W"}

// Valid reports whether s is one of the four edges.
func (s Side) Valid() bool { return s >= North && s <= West }

// String returns "N", "E", "S" or "W".
func (s Side) String() string {
	if !s.Valid() {
		return fmt.Sprintf("Side(%d)", uint8(s))
	}
	return sideNames[s]
}

// MarshalText implements encoding.TextMarshaler.
func (s Side) MarshalText() ([]byte, error) {
	if !s.Valid() {
		return nil, fmt.Errorf("tiles: invalid side %d", uint8(s))
	}
	return []byte(sideNames[s]), nil
}

// UnmarshalText implements encoding.TextUnmarshaler.
func (s *Side) UnmarshalText(b []byte) error {
	for i := North; i <= West; i++ {
		if sideNames[i] == string(b) {
			*s = i
			return nil
		}
	}
	return fmt.Errorf("tiles: invalid side %q", b)
}

// Opposite returns the opposite edge.
func (s Side) Opposite() Side {
	switch s {
	case North:
		return South
	case South:
		return North
	case East:
		return West
	case West:
		return East
	}
	return s
}

// Neighbor returns the tile adjacent to t across edge s.
func (t Tile) Neighbor(s Side) Tile {
	switch s {
	case North:
		t.Y--
	case South:
		t.Y++
	case East:
		t.X++
	case West:
		t.X--
	}
	return t
}

// Project converts WGS84 coordinates to global pixel coordinates at zoom level Zoom (x grows
// eastwards, y southwards; tile = floor(pixel / Size)).
func Project(lat, lon float64) (px, py float64) {
	lat = math.Max(-MaxLat, math.Min(MaxLat, lat))
	world := float64(N * Size)
	px = (lon + 180) / 360 * world
	s := math.Sin(lat * math.Pi / 180)
	py = (0.5 - math.Log((1+s)/(1-s))/(4*math.Pi)) * world
	return px, py
}

// Unproject converts global pixel coordinates at zoom level Zoom to WGS84 coordinates.
func Unproject(px, py float64) (lat, lon float64) {
	world := float64(N * Size)
	lon = px/world*360 - 180
	n := math.Pi - 2*math.Pi*py/world
	lat = 180 / math.Pi * math.Atan(math.Sinh(n))
	return lat, lon
}

// At returns the tile containing the given coordinates.
func At(lat, lon float64) Tile {
	px, py := Project(lat, lon)
	return Tile{int(math.Floor(px / Size)), int(math.Floor(py / Size))}
}

// EdgePoint returns the coordinates of an edge position: the centre of pixel offset on edge s.
func (t Tile) EdgePoint(s Side, offset int) (lat, lon float64) {
	x0, y0 := float64(t.X*Size), float64(t.Y*Size)
	o := float64(offset) + 0.5
	switch s {
	case North:
		return Unproject(x0+o, y0)
	case South:
		return Unproject(x0+o, y0+Size)
	case West:
		return Unproject(x0, y0+o)
	default: // East
		return Unproject(x0+Size, y0+o)
	}
}

// Center returns the coordinates of the tile centre.
func (t Tile) Center() (lat, lon float64) {
	return Unproject(float64(t.X*Size)+Size/2, float64(t.Y*Size)+Size/2)
}

// DistanceM returns the great-circle distance between two points in metres.
func DistanceM(lat1, lon1, lat2, lon2 float64) float64 {
	const r = 6371008.8
	rad := math.Pi / 180
	dLat := (lat2 - lat1) * rad
	dLon := (lon2 - lon1) * rad
	a := math.Sin(dLat/2)*math.Sin(dLat/2) + math.Cos(lat1*rad)*math.Cos(lat2*rad)*math.Sin(dLon/2)*math.Sin(dLon/2)
	return 2 * r * math.Asin(math.Min(1, math.Sqrt(a)))
}
