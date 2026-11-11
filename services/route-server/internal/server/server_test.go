package server

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/route-server/internal/auth"
	"github.com/WayneNani/better-maps/services/route-server/internal/osrm"
)

const (
	tokenPixel  = "pixel-token-0123456789-abcdefghijklmnopq"
	tokenTablet = "tablet-token-0123456789-abcdefghijklmnop"
	bonnLat     = "50.7295477"
	bonnLon     = "7.1057999"
)

type fakeRouter struct {
	mu     sync.Mutex
	calls  [][]osrm.Point
	route  osrm.Route
	err    error
	block  chan struct{}
	inside chan struct{}
}

func (f *fakeRouter) Route(ctx context.Context, points []osrm.Point) (osrm.Route, error) {
	f.mu.Lock()
	f.calls = append(f.calls, points)
	f.mu.Unlock()
	if f.inside != nil {
		f.inside <- struct{}{}
	}
	if f.block != nil {
		select {
		case <-f.block:
		case <-ctx.Done():
			return osrm.Route{}, ctx.Err()
		}
	}
	return f.route, f.err
}

type clock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *clock) now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

func (c *clock) advance(d time.Duration) {
	c.mu.Lock()
	c.t = c.t.Add(d)
	c.mu.Unlock()
}

type env struct {
	srv    *Server
	h      http.Handler
	router *fakeRouter
	logs   *bytes.Buffer
	clock  *clock
}

func newEnv(t *testing.T, cfg Config) *env {
	t.Helper()
	tokens, err := auth.Parse(strings.NewReader(auth.HashLine("pixel", tokenPixel) + "\n" + auth.HashLine("tablet", tokenTablet)))
	if err != nil {
		t.Fatal(err)
	}
	logs := &bytes.Buffer{}
	logger := slog.New(slog.NewTextHandler(logs, nil))
	router := &fakeRouter{route: osrm.Route{Geometry: "_c`|@_c`|@", DistanceM: 410123.456, DurationS: 15000.04, DataVersion: "2026-11-08T21:21:00Z"}}
	c := &clock{t: time.Unix(1_800_000_000, 0)}
	srv := New(cfg, tokens, router, logger, c.now)
	return &env{srv: srv, h: srv.Handler(), router: router, logs: logs, clock: c}
}

const validBody = `{"points":[[` + bonnLat + `,` + bonnLon + `],[49.453861,11.0772885]]}`

func (e *env) do(method, path, token, contentType, body string, hdr ...string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	if contentType != "" {
		r.Header.Set("Content-Type", contentType)
	}
	for i := 0; i+1 < len(hdr); i += 2 {
		r.Header.Set(hdr[i], hdr[i+1])
	}
	w := httptest.NewRecorder()
	e.h.ServeHTTP(w, r)
	return w
}

func (e *env) route(token, body string) *httptest.ResponseRecorder {
	return e.do("POST", "/v1/route", token, "application/json", body)
}

func errorCode(t *testing.T, w *httptest.ResponseRecorder) string {
	t.Helper()
	var v map[string]string
	if err := json.Unmarshal(w.Body.Bytes(), &v); err != nil {
		t.Fatalf("error body %q: %v", w.Body.String(), err)
	}
	return v["error"]
}

func TestRouteOK(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	w := e.route(tokenPixel, validBody)
	if w.Code != 200 {
		t.Fatalf("status %d: %s", w.Code, w.Body)
	}
	var resp map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}
	want := map[string]any{"geometry": "_c`|@_c`|@", "precision": 6.0, "distance_m": 410123.5, "duration_s": 15000.0,
		"data_timestamp": "2026-11-08T21:21:00Z"}
	for k, v := range want {
		if resp[k] != v {
			t.Errorf("%s = %v, want %v", k, resp[k], v)
		}
	}
	if len(resp) != len(want) {
		t.Errorf("unexpected fields: %v", resp)
	}
	if w.Header().Get("Cache-Control") != "no-store" {
		t.Error("Cache-Control missing")
	}
	if len(e.router.calls) != 1 || e.router.calls[0][0] != (osrm.Point{Lat: 50.7295477, Lon: 7.1057999}) ||
		e.router.calls[0][1] != (osrm.Point{Lat: 49.453861, Lon: 11.0772885}) {
		t.Fatalf("router calls %v", e.router.calls)
	}
}

