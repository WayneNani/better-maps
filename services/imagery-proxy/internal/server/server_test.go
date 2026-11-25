package server

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/imagery-proxy/internal/auth"
	"github.com/WayneNani/better-maps/services/imagery-proxy/internal/sources"
	"github.com/WayneNani/better-maps/services/imagery-proxy/internal/tilecache"
	"github.com/WayneNani/better-maps/services/imagery-proxy/internal/upstream"
)

const (
	tokenPixel  = "pixel-token-0123456789-abcdefghijklmnopq"
	tokenTablet = "tablet-token-0123456789-abcdefghijklmnop"
)

var jpeg = []byte("\xff\xd8\xff\xe0fake-jpeg")

type fakeTiles struct {
	mu     sync.Mutex
	calls  []tilecache.Key
	data   []byte
	err    error
	block  chan struct{}
	inside chan struct{}
}

func (f *fakeTiles) Tile(ctx context.Context, z, x, y int) ([]byte, error) {
	f.mu.Lock()
	f.calls = append(f.calls, tilecache.Key{Z: z, X: x, Y: y})
	f.mu.Unlock()
	if f.inside != nil {
		f.inside <- struct{}{}
	}
	if f.block != nil {
		select {
		case <-f.block:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	return f.data, f.err
}

func (f *fakeTiles) count() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.calls)
}

type memCache struct {
	mu   sync.Mutex
	m    map[tilecache.Key][]byte
	puts int
}

func (c *memCache) Get(k tilecache.Key) ([]byte, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	d, ok := c.m[k]
	return d, ok
}

func (c *memCache) Put(k tilecache.Key, d []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.m[k] = d
	c.puts++
	return nil
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

const testSources = `{"sources": [
  {"id": "s2", "name": "Sentinel-2", "attribution": "S2 by EOX", "license": "CC BY 4.0", "license_url": "https://cc/by", "min_zoom": 0},
  {"id": "nw", "name": "NRW", "attribution": "GeoBasis NRW ({year})", "license": "dl-de/zero-2.0", "min_zoom": 12,
   "coverage": "nw.geojson"}
]}`

// A box roughly around NRW.
const nwGeoJSON = `{"type": "FeatureCollection", "features": [{"type": "Feature", "properties": {},
  "geometry": {"type": "Polygon", "coordinates": [[[5.8, 50.3], [9.5, 50.3], [9.5, 52.5], [5.8, 52.5], [5.8, 50.3]]]}}]}`

type env struct {
	srv   *Server
	tiles *fakeTiles
	cache *memCache
	logs  *bytes.Buffer
	clock *clock
}

func newEnv(t *testing.T, cfg Config) *env {
	t.Helper()
	tokens, err := auth.Parse(strings.NewReader(auth.HashLine("pixel", tokenPixel) + "\n" + auth.HashLine("tablet", tokenTablet)))
	if err != nil {
		t.Fatal(err)
	}
	srcs, err := sources.Parse(strings.NewReader(testSources), func(string) (io.ReadCloser, error) {
		return io.NopCloser(strings.NewReader(nwGeoJSON)), nil
	})
	if err != nil {
		t.Fatal(err)
	}
	logs := &bytes.Buffer{}
	logger := slog.New(slog.NewTextHandler(logs, &slog.HandlerOptions{Level: slog.LevelDebug}))
	tiles := &fakeTiles{data: jpeg}
	cache := &memCache{m: map[tilecache.Key][]byte{}}
	c := &clock{t: time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)}
	return &env{srv: New(cfg, tokens, tiles, cache, srcs, logger, c.now), tiles: tiles, cache: cache, logs: logs, clock: c}
}

func (e *env) get(path, token string) *httptest.ResponseRecorder {
	r := httptest.NewRequest("GET", path, nil)
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	e.srv.Handler().ServeHTTP(w, r)
	return w
}

func errorCode(t *testing.T, w *httptest.ResponseRecorder) string {
	t.Helper()
	var v map[string]string
	if err := json.Unmarshal(w.Body.Bytes(), &v); err != nil {
		t.Fatalf("error body %q: %v", w.Body.String(), err)
	}
	return v["error"]
}

