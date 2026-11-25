// Package sources describes the imagery sources behind the proxy (one per state orthophoto
// service plus the worldwide Sentinel-2 mosaic) and answers which attributions apply to a map
// view.
//
// The sources file (config/sources.json) lists the sources in drawing order, bottom first, the
// same order as the cache "aerial" in config/mapproxy.yaml:
//
//	{"sources": [
//	  {"id": "s2cloudless2016", "name": "...", "attribution": "...", "license": "CC BY 4.0",
//	   "license_url": "https://...", "min_zoom": 0},
//	  {"id": "nw", "name": "...", "attribution": "...", "license": "dl-de/zero-2.0",
//	   "license_url": "https://...", "min_zoom": 12, "coverage": "coverage/nw.geojson"}
//	]}
//
// A source without "coverage" covers the whole world. Coverage files are GeoJSON (WGS84) with
// Polygon / MultiPolygon geometries, relative paths are resolved against the sources file. In
// "attribution", "{year}" is replaced by the current year (several providers ask for the year of
// data retrieval).
package sources

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

// BBox is a WGS84 bounding box.
type BBox struct {
	MinLon, MinLat, MaxLon, MaxLat float64
}

// Valid reports whether b is a non-empty box inside the WGS84 range.
func (b BBox) Valid() bool {
	return b.MinLon >= -180 && b.MaxLon <= 180 && b.MinLat >= -90 && b.MaxLat <= 90 &&
		b.MinLon < b.MaxLon && b.MinLat < b.MaxLat
}

// Intersects reports whether the boxes overlap.
func (b BBox) Intersects(o BBox) bool {
	return b.MinLon <= o.MaxLon && o.MinLon <= b.MaxLon && b.MinLat <= o.MaxLat && o.MinLat <= b.MaxLat
}

// Contains reports whether p lies in b (borders included).
func (b BBox) Contains(p Point) bool {
	return p.Lon >= b.MinLon && p.Lon <= b.MaxLon && p.Lat >= b.MinLat && p.Lat <= b.MaxLat
}

// ParseBBox parses "minLon,minLat,maxLon,maxLat".
func ParseBBox(s string) (BBox, error) {
	parts := strings.Split(s, ",")
	if len(parts) != 4 {
		return BBox{}, errors.New("bbox needs 4 numbers")
	}
	var v [4]float64
	for i, p := range parts {
		f, err := strconv.ParseFloat(strings.TrimSpace(p), 64)
		if err != nil {
			return BBox{}, errors.New("bbox: not a number")
		}
		v[i] = f
	}
	b := BBox{v[0], v[1], v[2], v[3]}
	if !b.Valid() {
		return BBox{}, errors.New("bbox out of range or empty")
	}
	return b, nil
}

// Point is a WGS84 position.
type Point struct{ Lon, Lat float64 }

// Ring is a closed polygon ring (the last point may or may not repeat the first).
type Ring []Point

// Polygon is an outer ring followed by holes.
type Polygon []Ring

// Coverage is a set of polygons (a MultiPolygon). A nil Coverage covers the world.
type Coverage struct {
	Polygons []Polygon
	BBox     BBox
}

// ContainsPoint reports whether p lies inside the coverage (inside an outer ring and outside
// its holes).
func (c *Coverage) ContainsPoint(p Point) bool {
	if c == nil {
		return true
	}
	if !c.BBox.Contains(p) {
		return false
	}
	for _, poly := range c.Polygons {
		if len(poly) == 0 || !ringContains(poly[0], p) {
			continue
		}
		inHole := false
		for _, hole := range poly[1:] {
			if ringContains(hole, p) {
				inHole = true
				break
			}
		}
		if !inHole {
			return true
		}
	}
	return false
}

// IntersectsBBox reports whether the coverage overlaps the box: a vertex of the coverage lies in
// the box, a corner of the box lies in the coverage, or an edge crosses the box.
func (c *Coverage) IntersectsBBox(b BBox) bool {
	if c == nil {
		return true
	}
	if !c.BBox.Intersects(b) {
		return false
	}
	corners := [4]Point{{b.MinLon, b.MinLat}, {b.MaxLon, b.MinLat}, {b.MaxLon, b.MaxLat}, {b.MinLon, b.MaxLat}}
	for _, p := range corners {
		if c.ContainsPoint(p) {
			return true
		}
	}
	for _, poly := range c.Polygons {
		for _, ring := range poly {
			for i := range ring {
				a, z := ring[i], ring[(i+1)%len(ring)]
				if b.Contains(a) {
					return true
				}
				for j := range corners {
					if segmentsIntersect(a, z, corners[j], corners[(j+1)%4]) {
						return true
					}
				}
			}
		}
	}
	return false
}

