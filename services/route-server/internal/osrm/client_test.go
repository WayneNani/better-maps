package osrm

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestURL(t *testing.T) {
	c, err := NewClient("http://osrm:5000/", time.Second)
	if err != nil {
		t.Fatal(err)
	}
	got := c.URL([]Point{{Lat: 50.7295477, Lon: 7.1057999}, {Lat: 49.453861, Lon: 11.0772885}})
	want := "http://osrm:5000/route/v1/driving/7.105800,50.729548;11.077288,49.453861" +
		"?overview=full&geometries=polyline6&alternatives=false&steps=false&annotations=false"
	if got != want {
		t.Fatalf("URL\n got %s\nwant %s", got, want)
	}
}

func TestNewClientRejectsBadURLs(t *testing.T) {
	for _, u := range []string{"", "osrm:5000", "ftp://osrm", "http://"} {
		if _, err := NewClient(u, time.Second); err == nil {
			t.Errorf("NewClient(%q) succeeded", u)
		}
	}
}

func TestParse(t *testing.T) {
	cases := []struct {
		name   string
		status int
		body   string
		want   Route
		err    error
	}{
		{"ok", 200, `{"code":"Ok","data_version":"2026-11-08T21:21:00Z","routes":[{"geometry":"_p~iF~ps|U","distance":1234.5,"duration":99.9,"weight":100}],"waypoints":[]}`,
			Route{Geometry: "_p~iF~ps|U", DistanceM: 1234.5, DurationS: 99.9, DataVersion: "2026-11-08T21:21:00Z"}, nil},
		{"ok without data version", 200, `{"code":"Ok","routes":[{"geometry":"abc","distance":1,"duration":2}]}`,
			Route{Geometry: "abc", DistanceM: 1, DurationS: 2}, nil},
		{"no route", 400, `{"code":"NoRoute","message":"Impossible route between points"}`, Route{}, ErrNoRoute},
		{"no segment", 400, `{"code":"NoSegment","message":"Could not find a matching segment for coordinate 1"}`, Route{}, ErrNoSegment},
		{"invalid query", 400, `{"code":"InvalidQuery","message":"Query string malformed close to position 28"}`, Route{}, ErrUpstream},
		{"not json", 502, `<html>bad gateway</html>`, Route{}, ErrUpstream},
		{"no routes", 200, `{"code":"Ok","routes":[]}`, Route{}, ErrUpstream},
		{"geojson geometry", 200, `{"code":"Ok","routes":[{"geometry":{"type":"LineString"},"distance":1,"duration":2}]}`, Route{}, ErrUpstream},
		{"empty geometry", 200, `{"code":"Ok","routes":[{"geometry":"","distance":1,"duration":2}]}`, Route{}, ErrUpstream},
		{"negative", 200, `{"code":"Ok","routes":[{"geometry":"a","distance":-1,"duration":2}]}`, Route{}, ErrUpstream},
		{"ok code but HTTP error", 500, `{"code":"Ok","routes":[{"geometry":"a","distance":1,"duration":2}]}`, Route{}, ErrUpstream},
	}
	for _, c := range cases {
		got, err := parse(c.status, []byte(c.body))
		if !errors.Is(err, c.err) || (c.err == nil && err != nil) {
			t.Errorf("%s: err = %v, want %v", c.name, err, c.err)
		}
		if got != c.want {
			t.Errorf("%s: route = %+v, want %+v", c.name, got, c.want)
		}
		if err != nil && strings.Contains(err.Error(), "position 28") {
			t.Errorf("%s: OSRM message passed on: %v", c.name, err)
		}
	}
}

func TestRouteAgainstFakeOSRM(t *testing.T) {
	var gotPath, gotQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath, gotQuery = r.URL.Path, r.URL.RawQuery
		w.Write([]byte(`{"code":"Ok","routes":[{"geometry":"xyz","distance":10,"duration":20}]}`))
	}))
	defer srv.Close()
	c, err := NewClient(srv.URL, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	r, err := c.Route(context.Background(), []Point{{1, 2}, {3, 4}, {5, 6}})
	if err != nil {
		t.Fatal(err)
	}
	if r.Geometry != "xyz" || r.DistanceM != 10 || r.DurationS != 20 {
		t.Fatalf("route %+v", r)
	}
	if gotPath != "/route/v1/driving/2.000000,1.000000;4.000000,3.000000;6.000000,5.000000" {
		t.Fatalf("path %s", gotPath)
	}
	if !strings.Contains(gotQuery, "overview=full") || !strings.Contains(gotQuery, "geometries=polyline6") {
		t.Fatalf("query %s", gotQuery)
	}
}

func TestRouteTimeoutAndErrorsHideCoordinates(t *testing.T) {
	block := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { <-block }))
	defer srv.Close()
	defer close(block)
	c, _ := NewClient(srv.URL, 50*time.Millisecond)
	_, err := c.Route(context.Background(), []Point{{50.123456, 7.654321}, {49, 11}})
	if !errors.Is(err, ErrUpstream) {
		t.Fatalf("err = %v, want ErrUpstream", err)
	}
	if strings.Contains(err.Error(), "50.123456") || strings.Contains(err.Error(), "7.654321") {
		t.Fatalf("error contains coordinates: %v", err)
	}

	// Connection refused: the url.Error would contain the URL.
	dead := httptest.NewServer(http.NotFoundHandler())
	dead.Close()
	c, _ = NewClient(dead.URL, time.Second)
	_, err = c.Route(context.Background(), []Point{{50.123456, 7.654321}, {49, 11}})
	if !errors.Is(err, ErrUpstream) || strings.Contains(err.Error(), "50.123456") {
		t.Fatalf("err = %v", err)
	}
}

func TestRouteResponseTooLarge(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(strings.Repeat(" ", MaxResponseBytes+10)))
	}))
	defer srv.Close()
	c, _ := NewClient(srv.URL, 5*time.Second)
	if _, err := c.Route(context.Background(), []Point{{1, 2}, {3, 4}}); !errors.Is(err, ErrUpstream) {
		t.Fatalf("err = %v", err)
	}
}
