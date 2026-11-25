package server

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/indoor-server/internal/auth"
	"github.com/WayneNani/better-maps/services/indoor-server/internal/dataset"
	"github.com/WayneNani/better-maps/services/indoor-server/internal/venues"
)

const (
	tokenPixel  = "pixel-token-0123456789-abcdefghijklmnopq"
	tokenTablet = "tablet-token-0123456789-abcdefghijklmnop"
)

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

const docT1 = `{"id":"w38212384","name":"Terminal 1","kind":"airport","features":[{"id":"n1","t":"p","k":"toilets","lv":[0],"g":"_c`

// testData writes a data directory: Terminal 1 (Frankfurt), Köln Hbf and a small venue.
func testData(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	os.MkdirAll(filepath.Join(dir, "venues"), 0o755)
	idx := venues.Index{DataTimestamp: "2026-11-24T21:21:02Z", Venues: []venues.IndexEntry{
		{ID: "w38212384", Name: "Terminal 1", Kind: "airport", BBox: [4]float64{8.5612, 50.0459, 8.5779, 50.0532},
			Levels: []venues.Level{{Level: -1, Label: "-1"}, {Level: 0, Label: "0"}}, Features: 4211},
		{ID: "r2", Name: "Köln Hbf", Kind: "station", BBox: [4]float64{6.955, 50.940, 6.962, 50.945},
			Levels: []venues.Level{{Level: 0, Label: "0"}}, Features: 900},
		{ID: "cw3", Kind: "other", BBox: [4]float64{8.57, 50.05, 8.571, 50.051}, Features: 12},
	}}
	b, _, _ := venues.Marshal(idx)
	os.WriteFile(filepath.Join(dir, "index.json"), b, 0o644)
	docs := map[string]string{"w38212384": docT1 + strings.Repeat("x", 3000) + `"}]}`, "r2": `{"id":"r2"}`, "cw3": `{"id":"cw3"}`}
	for id, d := range docs {
		var buf bytes.Buffer
		zw, _ := gzip.NewWriterLevel(&buf, gzip.BestCompression)
		zw.Write([]byte(d))
		zw.Close()
		os.WriteFile(filepath.Join(dir, "venues", id+".json.gz"), buf.Bytes(), 0o644)
	}
	return dir
}

type env struct {
	srv   *Server
	logs  *bytes.Buffer
	clock *clock
	dir   string
}

func newEnv(t *testing.T, cfg Config) *env {
	t.Helper()
	tokens, err := auth.Parse(strings.NewReader(auth.HashLine("pixel", tokenPixel) + "\n" + auth.HashLine("tablet", tokenTablet)))
	if err != nil {
		t.Fatal(err)
	}
	dir := testData(t)
	ds, err := dataset.Load(dir)
	if err != nil {
		t.Fatal(err)
	}
	logs := &bytes.Buffer{}
	logger := slog.New(slog.NewTextHandler(logs, &slog.HandlerOptions{Level: slog.LevelDebug}))
	c := &clock{t: time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)}
	return &env{srv: New(cfg, tokens, ds, logger, c.now), logs: logs, clock: c, dir: dir}
}

func (e *env) do(method, path, token string, headers ...string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, nil)
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	for i := 0; i+1 < len(headers); i += 2 {
		r.Header.Add(headers[i], headers[i+1])
	}
	w := httptest.NewRecorder()
	e.srv.Handler().ServeHTTP(w, r)
	return w
}

func (e *env) get(path, token string, headers ...string) *httptest.ResponseRecorder {
	return e.do("GET", path, token, headers...)
}

func errorCode(t *testing.T, w *httptest.ResponseRecorder) string {
	t.Helper()
	var v map[string]string
	if err := json.Unmarshal(w.Body.Bytes(), &v); err != nil {
		t.Fatalf("error body %q: %v", w.Body.String(), err)
	}
	return v["error"]
}

type listBody struct {
	DataTimestamp string `json:"data_timestamp"`
	Venues        []struct {
		ID           string         `json:"id"`
		Name         string         `json:"name"`
		Kind         string         `json:"kind"`
		BBox         [4]float64     `json:"bbox"`
		Levels       []venues.Level `json:"levels"`
		DefaultLevel *float64       `json:"default_level"`
		Features     int            `json:"features"`
		ETag         string         `json:"etag"`
	} `json:"venues"`
}

