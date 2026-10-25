// Package autobahn implements a TraFF source for the Autobahn API of Die Autobahn GmbH des Bundes
// (https://autobahn.api.bund.dev, base URL https://verkehr.autobahn.de/o/autobahn).
//
// Endpoints used (verified against the OpenAPI spec and live responses in October 2026):
//
//	GET /                              -> {"roads": ["A1", "A2", ...]}
//	GET /{roadId}/services/roadworks   -> {"roadworks": [Item...]}
//	GET /{roadId}/services/warning     -> {"warning": [Item...]}
//	GET /{roadId}/services/closure     -> {"closure": [Item...]}
//
// The API answers with weak ETags and supports If-None-Match. Live data differs from the OpenAPI
// spec in a few places, which the decoder tolerates: coordinates are JSON numbers instead of
// strings, "extent"/"point" are "lat,lon" instead of "lon,lat", and the road list may contain
// IDs with trailing spaces ("A60 ").
package autobahn

import (
	"encoding/json"
	"strconv"
	"strings"
)

// Service names of the Autobahn API that this source reads.
const (
	ServiceRoadworks = "roadworks"
	ServiceWarning   = "warning"
	ServiceClosure   = "closure"
)

// DefaultServices are the services fetched for every road.
var DefaultServices = []string{ServiceRoadworks, ServiceWarning, ServiceClosure}

// RoadList is the response of GET /.
type RoadList struct {
	Roads []string `json:"roads"`
}

// FlexFloat decodes a JSON number or a string containing a number.
type FlexFloat struct {
	Value float64
	Valid bool
}

// UnmarshalJSON implements json.Unmarshaler.
func (f *FlexFloat) UnmarshalJSON(b []byte) error {
	s := strings.Trim(strings.TrimSpace(string(b)), `"`)
	if s == "" || s == "null" {
		*f = FlexFloat{}
		return nil
	}
	v, err := strconv.ParseFloat(s, 64)
	if err != nil {
		*f = FlexFloat{}
		return nil // tolerate garbage, the item is then skipped for lack of coordinates
	}
	*f = FlexFloat{Value: v, Valid: true}
	return nil
}

// FlexBool decodes a JSON boolean or the strings "true"/"false".
type FlexBool bool

// UnmarshalJSON implements json.Unmarshaler.
func (f *FlexBool) UnmarshalJSON(b []byte) error {
	s := strings.ToLower(strings.Trim(strings.TrimSpace(string(b)), `"`))
	*f = FlexBool(s == "true" || s == "1")
	return nil
}

// FlexString decodes a JSON string or number into a string.
type FlexString string

// UnmarshalJSON implements json.Unmarshaler.
func (f *FlexString) UnmarshalJSON(b []byte) error {
	var s string
	if err := json.Unmarshal(b, &s); err == nil {
		*f = FlexString(s)
		return nil
	}
	if string(b) == "null" {
		*f = ""
		return nil
	}
	*f = FlexString(strings.TrimSpace(string(b)))
	return nil
}

// Int returns the value as an integer.
func (f FlexString) Int() (int, bool) {
	s := strings.TrimSpace(string(f))
	if s == "" {
		return 0, false
	}
	if v, err := strconv.Atoi(s); err == nil {
		return v, true
	}
	if v, err := strconv.ParseFloat(s, 64); err == nil {
		return int(v + 0.5), true
	}
	return 0, false
}

// Coordinate is a lat/long pair.
type Coordinate struct {
	Lat  FlexFloat `json:"lat"`
	Long FlexFloat `json:"long"`
}

// Impact describes the lane layout. Symbols list the lanes from left to right; "SEPARATE" marks
// the central reservation, lanes to its right belong to the direction of the item.
type Impact struct {
	Lower   string   `json:"lower"`
	Upper   string   `json:"upper"`
	Symbols []string `json:"symbols"`
}

// Geometry is a GeoJSON geometry (coordinates in lon/lat order).
type Geometry struct {
	Type        string          `json:"type"`
	Coordinates json.RawMessage `json:"coordinates"`
}

// Item is an entry of the roadworks, warning or closure lists.
type Item struct {
	Identifier          string      `json:"identifier"`
	Icon                string      `json:"icon"`
	IsBlocked           FlexBool    `json:"isBlocked"`
	Future              FlexBool    `json:"future"`
	Extent              string      `json:"extent"`
	Point               string      `json:"point"`
	Impact              *Impact     `json:"impact"`
	DisplayType         string      `json:"display_type"`
	Subtitle            string      `json:"subtitle"`
	Title               string      `json:"title"`
	StartTimestamp      string      `json:"startTimestamp"`
	DelayTimeValue      FlexString  `json:"delayTimeValue"`
	AbnormalTrafficType string      `json:"abnormalTrafficType"`
	AverageSpeed        FlexString  `json:"averageSpeed"`
	Coordinate          *Coordinate `json:"coordinate"`
	Description         []string    `json:"description"`
	Source              string      `json:"source"`
	Geometry            *Geometry   `json:"geometry"`
}

// DecodeItems decodes a service response ({"<service>": [...]}).
func DecodeItems(body []byte, service string) ([]Item, error) {
	var m map[string]json.RawMessage
	if err := json.Unmarshal(body, &m); err != nil {
		return nil, err
	}
	raw, ok := m[service]
	if !ok || string(raw) == "null" {
		return []Item{}, nil
	}
	var items []Item
	if err := json.Unmarshal(raw, &items); err != nil {
		return nil, err
	}
	if items == nil {
		items = []Item{}
	}
	return items, nil
}

// lonLat decodes GeoJSON coordinates of any (multi-)line or point geometry into a flat list of
// [lon, lat] pairs.
func (g *Geometry) lonLat() [][2]float64 {
	if g == nil || len(g.Coordinates) == 0 {
		return nil
	}
	var line [][2]float64
	if json.Unmarshal(g.Coordinates, &line) == nil {
		return line
	}
	var multi [][][2]float64
	if json.Unmarshal(g.Coordinates, &multi) == nil {
		var res [][2]float64
		for _, l := range multi {
			res = append(res, l...)
		}
		return res
	}
	var pt [2]float64
	if json.Unmarshal(g.Coordinates, &pt) == nil {
		return [][2]float64{pt}
	}
	return nil
}