func TestTileFetchedAndCached(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	w := e.get("/v1/tiles/14/8508/5489.jpg", tokenPixel)
	if w.Code != 200 || !bytes.Equal(w.Body.Bytes(), jpeg) {
		t.Fatalf("status %d body %q", w.Code, w.Body)
	}
	if ct := w.Header().Get("Content-Type"); ct != "image/jpeg" {
		t.Errorf("content type %q", ct)
	}
	if cc := w.Header().Get("Cache-Control"); cc != "private, max-age=604800" {
		t.Errorf("cache control %q", cc)
	}
	if e.tiles.count() != 1 || e.tiles.calls[0] != (tilecache.Key{Z: 14, X: 8508, Y: 5489}) || e.cache.puts != 1 {
		t.Fatalf("calls %v puts %d", e.tiles.calls, e.cache.puts)
	}
	// Second request from the cache.
	w = e.get("/v1/tiles/14/8508/5489.jpg", tokenTablet)
	if w.Code != 200 || e.tiles.count() != 1 {
		t.Fatalf("status %d, upstream calls %d", w.Code, e.tiles.count())
	}
}

func TestHead(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	r := httptest.NewRequest("HEAD", "/v1/tiles/3/1/2.jpg", nil)
	r.Header.Set("Authorization", "Bearer "+tokenPixel)
	w := httptest.NewRecorder()
	e.srv.Handler().ServeHTTP(w, r)
	if w.Code != 200 || w.Body.Len() != 0 || w.Header().Get("Content-Length") != "13" {
		t.Fatalf("status %d len %d cl %q", w.Code, w.Body.Len(), w.Header().Get("Content-Length"))
	}
}

func TestNoCache(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	e.srv.cache = nil
	for range 2 {
		if w := e.get("/v1/tiles/1/1/1.jpg", tokenPixel); w.Code != 200 {
			t.Fatal(w.Code)
		}
	}
	if e.tiles.count() != 2 {
		t.Fatal(e.tiles.count())
	}
}

func TestAuth(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	for _, header := range []string{"", "Bearer " + strings.Repeat("x", 43), "Basic " + tokenPixel, tokenPixel, "Bearer abc"} {
		for _, path := range []string{"/v1/tiles/1/0/0.jpg", "/v1/attribution"} {
			r := httptest.NewRequest("GET", path, nil)
			if header != "" {
				r.Header.Set("Authorization", header)
			}
			w := httptest.NewRecorder()
			e.srv.Handler().ServeHTTP(w, r)
			if w.Code != 401 || errorCode(t, w) != "unauthorized" || !strings.HasPrefix(w.Header().Get("WWW-Authenticate"), "Bearer") {
				t.Errorf("%q %s: status %d", header, path, w.Code)
			}
		}
	}
	if e.tiles.count() != 0 {
		t.Fatal("upstream called without a token")
	}
}

func TestBadTilePaths(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	for _, p := range []string{
		"/v1/tiles/1/2/0.jpg", "/v1/tiles/1/0/2.jpg", "/v1/tiles/20/0/0.jpg", "/v1/tiles/-1/0/0.jpg",
		"/v1/tiles/1/0/0.png", "/v1/tiles/1/0.jpg", "/v1/tiles/1/0/0/0.jpg", "/v1/tiles/a/0/0.jpg",
		"/v1/tiles/1/+0/0.jpg", "/v1/tiles/", "/v1/tiles/1/0/12345678.jpg",
	} {
		if w := e.get(p, tokenPixel); w.Code != 404 {
			t.Errorf("%s: status %d", p, w.Code)
		}
	}
	// ServeMux redirects unclean paths before any handler runs.
	if w := e.get("/v1/tiles/1//0.jpg", tokenPixel); w.Code == 200 {
		t.Error("unclean path served")
	}
	if e.tiles.count() != 0 {
		t.Fatal("upstream called for bad paths")
	}
	if w := e.get("/route/v1/driving", tokenPixel); w.Code != 404 {
		t.Fatal(w.Code)
	}
}

func TestParseTilePath(t *testing.T) {
	k, ok := ParseTilePath("/v1/tiles/19/524287/0.jpg", 0, 19)
	if !ok || k != (tilecache.Key{Z: 19, X: 524287, Y: 0}) {
		t.Fatal(k, ok)
	}
	if _, ok := ParseTilePath("/v1/tiles/2/0/0.jpg", 3, 19); ok {
		t.Fatal("below min zoom accepted")
	}
	if _, ok := ParseTilePath("/v1/tiles/0/0/0.jpg", 0, 19); !ok {
		t.Fatal("z0 rejected")
	}
}