func TestListFrankfurt(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	w := e.get("/v1/venues?bbox=8.50,50.00,8.65,50.08", tokenPixel)
	if w.Code != 200 || w.Header().Get("Content-Type") != "application/json" ||
		w.Header().Get("Cache-Control") != "private, max-age=3600" || w.Header().Get("ETag") == "" ||
		w.Header().Get("Content-Encoding") != "" || w.Header().Get("Vary") != "Accept-Encoding" {
		t.Fatalf("status %d headers %v", w.Code, w.Header())
	}
	var b listBody
	if err := json.Unmarshal(w.Body.Bytes(), &b); err != nil {
		t.Fatal(err)
	}
	if b.DataTimestamp != "2026-11-24T21:21:02Z" || len(b.Venues) != 2 || b.Venues[0].ID != "w38212384" || b.Venues[1].ID != "cw3" {
		t.Fatalf("%+v", b)
	}
	v := b.Venues[0]
	if v.Name != "Terminal 1" || v.Kind != "airport" || v.Features != 4211 || len(v.Levels) != 2 || v.DefaultLevel == nil ||
		v.BBox != [4]float64{8.5612, 50.0459, 8.5779, 50.0532} {
		t.Fatalf("%+v", v)
	}
	// The listed etag is the one the document is served with.
	doc := e.get("/v1/venues/w38212384", tokenPixel)
	if doc.Header().Get("ETag") != v.ETag || v.ETag == "" {
		t.Fatalf("etag %q vs %q", v.ETag, doc.Header().Get("ETag"))
	}
	if !strings.Contains(w.Body.String(), `"default_level":0`) || strings.Contains(w.Body.String(), `"name":""`) {
		t.Fatal(w.Body.String())
	}
	// Köln.
	w = e.get("/v1/venues?bbox=6.94,50.93,6.97,50.95", tokenPixel)
	json.Unmarshal(w.Body.Bytes(), &b)
	if len(b.Venues) != 1 || b.Venues[0].ID != "r2" {
		t.Fatalf("%+v", b)
	}
	// Nothing there: empty list, not null.
	w = e.get("/v1/venues?bbox=10,50,10.1,50.1", tokenPixel)
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"venues":[]`) {
		t.Fatal(w.Body.String())
	}
}

func TestListGzipAndETag(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	plain := e.get("/v1/venues?bbox=8.50,50.00,8.65,50.08", tokenPixel)
	gz := e.get("/v1/venues?bbox=8.50,50.00,8.65,50.08", tokenPixel, "Accept-Encoding", "br, gzip")
	if gz.Header().Get("Content-Encoding") != "gzip" || gz.Header().Get("ETag") != plain.Header().Get("ETag") {
		t.Fatal(gz.Header())
	}
	zr, err := gzip.NewReader(gz.Body)
	if err != nil {
		t.Fatal(err)
	}
	if b, _ := io.ReadAll(zr); !bytes.Equal(b, plain.Body.Bytes()) {
		t.Fatal("gzip body differs")
	}
	if gz.Header().Get("Content-Length") == "" {
		t.Fatal("no content length")
	}
	etag := plain.Header().Get("ETag")
	nm := e.get("/v1/venues?bbox=8.50,50.00,8.65,50.08", tokenPixel, "If-None-Match", `"other", W/`+etag)
	if nm.Code != 304 || nm.Body.Len() != 0 || nm.Header().Get("ETag") != etag {
		t.Fatal(nm.Code)
	}
	// Other box, other etag.
	if w := e.get("/v1/venues?bbox=8.50,50.00,8.565,50.08", tokenPixel, "If-None-Match", etag); w.Code != 200 {
		t.Fatal(w.Code)
	}
	// q=0 refuses gzip.
	if w := e.get("/v1/venues?bbox=8.50,50.00,8.65,50.08", tokenPixel, "Accept-Encoding", "gzip;q=0"); w.Header().Get("Content-Encoding") != "" {
		t.Fatal("gzip despite q=0")
	}
}

func TestBadBBox(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	for _, q := range []string{
		"", "?bbox=", "?bbox=1,2,3", "?bbox=1,2,3,4,5", "?bbox=a,50,8,51", "?bbox=8.6,50,8.5,50.1", "?bbox=8.5,50.1,8.6,50",
		"?bbox=8,50,9.01,50.5", "?bbox=8,50,8.5,51.2", "?bbox=-181,0,-180.5,0.5", "?bbox=0,89.5,0.5,91", "?bbox=NaN,50,8,50.5",
		"?bbox=8,50,Inf,50.5", "?bbox=8,50,8.1,50.1&bbox=8,50,8.1,50.1", "?bbx=8,50,8.1,50.1",
	} {
		w := e.get("/v1/venues"+q, tokenPixel)
		if w.Code != 400 || errorCode(t, w) != "bad_request" {
			t.Errorf("%s: status %d", q, w.Code)
		}
	}
	for _, ok := range []string{"8,50,9,51", "8, 50, 8.1, 50.1", "-180,-90,-179,-89", "8,50,8,50"} {
		if _, valid := ParseBBox(ok); !valid {
			t.Errorf("%s rejected", ok)
		}
	}
}

func TestListLimit(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	dir := t.TempDir()
	os.MkdirAll(filepath.Join(dir, "venues"), 0o755)
	idx := venues.Index{DataTimestamp: "x"}
	for i := 0; i < 150; i++ {
		id := fmt.Sprintf("w%d", 1000+i)
		idx.Venues = append(idx.Venues, venues.IndexEntry{ID: id, Kind: "other", BBox: [4]float64{8, 50, 8.01, 50.01}, Features: i})
		_, gz, _ := venues.Marshal(map[string]string{"id": id})
		os.WriteFile(filepath.Join(dir, "venues", id+".json.gz"), gz, 0o644)
	}
	b, _, _ := venues.Marshal(idx)
	os.WriteFile(filepath.Join(dir, "index.json"), b, 0o644)
	ds, err := dataset.Load(dir)
	if err != nil {
		t.Fatal(err)
	}
	e.srv.SetData(ds)
	var body listBody
	json.Unmarshal(e.get("/v1/venues?bbox=7.9,49.9,8.1,50.1", tokenPixel).Body.Bytes(), &body)
	if len(body.Venues) != MaxVenues || body.Venues[0].Features != 149 || body.Venues[99].Features != 50 {
		t.Fatalf("%d venues, first %d", len(body.Venues), body.Venues[0].Features)
	}
}

func TestVenueDocument(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	plain := e.get("/v1/venues/w38212384", tokenPixel)
	if plain.Code != 200 || plain.Header().Get("Content-Encoding") != "" || !strings.HasPrefix(plain.Body.String(), docT1) ||
		plain.Header().Get("Cache-Control") != "private, max-age=86400" || plain.Header().Get("Vary") != "Accept-Encoding" ||
		plain.Header().Get("Content-Type") != "application/json" {
		t.Fatalf("%d %v", plain.Code, plain.Header())
	}
	gz := e.get("/v1/venues/w38212384", tokenPixel, "Accept-Encoding", "gzip, deflate")
	if gz.Code != 200 || gz.Header().Get("Content-Encoding") != "gzip" || gz.Body.Len() >= plain.Body.Len() {
		t.Fatalf("%d %v", gz.Code, gz.Header())
	}
	stored, _ := os.ReadFile(filepath.Join(e.dir, "venues", "w38212384.json.gz"))
	if !bytes.Equal(gz.Body.Bytes(), stored) {
		t.Fatal("gzip not served as stored")
	}
	if gz.Header().Get("Content-Length") != itoa(len(stored)) || plain.Header().Get("Content-Length") != itoa(plain.Body.Len()) {
		t.Fatal("content length")
	}
	etag := plain.Header().Get("ETag")
	if gz.Header().Get("ETag") != etag || !strings.HasPrefix(etag, `"`) || strings.HasPrefix(etag, "W/") {
		t.Fatal(etag)
	}
	for _, inm := range []string{etag, "W/" + etag, `"x", ` + etag, "*"} {
		w := e.get("/v1/venues/w38212384", tokenPixel, "If-None-Match", inm)
		if w.Code != 304 || w.Body.Len() != 0 || w.Header().Get("ETag") != etag {
			t.Errorf("If-None-Match %s: %d", inm, w.Code)
		}
	}
	if w := e.get("/v1/venues/w38212384", tokenPixel, "If-None-Match", `"stale"`); w.Code != 200 {
		t.Fatal(w.Code)
	}
	// HEAD: headers of GET, no body.
	h := e.do("HEAD", "/v1/venues/w38212384", tokenPixel, "Accept-Encoding", "gzip")
	if h.Code != 200 || h.Body.Len() != 0 || h.Header().Get("Content-Length") != itoa(len(stored)) {
		t.Fatal(h.Code, h.Header())
	}
	hl := e.do("HEAD", "/v1/venues?bbox=8.5,50,8.6,50.1", tokenPixel)
	if hl.Code != 200 || hl.Body.Len() != 0 || hl.Header().Get("Content-Length") == "" {
		t.Fatal(hl.Code)
	}
}