func TestRouteGzip(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	w := e.do("POST", "/v1/route", tokenPixel, "application/json", validBody, "Accept-Encoding", "br, gzip")
	if w.Code != 200 || w.Header().Get("Content-Encoding") != "gzip" {
		t.Fatalf("status %d, encoding %q", w.Code, w.Header().Get("Content-Encoding"))
	}
	gz, err := gzip.NewReader(w.Body)
	if err != nil {
		t.Fatal(err)
	}
	data, _ := io.ReadAll(gz)
	if !strings.Contains(string(data), `"geometry"`) {
		t.Fatalf("body %s", data)
	}
	w = e.do("POST", "/v1/route", tokenPixel, "application/json", validBody, "Accept-Encoding", "gzip;q=0")
	if w.Header().Get("Content-Encoding") != "" {
		t.Fatal("gzip despite q=0")
	}
	w = e.do("POST", "/v1/route", tokenPixel, "application/json", validBody, "Accept-Encoding", "gzip; q=0.5")
	if w.Header().Get("Content-Encoding") != "gzip" {
		t.Fatal("no gzip with q=0.5")
	}
}

func TestAuth(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	cases := []struct{ name, header string }{
		{"none", ""},
		{"wrong token", "Bearer " + strings.Repeat("x", 43)},
		{"basic", "Basic " + tokenPixel},
		{"token without scheme", tokenPixel},
		{"short", "Bearer abc"},
	}
	for _, c := range cases {
		r := httptest.NewRequest("POST", "/v1/route", strings.NewReader(validBody))
		r.Header.Set("Content-Type", "application/json")
		if c.header != "" {
			r.Header.Set("Authorization", c.header)
		}
		w := httptest.NewRecorder()
		e.h.ServeHTTP(w, r)
		if w.Code != 401 || errorCode(t, w) != "unauthorized" {
			t.Errorf("%s: status %d %s", c.name, w.Code, w.Body)
		}
		if !strings.HasPrefix(w.Header().Get("WWW-Authenticate"), "Bearer") {
			t.Errorf("%s: WWW-Authenticate missing", c.name)
		}
	}
	if len(e.router.calls) != 0 {
		t.Fatal("router called without a valid token")
	}
	// Unauthorized requests are not checked further: a bad body still gives 401.
	if w := e.route("", "garbage"); w.Code != 401 {
		t.Fatalf("status %d", w.Code)
	}
}

func TestLogsNeitherTokensNorCoordinates(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	e.route(tokenPixel, validBody)
	e.route(strings.Repeat("y", 43), validBody)
	e.router.err = osrm.ErrUpstream
	e.route(tokenTablet, validBody)
	e.do("GET", "/v1/route/"+bonnLat+","+bonnLon, tokenPixel, "", "")
	logs := e.logs.String()
	for _, secret := range []string{tokenPixel, tokenTablet, strings.Repeat("y", 43), bonnLat, bonnLon, "50.72", "7.10", "11.07"} {
		if strings.Contains(logs, secret) {
			t.Errorf("log contains %q:\n%s", secret, logs)
		}
	}
	for _, want := range []string{"device=pixel", "device=tablet", "status=200", "status=401", "status=502", "reason=auth", "path=other"} {
		if !strings.Contains(logs, want) {
			t.Errorf("log lacks %q:\n%s", want, logs)
		}
	}
}

func TestRateLimitPerToken(t *testing.T) {
	cfg := DefaultConfig()
	cfg.RatePerMinute = 6 // one per 10 s
	cfg.RateBurst = 3
	e := newEnv(t, cfg)
	for i := 0; i < 3; i++ {
		if w := e.route(tokenPixel, validBody); w.Code != 200 {
			t.Fatalf("burst request %d: %d", i, w.Code)
		}
	}
	w := e.route(tokenPixel, validBody)
	if w.Code != 429 || errorCode(t, w) != "rate_limited" || w.Header().Get("Retry-After") != "10" {
		t.Fatalf("status %d, Retry-After %q", w.Code, w.Header().Get("Retry-After"))
	}
	// The other device has its own budget.
	if w := e.route(tokenTablet, validBody); w.Code != 200 {
		t.Fatalf("other device: %d", w.Code)
	}
	e.clock.advance(10 * time.Second)
	if w := e.route(tokenPixel, validBody); w.Code != 200 {
		t.Fatalf("after refill: %d", w.Code)
	}
	if !strings.Contains(e.logs.String(), "reason=rate_limit") {
		t.Fatal("rate limit not logged")
	}
}

