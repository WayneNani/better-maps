// Package export renders publications of the aggregator for consumers: a TraFF 0.8 feed (for the
// crowd source of services/traff-server) and a JSON document with all published segments.
//
// Both formats are stable interfaces (version 1); see README.md.
package export

import (
	"encoding/json"
	"encoding/xml"
	"fmt"
	"math"
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/aggregate"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/protocol"
)

// IDPrefix is the source prefix of all TraFF message IDs of this service.
const IDPrefix = "org.bettermaps.crowd:"

// MessageID returns the stable TraFF message ID of a segment. Consecutive windows of the same
// segment update the same message.
func MessageID(k protocol.Key) string { return IDPrefix + k.String() }

const timeLayout = "2006-01-02T15:04:05Z07:00"

type traffTime time.Time

func (t traffTime) MarshalXMLAttr(name xml.Name) (xml.Attr, error) {
	return xml.Attr{Name: name, Value: time.Time(t).UTC().Truncate(time.Second).Format(timeLayout)}, nil
}

type point struct {
	Text string `xml:",chardata"`
}

func newPoint(p aggregate.LatLon) *point { return &point{fmt.Sprintf("%+.6f %+.6f", p.Lat, p.Lon)} }

type location struct {
	Directionality string `xml:"directionality,attr"`
	RoadClass      string `xml:"road_class,attr"`
	From           *point `xml:"from"`
	To             *point `xml:"to"`
}

type event struct {
	Class string `xml:"class,attr"`
	Type  string `xml:"type,attr"`
	Speed int    `xml:"speed,attr"`
}

type message struct {
	ID             string    `xml:"id,attr"`
	ReceiveTime    traffTime `xml:"receive_time,attr"`
	UpdateTime     traffTime `xml:"update_time,attr"`
	ExpirationTime traffTime `xml:"expiration_time,attr"`
	Cancellation   bool      `xml:"cancellation,attr"`
	Forecast       bool      `xml:"forecast,attr"`
	Location       location  `xml:"location"`
	Events         []event   `xml:"events>event"`
}

type feed struct {
	XMLName  xml.Name  `xml:"feed"`
	Messages []message `xml:"message"`
}

// BBox restricts the exported segments ("minLat minLon maxLat maxLon", as in TraFF filters).
type BBox = protocol.BBox

func inBBox(p *aggregate.Publication, b *BBox) bool {
	return b == nil || b.Contains(p.From.Lat, p.From.Lon) || b.Contains(p.To.Lat, p.To.Lon)
}

// TraffMessage converts a congested publication into a TraFF message:
//   - id: stable per segment (MessageID),
//   - receive_time: start of the congestion episode, update_time: evaluation of the window,
//     expiration_time: when the publication expires unless a newer window confirms it,
//   - location: from/to on the tile edges, road class, one direction,
//   - one CONGESTION_* event with the estimated median speed (km/h).
func TraffMessage(p *aggregate.Publication) message {
	return message{
		ID:             MessageID(p.Key),
		ReceiveTime:    traffTime(p.FirstSeen),
		UpdateTime:     traffTime(p.Published),
		ExpirationTime: traffTime(p.Expires),
		Location: location{
			Directionality: "ONE_DIRECTION",
			RoadClass:      p.Key.Class.String(),
			From:           newPoint(p.From),
			To:             newPoint(p.To),
		},
		Events: []event{{Class: "CONGESTION", Type: p.State.TraffEvent(), Speed: int(math.Round(p.Speed))}},
	}
}

// TraffFeed renders all congested, confident publications as TraFF <feed>. Free-flowing segments
// are not included: the TraFF server cancels a message as soon as it disappears from the feed.
func TraffFeed(pubs []aggregate.Publication, bbox *BBox) ([]byte, error) {
	f := feed{Messages: []message{}}
	for i := range pubs {
		p := &pubs[i]
		if p.Congested() && inBBox(p, bbox) {
			f.Messages = append(f.Messages, TraffMessage(p))
		}
	}
	b, err := xml.MarshalIndent(f, "", "  ")
	if err != nil {
		return nil, err
	}
	return append([]byte(xml.Header), b...), nil
}