var itoa = strconv.Itoa

func TestUnknownVenues(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	for _, p := range []string{"/v1/venues/w999", "/v1/venues/", "/v1/venues/w1/x", "/v1/venues/..%2Findex.json",
		"/v1/venues/" + strings.Repeat("a", 41), "/v1/venues/w38212384.json.gz", "/v1/venuesx", "/v2/venues", "/"} {
		w := e.get(p, tokenPixel)
		if w.Code != 404 || errorCode(t, w) != "not_found" {
			t.Errorf("%s: %d", p, w.Code)
		}
	}
}

func TestAuth(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	for _, header := range []string{"", "Bearer " + strings.Repeat("x", 43), "Basic " + tokenPixel, tokenPixel, "Bearer abc"} {
		for _, path := range []string{"/v1/venues?bbox=8.5,50,8.6,50.1", "/v1/venues/w38212384", "/v1/venues/unknown"} {
			r := httptest.NewRequest("GET", path, nil)
			if header != "" {
				r.Header.Set("Authorization", header)
			}
			w := httptest.NewRecorder()
			e.srv.Handler().ServeHTTP(w, r)
			if w.Code != 401 || errorCode(t, w) != "unauthorized" || !strings.HasPrefix(w.Header().Get("WWW-Authenticate"), "Bearer") ||
				strings.Contains(w.Body.String(), "Terminal") {
				t.Errorf("%q %s: status %d", header, path, w.Code)
			}
		}
	}
	// Bad requests are not answered before authentication either.
	if w := e.get("/v1/venues?bbox=x", ""); w.Code != 401 {
		t.Fatal(w.Code)
	}
}

