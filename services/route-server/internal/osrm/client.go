// Package osrm calls the route service of an OSRM backend (osrm-routed, HTTP API v1).
//
// Only the route service is used; the client never forwards other services or parameters of the
// caller.
package osrm

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// Point is a WGS84 coordinate.
type Point struct {
	Lat, Lon float64
}

// Route is the fastest car route through the points.
type Route struct {
	// Geometry is the whole route as an encoded polyline with precision 6 (Google polyline
	// algorithm, 1e-6 degrees).
	Geometry  string
	DistanceM float64
	DurationS float64
	// DataVersion is the timestamp of the OSM data (osrm-extract --data_version osmosis), empty if
	// unknown.
	DataVersion string
}

var (
	// ErrNoRoute: the points are not connected by roads.
	ErrNoRoute = errors.New("no route")
	// ErrNoSegment: a point is too far from any road.
	ErrNoSegment = errors.New("point not near a road")
	// ErrUpstream: OSRM failed or answered something unexpected.
	ErrUpstream = errors.New("routing backend error")
)

// MaxResponseBytes limits the OSRM response (a route across Germany is ~150 KB).
const MaxResponseBytes = 16 << 20

// Client calls OSRM.
type Client struct {
	base    string
	http    *http.Client
	timeout time.Duration
}

// NewClient returns a client for the OSRM server at base (e.g. http://osrm:5000).
func NewClient(base string, timeout time.Duration) (*Client, error) {
	u, err := url.Parse(base)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return nil, fmt.Errorf("invalid OSRM URL %q", base)
	}
	return &Client{
		base:    strings.TrimRight(base, "/"),
		timeout: timeout,
		http: &http.Client{
			// No redirects: OSRM never redirects, and the gateway must not be pointed elsewhere.
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
			Transport: &http.Transport{
				MaxIdleConnsPerHost: 16,
				IdleConnTimeout:     90 * time.Second,
			},
		},
	}, nil
}

type response struct {
	Code    string `json:"code"`
	Message string `json:"message"`
	Routes  []struct {
		Geometry json.RawMessage `json:"geometry"`
		Distance float64         `json:"distance"`
		Duration float64         `json:"duration"`
	} `json:"routes"`
	DataVersion string `json:"data_version"`
}

// URL returns the OSRM request URL for points (exported for tests).
func (c *Client) URL(points []Point) string {
	var b strings.Builder
	b.WriteString(c.base)
	b.WriteString("/route/v1/driving/")
	for i, p := range points {
		if i > 0 {
			b.WriteByte(';')
		}
		b.WriteString(strconv.FormatFloat(p.Lon, 'f', 6, 64))
		b.WriteByte(',')
		b.WriteString(strconv.FormatFloat(p.Lat, 'f', 6, 64))
	}
	b.WriteString("?overview=full&geometries=polyline6&alternatives=false&steps=false&annotations=false")
	return b.String()
}

// Route asks OSRM for the fastest route through points.
func (c *Client) Route(ctx context.Context, points []Point) (Route, error) {
	if c.timeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, c.timeout)
		defer cancel()
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.URL(points), nil)
	if err != nil {
		return Route{}, err
	}
	resp, err := c.http.Do(req)
	if err != nil {
		if ctx.Err() != nil {
			return Route{}, fmt.Errorf("%w: %w", ErrUpstream, ctx.Err())
		}
		// The error contains the URL with the coordinates: don't pass it on (it would be logged).
		return Route{}, fmt.Errorf("%w: request failed", ErrUpstream)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, MaxResponseBytes+1))
	if err != nil {
		return Route{}, fmt.Errorf("%w: reading response", ErrUpstream)
	}
	if len(body) > MaxResponseBytes {
		return Route{}, fmt.Errorf("%w: response too large", ErrUpstream)
	}
	return parse(resp.StatusCode, body)
}

func parse(status int, body []byte) (Route, error) {
	var r response
	if err := json.Unmarshal(body, &r); err != nil {
		return Route{}, fmt.Errorf("%w: HTTP %d, invalid JSON", ErrUpstream, status)
	}
	switch r.Code {
	case "Ok":
	case "NoRoute":
		return Route{}, ErrNoRoute
	case "NoSegment":
		return Route{}, ErrNoSegment
	default:
		// The message of OSRM may quote the request; only the code is passed on.
		return Route{}, fmt.Errorf("%w: HTTP %d, code %q", ErrUpstream, status, truncate(r.Code, 40))
	}
	if status != http.StatusOK || len(r.Routes) == 0 {
		return Route{}, fmt.Errorf("%w: HTTP %d, %d routes", ErrUpstream, status, len(r.Routes))
	}
	route := r.Routes[0]
	var geometry string
	if err := json.Unmarshal(route.Geometry, &geometry); err != nil || geometry == "" {
		return Route{}, fmt.Errorf("%w: geometry is not an encoded polyline", ErrUpstream)
	}
	if route.Distance < 0 || route.Duration < 0 {
		return Route{}, fmt.Errorf("%w: negative distance or duration", ErrUpstream)
	}
	return Route{Geometry: geometry, DistanceM: route.Distance, DurationS: route.Duration, DataVersion: r.DataVersion}, nil
}

func truncate(s string, n int) string {
	if len(s) > n {
		return s[:n]
	}
	return s
}
