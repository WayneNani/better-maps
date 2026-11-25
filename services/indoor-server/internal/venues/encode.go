package venues

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"math"

	"github.com/WayneNani/better-maps/services/indoor-server/internal/geo"
)

// Attribution and License are part of every venue document.
const (
	Attribution = "© OpenStreetMap-Mitwirkende"
	License     = "ODbL-1.0"
)

// Document is the JSON of GET /v1/venues/{id} (see README "API").
type Document struct {
	ID            string       `json:"id"`
	Name          string       `json:"name,omitempty"`
	Kind          string       `json:"kind"`
	BBox          [4]float64   `json:"bbox"`
	Levels        []Level      `json:"levels"`
	DefaultLevel  float64      `json:"default_level"`
	DataTimestamp string       `json:"data_timestamp"`
	Attribution   string       `json:"attribution"`
	License       string       `json:"license"`
	Features      []DocFeature `json:"features"`
}

// DocFeature is one feature of a venue document.
type DocFeature struct {
	ID     string            `json:"id"`
	T      string            `json:"t"`
	K      string            `json:"k"`
	N      string            `json:"n,omitempty"`
	R      string            `json:"r,omitempty"`
	Levels []float64         `json:"lv"`
	G      any               `json:"g"`
	Tags   map[string]string `json:"tg,omitempty"`
}

// IndexEntry is one venue of index.json (the gateway adds the ETag when serving it).
type IndexEntry struct {
	ID           string     `json:"id"`
	Name         string     `json:"name,omitempty"`
	Kind         string     `json:"kind"`
	BBox         [4]float64 `json:"bbox"`
	Levels       []Level    `json:"levels"`
	DefaultLevel float64    `json:"default_level"`
	Features     int        `json:"features"`
}

// Index is index.json.
type Index struct {
	DataTimestamp string       `json:"data_timestamp"`
	Venues        []IndexEntry `json:"venues"`
}

// Encode returns the venue document and its index entry. Geometry is simplified with tol metres;
// features whose geometry degenerates are dropped (and the index counts what remains).
func Encode(v *Venue, dataTimestamp string, tol float64) (Document, IndexEntry) {
	doc := Document{
		ID: v.ID, Name: v.Name, Kind: v.Kind, BBox: outward(v.BBox), Levels: v.Levels, DefaultLevel: v.DefaultLevel,
		DataTimestamp: dataTimestamp, Attribution: Attribution, License: License,
		Features: make([]DocFeature, 0, len(v.Features)),
	}
	if doc.Levels == nil {
		doc.Levels = []Level{}
	}
	for _, f := range v.Features {
		g, ok := encodeGeometry(f, tol)
		if !ok {
			continue
		}
		doc.Features = append(doc.Features, DocFeature{
			ID: f.ID, T: string(rune(f.T)), K: f.Kind, N: f.Name, R: f.Ref, Levels: f.Levels, G: g, Tags: f.Tags,
		})
	}
	entry := IndexEntry{ID: doc.ID, Name: doc.Name, Kind: doc.Kind, BBox: doc.BBox, Levels: doc.Levels,
		DefaultLevel: doc.DefaultLevel, Features: len(doc.Features)}
	return doc, entry
}

// outward rounds a box to 1e-6 degrees so that it still contains every encoded coordinate.
func outward(b geo.BBox) [4]float64 {
	if !b.Valid() {
		return [4]float64{}
	}
	return [4]float64{math.Floor(b.MinLon*1e6) / 1e6, math.Floor(b.MinLat*1e6) / 1e6,
		math.Ceil(b.MaxLon*1e6) / 1e6, math.Ceil(b.MaxLat*1e6) / 1e6}
}

func encodeGeometry(f *Feature, tol float64) (any, bool) {
	switch f.T {
	case GeomPoint:
		return geo.Encode(geo.Round6([]geo.Point{f.Point})), true
	case GeomLine:
		var out []string
		for _, l := range f.Lines {
			pts := geo.Round6(geo.Simplify(l, tol))
			if len(pts) >= 2 {
				out = append(out, geo.Encode(pts))
			}
		}
		return out, len(out) > 0
	case GeomArea:
		var out [][]string
		for _, p := range f.Polys {
			var rings []string
			for i, r := range p {
				pts := ring6(geo.SimplifyRing(r, tol))
				if len(pts) < 3 {
					if i == 0 {
						break
					}
					continue
				}
				rings = append(rings, geo.Encode(pts))
			}
			if len(rings) > 0 {
				out = append(out, rings)
			}
		}
		return out, len(out) > 0
	}
	return nil, false
}

// ring6 rounds a ring and drops a closing point that rounding may have created.
func ring6(r geo.Ring) []geo.Point {
	pts := geo.Round6(r)
	for len(pts) > 1 && pts[0] == pts[len(pts)-1] {
		pts = pts[:len(pts)-1]
	}
	return pts
}

// Marshal returns the JSON and its gzip (level 9) compression.
func Marshal(v any) (plain, gz []byte, err error) {
	plain, err = json.Marshal(v)
	if err != nil {
		return nil, nil, err
	}
	var buf bytes.Buffer
	w, err := gzip.NewWriterLevel(&buf, gzip.BestCompression)
	if err != nil {
		return nil, nil, err
	}
	if _, err := w.Write(plain); err != nil {
		return nil, nil, err
	}
	if err := w.Close(); err != nil {
		return nil, nil, err
	}
	return plain, buf.Bytes(), nil
}
