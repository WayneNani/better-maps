// Package traff implements the TraFF data model (messages, locations, events) and the XML
// structures of the TraFF 0.8 subscription protocol (request, response, filter_list).
//
// The XML layout follows the TraFF specification (https://gitlab.com/traffxml/traff) and is
// checked against what the CoMaps client in libs/traffxml (branch "traffic") parses.
package traff

import (
	"encoding/xml"
	"fmt"
	"strconv"
	"strings"
	"time"
)

// TimeLayout is the ISO 8601 layout used for all timestamps. It is understood by the CoMaps
// client (libs/traffic/iso_time.cpp).
const TimeLayout = "2006-01-02T15:04:05Z07:00"

// Time is a timestamp that is serialized as an ISO 8601 XML attribute in UTC.
type Time struct{ time.Time }

// NewTime wraps t, truncated to whole seconds and converted to UTC.
func NewTime(t time.Time) Time { return Time{t.UTC().Truncate(time.Second)} }

// TimePtr returns a pointer to NewTime(t).
func TimePtr(t time.Time) *Time {
	tt := NewTime(t)
	return &tt
}

// MarshalXMLAttr implements xml.MarshalerAttr.
func (t Time) MarshalXMLAttr(name xml.Name) (xml.Attr, error) {
	return xml.Attr{Name: name, Value: t.UTC().Format(TimeLayout)}, nil
}

// UnmarshalXMLAttr implements xml.UnmarshalerAttr.
func (t *Time) UnmarshalXMLAttr(attr xml.Attr) error {
	for _, layout := range []string{time.RFC3339Nano, "2006-01-02T15:04:05Z0700", "2006-01-02T15:04:05"} {
		if v, err := time.Parse(layout, attr.Value); err == nil {
			t.Time = v.UTC()
			return nil
		}
	}
	return fmt.Errorf("traff: invalid time %q in attribute %s", attr.Value, attr.Name.Local)
}

// RoadClass is a TraFF road class. The zero value means "not specified".
type RoadClass string

// Road classes, highest first.
const (
	RoadClassMotorway  RoadClass = "MOTORWAY"
	RoadClassTrunk     RoadClass = "TRUNK"
	RoadClassPrimary   RoadClass = "PRIMARY"
	RoadClassSecondary RoadClass = "SECONDARY"
	RoadClassTertiary  RoadClass = "TERTIARY"
	RoadClassOther     RoadClass = "OTHER"
)

// Rank returns a number that is higher for more important road classes, or -1 if the road class
// is unknown or empty.
func (rc RoadClass) Rank() int {
	switch rc {
	case RoadClassMotorway:
		return 5
	case RoadClassTrunk:
		return 4
	case RoadClassPrimary:
		return 3
	case RoadClassSecondary:
		return 2
	case RoadClassTertiary:
		return 1
	case RoadClassOther:
		return 0
	}
	return -1
}

// ParseRoadClass parses a road class string (case-insensitive).
func ParseRoadClass(s string) (RoadClass, error) {
	rc := RoadClass(strings.ToUpper(strings.TrimSpace(s)))
	if rc.Rank() < 0 {
		return "", fmt.Errorf("traff: unknown road class %q", s)
	}
	return rc, nil
}

// Directionality values.
const (
	OneDirection   = "ONE_DIRECTION"
	BothDirections = "BOTH_DIRECTIONS"
)

// Ramps values.
const (
	RampsNone  = "NONE"
	RampsAll   = "ALL_RAMPS"
	RampsEntry = "ENTRY_RAMP"
	RampsExit  = "EXIT_RAMP"
)

// LatLon is a WGS84 coordinate pair.
type LatLon struct {
	Lat, Lon float64
}

// Valid reports whether the coordinates are within the WGS84 value range.
func (p LatLon) Valid() bool {
	return p.Lat >= -90 && p.Lat <= 90 && p.Lon >= -180 && p.Lon <= 180
}

// String formats the point the way TraFF expects it ("+52.334801 +12.814650").
func (p LatLon) String() string { return fmt.Sprintf("%+.6f %+.6f", p.Lat, p.Lon) }

// Point is a reference point of a location (from, to, at, via, not_via).
type Point struct {
	Coord        LatLon
	Distance     *float64
	JunctionName string
	JunctionRef  string
}

// NewPoint creates a point with the given coordinates.
func NewPoint(lat, lon float64) *Point { return &Point{Coord: LatLon{lat, lon}} }

type xmlPoint struct {
	Distance     *float64 `xml:"distance,attr,omitempty"`
	JunctionName string   `xml:"junction_name,attr,omitempty"`
	JunctionRef  string   `xml:"junction_ref,attr,omitempty"`
	Text         string   `xml:",chardata"`
}

// MarshalXML implements xml.Marshaler.
func (p Point) MarshalXML(e *xml.Encoder, start xml.StartElement) error {
	return e.EncodeElement(xmlPoint{p.Distance, p.JunctionName, p.JunctionRef, p.Coord.String()}, start)
}

// UnmarshalXML implements xml.Unmarshaler.
func (p *Point) UnmarshalXML(d *xml.Decoder, start xml.StartElement) error {
	var x xmlPoint
	if err := d.DecodeElement(&x, &start); err != nil {
		return err
	}
	c, err := ParseCoordinates(x.Text)
	if err != nil {
		return err
	}
	*p = Point{Coord: c, Distance: x.Distance, JunctionName: x.JunctionName, JunctionRef: x.JunctionRef}
	return nil
}

