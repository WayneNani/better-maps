package datex2

import (
	"fmt"
	"math"
	"regexp"
	"sort"
	"strings"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

// IDPrefix is prepended to all message IDs: "de.datex2.<feed>:<situationRecordId>".
const IDPrefix = "de.datex2."

// Skip reasons, reported in Stats as "skipped_<reason>".
const (
	SkipNoID          = "no_id"
	SkipUnsupported   = "unsupported_type"
	SkipCancelled     = "cancelled"
	SkipSuspended     = "suspended"
	SkipEnded         = "ended"
	SkipFuture        = "beyond_horizon"
	SkipAlertCOnly    = "alertc_only"
	SkipLinearRefOnly = "linear_reference_only"
	SkipNoLocation    = "no_location"
	SkipArea          = "area_location"
)

// Stats counts what happened during a mapping run. Keys: "situations", "records", "messages"
// and "skipped_<reason>".
type Stats map[string]int

// Options control the mapping.
type Options struct {
	// Feed is the feed name used in message IDs. It keeps IDs of different feeds apart.
	Feed string
	// Now is the reference time for validity checks.
	Now time.Time
	// Horizon skips records that start later than Now+Horizon (0 = no limit).
	Horizon time.Duration
}

// MessageID returns the TraFF message ID for a situation record.
func MessageID(feed, recordID string) string {
	return IDPrefix + feed + ":" + strings.TrimSpace(recordID)
}

var (
	reRoadNumber = regexp.MustCompile(`^([A-Za-z]{1,3})\s*-?\s*(\d{1,4}[A-Za-z]?)$`)
	// German location descriptors: "A8 Stuttgart Richtung Karlsruhe zwischen ...".
	// The road may follow a district: "Kreis Tübingen, K6901 zwischen ...".
	reDescriptorRoad = regexp.MustCompile(`^(?:[^,]*,\s*)?(A|B|L|K|St|S)\s?(\d{1,4}[a-zA-Z]?)\b`)
	// The rest after the road: "Stuttgart Richtung Karlsruhe zwischen ...".
	reDirection = regexp.MustCompile(`^([^,]+?)\s+Richtung\s+(.+?)(?:\s+(?:zwischen|Ausfahrt|Einfahrt|Anschlussstelle|Höhe|in Höhe|bei|am|an der|ab|nach)\b|,|\(|$)`)
)

// RoadRef converts a road number into the German signage form used in OSM ("A5" -> "A 5").
func RoadRef(number string) string {
	number = strings.TrimSpace(number)
	if m := reRoadNumber.FindStringSubmatch(number); m != nil {
		return strings.ToUpper(m[1]) + " " + m[2]
	}
	return number
}

// RoadClassFor derives the TraFF road class from a German road reference (A = Autobahn,
// B = Bundesstraße, L/S/St = Landes-/Staatsstraße, K = Kreisstraße) or, failing that, from the
// OpenLR functional road class.
func RoadClassFor(ref, frc string) traff.RoadClass {
	if m := reRoadNumber.FindStringSubmatch(ref); m != nil {
		switch strings.ToUpper(m[1]) {
		case "A", "BAB":
			return traff.RoadClassMotorway
		case "B":
			return traff.RoadClassPrimary
		case "L", "S", "ST":
			return traff.RoadClassSecondary
		case "K":
			return traff.RoadClassTertiary
		}
	}
	switch frc {
	case "FRC0":
		return traff.RoadClassMotorway
	case "FRC1":
		return traff.RoadClassTrunk
	case "FRC2":
		return traff.RoadClassPrimary
	case "FRC3":
		return traff.RoadClassSecondary
	case "FRC4":
		return traff.RoadClassTertiary
	case "FRC5", "FRC6", "FRC7":
		return traff.RoadClassOther
	}
	return ""
}

// descriptor returns the location descriptor comment of a record (or its first comment).
func (r *Record) descriptor() string {
	for _, c := range r.Comments {
		if c.Type == "locationDescriptor" {
			return c.Text
		}
	}
	return ""
}

func round6(v float64) float64 { return math.Round(v*1e6) / 1e6 }

func newPoint(p traff.LatLon) *traff.Point { return traff.NewPoint(round6(p.Lat), round6(p.Lon)) }

// location builds the TraFF location of a record. ok is false if the record has no coordinates;
// reason tells why.
func (r *Record) location(country string) (loc *traff.Location, reason string) {
	l := &r.Location
	if l.Area {
		// Areas (e.g. a weather warning for a whole state) have no road-based location.
		return nil, SkipArea
	}
	if len(l.Points) == 0 {
		switch {
		case l.HasAlertC:
			return nil, SkipAlertCOnly
		case l.HasLinearReference:
			return nil, SkipLinearRefOnly
		}
		return nil, SkipNoLocation
	}
	loc = &traff.Location{Country: country, Directionality: traff.OneDirection}
	if l.BothDirections {
		loc.Directionality = traff.BothDirections
	}
	number := l.RoadNumber
	desc := strings.TrimSpace(r.descriptor())
	if m := reDescriptorRoad.FindStringSubmatch(desc); m != nil {
		if number == "" {
			number = m[1] + m[2]
		}
		if d := reDirection.FindStringSubmatch(strings.TrimSpace(desc[len(m[0]):])); d != nil {
			loc.Origin = strings.TrimSpace(d[1])
			loc.Destination = strings.TrimSpace(d[2])
		}
	}
	if number != "" {
		loc.RoadRef = RoadRef(number)
	} else {
		// Street names are only used without road number; with a number, the MDM profile puts
		// section names ("Stuttgart-Großbottwar") into roadName.
		loc.RoadName = strings.TrimSpace(l.RoadName)
	}
	loc.RoadClass = RoadClassFor(loc.RoadRef, l.FRC)
	pts := l.Points
	if len(pts) == 1 {
		loc.At = newPoint(pts[0])
	} else {
		loc.From = newPoint(pts[0])
		loc.To = newPoint(pts[len(pts)-1])
		if len(pts) >= 3 {
			loc.Via = newPoint(pts[len(pts)/2])
		}
	}
	return loc, ""
}

// window determines the validity of a record at time now. A non-empty reason means that the
// record is not (or no longer) valid.
func (r *Record) window(now time.Time) (start, end time.Time, reason string) {
	if r.Cancelled {
		return start, end, SkipCancelled
	}
	if r.ValidityStatus == "suspended" {
		return start, end, SkipSuspended
	}
	start, end = r.OverallStart, r.OverallEnd
	if len(r.Periods) > 0 {
		// Use the current or next valid period.
		periods := append([]Period(nil), r.Periods...)
		sort.SliceStable(periods, func(i, j int) bool { return periods[i].Start.Before(periods[j].Start) })
		found := false
		for _, p := range periods {
			if !p.End.IsZero() && !p.End.After(now) {
				continue
			}
			if !p.Start.IsZero() && p.Start.After(start) {
				start = p.Start
			}
			if !p.End.IsZero() && (end.IsZero() || p.End.Before(end)) {
				end = p.End
			}
			found = true
			break
		}
		if !found {
			return start, end, SkipEnded
		}
	}
	if !end.IsZero() && !end.After(now) {
		return start, end, SkipEnded
	}
	return start, end, ""
}

// group collects the records of one situation that share a location.
type group struct {
	id         string
	loc        *traff.Location
	events     []traff.Event
	start, end time.Time
	openEnd    bool
}

func (g *group) addEvents(evs []traff.Event) {
	for _, ev := range evs {
		dup := false
		for _, have := range g.events {
			if have.Type == ev.Type {
				dup = true
				break
			}
		}
		if !dup {
			g.events = append(g.events, ev)
		}
	}
}

func locationKey(l *traff.Location) string {
	var b strings.Builder
	fmt.Fprintf(&b, "%s|%s|%s|%s|%s|%s", l.RoadRef, l.RoadName, l.Directionality, l.Origin, l.Destination, l.RoadClass)
	for _, p := range []*traff.Point{l.From, l.At, l.Via, l.To} {
		if p != nil {
			b.WriteString("|" + p.Coord.String())
		} else {
			b.WriteString("|-")
		}
	}
	return b.String()
}

var urgencies = map[string]string{"urgent": "URGENT", "extremelyUrgent": "X_URGENT"}

// Map converts a publication into TraFF messages. Records of one situation that share a
// location become one message (e.g. a lane closure and the roadworks causing it); its ID is
// derived from the first of these records. Timestamps (receive/update/expiration) are left zero;
// the store sets them.
func Map(pub *Publication, opt Options) ([]traff.Message, Stats) {
	stats := Stats{}
	country := strings.ToUpper(strings.TrimSpace(pub.Country))
	if len(country) != 2 {
		country = "DE"
	}
	msgs := []traff.Message{}
	seen := map[string]bool{}
	for si := range pub.Situations {
		sit := &pub.Situations[si]
		stats["situations"]++
		var groups []*group
		byKey := map[string]*group{}
		for ri := range sit.Records {
			r := &sit.Records[ri]
			stats["records"]++
			reason := ""
			var evs []traff.Event
			var loc *traff.Location
			var start, end time.Time
			switch {
			case r.ID == "":
				reason = SkipNoID
			case !supportedType(r.Type):
				reason = SkipUnsupported
			}
			if reason == "" {
				var ok bool
				if evs, ok = recordEventList(r); !ok {
					reason = SkipUnsupported
				}
			}
			if reason == "" {
				start, end, reason = r.window(opt.Now)
			}
			if reason == "" && opt.Horizon > 0 && start.After(opt.Now.Add(opt.Horizon)) {
				reason = SkipFuture
			}
			if reason == "" {
				loc, reason = r.location(country)
			}
			if reason != "" {
				stats["skipped_"+reason]++
				continue
			}
			key := locationKey(loc)
			g := byKey[key]
			if g == nil {
				g = &group{id: MessageID(opt.Feed, r.ID), loc: loc, start: start, end: end, openEnd: end.IsZero()}
				byKey[key] = g
				groups = append(groups, g)
			} else {
				if !start.IsZero() && (g.start.IsZero() || start.Before(g.start)) {
					g.start = start
				}
				if end.IsZero() {
					g.openEnd = true
				} else if end.After(g.end) {
					g.end = end
				}
			}
			g.addEvents(evs)
		}
		for _, g := range groups {
			if seen[g.id] {
				stats["skipped_duplicate_id"]++
				continue
			}
			seen[g.id] = true
			m := traff.Message{ID: g.id, Location: g.loc, Events: g.events, Urgency: urgencies[sit.Urgency]}
			if !g.start.IsZero() {
				m.StartTime = traff.TimePtr(g.start)
			}
			if !g.openEnd && !g.end.IsZero() {
				m.EndTime = traff.TimePtr(g.end)
			}
			msgs = append(msgs, m)
		}
	}
	stats["messages"] = len(msgs)
	return msgs, stats
}
