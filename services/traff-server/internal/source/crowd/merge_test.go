package crowd

import (
	"slices"
	"testing"

	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

func TestChainStraight(t *testing.T) {
	var segs []Segment
	for dx := range 5 {
		segs = append(segs, straight(dx))
	}
	msgs, st := mapSegs(t, segs...)
	if len(msgs) != 1 || msgs[0].ID != id(4, 0, "W", "E") {
		t.Fatalf("messages = %v", ids(msgs))
	}
	loc := msgs[0].Location
	if !near(loc.From.Coord, segs[0].From) || !near(loc.To.Coord, segs[4].To) || loc.Via == nil || !(near(loc.Via.Coord, segs[1].To) || near(loc.Via.Coord, segs[2].To)) {
		t.Errorf("location = %+v %+v %+v", loc.From, loc.Via, loc.To)
	}
	if loc.RoadClass != traff.RoadClassMotorway || loc.Directionality != traff.OneDirection || loc.RoadRef != "" {
		t.Errorf("location = %+v", loc)
	}
	if st.Merge.Joins != 4 || st.Merge.Longest != 5 || st.Merge.CornerJoins != 0 || st.Congested != 5 || st.Messages != 1 {
		t.Errorf("stats = %+v", st)
	}
	// The order of the segments in the export does not matter.
	slices.Reverse(segs)
	if again, _ := mapSegs(t, segs...); len(again) != 1 || again[0].ID != msgs[0].ID {
		t.Errorf("reversed input: %v", ids(again))
	}
}

func TestChainBreaks(t *testing.T) {
	free := opts(straight(2), func(s *Segment) { s.Congested, s.State = false, "FREE_FLOW" })
	trunk := opts(straight(2), func(s *Segment) { s.RoadClass = "TRUNK" })
	cases := []struct {
		name string
		segs []Segment
		want []string
	}{
		{"gap without segment", []Segment{straight(0), straight(1), straight(3), straight(4)},
			[]string{id(1, 0, "W", "E"), id(4, 0, "W", "E")}},
		{"free-flowing tile", []Segment{straight(0), straight(1), free, straight(3)},
			[]string{id(1, 0, "W", "E"), id(3, 0, "W", "E")}},
		{"road class changes", []Segment{straight(0), straight(1), trunk},
			[]string{id(1, 0, "W", "E"), IDPrefix + "17/70402/43000/W-E/TRUNK"}},
		// A road 37 m (0.2 tiles) to the side: a different road, not a continuation.
		{"entry point too far from exit point", []Segment{straight(0), seg(1, 0, "W", 0.7, "E", 0.7, "QUEUE", 15)},
			[]string{id(0, 0, "W", "E"), id(1, 0, "W", "E")}},
		{"entry point within tolerance", []Segment{straight(0), seg(1, 0, "W", 0.55, "E", 0.5, "QUEUE", 15)},
			[]string{id(1, 0, "W", "E")}},
		// Both carriageways on the same line, as in the simulator: the directions stay apart.
		{"opposite direction", []Segment{straight(0), straight(1), straight(2),
			seg(0, 0, "E", 0.5, "W", 0.5, "QUEUE", 12), seg(1, 0, "E", 0.5, "W", 0.5, "QUEUE", 12), seg(2, 0, "E", 0.5, "W", 0.5, "QUEUE", 12)},
			[]string{id(0, 0, "E", "W"), id(2, 0, "W", "E")}},
		// Turning back into the tile it came from is not a continuation either.
		{"u-turn", []Segment{straight(0), seg(1, 0, "W", 0.5, "W", 0.6, "QUEUE", 12)},
			[]string{id(0, 0, "W", "E")}},
	}
	for _, c := range cases {
		msgs, _ := mapSegs(t, c.segs...)
		if got := ids(msgs); !slices.Equal(got, c.want) {
			t.Errorf("%s: %v, want %v", c.name, got, c.want)
		}
	}
}

func TestChainFork(t *testing.T) {
	// The motorway forks in tile 1: both branches continue as motorway.
	msgs, st := mapSegs(t, straight(0),
		seg(1, 0, "W", 0.5, "E", 0.5, "QUEUE", 12), straight(2),
		seg(1, 0, "W", 0.5, "S", 0.5, "QUEUE", 12), seg(1, 1, "N", 0.5, "S", 0.5, "QUEUE", 12))
	want := []string{id(0, 0, "W", "E"), id(1, 1, "N", "S"), id(2, 0, "W", "E")}
	if got := ids(msgs); !slices.Equal(got, want) {
		t.Errorf("fork: %v, want %v", got, want)
	}
	if st.Merge.Forks != 1 || st.Merge.Merges != 0 || st.Merge.Joins != 2 {
		t.Errorf("stats = %+v", st.Merge)
	}
}

func TestChainMerge(t *testing.T) {
	// Two motorways join in tile 0 and continue east.
	msgs, st := mapSegs(t, straight(-1), straight(0),
		seg(0, -1, "N", 0.5, "S", 0.5, "QUEUE", 12), seg(0, 0, "N", 0.5, "E", 0.5, "QUEUE", 12),
		straight(1), straight(2))
	want := []string{id(0, 0, "N", "E"), id(0, 0, "W", "E"), id(2, 0, "W", "E")}
	if got := ids(msgs); !slices.Equal(got, want) {
		t.Errorf("merge: %v, want %v", got, want)
	}
	if st.Merge.Merges != 1 || st.Merge.Forks != 0 || st.Merge.Joins != 3 {
		t.Errorf("stats = %+v", st.Merge)
	}
}

func TestChainCorner(t *testing.T) {
	// The road leaves tile 0 near its south-east corner, clips the corner of tile (0,1) for a
	// few metres (not reported) and continues in tile (1,1).
	a := seg(0, 0, "W", 0.5, "S", 0.95, "STATIONARY_TRAFFIC", 5)
	b := seg(1, 1, "W", 0.05, "E", 0.5, "STATIONARY_TRAFFIC", 5)
	msgs, st := mapSegs(t, a, b)
	if got := ids(msgs); !slices.Equal(got, []string{id(1, 1, "W", "E")}) || st.Merge.CornerJoins != 1 || st.Merge.Joins != 1 {
		t.Errorf("corner: %v, stats %+v", got, st.Merge)
	}
	// Mirrored: leaving through the north edge near the north-west corner, continuing west.
	msgs, st = mapSegs(t, seg(0, 0, "S", 0.5, "N", 0.04, "QUEUE", 12), seg(-1, -1, "E", 0.97, "W", 0.5, "QUEUE", 12))
	if got := ids(msgs); !slices.Equal(got, []string{id(-1, -1, "E", "W")}) || st.Merge.CornerJoins != 1 {
		t.Errorf("mirrored corner: %v, stats %+v", got, st.Merge)
	}
	// Two candidates behind the corner: a fork, both branches become chains of their own.
	b2 := seg(1, 1, "W", 0.07, "S", 0.5, "STATIONARY_TRAFFIC", 5)
	if msgs, st = mapSegs(t, a, b, b2); len(msgs) != 3 || st.Merge.Forks != 1 || st.Merge.Joins != 0 {
		t.Errorf("fork behind the corner: %v, stats %+v", ids(msgs), st.Merge)
	}
	// Exit and entry about 100 m apart: no corner passage.
	far := seg(1, 1, "W", 0.4, "E", 0.5, "STATIONARY_TRAFFIC", 5)
	if msgs, st = mapSegs(t, seg(0, 0, "W", 0.5, "S", 0.6, "STATIONARY_TRAFFIC", 5), far); len(msgs) != 2 || st.Merge.CornerJoins != 0 {
		t.Errorf("far corner: %v", ids(msgs))
	}
	// With a reported segment in the corner tile, the direct link is used.
	c := seg(0, 1, "N", 0.95, "E", 0.05, "STATIONARY_TRAFFIC", 5)
	if msgs, st = mapSegs(t, a, b, c); len(msgs) != 1 || st.Merge.Joins != 2 || st.Merge.CornerJoins != 0 || st.Merge.Longest != 3 {
		t.Errorf("direct link: %v, stats %+v", ids(msgs), st.Merge)
	}
}

func TestChainRing(t *testing.T) {
	// A congested ring through four tiles; it is cut before the segment with the smallest key.
	a := seg(0, 0, "S", 0.5, "E", 0.5, "QUEUE", 12)
	b := seg(1, 0, "W", 0.5, "S", 0.5, "QUEUE", 12)
	c := seg(1, 1, "N", 0.5, "W", 0.5, "QUEUE", 12)
	d := seg(0, 1, "E", 0.5, "N", 0.5, "QUEUE", 12)
	msgs, st := mapSegs(t, c, a, d, b)
	if len(msgs) != 1 || msgs[0].ID != id(0, 0, "S", "E") || st.Merge.Joins != 3 || st.Merge.Longest != 4 {
		t.Fatalf("ring: %v, stats %+v", ids(msgs), st.Merge)
	}
	if !near(msgs[0].Location.From.Coord, b.From) || !near(msgs[0].Location.To.Coord, a.To) {
		t.Errorf("ring location = %+v", msgs[0].Location)
	}
}

func TestNoMerging(t *testing.T) {
	segs := []Segment{straight(0), straight(1), straight(2)}
	msgs, st := Map(&Document{Version: 1, Segments: segs}, t0, MergeOptions{})
	if len(msgs) != 3 || st.Merge.Joins != 0 {
		t.Fatalf("without merging: %v", ids(msgs))
	}
	for i, m := range msgs {
		if m.ID != id(i, 0, "W", "E") || m.Location.Via != nil || !near(m.Location.From.Coord, segs[i].From) {
			t.Errorf("message %d = %+v", i, m)
		}
	}
}

func TestSplitAndStableIDs(t *testing.T) {
	jam := func(from, to int) []Segment {
		var segs []Segment
		for dx := from; dx <= to; dx++ {
			segs = append(segs, straight(dx))
		}
		return segs
	}
	opt := MergeOptions{Tolerance: 25, MaxLength: 500} // two tiles of about 186 m per message
	msgs, st := Map(&Document{Version: 1, Segments: jam(0, 9)}, t0, opt)
	want := []string{id(1, 0, "W", "E"), id(3, 0, "W", "E"), id(5, 0, "W", "E"), id(7, 0, "W", "E"), id(9, 0, "W", "E")}
	if got := ids(msgs); !slices.Equal(got, want) || st.Merge.Splits != 4 || st.Merge.Longest != 2 {
		t.Fatalf("split: %v, stats %+v", got, st.Merge)
	}
	// The jam grows at its tail: the existing messages keep their IDs.
	msgs, _ = Map(&Document{Version: 1, Segments: jam(-1, 9)}, t0, opt)
	if got := ids(msgs); !slices.Equal(got, append([]string{id(-1, 0, "W", "E")}, want...)) {
		t.Errorf("grown: %v", got)
	}
	// Without length limit, too.
	a, _ := mapSegs(t, jam(3, 6)...)
	b, _ := mapSegs(t, jam(0, 6)...)
	if len(a) != 1 || len(b) != 1 || a[0].ID != b[0].ID || a[0].ID != id(6, 0, "W", "E") {
		t.Errorf("growing tail changes the ID: %v %v", ids(a), ids(b))
	}
	// A single segment longer than the limit is never cut.
	if parts := split(chain{{length: 900}, {length: 100}}, 500); len(parts) != 2 || len(parts[0]) != 1 {
		t.Errorf("parts = %v", parts)
	}
}
