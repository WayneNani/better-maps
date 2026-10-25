package server

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/aggregate"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/bhttp"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/client"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/clientcheck"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/ohttp"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/protocol"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/tiles"
)

var t0 = time.Date(2026, 10, 26, 8, 0, 0, 0, time.UTC)

type clock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *clock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

func (c *clock) Set(t time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = t
}

type env struct {
	srv    *Server
	http   *httptest.Server
	clock  *clock
	logs   *bytes.Buffer
	sender *client.Sender
}

func newEnv(t *testing.T, mutate func(*Config)) *env {
	t.Helper()
	cfg := DefaultConfig()
	if mutate != nil {
		mutate(&cfg)
	}
	c := &clock{t: t0.Add(time.Minute)}
	keys, err := ohttp.NewKeyRing(bytes.Repeat([]byte{1}, 32), 24*time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	logs := &bytes.Buffer{}
	log := slog.New(slog.NewTextHandler(logs, &slog.HandlerOptions{Level: slog.LevelDebug}))
	s := New(cfg, keys, aggregate.New(cfg.Aggregation, c.Now()), log)
	s.Now = c.Now
	hs := httptest.NewServer(s.Handler())
	t.Cleanup(hs.Close)
	return &env{srv: s, http: hs, clock: c, logs: logs, sender: &client.Sender{
		RelayURL: hs.URL + "/gateway", KeysURL: hs.URL + "/ohttp-keys", HTTP: hs.Client(), Now: c.Now,
	}}
}

func reports(window time.Time, n int, bucket int) []protocol.Report {
	var res []protocol.Report
	for i := range n {
		res = append(res, protocol.Report{
			Window: window.Unix(), Tile: tiles.Tile{X: 70420 + i, Y: 43010},
			Entry: protocol.Edge{Side: tiles.West, Offset: 100}, Exit: protocol.Edge{Side: tiles.East, Offset: 101},
			RoadClass: protocol.Motorway, SpeedBucket: bucket,
		})
	}
	return res
}

func TestKeysEndpoint(t *testing.T) {
	e := newEnv(t, nil)
	resp, err := e.http.Client().Get(e.http.URL + "/ohttp-keys")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	cfgs, err := ohttp.ParseKeyConfigs(b)
	if err != nil || len(cfgs) != 2 || resp.Header.Get("Content-Type") != ohttp.ContentTypeKeys {
		t.Fatalf("keys: %+v, %v, %s", cfgs, err, resp.Header.Get("Content-Type"))
	}
}

func TestSubmitThroughGateway(t *testing.T) {
	e := newEnv(t, nil)
	ctx := context.Background()
	for range 10 {
		if err := e.sender.Send(ctx, reports(t0, 3, 0)); err != nil {
			t.Fatal(err)
		}
	}
	h := e.srv.Health()
	if h.Gateway.BundlesAccepted != 10 || h.Aggregator.ReportsAccepted != 30 || h.Memory.OpenSegments != 3 {
		t.Fatalf("health = %+v", h)
	}
	// After the grace period the three segments are published as stationary traffic.
	e.clock.Set(t0.Add(20 * time.Minute))
	e.srv.Tick()
	resp, err := e.http.Client().Get(e.http.URL + "/v1/traff")
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	body := strings.SplitN(string(b), "\n", 2)[1]
	res, err := clientcheck.ParseResponse([]byte(`<response status="OK">` + body + `</response>`))
	if err != nil || len(res.Accepted) != 3 || len(res.Rejected) != 0 {
		t.Fatalf("feed %s: %+v, %v", b, res, err)
	}
	for _, m := range res.Accepted {
		if m.Events[0] != "CONGESTION_STATIONARY_TRAFFIC" {
			t.Fatalf("event %v", m.Events)
		}
	}
	// JSON export and bbox filter.
	resp, err = e.http.Client().Get(e.http.URL + "/v1/segments?bbox=52.4,13.4,52.5,13.4175")
	if err != nil {
		t.Fatal(err)
	}
	var doc struct{ Segments []struct{ ID string } }
	if err := json.NewDecoder(resp.Body).Decode(&doc); err != nil || len(doc.Segments) != 2 {
		t.Fatalf("segments: %+v, %v", doc, err)
	}
	resp.Body.Close()
	for _, path := range []string{"/v1/traff?bbox=1,2,3", "/v1/segments?bbox=a,b,c,d", "/v1/traff?bbox=5,5,1,1"} {
		resp, err := e.http.Client().Get(e.http.URL + path)
		if err != nil || resp.StatusCode != http.StatusBadRequest {
			t.Errorf("%s: %v %v", path, resp.StatusCode, err)
		}
		resp.Body.Close()
	}
}

func encapsulate(t *testing.T, e *env, plain []byte) ([]byte, *ohttp.ClientContext) {
	t.Helper()
	cfg := e.srv.keys.Configs(e.clock.Now())[0]
	enc, cctx, err := ohttp.EncapsulateRequest(cfg, plain, rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return enc, cctx
}

func post(t *testing.T, e *env, body []byte, ct string) *http.Response {
	t.Helper()
	resp, err := e.http.Client().Post(e.http.URL+"/gateway", ct, bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func innerResponse(t *testing.T, resp *http.Response, cctx *ohttp.ClientContext) *bhttp.Response {
	t.Helper()
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK || resp.Header.Get("Content-Type") != ohttp.ContentTypeResponse {
		t.Fatalf("outer status %d: %s", resp.StatusCode, b)
	}
	pt, err := cctx.DecapsulateResponse(b)
	if err != nil {
		t.Fatal(err)
	}
	inner, err := bhttp.UnmarshalResponse(pt, 1<<16)
	if err != nil {
		t.Fatal(err)
	}
	return inner
}

func problemType(r *bhttp.Response) string {
	var p struct{ Type, Title string }
	_ = json.Unmarshal(r.Body, &p)
	return p.Type + " " + p.Title
}

func TestReplayIsRejected(t *testing.T) {
	e := newEnv(t, nil)
	plain, err := client.EncodeRequest(reports(t0, 1, 2), e.clock.Now(), "x")
	if err != nil {
		t.Fatal(err)
	}
	enc, cctx := encapsulate(t, e, plain)
	if r := innerResponse(t, post(t, e, enc, ohttp.ContentTypeRequest), cctx); r.Status != http.StatusNoContent {
		t.Fatalf("first: %d %s", r.Status, r.Body)
	}
	r := innerResponse(t, post(t, e, enc, ohttp.ContentTypeRequest), cctx)
	if r.Status != http.StatusBadRequest || !strings.HasPrefix(problemType(r), ProblemReplay) {
		t.Fatalf("replay: %d %s", r.Status, r.Body)
	}
	if h := e.srv.Health(); h.Gateway.Replays != 1 || h.Aggregator.ReportsAccepted != 1 {
		t.Fatalf("health %+v", h)
	}
	// Much later the replay cache has forgotten the request, but the Date check rejects it.
	e.clock.Set(e.clock.Now().Add(11 * time.Minute))
	e.srv.Tick()
	r = innerResponse(t, post(t, e, enc, ohttp.ContentTypeRequest), cctx)
	if r.Status != http.StatusBadRequest || !strings.HasPrefix(problemType(r), ProblemDate) || r.Header.Get("date") == "" {
		t.Fatalf("old replay: %d %s", r.Status, r.Body)
	}
}

func TestWrongAndUnknownKeys(t *testing.T) {
	e := newEnv(t, nil)
	plain, _ := client.EncodeRequest(reports(t0, 1, 2), e.clock.Now(), "x")
	// Same key ID, but a key the gateway does not have.
	other, _ := ohttp.NewPrivateKey(e.srv.keys.Configs(e.clock.Now())[0].KeyID, ohttp.DefaultKEM,
		bytes.Repeat([]byte{9}, 32), ohttp.DefaultSuites)
	enc, _, _ := ohttp.EncapsulateRequest(other.Config, plain, rand.Reader)
	resp := post(t, e, enc, ohttp.ContentTypeRequest)
	resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("wrong key: %d", resp.StatusCode)
	}
	// Unknown key ID: problem type ohttp-key.
	stale, _ := ohttp.NewPrivateKey(200, ohttp.DefaultKEM, bytes.Repeat([]byte{9}, 32), ohttp.DefaultSuites)
	enc, _, _ = ohttp.EncapsulateRequest(stale.Config, plain, rand.Reader)
	resp = post(t, e, enc, ohttp.ContentTypeRequest)
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest || !strings.Contains(string(b), ProblemOHTTPKey) {
		t.Fatalf("unknown key: %d %s", resp.StatusCode, b)
	}
	if h := e.srv.Health(); h.Gateway.DecryptErrors != 1 || h.Gateway.UnknownKey != 1 || h.Aggregator.ReportsAccepted != 0 {
		t.Fatalf("health %+v", h.Gateway)
	}
	// A sender with a stale key refetches the configuration and retries.
	e.sender.FetchKeys(context.Background())
	e.clock.Set(e.clock.Now().Add(72 * time.Hour)) // two key epochs later
	if err := e.sender.Send(context.Background(), reports(e.clock.Now().Truncate(15*time.Minute), 1, 1)); err != nil {
		t.Fatalf("retry after key rotation: %v", err)
	}
}

func TestOuterErrors(t *testing.T) {
	e := newEnv(t, func(c *Config) { c.MaxRequestBytes = 600 })
	cases := []struct {
		body []byte
		ct   string
		want int
	}{
		{[]byte("x"), "text/plain", http.StatusUnsupportedMediaType},
		{make([]byte, 601), ohttp.ContentTypeRequest, http.StatusRequestEntityTooLarge},
		{[]byte{1, 2, 3}, ohttp.ContentTypeRequest, http.StatusBadRequest},
	}
	for _, c := range cases {
		resp := post(t, e, c.body, c.ct)
		resp.Body.Close()
		if resp.StatusCode != c.want {
			t.Errorf("%s/%d bytes: status %d, want %d", c.ct, len(c.body), resp.StatusCode, c.want)
		}
	}
	resp, _ := e.http.Client().Get(e.http.URL + "/gateway")
	resp.Body.Close()
	if resp.StatusCode != http.StatusMethodNotAllowed {
		t.Errorf("GET /gateway: %d", resp.StatusCode)
	}
	// There is no way to submit reports without OHTTP.
	resp, _ = e.http.Client().Post(e.http.URL+"/v1/reports", "application/json", strings.NewReader(`{}`))
	resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("POST /v1/reports: %d", resp.StatusCode)
	}
}

func TestRateLimit(t *testing.T) {
	e := newEnv(t, func(c *Config) { c.RateLimit, c.RateBurst = 1, 2 })
	codes := map[int]int{}
	for range 4 {
		resp := post(t, e, []byte{1}, ohttp.ContentTypeRequest)
		resp.Body.Close()
		codes[resp.StatusCode]++
	}
	if codes[http.StatusServiceUnavailable] != 2 || e.srv.Health().Gateway.RateLimited != 2 {
		t.Fatalf("codes %v", codes)
	}
	// Tokens refill over time.
	e.clock.Set(e.clock.Now().Add(2 * time.Second))
	resp := post(t, e, []byte{1}, ohttp.ContentTypeRequest)
	resp.Body.Close()
	if resp.StatusCode == http.StatusServiceUnavailable {
		t.Fatal("bucket did not refill")
	}
}

func TestInnerErrors(t *testing.T) {
	e := newEnv(t, nil)
	now := e.clock.Now()
	date := now.UTC().Format(http.TimeFormat)
	good, _ := json.Marshal(protocol.Bundle{Version: 1, Reports: reports(t0, 1, 1)})
	req := func(method, path, ct, date string, body []byte) []byte {
		h := bhttp.Fields{{Name: "content-type", Value: ct}}
		if date != "" {
			h = append(h, bhttp.Field{Name: "date", Value: date})
		}
		return (&bhttp.Request{Method: method, Scheme: "https", Authority: "x", Path: path, Header: h, Body: body}).Marshal(0)
	}
	late, _ := json.Marshal(protocol.Bundle{Version: 1, Reports: reports(t0.Add(-time.Hour), 1, 1)})
	cases := []struct {
		name    string
		plain   []byte
		status  int
		problem string
	}{
		{"malformed", []byte{0x02}, http.StatusBadRequest, "about:blank"},
		{"no date", req("POST", protocol.ReportPath, "application/json", "", good), http.StatusBadRequest, ProblemDate},
		{"old date", req("POST", protocol.ReportPath, "application/json", now.Add(-6*time.Minute).UTC().Format(http.TimeFormat), good), http.StatusBadRequest, ProblemDate},
		{"path", req("POST", "/admin", "application/json", date, good), http.StatusNotFound, "about:blank"},
		{"method", req("GET", protocol.ReportPath, "application/json", date, nil), http.StatusMethodNotAllowed, "about:blank"},
		{"content type", req("POST", protocol.ReportPath, "text/plain", date, good), http.StatusUnsupportedMediaType, "about:blank"},
		{"syntax", req("POST", protocol.ReportPath, "application/json", date, []byte(`{"version":1,"bogus":1}`)), http.StatusBadRequest, ProblemInvalid + " syntax"},
		{"late", req("POST", protocol.ReportPath, "application/json", date, late), http.StatusBadRequest, ProblemInvalid + " window_late"},
		{"ok", req("POST", protocol.ReportPath, "application/json; charset=utf-8", date, good), http.StatusNoContent, " "},
	}
	for _, c := range cases {
		enc, cctx := encapsulate(t, e, c.plain)
		r := innerResponse(t, post(t, e, enc, ohttp.ContentTypeRequest), cctx)
		if r.Status != c.status || !strings.HasPrefix(problemType(r), c.problem) {
			t.Errorf("%s: %d %q", c.name, r.Status, problemType(r))
		}
	}
	h := e.srv.Health()
	if h.Rejected[protocol.ReasonSyntax] != 1 || h.Rejected[protocol.ReasonLate] != 1 || h.Gateway.DateRejected != 2 ||
		h.Gateway.BadInner != 4 || h.Gateway.BundlesAccepted != 1 {
		t.Fatalf("health %+v", h)
	}
	// The sender reports inner rejections as RejectedError.
	err := e.sender.Send(context.Background(), reports(t0.Add(-time.Hour), 1, 1))
	var rej *client.RejectedError
	if !errors.As(err, &rej) || rej.Status != http.StatusBadRequest || rej.Title != "window_late" || rej.Error() == "" {
		t.Fatalf("sender error: %v", err)
	}
}

// Decrypted request data does not outlive the request: the plaintext buffer is zeroed.
func TestPlaintextIsCleared(t *testing.T) {
	e := newEnv(t, nil)
	plain, _ := client.EncodeRequest(reports(t0, 2, 3), e.clock.Now(), "x")
	if r := e.srv.processInner(plain, []byte("enc-1"), e.clock.Now()); r.Status != http.StatusNoContent {
		t.Fatalf("status %d", r.Status)
	}
	for _, b := range plain {
		if b != 0 {
			t.Fatal("plaintext not cleared")
		}
	}
	bad := []byte{0x02, 0x55}
	e.srv.processInner(bad, []byte("enc-2"), e.clock.Now())
	if bad[0] != 0 || bad[1] != 0 {
		t.Fatal("plaintext of a rejected request not cleared")
	}
}

// Neither the log nor /healthz contain report data, client addresses or user agents.
func TestNoPersonalDataInLogsAndHealth(t *testing.T) {
	e := newEnv(t, nil)
	e.sender.HTTP = &http.Client{Transport: uaTransport{e.http.Client().Transport}}
	for i := range 12 {
		if err := e.sender.Send(context.Background(), reports(t0, 1+i%3, i%7)); err != nil {
			t.Fatal(err)
		}
	}
	e.clock.Set(t0.Add(25 * time.Minute))
	e.srv.Tick()
	e.srv.LogSummary()
	resp, _ := e.http.Client().Get(e.http.URL + "/healthz")
	health, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	logs := e.logs.String()
	if !strings.Contains(logs, "summary") || !strings.Contains(string(health), `"status":"ok"`) {
		t.Fatalf("logs %q health %s", logs, health)
	}
	for _, s := range []string{logs, string(health)} {
		for _, bad := range []string{"127.0.0.1", "crowd-test-agent", "70420", "70421", "43010", "speed_bucket", "tile"} {
			if strings.Contains(s, bad) {
				t.Errorf("%q found in %s", bad, s)
			}
		}
	}
	if !strings.Contains(string(health), `"reports_accepted":`+strconv.Itoa(4*(1+2+3))) {
		t.Errorf("health lacks counters: %s", health)
	}
}

type uaTransport struct{ rt http.RoundTripper }

func (u uaTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	r.Header.Set("User-Agent", "crowd-test-agent")
	return u.rt.RoundTrip(r)
}

func TestTokenBucket(t *testing.T) {
	b := newTokenBucket(10, 1)
	now := time.Unix(0, 0)
	if !b.allow(now) || b.allow(now) {
		t.Fatal("burst")
	}
	if !b.allow(now.Add(100 * time.Millisecond)) {
		t.Fatal("refill")
	}
}