func TestMethod(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	r := httptest.NewRequest("POST", "/v1/tiles/1/0/0.jpg", nil)
	r.Header.Set("Authorization", "Bearer "+tokenPixel)
	w := httptest.NewRecorder()
	e.srv.Handler().ServeHTTP(w, r)
	if w.Code != 405 || w.Header().Get("Allow") != "GET, HEAD" {
		t.Fatal(w.Code)
	}
	r = httptest.NewRequest("DELETE", "/healthz", nil)
	w = httptest.NewRecorder()
	e.srv.Handler().ServeHTTP(w, r)
	if w.Code != 405 {
		t.Fatal(w.Code)
	}
}

func TestHealthz(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	w := e.get("/healthz", "")
	if w.Code != 200 || w.Body.String() != "ok\n" {
		t.Fatal(w.Code, w.Body)
	}
	if e.logs.Len() != 0 {
		t.Fatal("healthz logged")
	}
}

func TestRateLimit(t *testing.T) {
	cfg := DefaultConfig()
	cfg.RatePerMinute = 60
	cfg.RateBurst = 3
	e := newEnv(t, cfg)
	for i := range 3 {
		if w := e.get("/v1/tiles/1/0/0.jpg", tokenPixel); w.Code != 200 {
			t.Fatalf("request %d: %d", i, w.Code)
		}
	}
	w := e.get("/v1/tiles/1/0/0.jpg", tokenPixel)
	if w.Code != 429 || w.Header().Get("Retry-After") != "1" || errorCode(t, w) != "rate_limited" {
		t.Fatalf("status %d retry %q", w.Code, w.Header().Get("Retry-After"))
	}
	// Other device unaffected.
	if w := e.get("/v1/tiles/1/0/0.jpg", tokenTablet); w.Code != 200 {
		t.Fatal(w.Code)
	}
	e.clock.advance(time.Second)
	if w := e.get("/v1/tiles/1/0/0.jpg", tokenPixel); w.Code != 200 {
		t.Fatal(w.Code)
	}
	e.clock.advance(time.Hour)
	e.srv.Prune()
	if e.srv.perTok.Len() != 0 {
		t.Fatal("buckets not pruned")
	}
}

func TestUnauthorizedFlood(t *testing.T) {
	cfg := DefaultConfig()
	cfg.UnauthorizedPerSecond = 1
	cfg.UnauthorizedBurst = 2
	e := newEnv(t, cfg)
	wrong := strings.Repeat("y", 43)
	e.get("/v1/tiles/1/0/0.jpg", wrong)
	e.get("/v1/tiles/1/0/0.jpg", wrong)
	if w := e.get("/v1/tiles/1/0/0.jpg", wrong); w.Code != 429 {
		t.Fatal(w.Code)
	}
	// Valid tokens still work: a valid lookup refunds its probe.
	e.clock.advance(time.Second)
	for range 5 {
		if w := e.get("/v1/tiles/1/0/0.jpg", tokenPixel); w.Code != 200 {
			t.Fatal(w.Code)
		}
	}
}

func TestBackendErrors(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	e.tiles.err = errors.New("upstream: status 500")
	w := e.get("/v1/tiles/2/1/1.jpg", tokenPixel)
	if w.Code != 502 || errorCode(t, w) != "backend_error" {
		t.Fatal(w.Code)
	}
	if e.cache.puts != 0 {
		t.Fatal("error cached")
	}
	e.tiles.err = upstream.ErrNotFound
	if w := e.get("/v1/tiles/2/1/1.jpg", tokenPixel); w.Code != 404 {
		t.Fatal(w.Code)
	}
}

func TestConcurrentRequestsShareOneRendering(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	e.tiles.block = make(chan struct{})
	e.tiles.inside = make(chan struct{}, 10)
	var wg sync.WaitGroup
	codes := make([]int, 5)
	for i := range codes {
		wg.Add(1)
		go func() {
			defer wg.Done()
			codes[i] = e.get("/v1/tiles/5/3/4.jpg", tokenPixel).Code
		}()
	}
	<-e.tiles.inside
	time.Sleep(50 * time.Millisecond) // let the others join the flight
	close(e.tiles.block)
	wg.Wait()
	for _, c := range codes {
		if c != 200 {
			t.Fatal(codes)
		}
	}
	if n := e.tiles.count(); n > 2 {
		t.Fatalf("%d renderings for one tile", n)
	}
}