// ringContains is the even-odd rule.
func ringContains(r Ring, p Point) bool {
	in := false
	n := len(r)
	for i, j := 0, n-1; i < n; j, i = i, i+1 {
		a, b := r[i], r[j]
		if (a.Lat > p.Lat) != (b.Lat > p.Lat) &&
			p.Lon < (b.Lon-a.Lon)*(p.Lat-a.Lat)/(b.Lat-a.Lat)+a.Lon {
			in = !in
		}
	}
	return in
}

func orient(a, b, c Point) float64 {
	return (b.Lon-a.Lon)*(c.Lat-a.Lat) - (b.Lat-a.Lat)*(c.Lon-a.Lon)
}

func segmentsIntersect(p1, p2, q1, q2 Point) bool {
	d1, d2 := orient(q1, q2, p1), orient(q1, q2, p2)
	d3, d4 := orient(p1, p2, q1), orient(p1, p2, q2)
	return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
}

// Source is one imagery source.
type Source struct {
	ID          string    `json:"id"`
	Name        string    `json:"name"`
	Attribution string    `json:"attribution"`
	License     string    `json:"license"`
	LicenseURL  string    `json:"license_url"`
	MinZoom     int       `json:"min_zoom"`
	CoverageRef string    `json:"coverage,omitempty"`
	Coverage    *Coverage `json:"-"`
}

// Set is the list of sources in drawing order (bottom first).
type Set struct {
	Sources []Source
}

var reID = regexp.MustCompile(`^[a-z0-9_-]{1,32}$`)

// Load reads the sources file and the coverage files it references.
func Load(path string) (*Set, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	return Parse(f, func(ref string) (io.ReadCloser, error) {
		if filepath.IsAbs(ref) || strings.Contains(ref, "..") {
			return nil, fmt.Errorf("coverage path %q must be relative, without ..", ref)
		}
		return os.Open(filepath.Join(filepath.Dir(path), ref))
	})
}

// Parse reads a sources file; open opens the referenced coverage files.
func Parse(r io.Reader, open func(ref string) (io.ReadCloser, error)) (*Set, error) {
	var file struct {
		Sources []Source `json:"sources"`
	}
	dec := json.NewDecoder(r)
	dec.DisallowUnknownFields()
	if err := dec.Decode(&file); err != nil {
		return nil, fmt.Errorf("sources: %w", err)
	}
	if len(file.Sources) == 0 {
		return nil, errors.New("sources: no sources")
	}
	seen := map[string]bool{}
	for i := range file.Sources {
		s := &file.Sources[i]
		if !reID.MatchString(s.ID) || seen[s.ID] {
			return nil, fmt.Errorf("sources: invalid or duplicate id %q", s.ID)
		}
		seen[s.ID] = true
		if s.Name == "" || s.Attribution == "" || s.License == "" {
			return nil, fmt.Errorf("sources: %s: name, attribution and license are required", s.ID)
		}
		if s.MinZoom < 0 || s.MinZoom > 22 {
			return nil, fmt.Errorf("sources: %s: min_zoom out of range", s.ID)
		}
		if s.CoverageRef == "" {
			continue
		}
		rc, err := open(s.CoverageRef)
		if err != nil {
			return nil, fmt.Errorf("sources: %s: %w", s.ID, err)
		}
		cov, err := ParseGeoJSON(rc)
		rc.Close()
		if err != nil {
			return nil, fmt.Errorf("sources: %s: %w", s.ID, err)
		}
		s.Coverage = cov
	}
	return &Set{Sources: file.Sources}, nil
}

// ParseGeoJSON reads the Polygon and MultiPolygon geometries of a GeoJSON FeatureCollection,
// Feature or bare geometry.
func ParseGeoJSON(r io.Reader) (*Coverage, error) {
	var raw json.RawMessage
	if err := json.NewDecoder(io.LimitReader(r, 64<<20)).Decode(&raw); err != nil {
		return nil, fmt.Errorf("geojson: %w", err)
	}
	cov := &Coverage{BBox: BBox{180, 90, -180, -90}}
	if err := collect(raw, cov, 0); err != nil {
		return nil, err
	}
	if len(cov.Polygons) == 0 {
		return nil, errors.New("geojson: no polygons")
	}
	return cov, nil
}

type geoObject struct {
	Type        string            `json:"type"`
	Features    []json.RawMessage `json:"features"`
	Geometry    json.RawMessage   `json:"geometry"`
	Geometries  []json.RawMessage `json:"geometries"`
	Coordinates json.RawMessage   `json:"coordinates"`
}

