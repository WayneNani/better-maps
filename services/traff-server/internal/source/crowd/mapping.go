package crowd

import (
	"cmp"
	"math"
	"slices"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

// MapStats count what happened to the segments of an export.
type MapStats struct {
	Segments  int            // segments in the export
	Congested int            // congested segments used
	Skipped   map[string]int // segments not used, by reason
	Messages  int
	Merge     MergeStats
}

// Map turns the segments of an export into TraFF messages: invalid, free-flowing and expired
// segments are dropped, adjacent segments are joined (see buildChains) and every chain becomes
// one message (see chainMessage). The result is sorted by ID.
func Map(doc *Document, now time.Time, opt MergeOptions) ([]traff.Message, MapStats) {
	st := MapStats{Segments: len(doc.Segments), Skipped: map[string]int{}}
	seen := map[string]bool{}
	var segs []*segment
	for i := range doc.Segments {
		s, reason := parseSegment(&doc.Segments[i], now)
		if s != nil && seen[s.key] {
			s, reason = nil, skipInvalid
		}
		if s == nil {
			st.Skipped[reason]++
			continue
		}
		seen[s.key] = true
		segs = append(segs, s)
	}
	st.Congested = len(segs)
	slices.SortFunc(segs, func(a, b *segment) int { return cmp.Compare(a.key, b.key) })
	chains, ms := buildChains(segs, opt)
	st.Merge = ms
	msgs := make([]traff.Message, 0, len(chains))
	for _, c := range chains {
		msgs = append(msgs, chainMessage(c))
	}
	slices.SortFunc(msgs, func(a, b traff.Message) int { return cmp.Compare(a.ID, b.ID) })
	st.Messages = len(msgs)
	return msgs, st
}

// chainID returns the message ID of a chain. It is derived from the downstream segment: the
// head of a jam (the bottleneck) usually stays in place while the tail grows and shrinks, so
// the ID survives most changes of a jam.
func chainID(c chain) string { return IDPrefix + c[len(c)-1].key }

// chainMessage converts a chain into a TraFF message:
//   - from = entry point of the first segment, to = exit point of the last one, via = the
//     junction between two segments closest to the middle of the chain (for chains of two or
//     more segments), so that the client's decoder follows the road;
//   - road_class of the segments, directionality ONE_DIRECTION, no road reference (the crowd
//     data does not know it);
//   - one CONGESTION_* event: the state is the length-weighted mean of the severities (heavy
//     traffic 1, slow traffic 2, queue 3, stationary traffic 4; rounded half up), the speed the
//     length-weighted harmonic mean of the segment speeds, i.e. the speed that gives the same
//     travel time over the chain. The client applies the speed as maximum speed to the whole
//     location, so the minimum speed of the chain would overestimate the delay.
//
// Timestamps are owned by the store.
func chainMessage(c chain) traff.Message {
	first, last := c[0], c[len(c)-1]
	loc := &traff.Location{
		Directionality: traff.OneDirection,
		RoadClass:      last.class,
		From:           &traff.Point{Coord: first.from},
		To:             &traff.Point{Coord: last.to},
	}
	if len(c) > 1 {
		loc.Via = &traff.Point{Coord: c[viaIndex(c)].to}
	}
	sev, speed := aggregate(c)
	return traff.Message{
		ID:       chainID(c),
		Location: loc,
		Events:   []traff.Event{{Class: "CONGESTION", Type: "CONGESTION_" + stateNames[sev], Speed: &speed}},
	}
}

// viaIndex returns the index i of the segment whose exit point (the junction between c[i] and
// c[i+1]) is closest to half the length of the chain.
func viaIndex(c chain) int {
	half := c.length() / 2
	best, bestDiff, pos := 0, math.Inf(1), 0.0
	for i := 0; i < len(c)-1; i++ {
		pos += c[i].length
		if d := math.Abs(pos - half); d < bestDiff {
			best, bestDiff = i, d
		}
	}
	return best
}

// minWeight keeps degenerate segments (entry and exit at the same point) from vanishing in the
// weighted means.
const minWeight = 1.0

// minSpeed bounds the speed from below; a speed of 0 would make the client treat the road as
// impassable.
const minSpeed = 1.0

// aggregate returns the length-weighted severity (1–4) and harmonic mean speed (km/h, ≥ 1) of a
// chain.
func aggregate(c chain) (severity, speed int) {
	var total, sev, hours float64
	for _, s := range c {
		w := max(s.length, minWeight)
		total += w
		sev += w * float64(s.severity)
		hours += w / max(s.speed, minSpeed)
	}
	severity = min(4, max(1, int(math.Floor(sev/total+0.5))))
	speed = int(math.Max(minSpeed, math.Round(total/hours)))
	return severity, speed
}