// ParseCoordinates parses the character data of a point element ("lat lon").
func ParseCoordinates(s string) (LatLon, error) {
	var p LatLon
	f := strings.Fields(s)
	if len(f) != 2 {
		return p, fmt.Errorf("traff: invalid coordinates %q", s)
	}
	var err1, err2 error
	p.Lat, err1 = strconv.ParseFloat(f[0], 64)
	p.Lon, err2 = strconv.ParseFloat(f[1], 64)
	if err1 != nil || err2 != nil {
		return p, fmt.Errorf("traff: invalid coordinates %q", s)
	}
	if !p.Valid() {
		return p, fmt.Errorf("traff: coordinates out of range %q", s)
	}
	return p, nil
}

// Location is a TraFF location.
type Location struct {
	Country        string    `xml:"country,attr,omitempty"`
	Destination    string    `xml:"destination,attr,omitempty"`
	Direction      string    `xml:"direction,attr,omitempty"`
	Directionality string    `xml:"directionality,attr,omitempty"`
	Fuzziness      string    `xml:"fuzziness,attr,omitempty"`
	Origin         string    `xml:"origin,attr,omitempty"`
	Ramps          string    `xml:"ramps,attr,omitempty"`
	RoadClass      RoadClass `xml:"road_class,attr,omitempty"`
	RoadName       string    `xml:"road_name,attr,omitempty"`
	RoadRef        string    `xml:"road_ref,attr,omitempty"`
	Territory      string    `xml:"territory,attr,omitempty"`
	Town           string    `xml:"town,attr,omitempty"`
	From           *Point    `xml:"from,omitempty"`
	At             *Point    `xml:"at,omitempty"`
	Via            *Point    `xml:"via,omitempty"`
	NotVia         *Point    `xml:"not_via,omitempty"`
	To             *Point    `xml:"to,omitempty"`
}

// Points returns all reference points that are set.
func (l *Location) Points() []*Point {
	var res []*Point
	for _, p := range []*Point{l.From, l.At, l.Via, l.NotVia, l.To} {
		if p != nil {
			res = append(res, p)
		}
	}
	return res
}

// Event is a TraFF event. Only the quantifiers used by this server are modeled.
type Event struct {
	Class       string `xml:"class,attr"`
	Type        string `xml:"type,attr"`
	Length      *int   `xml:"length,attr,omitempty"`
	Probability *int   `xml:"probability,attr,omitempty"`
	QDimension  string `xml:"q_dimension,attr,omitempty"`
	QDuration   string `xml:"q_duration,attr,omitempty"`
	QInt        *int   `xml:"q_int,attr,omitempty"`
	QWeight     string `xml:"q_weight,attr,omitempty"`
	Speed       *int   `xml:"speed,attr,omitempty"`
}

// Replaces names a message that is replaced by the containing message.
type Replaces struct {
	ID string `xml:"id,attr"`
}

// Merge holds the list of replaced messages.
type Merge struct {
	Replaces []Replaces `xml:"replaces"`
}

// Message is a TraFF message.
type Message struct {
	XMLName        xml.Name  `xml:"message"`
	ID             string    `xml:"id,attr"`
	ReceiveTime    Time      `xml:"receive_time,attr"`
	UpdateTime     Time      `xml:"update_time,attr"`
	ExpirationTime Time      `xml:"expiration_time,attr"`
	StartTime      *Time     `xml:"start_time,attr,omitempty"`
	EndTime        *Time     `xml:"end_time,attr,omitempty"`
	Cancellation   bool      `xml:"cancellation,attr"`
	Forecast       bool      `xml:"forecast,attr"`
	Urgency        string    `xml:"urgency,attr,omitempty"`
	Merge          *Merge    `xml:"merge,omitempty"`
	Location       *Location `xml:"location,omitempty"`
	Events         []Event   `xml:"events>event"`
}

// EffectiveExpiration returns the latest of expiration, start and end time. This mirrors
// TraffMessage::GetEffectiveExpirationTime() in the CoMaps client.
func (m *Message) EffectiveExpiration() time.Time {
	t := m.ExpirationTime.Time
	if m.StartTime != nil && m.StartTime.After(t) {
		t = m.StartTime.Time
	}
	if m.EndTime != nil && m.EndTime.After(t) {
		t = m.EndTime.Time
	}
	return t
}

// BBox returns the bounding box enclosing all reference points of the message location. ok is
// false if the message has no location.
func (m *Message) BBox() (b BBox, ok bool) {
	if m.Location == nil {
		return BBox{}, false
	}
	for _, p := range m.Location.Points() {
		if !ok {
			b = BBox{MinLat: p.Coord.Lat, MinLon: p.Coord.Lon, MaxLat: p.Coord.Lat, MaxLon: p.Coord.Lon}
			ok = true
			continue
		}
		b = b.Extend(p.Coord)
	}
	return b, ok
}

// Clone returns a deep copy of the message.
func (m *Message) Clone() Message {
	c := *m
	if m.StartTime != nil {
		t := *m.StartTime
		c.StartTime = &t
	}
	if m.EndTime != nil {
		t := *m.EndTime
		c.EndTime = &t
	}
	if m.Merge != nil {
		mg := Merge{Replaces: append([]Replaces(nil), m.Merge.Replaces...)}
		c.Merge = &mg
	}
	if m.Location != nil {
		l := *m.Location
		for _, pp := range []**Point{&l.From, &l.At, &l.Via, &l.NotVia, &l.To} {
			if *pp != nil {
				p := **pp
				if p.Distance != nil {
					d := *p.Distance
					p.Distance = &d
				}
				*pp = &p
			}
		}
		c.Location = &l
	}
	if m.Events != nil {
		c.Events = make([]Event, len(m.Events))
		copy(c.Events, m.Events)
	}
	return c
}

// Feed is a list of messages.
type Feed struct {
	XMLName  xml.Name  `xml:"feed"`
	Messages []Message `xml:"message"`
}