func TestDefaultRateLimitAllowsChargingPlanner(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	// A route and ~40 detours of the charging planner at once, then a reroute.
	for i := 0; i < 42; i++ {
		if w := e.route(tokenPixel, validBody); w.Code != 200 {
			t.Fatalf("request %d: %d", i, w.Code)
		}
	}
}

func TestUnauthorizedFloodIsThrottled(t *testing.T) {
	cfg := DefaultConfig()
	cfg.UnauthorizedPerSecond = 1
	cfg.UnauthorizedBurst = 2
	e := newEnv(t, cfg)
	bad := strings.Repeat("z", 43)
	for i := 0; i < 2; i++ {
		if w := e.route(bad, validBody); w.Code != 401 {
			t.Fatalf("attempt %d: %d", i, w.Code)
		}
	}
	if w := e.route(bad, validBody); w.Code != 429 {
		t.Fatalf("flood: %d", w.Code)
	}
	e.clock.advance(time.Second)
	// Valid tokens refund their probe, so they are never locked out by their own requests.
	for i := 0; i < 5; i++ {
		if w := e.route(tokenPixel, validBody); w.Code != 200 {
			t.Fatalf("valid token %d: %d", i, w.Code)
		}
	}
}

func TestInputValidation(t *testing.T) {
	cfg := DefaultConfig()
	cfg.MaxPoints = 3
	cfg.MaxRequestBytes = 200
	e := newEnv(t, cfg)
	cases := []struct {
		name, contentType, body string
		status                  int
		code                    string
	}{
		{"no content type", "", validBody, 415, "unsupported_media_type"},
		{"form", "application/x-www-form-urlencoded", validBody, 415, "unsupported_media_type"},
		{"json with charset", "application/json; charset=utf-8", validBody, 200, ""},
		{"not json", "application/json", "points", 400, "bad_request"},
		{"trailing data", "application/json", validBody + "{}", 400, "bad_request"},
		{"unknown field", "application/json", `{"points":[[1,2],[3,4]],"profile":"foot"}`, 400, "bad_request"},
		{"one point", "application/json", `{"points":[[1,2]]}`, 400, "point_count"},
		{"no points", "application/json", `{}`, 400, "point_count"},
		{"too many points", "application/json", `{"points":[[1,2],[1,2],[1,2],[1,2]]}`, 400, "point_count"},
		{"three values", "application/json", `{"points":[[1,2,3],[1,2]]}`, 400, "bad_point"},
		{"lat out of range", "application/json", `{"points":[[91,2],[1,2]]}`, 400, "bad_point"},
		{"lon out of range", "application/json", `{"points":[[1,2],[1,-180.5]]}`, 400, "bad_point"},
		{"string", "application/json", `{"points":[["1","2"],[1,2]]}`, 400, "bad_request"},
		{"too large", "application/json", `{"points":[[1,2],[1,2]]` + strings.Repeat(" ", 300) + `}`, 413, "too_large"},
	}
	for _, c := range cases {
		w := e.do("POST", "/v1/route", tokenPixel, c.contentType, c.body)
		if w.Code != c.status {
			t.Errorf("%s: status %d, want %d (%s)", c.name, w.Code, c.status, w.Body)
			continue
		}
		if c.code != "" && errorCode(t, w) != c.code {
			t.Errorf("%s: code %q, want %q", c.name, errorCode(t, w), c.code)
		}
	}
}

func TestBodyLargerThanContentLength(t *testing.T) {
	cfg := DefaultConfig()
	cfg.MaxRequestBytes = 100
	e := newEnv(t, cfg)
	r := httptest.NewRequest("POST", "/v1/route", strings.NewReader(`{"points":[[1,2],[1,2]]`+strings.Repeat(" ", 200)+`}`))
	r.ContentLength = -1 // chunked
	r.Header.Set("Authorization", "Bearer "+tokenPixel)
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	e.h.ServeHTTP(w, r)
	if w.Code != 413 {
		t.Fatalf("status %d", w.Code)
	}
}