func TestOverloaded(t *testing.T) {
	cfg := DefaultConfig()
	cfg.MaxConcurrent = 1
	cfg.QueueTimeout = 20 * time.Millisecond
	e := newEnv(t, cfg)
	e.tiles.block = make(chan struct{})
	e.tiles.inside = make(chan struct{}, 10)
	done := make(chan int)
	go func() { done <- e.get("/v1/tiles/5/3/4.jpg", tokenPixel).Code }()
	<-e.tiles.inside
	w := e.get("/v1/tiles/5/3/5.jpg", tokenPixel)
	if w.Code != 503 || w.Header().Get("Retry-After") != "2" {
		t.Fatal(w.Code)
	}
	close(e.tiles.block)
	if c := <-done; c != 200 {
		t.Fatal(c)
	}
}

type attributionBody struct {
	Sources []sources.Attribution `json:"sources"`
}

func (e *env) attribution(t *testing.T, query string) []string {
	t.Helper()
	w := e.get("/v1/attribution"+query, tokenPixel)
	if w.Code != 200 {
		t.Fatalf("%s: status %d %s", query, w.Code, w.Body)
	}
	var b attributionBody
	if err := json.Unmarshal(w.Body.Bytes(), &b); err != nil {
		t.Fatal(err)
	}
	var ids []string
	for _, s := range b.Sources {
		ids = append(ids, s.ID+":"+s.Attribution)
	}
	return ids
}

func TestAttribution(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	all := strings.Join(e.attribution(t, ""), "|")
	if all != "nw:GeoBasis NRW (2026)|s2:S2 by EOX" {
		t.Fatal(all)
	}
	// Bonn, zoomed in: only NRW.
	if got := strings.Join(e.attribution(t, "?bbox=7.0,50.7,7.2,50.8&z=15"), "|"); got != "nw:GeoBasis NRW (2026)" {
		t.Fatal(got)
	}
	// Bonn, zoomed out: NRW is not used below z12.
	if got := strings.Join(e.attribution(t, "?bbox=7.0,50.7,7.2,50.8&z=8"), "|"); got != "s2:S2 by EOX" {
		t.Fatal(got)
	}
	// Across the border to Belgium: both.
	if got := strings.Join(e.attribution(t, "?bbox=5.5,50.5,6.2,50.9&z=13"), "|"); got != "nw:GeoBasis NRW (2026)|s2:S2 by EOX" {
		t.Fatal(got)
	}
	// Paris.
	if got := strings.Join(e.attribution(t, "?bbox=2.2,48.8,2.4,48.9&z=14"), "|"); got != "s2:S2 by EOX" {
		t.Fatal(got)
	}
	for _, q := range []string{"?bbox=1,2,3", "?bbox=7.2,50.7,7.0,50.8", "?bbox=7,50,8,51&z=x", "?bbox=7,50,8,51&z=99"} {
		if w := e.get("/v1/attribution"+q, tokenPixel); w.Code != 400 {
			t.Errorf("%s: %d", q, w.Code)
		}
	}
}

func TestLogsWithoutCoordinatesOrTokens(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	e.get("/v1/tiles/14/8508/5489.jpg", tokenPixel)
	e.get("/v1/tiles/14/8508/5490.jpg", "wrong-token-0123456789abcdef")
	e.get("/v1/attribution?bbox=7.0123,50.7123,7.2,50.8&z=15", tokenPixel)
	e.get("/v1/tiles/99/8508/5489.jpg", tokenPixel)
	logs := e.logs.String()
	for _, secret := range []string{"8508", "5489", "5490", "7.0123", "50.7123", tokenPixel, "wrong-token"} {
		if strings.Contains(logs, secret) {
			t.Errorf("log contains %q:\n%s", secret, logs)
		}
	}
	for _, want := range []string{"path=tile", "path=attribution", "device=pixel", "cache=miss", "reason=auth", "reason=bad_tile"} {
		if !strings.Contains(logs, want) {
			t.Errorf("log lacks %q:\n%s", want, logs)
		}
	}
}

func TestSuccessfulTilesOnlyAtDebug(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	e.srv.log = slog.New(slog.NewTextHandler(e.logs, nil))
	e.get("/v1/tiles/1/0/0.jpg", tokenPixel)
	if e.logs.Len() != 0 {
		t.Fatalf("logged at info: %s", e.logs)
	}
}
