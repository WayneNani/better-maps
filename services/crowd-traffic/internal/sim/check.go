package sim

import (
	"fmt"
	"sort"
	"strings"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/export"
)

// CheckResult compares a JSON export with the scenario.
type CheckResult struct {
	Published map[string]int // published segments per road
	Congested map[string]int // congested segments per road
	JamTotal  int            // segments completely inside a jam
	JamFound  int            // of those: published and congested
	Problems  []string
}

// OK reports whether the export matches the scenario: congestion only inside jams, at least
// one jam segment detected (if the scenario has jams), nothing published below k and nothing
// on roads with fewer than k vehicles per window.
func (r CheckResult) OK() bool { return len(r.Problems) == 0 }

func (r CheckResult) String() string {
	var b strings.Builder
	roads := make([]string, 0, len(r.Published))
	for name := range r.Published {
		roads = append(roads, name)
	}
	sort.Strings(roads)
	for _, name := range roads {
		fmt.Fprintf(&b, "  %-28s published %3d  congested %3d\n", name, r.Published[name], r.Congested[name])
	}
	fmt.Fprintf(&b, "  jam segments detected: %d of %d\n", r.JamFound, r.JamTotal)
	for _, p := range r.Problems {
		fmt.Fprintf(&b, "  PROBLEM: %s\n", p)
	}
	return b.String()
}

// Check evaluates a JSON export against the scenario.
func Check(doc export.Document, sc Scenario) CheckResult {
	res := CheckResult{Published: map[string]int{}, Congested: map[string]int{}}
	roadOf := map[string]Road{}
	jamInside, jamTouch := map[string]bool{}, map[string]bool{}
	for _, r := range sc.Roads {
		res.Published[r.Name] = 0
		in, touch, all := r.SegmentZones()
		for k := range all {
			roadOf[export.MessageID(k)] = r
		}
		for k := range in {
			jamInside[export.MessageID(k)] = true
		}
		for k := range touch {
			jamTouch[export.MessageID(k)] = true
		}
	}
	res.JamTotal = len(jamInside)
	for _, s := range doc.Segments {
		r, ok := roadOf[s.ID]
		switch {
		case !ok:
			res.Problems = append(res.Problems, "segment on no simulated road: "+s.ID)
			continue
		case s.Reports < doc.K:
			res.Problems = append(res.Problems, fmt.Sprintf("%s published with %d < k reports", s.ID, s.Reports))
		case r.VehiclesPerWindow*sc.Windows < doc.K:
			res.Problems = append(res.Problems, fmt.Sprintf("%s (%s) published although the road has fewer than k vehicles", s.ID, r.Name))
		}
		res.Published[r.Name]++
		if s.Congested {
			res.Congested[r.Name]++
			if !jamTouch[s.ID] {
				res.Problems = append(res.Problems, fmt.Sprintf("congestion outside the jam: %s %s %.0f km/h", s.ID, s.State, s.SpeedKmh))
			}
			if jamInside[s.ID] {
				res.JamFound++
			}
		}
	}
	if res.JamTotal > 0 && res.JamFound == 0 {
		res.Problems = append(res.Problems, "no jam segment detected")
	}
	return res
}