func TestMethods(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	for _, m := range []string{"POST", "PUT", "DELETE", "PATCH", "OPTIONS"} {
		for _, p := range []string{"/v1/venues?bbox=8.5,50,8.6,50.1", "/v1/venues/w38212384", "/healthz"} {
			w := e.do(m, p, tokenPixel)
			if w.Code != 405 || w.Header().Get("Allow") != "GET, HEAD" {
				t.Errorf("%s %s: %d", m, p, w.Code)
			}
		}
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
		if w := e.get("/v1/venues/r2", tokenPixel); w.Code != 200 {
			t.Fatalf("request %d: %d", i, w.Code)
		}
	}
	w := e.get("/v1/venues/r2", tokenPixel)
	if w.Code != 429 || w.Header().Get("Retry-After") != "1" || errorCode(t, w) != "rate_limited" {
		t.Fatalf("status %d retry %q", w.Code, w.Header().Get("Retry-After"))
	}
	// Other device unaffected.
	if w := e.get("/v1/venues/r2", tokenTablet); w.Code != 200 {
		t.Fatal(w.Code)
	}
	e.clock.advance(time.Second)
	if w := e.get("/v1/venues?bbox=8.5,50,8.6,50.1", tokenPixel); w.Code != 200 {
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
	e.get("/v1/venues/r2", wrong)
	e.get("/v1/venues/r2", wrong)
	if w := e.get("/v1/venues/r2", wrong); w.Code != 429 || w.Header().Get("Retry-After") == "" {
		t.Fatal(w.Code)
	}
	// Valid tokens still work: a valid lookup refunds its probe.
	e.clock.advance(time.Second)
	for range 5 {
		if w := e.get("/v1/venues/r2", tokenPixel); w.Code != 200 {
			t.Fatal(w.Code)
		}
	}
}

func TestLogsWithoutCoordinatesTokensOrIDs(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	e.get("/v1/venues?bbox=8.5123,50.0123,8.6456,50.0789", tokenPixel)
	e.get("/v1/venues/w38212384", tokenPixel, "Accept-Encoding", "gzip")
	e.get("/v1/venues/w38212384", "wrong-token-0123456789abcdef")
	e.get("/v1/venues/w99999", tokenPixel)
	e.get("/v1/venues?bbox=7.7777,50,7.1,50.1", tokenPixel)
	e.get("/secret/path/x7y8", tokenPixel)
	logs := e.logs.String()
	for _, secret := range []string{"8.5123", "50.0123", "8.6456", "7.7777", "38212384", "99999", tokenPixel, "wrong-token", "x7y8", "secret"} {
		if strings.Contains(logs, secret) {
			t.Errorf("log contains %q:\n%s", secret, logs)
		}
	}
	for _, want := range []string{"path=venues", "path=venue", "path=other", "device=pixel", "reason=auth", "reason=bad_bbox",
		"reason=unknown_venue", "gzip=true", "status=200", "bytes="} {
		if !strings.Contains(logs, want) {
			t.Errorf("log lacks %q:\n%s", want, logs)
		}
	}
}

func TestSetDataAndEmpty(t *testing.T) {
	e := newEnv(t, DefaultConfig())
	e.srv.SetData(nil)
	if e.srv.Data().Len() != 0 {
		t.Fatal("not empty")
	}
	w := e.get("/v1/venues?bbox=8.50,50.00,8.65,50.08", tokenPixel)
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"venues":[]`) {
		t.Fatal(w.Body.String())
	}
	if w := e.get("/v1/venues/w38212384", tokenPixel); w.Code != 404 {
		t.Fatal(w.Code)
	}
}