func TestBackendErrors(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	cases := []struct {
		err    error
		status int
		code   string
	}{
		{osrm.ErrNoRoute, 404, "no_route"},
		{osrm.ErrNoSegment, 422, "no_segment"},
		{osrm.ErrUpstream, 502, "backend_error"},
		{context.DeadlineExceeded, 502, "backend_error"},
	}
	for _, c := range cases {
		e.router.err = c.err
		w := e.route(tokenPixel, validBody)
		if w.Code != c.status || errorCode(t, w) != c.code {
			t.Errorf("%v: status %d %s", c.err, w.Code, w.Body)
		}
	}
}

func TestMethodsAndPaths(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	if w := e.do("GET", "/v1/route", tokenPixel, "", ""); w.Code != 405 || w.Header().Get("Allow") != "POST" {
		t.Errorf("GET /v1/route: %d", w.Code)
	}
	for _, p := range []string{"/route/v1/driving/7,50;11,49", "/table/v1/driving/7,50;11,49", "/v1/table", "/"} {
		if w := e.do("GET", p, tokenPixel, "", ""); w.Code != 404 {
			t.Errorf("%s: %d", p, w.Code)
		}
	}
	w := e.do("GET", "/healthz", "", "", "")
	if w.Code != 200 || w.Body.String() != "ok\n" {
		t.Errorf("healthz: %d %q", w.Code, w.Body)
	}
	if w := e.do("POST", "/healthz", "", "", ""); w.Code != 405 {
		t.Errorf("POST healthz: %d", w.Code)
	}
	if len(e.router.calls) != 0 {
		t.Fatal("router called")
	}
}

func TestConcurrencyLimit(t *testing.T) {
	cfg := DefaultConfig()
	cfg.MaxConcurrent = 1
	cfg.QueueTimeout = 50 * time.Millisecond
	e := newEnv(t, cfg)
	e.router.block = make(chan struct{})
	e.router.inside = make(chan struct{}, 1)
	done := make(chan int)
	go func() { done <- e.route(tokenPixel, validBody).Code }()
	<-e.router.inside
	w := e.route(tokenTablet, validBody)
	if w.Code != 503 || errorCode(t, w) != "overloaded" {
		t.Fatalf("second request: %d %s", w.Code, w.Body)
	}
	close(e.router.block)
	if code := <-done; code != 200 {
		t.Fatalf("first request: %d", code)
	}
	e.router.inside = nil
	if w := e.route(tokenTablet, validBody); w.Code != 200 {
		t.Fatalf("after release: %d", w.Code)
	}
}

func TestPrune(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	e.route(tokenPixel, validBody)
	if e.srv.perTok.Len() != 1 {
		t.Fatal("no bucket")
	}
	e.clock.advance(time.Hour)
	e.srv.Prune()
	if e.srv.perTok.Len() != 0 {
		t.Fatal("bucket not pruned")
	}
}

func TestAgainstFakeOSRM(t *testing.T) {
	var gotPath string
	fake := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		io.WriteString(w, `{"code":"Ok","data_version":"2026-11-08T21:21:00Z","routes":[{"geometry":"abc","distance":1000.04,"duration":60.06}]}`)
	}))
	defer fake.Close()
	client, err := osrm.NewClient(fake.URL, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	tokens, _ := auth.Parse(strings.NewReader(auth.HashLine("pixel", tokenPixel)))
	srv := New(DefaultConfig(), tokens, client, slog.New(slog.NewTextHandler(io.Discard, nil)), nil)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	req, _ := http.NewRequest("POST", ts.URL+"/v1/route", strings.NewReader(validBody))
	req.Header.Set("Authorization", "Bearer "+tokenPixel)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req) // transparently gunzips
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	want := `{"geometry":"abc","precision":6,"distance_m":1000,"duration_s":60.1,"data_timestamp":"2026-11-08T21:21:00Z"}`
	if resp.StatusCode != 200 || strings.TrimSpace(string(body)) != want {
		t.Fatalf("status %d body %s", resp.StatusCode, body)
	}
	if gotPath != "/route/v1/driving/7.105800,50.729548;11.077288,49.453861" {
		t.Fatalf("OSRM path %s", gotPath)
	}
}