// Segment is one entry of the JSON export.
type Segment struct {
	ID          string           `json:"id"`
	Tile        [3]int           `json:"tile"` // zoom, x, y
	Entry       string           `json:"entry"`
	Exit        string           `json:"exit"`
	RoadClass   string           `json:"road_class"`
	From        aggregate.LatLon `json:"from"`
	To          aggregate.LatLon `json:"to"`
	WindowStart time.Time        `json:"window_start"`
	WindowEnd   time.Time        `json:"window_end"`
	Reports     int              `json:"reports"`
	SpeedKmh    float64          `json:"speed_kmh"`
	RefSpeedKmh float64          `json:"ref_speed_kmh"`
	RefSource   string           `json:"ref_source"` // "observed" or "road_class_default"
	Ratio       float64          `json:"ratio"`
	SlowShare   float64          `json:"slow_share"`
	SlowShareSE float64          `json:"slow_share_se"`
	State       string           `json:"state"`
	Confident   bool             `json:"confident"`
	Congested   bool             `json:"congested"`
	TraffEvent  string           `json:"traff_event,omitempty"`
	FirstSeen   time.Time        `json:"first_seen"`
	Updated     time.Time        `json:"updated"`
	Expires     time.Time        `json:"expires"`
}

// Document is the JSON export.
type Document struct {
	Version       int       `json:"version"`
	Generated     time.Time `json:"generated"`
	WindowSeconds int       `json:"window_seconds"`
	K             int       `json:"k"`
	Epsilon       float64   `json:"epsilon"`
	Segments      []Segment `json:"segments"`
}

func round(v float64, digits int) float64 {
	p := math.Pow(10, float64(digits))
	return math.Round(v*p) / p
}

// JSON renders all publications (including free-flowing segments) as JSON.
func JSON(pubs []aggregate.Publication, bbox *BBox, cfg aggregate.Config, now time.Time) ([]byte, error) {
	doc := Document{
		Version:       1,
		Generated:     now.UTC().Truncate(time.Second),
		WindowSeconds: int(cfg.Window / time.Second),
		K:             cfg.K,
		Epsilon:       protocol.Epsilon,
		Segments:      []Segment{},
	}
	for i := range pubs {
		p := &pubs[i]
		if !inBBox(p, bbox) {
			continue
		}
		src := "road_class_default"
		if p.RefObserved {
			src = "observed"
		}
		doc.Segments = append(doc.Segments, Segment{
			ID:          MessageID(p.Key),
			Tile:        [3]int{17, p.Key.Tile.X, p.Key.Tile.Y},
			Entry:       p.Key.Entry.String(),
			Exit:        p.Key.Exit.String(),
			RoadClass:   p.Key.Class.String(),
			From:        aggregate.LatLon{Lat: round(p.From.Lat, 6), Lon: round(p.From.Lon, 6)},
			To:          aggregate.LatLon{Lat: round(p.To.Lat, 6), Lon: round(p.To.Lon, 6)},
			WindowStart: p.WindowStart.UTC(),
			WindowEnd:   p.WindowEnd.UTC(),
			Reports:     p.Reports,
			SpeedKmh:    round(p.Speed, 1),
			RefSpeedKmh: round(p.RefSpeed, 1),
			RefSource:   src,
			Ratio:       round(p.Ratio, 3),
			SlowShare:   round(p.SlowShare, 3),
			SlowShareSE: round(p.SlowShareSE, 3),
			State:       p.State.String(),
			Confident:   p.Confident,
			Congested:   p.Congested(),
			TraffEvent:  p.State.TraffEvent(),
			FirstSeen:   p.FirstSeen.UTC().Truncate(time.Second),
			Updated:     p.Published.UTC().Truncate(time.Second),
			Expires:     p.Expires.UTC().Truncate(time.Second),
		})
	}
	return json.MarshalIndent(doc, "", "  ")
}
