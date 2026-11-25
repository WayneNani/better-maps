package venues

import (
	"math"

	"github.com/WayneNani/better-maps/services/indoor-server/internal/geo"
)

// grid is a uniform grid over bounding boxes (in degrees) for neighbourhood queries.
type grid struct {
	cell  float64
	cells map[[2]int32][]int32
	// stamp/gen deduplicate the results of one query (an item spans several cells).
	stamp []uint32
	gen   uint32
}

func newGrid(cellDeg float64) *grid {
	return &grid{cell: cellDeg, cells: map[[2]int32][]int32{}}
}

func (g *grid) span(b geo.BBox) (x0, y0, x1, y1 int32) {
	return int32(math.Floor(b.MinLon / g.cell)), int32(math.Floor(b.MinLat / g.cell)),
		int32(math.Floor(b.MaxLon / g.cell)), int32(math.Floor(b.MaxLat / g.cell))
}

// insert adds item i (0, 1, 2, ... without gaps is cheapest) with box b.
func (g *grid) insert(i int, b geo.BBox) {
	x0, y0, x1, y1 := g.span(b)
	for x := x0; x <= x1; x++ {
		for y := y0; y <= y1; y++ {
			k := [2]int32{x, y}
			g.cells[k] = append(g.cells[k], int32(i))
		}
	}
	for len(g.stamp) <= i {
		g.stamp = append(g.stamp, 0)
	}
}

// query calls fn once for every item whose cells overlap b (the caller checks the boxes).
func (g *grid) query(b geo.BBox, fn func(i int)) {
	g.gen++
	x0, y0, x1, y1 := g.span(b)
	for x := x0; x <= x1; x++ {
		for y := y0; y <= y1; y++ {
			for _, i := range g.cells[[2]int32{x, y}] {
				if g.stamp[i] != g.gen {
					g.stamp[i] = g.gen
					fn(int(i))
				}
			}
		}
	}
}

// unionFind over n items.
type unionFind []int32

func newUnionFind(n int) unionFind {
	u := make(unionFind, n)
	for i := range u {
		u[i] = int32(i)
	}
	return u
}

func (u unionFind) find(i int) int {
	for int(u[i]) != i {
		u[i] = u[u[i]]
		i = int(u[i])
	}
	return i
}

func (u unionFind) union(a, b int) {
	ra, rb := u.find(a), u.find(b)
	if ra != rb {
		if ra < rb {
			u[rb] = int32(ra)
		} else {
			u[ra] = int32(rb)
		}
	}
}
