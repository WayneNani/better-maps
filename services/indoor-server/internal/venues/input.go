package venues

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strconv"
	"strings"

	"github.com/WayneNani/better-maps/services/indoor-server/internal/geo"
)

// Raw is one feature of the osmium export (GeoJSONSeq with --add-unique-id=type_id).
type Raw struct {
	// ID is the OSM id: "n123", "w123" or "r123" (osmium's area ids "a246"/"a247" are converted
	// back to the way or relation they came from).
	ID   string
	Tags map[string]string
	// Exactly one of the geometries is set, matching Kind.
	Kind  GeomKind
	Point geo.Point
	Lines [][]geo.Point
	Polys []geo.Polygon
}

// GeomKind is the geometry type of a Raw feature.
type GeomKind byte

// Geometry kinds, also the "t" field of a feature in the venue document.
const (
	GeomArea  GeomKind = 'a'
	GeomLine  GeomKind = 'l'
	GeomPoint GeomKind = 'p'
)

type rawJSON struct {
	Type     string `json:"type"`
	ID       string `json:"id"`
	Geometry struct {
		Type        string          `json:"type"`
		Coordinates json.RawMessage `json:"coordinates"`
	} `json:"geometry"`
	Properties map[string]any `json:"properties"`
}

// maxLine bounds a single GeoJSON line (a huge multipolygon such as a whole aerodrome is a few MB).
const maxLine = 256 << 20

// ReadGeoJSONSeq reads newline-delimited GeoJSON features (with or without the RS record
// separator) and calls fn for every feature with a usable geometry. Features with an unknown id
// or geometry type are skipped and counted in skipped. Malformed JSON is an error.
func ReadGeoJSONSeq(r io.Reader, fn func(*Raw) error) (skipped int, err error) {
	br := bufio.NewReaderSize(r, 1<<20)
	lineNo := 0
	for {
		line, rerr := br.ReadSlice('\n')
		if errors.Is(rerr, bufio.ErrBufferFull) {
			// Long line: collect it.
			buf := append([]byte(nil), line...)
			for errors.Is(rerr, bufio.ErrBufferFull) {
				line, rerr = br.ReadSlice('\n')
				buf = append(buf, line...)
				if len(buf) > maxLine {
					return skipped, fmt.Errorf("line %d: longer than %d bytes", lineNo+1, maxLine)
				}
			}
			line = buf
		}
		if len(line) > 0 {
			lineNo++
			line = bytes.TrimSpace(bytes.TrimLeft(line, "\x1e"))
			if len(line) > 0 {
				raw, ok, err := parseRaw(line)
				if err != nil {
					return skipped, fmt.Errorf("line %d: %w", lineNo, err)
				}
				if !ok {
					skipped++
				} else if err := fn(raw); err != nil {
					return skipped, err
				}
			}
		}
		if rerr == io.EOF {
			return skipped, nil
		}
		if rerr != nil {
			return skipped, rerr
		}
	}
}

func parseRaw(line []byte) (*Raw, bool, error) {
	var j rawJSON
	if err := json.Unmarshal(line, &j); err != nil {
		return nil, false, err
	}
	id, ok := osmID(j.ID)
	if !ok || j.Type != "Feature" {
		return nil, false, nil
	}
	tags := make(map[string]string, len(j.Properties))
	for k, v := range j.Properties {
		if s, ok := v.(string); ok {
			tags[k] = s
		}
	}
	raw := &Raw{ID: id, Tags: tags}
	c := j.Geometry.Coordinates
	switch j.Geometry.Type {
	case "Point":
		var p [2]float64
		if json.Unmarshal(c, &p) != nil {
			return nil, false, nil
		}
		raw.Kind, raw.Point = GeomPoint, geo.Point{Lon: p[0], Lat: p[1]}
	case "LineString":
		var l [][2]float64
		if json.Unmarshal(c, &l) != nil || len(l) < 2 {
			return nil, false, nil
		}
		raw.Kind, raw.Lines = GeomLine, [][]geo.Point{points(l)}
	case "MultiLineString":
		var ls [][][2]float64
		if json.Unmarshal(c, &ls) != nil {
			return nil, false, nil
		}
		raw.Kind = GeomLine
		for _, l := range ls {
			if len(l) >= 2 {
				raw.Lines = append(raw.Lines, points(l))
			}
		}
	case "Polygon":
		var rings [][][2]float64
		if json.Unmarshal(c, &rings) != nil {
			return nil, false, nil
		}
		raw.Kind = GeomArea
		if p := polygon(rings); p != nil {
			raw.Polys = []geo.Polygon{p}
		}
	case "MultiPolygon":
		var polys [][][][2]float64
		if json.Unmarshal(c, &polys) != nil {
			return nil, false, nil
		}
		raw.Kind = GeomArea
		for _, rings := range polys {
			if p := polygon(rings); p != nil {
				raw.Polys = append(raw.Polys, p)
			}
		}
	default:
		return nil, false, nil
	}
	if (raw.Kind == GeomLine && len(raw.Lines) == 0) || (raw.Kind == GeomArea && len(raw.Polys) == 0) {
		return nil, false, nil
	}
	return raw, true, nil
}

func points(l [][2]float64) []geo.Point {
	out := make([]geo.Point, len(l))
	for i, c := range l {
		out[i] = geo.Point{Lon: c[0], Lat: c[1]}
	}
	return out
}

// polygon converts GeoJSON rings (closed) to a geo.Polygon (open rings). A polygon whose outer
// ring is degenerate is nil; degenerate holes are dropped.
func polygon(rings [][][2]float64) geo.Polygon {
	var p geo.Polygon
	for i, r := range rings {
		pts := points(r)
		if len(pts) > 1 && pts[0] == pts[len(pts)-1] {
			pts = pts[:len(pts)-1]
		}
		if len(pts) < 3 {
			if i == 0 {
				return nil
			}
			continue
		}
		p = append(p, geo.Ring(pts))
	}
	return p
}

// osmID converts an osmium unique id ("n1", "w2", "r3", or the area ids "a4" = way 2 and
// "a7" = relation 3) to the OSM id.
func osmID(s string) (string, bool) {
	if len(s) < 2 {
		return "", false
	}
	n, err := strconv.ParseInt(s[1:], 10, 64)
	if err != nil || n <= 0 || strings.HasPrefix(s[1:], "+") {
		return "", false
	}
	switch s[0] {
	case 'n', 'w', 'r':
		return s, true
	case 'a':
		if n%2 == 0 {
			return "w" + strconv.FormatInt(n/2, 10), true
		}
		return "r" + strconv.FormatInt((n-1)/2, 10), true
	}
	return "", false
}
