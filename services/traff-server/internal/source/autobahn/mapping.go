package autobahn

import (
	"errors"
	"fmt"
	"math"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode"

	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

// IDPrefix is prepended to all message IDs of this source: "de.autobahn.<service>:<identifier>".
const IDPrefix = "de.autobahn."

// Reasons for skipping an item.
var (
	ErrNoID       = errors.New("item has no identifier")
	ErrNoLocation = errors.New("item has no usable coordinates")
	ErrEnded      = errors.New("item validity has ended")
	ErrNoStart    = errors.New("future item without start time")
)

// Event classes and types used by the mapping. Types marked (*) are understood by the CoMaps
// client (libs/traffxml/traff_model.hpp) and influence routing; the others follow the TraFF event
// list and are ignored by the current client version.
const (
	classCongestion   = "CONGESTION"
	classConstruction = "CONSTRUCTION"
	classDelay        = "DELAY"
	classHazard       = "HAZARD"
	classIncident     = "INCIDENT"
	classRestriction  = "RESTRICTION"

	congestionStationary = "CONGESTION_STATIONARY_TRAFFIC" // (*)
	congestionQueue      = "CONGESTION_QUEUE"              // (*)
	congestionSlow       = "CONGESTION_SLOW_TRAFFIC"       // (*)
	congestionHeavy      = "CONGESTION_HEAVY_TRAFFIC"      // (*)
	congestionProblem    = "CONGESTION_TRAFFIC_PROBLEM"    // (*)
	delayDelay           = "DELAY_DELAY"                   // (*)
	restrictionClosed    = "RESTRICTION_CLOSED"            // (*)
	restrictionRamp      = "RESTRICTION_RAMP_CLOSED"       // (*)
	restrictionSpeed     = "RESTRICTION_SPEED_LIMIT"       // (*)
	restrictionLane      = "RESTRICTION_LANE_CLOSED"
	restrictionContra    = "RESTRICTION_CONTRAFLOW"
	restrictionWidth     = "RESTRICTION_TEMP_MAX_WIDTH"
	restrictionWeight    = "RESTRICTION_TEMP_MAX_WEIGHT"
	constructionRoadwork = "CONSTRUCTION_ROADWORKS"
	incidentAccident     = "INCIDENT_ACCIDENT"
	incidentRescue       = "INCIDENT_RESCUE"
	hazardPeople         = "HAZARD_PEOPLE_ON_ROAD"
	hazardObject         = "HAZARD_OBJECT_ON_ROAD"
	hazardWrongWay       = "HAZARD_WRONG_WAY_DRIVER"
	hazardAnimals        = "HAZARD_ANIMALS_ON_ROAD"
)

// congestionSeverity orders congestion types; the most severe one is used.
var congestionSeverity = map[string]int{
	congestionProblem: 1, congestionHeavy: 2, congestionSlow: 3, congestionQueue: 4, congestionStationary: 5,
}

var abnormalTraffic = map[string]string{
	"STATIONARY_TRAFFIC": congestionStationary,
	"QUEUING_TRAFFIC":    congestionQueue,
	"SLOW_TRAFFIC":       congestionSlow,
	"HEAVY_TRAFFIC":      congestionHeavy,
}

// Bullet points ("- Im Stillstand") of INRIX warnings and the events they map to.
var warningBullets = []struct {
	prefix string
	class  string
	typ    string
}{
	{"Im Stillstand", classCongestion, congestionStationary},
	{"Stau", classCongestion, congestionQueue},
	{"Stockender Verkehr", classCongestion, congestionQueue},
	{"Langsamer Verkehr", classCongestion, congestionSlow},
	{"Zähfließender Verkehr", classCongestion, congestionSlow},
	{"Dichter Verkehr", classCongestion, congestionHeavy},
	{"Unfall", classIncident, incidentAccident},
	{"Einsatzfahrzeuge", classIncident, incidentRescue},
	{"Personen auf der Fahrbahn", classHazard, hazardPeople},
	{"Gegenstände auf der Fahrbahn", classHazard, hazardObject},
	{"Gegenstand auf der Fahrbahn", classHazard, hazardObject},
	{"Tiere auf der Fahrbahn", classHazard, hazardAnimals},
	{"Falschfahrer", classHazard, hazardWrongWay},
	{"Fahrstreifen gesperrt", classRestriction, restrictionLane},
	{"Vollsperrung", classRestriction, restrictionClosed},
	{"Gesperrt", classRestriction, restrictionClosed},
}

var (
	reSpeed  = regexp.MustCompile(`Max\.\s*(\d{1,3})\s*km/h`)
	reWidth  = regexp.MustCompile(`Durchfahrts?breite:\s*(\d+(?:[.,]\d+)?)\s*m`)
	reRoadID = regexp.MustCompile(`^([A-Za-z]+)\s*(\d+)$`)
)

// RoadRef converts an API road ID ("A7", "A60 ") into the German signage form used in OSM
// ("A 7").
func RoadRef(road string) string {
	road = strings.TrimSpace(road)
	if m := reRoadID.FindStringSubmatch(road); m != nil {
		return strings.ToUpper(m[1]) + " " + m[2]
	}
	return road
}

// RoadClassFor returns the TraFF road class for an API road ID.
func RoadClassFor(road string) traff.RoadClass {
	road = strings.TrimSpace(road)
	if len(road) > 1 && (road[0] == 'A' || road[0] == 'a') && unicode.IsDigit(rune(road[1])) {
		return traff.RoadClassMotorway
	}
	return ""
}

// MessageID returns the TraFF message ID for an item.
func MessageID(service, identifier string) string {
	return IDPrefix + service + ":" + strings.TrimSpace(identifier)
}

// points returns the item geometry as a list of valid coordinates without consecutive duplicates.
func (it *Item) points() []traff.LatLon {
	var res []traff.LatLon
	for _, c := range it.Geometry.lonLat() {
		p := traff.LatLon{Lat: c[1], Lon: c[0]}
		if !p.Valid() || (p.Lat == 0 && p.Lon == 0) {
			continue
		}
		if n := len(res); n > 0 && res[n-1] == p {
			continue
		}
		res = append(res, p)
	}
	if len(res) == 0 && it.Coordinate != nil && it.Coordinate.Lat.Valid && it.Coordinate.Long.Valid {
		p := traff.LatLon{Lat: it.Coordinate.Lat.Value, Lon: it.Coordinate.Long.Value}
		if p.Valid() && !(p.Lat == 0 && p.Lon == 0) {
			res = append(res, p)
		}
	}
	return res
}

func round6(v float64) float64 { return math.Round(v*1e6) / 1e6 }

func newPoint(p traff.LatLon) *traff.Point { return traff.NewPoint(round6(p.Lat), round6(p.Lon)) }

// location builds the TraFF location of an item.
func (it *Item) location(road string) (*traff.Location, error) {
	pts := it.points()
	if len(pts) == 0 {
		return nil, ErrNoLocation
	}
	loc := &traff.Location{
		Country:        "DE",
		Directionality: traff.OneDirection,
		RoadClass:      RoadClassFor(road),
		RoadRef:        RoadRef(road),
	}
	if o, d, ok := strings.Cut(it.Subtitle, "->"); ok {
		loc.Origin = strings.TrimSpace(o)
		loc.Destination = strings.TrimSpace(d)
	}
	if len(pts) == 1 {
		loc.At = newPoint(pts[0])
	} else {
		loc.From = newPoint(pts[0])
		loc.To = newPoint(pts[len(pts)-1])
		if len(pts) >= 3 {
			loc.Via = newPoint(pts[len(pts)/2])
		}
	}
	return loc, nil
}

// rampsFor determines which ramps a CLOSURE_ENTRY_EXIT item affects from its description.
func rampsFor(it *Item) string {
	text := it.Subtitle + "\n" + strings.Join(it.Description, "\n")
	entry := strings.Contains(text, "Auffahrt")
	exit := strings.Contains(text, "Abfahrt")
	switch {
	case entry && !exit:
		return traff.RampsEntry
	case exit && !entry:
		return traff.RampsExit
	default:
		return traff.RampsAll
	}
}

// laneState interprets the lane symbols. Lanes right of the last "SEPARATE" belong to the
// direction of the item; "BORDER_RIGHT" separates the driving lanes from the hard shoulder.
// ARROW_UP marks a lane open in the direction of the item, ARROW_DOWN one open in the opposite
// direction.
type laneState struct {
	known       bool
	closed      int // closed driving lanes in the direction of the item
	open        int // open driving lanes in the direction of the item
	contraflow  bool
	allClosedUs bool
}

func lanes(imp *Impact) laneState {
	var ls laneState
	if imp == nil || len(imp.Symbols) == 0 {
		return ls
	}
	sym := imp.Symbols
	sep := -1
	for i, s := range sym {
		if s == "SEPARATE" {
			sep = i
		}
	}
	ours := sym[sep+1:]
	for _, s := range ours {
		if s == "BORDER_RIGHT" {
			break
		}
		switch s {
		case "CLOSED":
			ls.closed++
		case "ARROW_UP", "ARROW_DOWN", "ARROW_LEFT", "ARROW_RIGHT":
			ls.open++
		}
	}
	if sep >= 0 {
		for _, s := range sym[:sep] {
			if s == "ARROW_UP" {
				ls.contraflow = true
			}
		}
	}
	ls.known = ls.closed+ls.open > 0
	ls.allClosedUs = ls.known && ls.open == 0
	return ls
}

type eventSet struct{ events []traff.Event }

func (s *eventSet) add(class, typ string) *traff.Event {
	for i := range s.events {
		if s.events[i].Type == typ {
			return &s.events[i]
		}
	}
	s.events = append(s.events, traff.Event{Class: class, Type: typ})
	return &s.events[len(s.events)-1]
}

func (s *eventSet) has(typ string) bool {
	for _, e := range s.events {
		if e.Type == typ {
			return true
		}
	}
	return false
}

func intPtr(v int) *int { return &v }

// constructionEvents adds speed limit, width and lane information found in roadworks items.
func constructionEvents(it *Item, es *eventSet) {
	text := strings.Join(it.Description, "\n")
	if m := reSpeed.FindStringSubmatch(text); m != nil {
		if v, err := strconv.Atoi(m[1]); err == nil && v > 0 && v < 200 {
			es.add(classRestriction, restrictionSpeed).Speed = intPtr(v)
		}
	}
	if m := reWidth.FindStringSubmatch(text); m != nil {
		es.add(classRestriction, restrictionWidth).QDimension = strings.ReplaceAll(m[1], ",", ".")
	}
	ls := lanes(it.Impact)
	switch {
	case ls.allClosedUs && ls.contraflow:
		es.add(classRestriction, restrictionContra)
	case ls.allClosedUs:
		es.add(classRestriction, restrictionClosed)
	case ls.closed > 0:
		es.add(classRestriction, restrictionLane).QInt = intPtr(ls.closed)
	}
}

// warningEvents maps an INRIX warning to congestion, delay and incident events.
func warningEvents(it *Item, es *eventSet) {
	congestion := abnormalTraffic[it.AbnormalTrafficType]
	for i, line := range it.Description {
		line = strings.TrimSpace(line)
		// The headline ("Unfall, seit 25.10.2026, 15:04") names the kind of the warning.
		if i <= 2 && strings.HasPrefix(line, "Unfall") {
			es.add(classIncident, incidentAccident)
		}
		if !strings.HasPrefix(line, "- ") {
			continue
		}
		bullet := strings.TrimPrefix(line, "- ")
		for _, b := range warningBullets {
			if !strings.HasPrefix(bullet, b.prefix) {
				continue
			}
			if b.class == classCongestion {
				if congestionSeverity[b.typ] > congestionSeverity[congestion] {
					congestion = b.typ
				}
			} else {
				es.add(b.class, b.typ)
			}
			break
		}
	}
	if congestion != "" {
		ev := traff.Event{Class: classCongestion, Type: congestion}
		if v, ok := it.AverageSpeed.Int(); ok && v > 0 && v < 200 {
			ev.Speed = intPtr(v)
		}
		es.events = append([]traff.Event{ev}, es.events...)
	}
	if d, ok := it.DelayTimeValue.Int(); ok && d > 0 {
		es.add(classDelay, delayDelay).QDuration = fmt.Sprintf("%02d:%02d", d/60, d%60)
	}
	if len(es.events) == 0 {
		es.add(classCongestion, congestionProblem)
	}
}

// parseTimestamp parses the startTimestamp field.
func parseTimestamp(s string) (time.Time, bool) {
	s = strings.TrimSpace(s)
	if s == "" {
		return time.Time{}, false
	}
	for _, layout := range []string{time.RFC3339Nano, "2006-01-02T15:04:05.000Z0700", "2006-01-02T15:04:05Z0700"} {
		if t, err := time.Parse(layout, s); err == nil {
			return t, true
		}
	}
	return time.Time{}, false
}

// validity determines start and end time of an item at time now.
func (it *Item) validity(now time.Time, tz *time.Location) (start, end time.Time, err error) {
	windows, overallEnd := ParseSchedule(it.Description, tz)
	if len(windows) > 0 {
		w, ok := CurrentWindow(windows, now)
		if !ok {
			return start, end, ErrEnded
		}
		start, end = w.Start, w.End
	} else {
		start, _ = parseTimestamp(it.StartTimestamp)
		end = overallEnd
	}
	if start.IsZero() {
		start, _ = parseTimestamp(it.StartTimestamp)
	}
	if !end.IsZero() && !end.After(now) {
		return start, end, ErrEnded
	}
	if bool(it.Future) && start.IsZero() {
		return start, end, ErrNoStart
	}
	return start, end, nil
}

// MapItem converts an Autobahn API item into a TraFF message. road is the road ID the item was
// listed under, service the API service ("roadworks", "warning" or "closure"). Timestamps
// (receive/update/expiration) are left zero; the store sets them.
func MapItem(road, service string, it *Item, now time.Time, tz *time.Location) (traff.Message, error) {
	var msg traff.Message
	if strings.TrimSpace(it.Identifier) == "" {
		return msg, ErrNoID
	}
	loc, err := it.location(road)
	if err != nil {
		return msg, err
	}
	start, end, err := it.validity(now, tz)
	if err != nil {
		return msg, err
	}

	var es eventSet
	switch it.DisplayType {
	case "CLOSURE":
		es.add(classRestriction, restrictionClosed)
	case "CLOSURE_ENTRY_EXIT":
		loc.Ramps = rampsFor(it)
		es.add(classRestriction, restrictionRamp)
	case "ROADWORKS", "SHORT_TERM_ROADWORKS":
		es.add(classConstruction, constructionRoadwork)
		constructionEvents(it, &es)
	case "WEIGHT_LIMIT_35":
		es.add(classRestriction, restrictionWeight).QWeight = "3.5"
	case "WARNING":
		warningEvents(it, &es)
	default:
		switch service {
		case ServiceClosure:
			es.add(classRestriction, restrictionClosed)
		case ServiceRoadworks:
			es.add(classConstruction, constructionRoadwork)
			constructionEvents(it, &es)
		default:
			warningEvents(it, &es)
		}
	}
	if bool(it.IsBlocked) && !es.has(restrictionClosed) && !es.has(restrictionRamp) {
		es.add(classRestriction, restrictionClosed)
	}

	msg = traff.Message{
		ID:       MessageID(service, it.Identifier),
		Location: loc,
		Events:   es.events,
		Forecast: false,
	}
	if !start.IsZero() {
		msg.StartTime = traff.TimePtr(start)
	}
	if !end.IsZero() {
		msg.EndTime = traff.TimePtr(end)
	}
	return msg, nil
}
