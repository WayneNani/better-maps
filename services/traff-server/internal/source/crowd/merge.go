package crowd

import (
	"cmp"
	"slices"
)

// CornerGap is the length (metres) of a tile passage that the app does not report: a road that
// only clips the corner of a tile for less than this distance leaves no segment in that tile.
// Two segments in diagonally adjacent tiles whose exit and entry points are at most
// CornerGap + tolerance apart are joined across such a corner.
const CornerGap = 50

// MergeOptions control how adjacent segments are joined into chains.
type MergeOptions struct {
	// Tolerance is the largest distance in metres between the exit point of a segment and the
	// entry point of its successor. Zero or less disables merging: every segment becomes its
	// own message.
	Tolerance float64
	// MaxLength is the largest length of a chain in metres; longer chains are split, counted
	// from the downstream end. Zero or less means no limit.
	MaxLength float64
}

// MergeStats describe the result of a merge run.
type MergeStats struct {
	Joins       int // links between segments that are part of a chain
	CornerJoins int // of these, joins across the corner of a tile without segment
	Forks       int // segments with more than one possible successor (chain ends there)
	Merges      int // segments with more than one possible predecessor (chain starts there)
	Splits      int // additional chains due to MaxLength
	Longest     int // number of segments of the longest chain
}

// entryKey indexes segments by their entry edge.
type entryKey struct {
	tile  tile
	entry side
	class string
}

// chain is a sequence of segments in driving direction (upstream first).
type chain []*segment

// length returns the sum of the segment lengths.
func (c chain) length() float64 {
	l := 0.0
	for _, s := range c {
		l += s.length
	}
	return l
}

// buildChains joins adjacent congested segments into chains. Segment a is linked to segment b if
// both have the same road class, b starts in the tile across the exit edge of a, on the opposite
// edge (so b continues in the direction of a), and the exit point of a and the entry point of b
// are at most opt.Tolerance apart. If a has no such successor, b may also start in a tile
// diagonally adjacent across a corner (see CornerGap).
//
// A link becomes part of a chain only if it is unambiguous: a has exactly one successor and b
// exactly one predecessor. At forks and merges the chains end, and every branch becomes a chain
// of its own. Gaps (a tile without congested segment) and the opposite direction (whose
// segments enter on the other edge) never link.
//
// The segments must be sorted by key; the result is deterministic.
func buildChains(segs []*segment, opt MergeOptions) ([]chain, MergeStats) {
	var st MergeStats
	for _, s := range segs {
		s.successors, s.preds = nil, nil
	}
	if opt.Tolerance > 0 {
		link(segs, opt.Tolerance, &st)
	}

	visited := make(map[*segment]bool, len(segs))
	var chains []chain
	// collect walks upstream from the downstream end h.
	collect := func(h *segment) chain {
		c := chain{h}
		visited[h] = true
		for cur := h; len(cur.preds) == 1; {
			p := cur.preds[0]
			if len(p.successors) != 1 || visited[p] {
				break
			}
			visited[p] = true
			c = append(c, p)
			cur = p
		}
		slices.Reverse(c)
		return c
	}
	for _, s := range segs {
		if !chainable(s) {
			chains = append(chains, collect(s))
		}
	}
	// What is left forms closed rings (e.g. a congested roundabout or ring road); they are cut
	// before the segment with the smallest key.
	for _, s := range segs {
		if !visited[s] {
			chains = append(chains, collect(s))
		}
	}

	var res []chain
	for _, c := range chains {
		for i := 1; i < len(c); i++ {
			st.Joins++
			if c[i].tile != c[i-1].tile.neighbor(c[i-1].exit) {
				st.CornerJoins++
			}
		}
		parts := split(c, opt.MaxLength)
		st.Splits += len(parts) - 1
		for _, p := range parts {
			st.Longest = max(st.Longest, len(p))
		}
		res = append(res, parts...)
	}
	return res, st
}

// chainable reports whether the link from s to its successor is part of a chain.
func chainable(s *segment) bool {
	return len(s.successors) == 1 && len(s.successors[0].preds) == 1
}

// link finds the successors and predecessors of all segments.
func link(segs []*segment, tol float64, st *MergeStats) {
	byEntry := map[entryKey][]*segment{}
	for _, s := range segs {
		k := entryKey{s.tile, s.entry, string(s.class)}
		byEntry[k] = append(byEntry[k], s)
	}
	candidates := func(a *segment, t tile, entry side, maxDist float64) []*segment {
		var res []*segment
		for _, b := range byEntry[entryKey{t, entry, string(a.class)}] {
			if distance(a.to, b.from) <= maxDist {
				res = append(res, b)
			}
		}
		return res
	}
	for _, a := range segs {
		next := a.tile.neighbor(a.exit)
		succ := candidates(a, next, a.exit.opposite(), tol)
		if len(succ) == 0 {
			// The road may clip the corner of the next tile without a reported segment there
			// and continue in a tile diagonally adjacent to a.
			for _, e := range a.exit.perpendicular() {
				succ = append(succ, candidates(a, next.neighbor(e), e.opposite(), CornerGap+tol)...)
			}
			slices.SortFunc(succ, func(x, y *segment) int { return cmp.Compare(x.key, y.key) })
		}
		a.successors = succ
		for _, b := range succ {
			b.preds = append(b.preds, a)
		}
		if len(succ) > 1 {
			st.Forks++
		}
	}
	for _, s := range segs {
		if len(s.preds) > 1 {
			st.Merges++
		}
	}
}

// split cuts a chain into parts of at most maxLen metres, starting at the downstream end, so
// that the downstream part (and with it the message ID, see chainID) stays the same while a jam
// grows at its tail. A single segment longer than maxLen is never cut.
func split(c chain, maxLen float64) []chain {
	if maxLen <= 0 || c.length() <= maxLen {
		return []chain{c}
	}
	var parts []chain
	end := len(c)
	l := 0.0
	for i := len(c) - 1; i >= 0; i-- {
		if i < end-1 && l+c[i].length > maxLen {
			parts = append(parts, c[i+1:end])
			end, l = i+1, 0
		}
		l += c[i].length
	}
	parts = append(parts, c[:end])
	slices.Reverse(parts)
	return parts
}
