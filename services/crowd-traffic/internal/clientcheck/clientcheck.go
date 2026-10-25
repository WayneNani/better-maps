// Package clientcheck is a copy of services/traff-server/internal/clientcheck (keep in sync).
// It re-implements the acceptance rules of the CoMaps TraFF client
// (libs/traffxml/traff_model_xml.cpp, branch "traffic") in Go. Tests use it to verify that the
// server produces responses the app can actually consume.
//
// Rules mirrored from ParseResponse / FeedFromXml / MessageFromXml:
//   - the root element must be <response> with a known status attribute;
//   - every <message> needs id, receive_time, update_time and expiration_time (ISO 8601);
//   - messages that are not cancellations need a <location> with at least two of from/to/at,
//     each with valid "lat lon" coordinates, and an <events> element with at least one event
//     whose class is known and whose type starts with "<class>_" and is known to the client.
//
// Events the client does not know are skipped silently by the client; they are reported in
// Result.SkippedEvents.
package clientcheck

import (
	"encoding/xml"
	"fmt"
	"regexp"
	"strconv"
	"strings"
)

var statuses = map[string]bool{
	"OK": true, "INVALID": true, "SUBSCRIPTION_REJECTED": true, "NOT_COVERED": true, "PARTIALLY_COVERED": true,
	"SUBSCRIPTION_UNKNOWN": true, "PUSH_REJECTED": true, "INTERNAL_ERROR": true,
}

var eventClasses = map[string]bool{
	"INVALID": true, "ACTIVITY": true, "AUTHORITY": true, "CARPOOL": true, "CONGESTION": true, "CONSTRUCTION": true,
	"DELAY": true, "ENVIRONMENT": true, "EQUIPMENT_STATUS": true, "HAZARD": true, "INCIDENT": true, "RESTRICTION": true,
	"SECURITY": true, "TRANSPORT": true, "WEATHER": true,
}

// KnownEventTypes are the event types in kEventTypeMap of the client.
var KnownEventTypes = map[string]bool{
	"INVALID":                                     true,
	"CONGESTION_CLEARED":                          true,
	"CONGESTION_FORECAST_WITHDRAWN":               true,
	"CONGESTION_HEAVY_TRAFFIC":                    true,
	"CONGESTION_LONG_QUEUE":                       true,
	"CONGESTION_NONE":                             true,
	"CONGESTION_NORMAL_TRAFFIC":                   true,
	"CONGESTION_QUEUE":                            true,
	"CONGESTION_QUEUE_LIKELY":                     true,
	"CONGESTION_SLOW_TRAFFIC":                     true,
	"CONGESTION_STATIONARY_TRAFFIC":               true,
	"CONGESTION_STATIONARY_TRAFFIC_LIKELY":        true,
	"CONGESTION_TRAFFIC_BUILDING_UP":              true,
	"CONGESTION_TRAFFIC_CONGESTION":               true,
	"CONGESTION_TRAFFIC_EASING":                   true,
	"CONGESTION_TRAFFIC_FLOWING_FREELY":           true,
	"CONGESTION_TRAFFIC_HEAVIER_THAN_NORMAL":      true,
	"CONGESTION_TRAFFIC_LIGHTER_THAN_NORMAL":      true,
	"CONGESTION_TRAFFIC_MUCH_HEAVIER_THAN_NORMAL": true,
	"CONGESTION_TRAFFIC_PROBLEM":                  true,
	"DELAY_CLEARANCE":                             true,
	"DELAY_DELAY":                                 true,
	"DELAY_DELAY_POSSIBLE":                        true,
	"DELAY_FORECAST_WITHDRAWN":                    true,
	"DELAY_LONG_DELAY":                            true,
	"DELAY_SEVERAL_HOURS":                         true,
	"DELAY_UNCERTAIN_DURATION":                    true,
	"DELAY_VERY_LONG_DELAY":                       true,
	"RESTRICTION_BLOCKED":                         true,
	"RESTRICTION_BLOCKED_AHEAD":                   true,
	"RESTRICTION_CARRIAGEWAY_BLOCKED":             true,
	"RESTRICTION_CARRIAGEWAY_CLOSED":              true,
	"RESTRICTION_CLOSED":                          true,
	"RESTRICTION_CLOSED_AHEAD":                    true,
	"RESTRICTION_ENTRY_BLOCKED":                   true,
	"RESTRICTION_ENTRY_REOPENED":                  true,
	"RESTRICTION_EXIT_BLOCKED":                    true,
	"RESTRICTION_EXIT_REOPENED":                   true,
	"RESTRICTION_OPEN":                            true,
	"RESTRICTION_RAMP_BLOCKED":                    true,
	"RESTRICTION_RAMP_CLOSED":                     true,
	"RESTRICTION_RAMP_REOPENED":                   true,
	"RESTRICTION_REOPENED":                        true,
	"RESTRICTION_SPEED_LIMIT":                     true,
	"RESTRICTION_SPEED_LIMIT_LIFTED":              true,
}