func collect(raw json.RawMessage, cov *Coverage, depth int) error {
	if depth > 4 {
		return errors.New("geojson: nested too deeply")
	}
	var o geoObject
	if err := json.Unmarshal(raw, &o); err != nil {
		return fmt.Errorf("geojson: %w", err)
	}
	switch o.Type {
	case "FeatureCollection":
		for _, f := range o.Features {
			if err := collect(f, cov, depth+1); err != nil {
				return err
			}
		}
	case "Feature":
		if len(o.Geometry) > 0 && string(o.Geometry) != "null" {
			return collect(o.Geometry, cov, depth+1)
		}
	case "GeometryCollection":
		for _, g := range o.Geometries {
			if err := collect(g, cov, depth+1); err != nil {
				return err
			}
		}
	case "Polygon":
		var c [][][]float64
		if err := json.Unmarshal(o.Coordinates, &c); err != nil {
			return fmt.Errorf("geojson polygon: %w", err)
		}
		return addPolygon(cov, c)
	case "MultiPolygon":
		var c [][][][]float64
		if err := json.Unmarshal(o.Coordinates, &c); err != nil {
			return fmt.Errorf("geojson multipolygon: %w", err)
		}
		for _, p := range c {
			if err := addPolygon(cov, p); err != nil {
				return err
			}
		}
	default:
		return fmt.Errorf("geojson: unsupported type %q", o.Type)
	}
	return nil
}

func addPolygon(cov *Coverage, rings [][][]float64) error {
	var poly Polygon
	for _, r := range rings {
		if len(r) < 3 {
			return errors.New("geojson: ring with fewer than 3 points")
		}
		ring := make(Ring, 0, len(r))
		for _, p := range r {
			if len(p) < 2 || p[0] < -180 || p[0] > 180 || p[1] < -90 || p[1] > 90 {
				return errors.New("geojson: invalid position")
			}
			pt := Point{p[0], p[1]}
			ring = append(ring, pt)
			cov.BBox.MinLon = min(cov.BBox.MinLon, pt.Lon)
			cov.BBox.MinLat = min(cov.BBox.MinLat, pt.Lat)
			cov.BBox.MaxLon = max(cov.BBox.MaxLon, pt.Lon)
			cov.BBox.MaxLat = max(cov.BBox.MaxLat, pt.Lat)
		}
		poly = append(poly, ring)
	}
	cov.Polygons = append(cov.Polygons, poly)
	return nil
}

// Attribution is one entry of the attribution answer.
type Attribution struct {
	ID          string     `json:"id"`
	Name        string     `json:"name"`
	Attribution string     `json:"attribution"`
	License     string     `json:"license"`
	LicenseURL  string     `json:"license_url,omitempty"`
	MinZoom     int        `json:"min_zoom"`
	BBox        [4]float64 `json:"bbox"`
}

func (s *Source) attribution(year int) Attribution {
	b := [4]float64{-180, -85.06, 180, 85.06}
	if s.Coverage != nil {
		cb := s.Coverage.BBox
		b = [4]float64{cb.MinLon, cb.MinLat, cb.MaxLon, cb.MaxLat}
	}
	return Attribution{
		ID: s.ID, Name: s.Name, Attribution: strings.ReplaceAll(s.Attribution, "{year}", strconv.Itoa(year)),
		License: s.License, LicenseURL: s.LicenseURL, MinZoom: s.MinZoom, BBox: b,
	}
}

// All returns the attributions of all sources (top first).
func (s *Set) All(year int) []Attribution {
	out := make([]Attribution, 0, len(s.Sources))
	for i := len(s.Sources) - 1; i >= 0; i-- {
		out = append(out, s.Sources[i].attribution(year))
	}
	return out
}

// For returns the attributions of the sources visible in bbox at zoom (top first). A source is
// visible if it is used at that zoom, its coverage overlaps the box and the sources above it do
// not cover the whole box (checked at the corners and the centre, which is enough for
// attribution purposes).
func (s *Set) For(b BBox, zoom int, year int) []Attribution {
	probes := []Point{{b.MinLon, b.MinLat}, {b.MaxLon, b.MinLat}, {b.MaxLon, b.MaxLat}, {b.MinLon, b.MaxLat},
		{(b.MinLon + b.MaxLon) / 2, (b.MinLat + b.MaxLat) / 2}}
	covered := make([]bool, len(probes))
	var out []Attribution
	for i := len(s.Sources) - 1; i >= 0; i-- {
		src := &s.Sources[i]
		if zoom < src.MinZoom || !src.Coverage.IntersectsBBox(b) {
			continue
		}
		allCovered := true
		for _, c := range covered {
			allCovered = allCovered && c
		}
		if allCovered {
			break
		}
		out = append(out, src.attribution(year))
		for j, p := range probes {
			covered[j] = covered[j] || src.Coverage.ContainsPoint(p)
		}
	}
	return out
}
