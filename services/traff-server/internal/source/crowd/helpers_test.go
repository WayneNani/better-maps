package crowd

import (
	"math"
	"slices"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

var t0 = time.Date(2026, 10, 26, 8, 20, 0, 0, time.UTC)

// Base tile in Berlin (A 100 area).
const bx, by = 70400, 43000

// edge returns the point at fraction f (0…1, west→east or north→south) along edge s of tile t.
func edge(t tile, s side, f float64) LatLon {
	px, py := float64(t.x), float64(t.y)
	switch s {
	case north:
		px += f
	case south:
		px, py = px+f, py+1
	case west:
		py += f
	case east:
		px, py = px+1, py+f
	}
	n := float64(int(1) << Zoom)
	return LatLon{
		Lat: 180 / math.Pi * math.Atan(math.Sinh(math.Pi-2*math.Pi*py/n)),
		Lon: px/n*360 - 180,
	}
}

// seg builds a congested segment through tile (bx+dx, by+dy) from edge entry at fraction ef to
// edge exit at fraction xf.
func seg(dx, dy int, entry string, ef float64, exit string, xf float64, state string, speed float64) Segment {
	t := tile{bx + dx, by + dy}
	en, _ := parseSide(entry)
	ex, _ := parseSide(exit)
	return Segment{
		Tile: []int{Zoom, t.x, t.y}, Entry: entry, Exit: exit, RoadClass: "MOTORWAY",
		From: edge(t, en, ef), To: edge(t, ex, xf), Reports: 12, SpeedKmh: speed, State: state,
		Confident: true, Congested: true, Expires: t0.Add(20 * time.Minute),
	}
}

// straight returns a congested west→east segment in the middle of the tile row.
func straight(dx int) Segment { return seg(dx, 0, "W", 0.5, "E", 0.5, "STATIONARY_TRAFFIC", 5) }

func opts(s Segment, fs ...func(*Segment)) Segment {
	c := s
	c.Tile = slices.Clone(s.Tile)
	for _, f := range fs {
		f(&c)
	}
	return c
}

func key(dx, dy int, entry, exit string) string {
	t := tile{bx + dx, by + dy}
	en, _ := parseSide(entry)
	ex, _ := parseSide(exit)
	return segmentKey(t, en, ex, traff.RoadClassMotorway)
}

func id(dx, dy int, entry, exit string) string { return IDPrefix + key(dx, dy, entry, exit) }

// mapSegs maps the segments with default merge options at t0.
func mapSegs(t *testing.T, segs ...Segment) ([]traff.Message, MapStats) {
	t.Helper()
	return Map(&Document{Version: 1, Generated: t0, Segments: segs}, t0, MergeOptions{Tolerance: DefaultMergeDistance, MaxLength: DefaultMaxLength})
}

func ids(msgs []traff.Message) []string {
	var res []string
	for _, m := range msgs {
		res = append(res, m.ID)
	}
	return res
}

func near(a traff.LatLon, b LatLon) bool {
	return math.Abs(a.Lat-b.Lat) < 1e-9 && math.Abs(a.Lon-b.Lon) < 1e-9
}