var (
	reISO    = regexp.MustCompile(`([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2}(\.[0-9]*)?)(Z|(([+-][0-9]{2})(:?([0-9]{2}))?))?`)
	reLatLon = regexp.MustCompile(`([+-]?[0-9]*\.?[0-9]*)\s+([+-]?[0-9]*\.?[0-9]*)`)
)

type node struct {
	XMLName xml.Name
	Attrs   []xml.Attr `xml:",any,attr"`
	Nodes   []node     `xml:",any"`
	Text    string     `xml:",chardata"`
}

func (n *node) attr(name string) (string, bool) {
	for _, a := range n.Attrs {
		if a.Name.Local == name {
			return a.Value, true
		}
	}
	return "", false
}

func (n *node) child(name string) *node {
	for i := range n.Nodes {
		if n.Nodes[i].XMLName.Local == name {
			return &n.Nodes[i]
		}
	}
	return nil
}

func (n *node) children(name string) []*node {
	var res []*node
	for i := range n.Nodes {
		if n.Nodes[i].XMLName.Local == name {
			res = append(res, &n.Nodes[i])
		}
	}
	return res
}

// Message describes how the client sees a message.
type Message struct {
	ID           string
	Cancellation bool
	Events       []string // event types the client understands
}

// Result is the client's view of a response.
type Result struct {
	Status         string
	SubscriptionID string
	Timeout        int
	HasFeed        bool
	Accepted       []Message
	Rejected       map[string]string // message ID (or index) -> reason
	SkippedEvents  map[string]int    // event types the client ignores
}

// ParseResponse parses a response document like the client does. An error means the client
// would treat the response as invalid (ResponseStatus::Invalid).
func ParseResponse(data []byte) (*Result, error) {
	var root node
	if err := xml.Unmarshal(data, &root); err != nil {
		return nil, fmt.Errorf("not well-formed XML: %w", err)
	}
	if root.XMLName.Local != "response" {
		return nil, fmt.Errorf("root element is %q, not response", root.XMLName.Local)
	}
	status, ok := root.attr("status")
	if !ok || !statuses[status] {
		return nil, fmt.Errorf("missing or unknown status %q", status)
	}
	res := &Result{Status: status, Rejected: map[string]string{}, SkippedEvents: map[string]int{}}
	res.SubscriptionID, _ = root.attr("subscription_id")
	if t, ok := root.attr("timeout"); ok {
		v, err := strconv.Atoi(t)
		if err != nil {
			return nil, fmt.Errorf("timeout %q is not an integer", t)
		}
		res.Timeout = v
	}
	if feed := root.child("feed"); feed != nil {
		res.HasFeed = true
		for i, m := range feed.children("message") {
			msg, reason := checkMessage(m, res.SkippedEvents)
			if reason != "" {
				key := msg.ID
				if key == "" {
					key = fmt.Sprintf("#%d", i)
				}
				res.Rejected[key] = reason
				continue
			}
			res.Accepted = append(res.Accepted, msg)
		}
	}
	return res, nil
}

func checkTime(m *node, name string, required bool) string {
	v, ok := m.attr(name)
	if !ok {
		if required {
			return "missing " + name
		}
		return ""
	}
	if !reISO.MatchString(v) && required {
		return "invalid " + name
	}
	return ""
}

func validPoint(n *node) bool {
	if n == nil {
		return false
	}
	m := reLatLon.FindStringSubmatch(n.Text)
	if m == nil || m[1] == "" || m[2] == "" {
		return false
	}
	_, err1 := strconv.ParseFloat(m[1], 64)
	_, err2 := strconv.ParseFloat(m[2], 64)
	return err1 == nil && err2 == nil
}

func checkMessage(m *node, skipped map[string]int) (Message, string) {
	var msg Message
	id, ok := m.attr("id")
	if !ok {
		return msg, "no id"
	}
	msg.ID = id
	for _, t := range []string{"receive_time", "update_time", "expiration_time"} {
		if r := checkTime(m, t, true); r != "" {
			return msg, r
		}
	}
	if c, ok := m.attr("cancellation"); ok {
		// pugixml as_bool(): true if the first character is one of 1, t, T, y, Y.
		msg.Cancellation = c != "" && strings.ContainsRune("1tTyY", rune(c[0]))
	}
	if msg.Cancellation {
		return msg, ""
	}
	loc := m.child("location")
	if loc == nil {
		return msg, "no location"
	}
	n := 0
	for _, p := range []string{"from", "to", "at"} {
		if validPoint(loc.child(p)) {
			n++
		}
	}
	if n < 2 {
		return msg, fmt.Sprintf("only %d of from/to/at", n)
	}
	events := m.child("events")
	if events == nil {
		return msg, "no events"
	}
	for _, e := range events.children("event") {
		class, ok1 := e.attr("class")
		typ, ok2 := e.attr("type")
		if !ok1 || !ok2 || !eventClasses[class] || !strings.HasPrefix(typ, class+"_") || !KnownEventTypes[typ] {
			skipped[typ]++
			continue
		}
		msg.Events = append(msg.Events, typ)
	}
	if len(msg.Events) == 0 {
		return msg, "no event known to the client"
	}
	return msg, ""
}
