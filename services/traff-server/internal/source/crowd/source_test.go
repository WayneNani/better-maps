package crowd

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeServer imitates the export of crowd-traffic.
type fakeServer struct {
	mu       sync.Mutex
	status   int
	body     string
	delay    time.Duration
	requests []*http.Request
}

func (f *fakeServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	f.requests = append(f.requests, r)
	status, body, delay := f.status, f.body, f.delay
	f.mu.Unlock()
	if delay > 0 {
		select {
		case <-time.After(delay):
		case <-r.Context().Done():
			return
		}
	}
	if status != http.StatusOK {
		http.Error(w, "unavailable", status)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte(body))
}

func (f *fakeServer) serve(status int, body string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.status, f.body = status, body
}

func (f *fakeServer) serveDoc(t *testing.T, segs ...Segment) {
	t.Helper()
	data, err := json.Marshal(Document{Version: 1, Generated: t0, Segments: segs})
	if err != nil {
		t.Fatal(err)
	}
	f.serve(http.StatusOK, string(data))
}

func newFake(t *testing.T) (*fakeServer, *httptest.Server) {
	t.Helper()
	f := &fakeServer{status: http.StatusOK}
	srv := httptest.NewServer(f)
	t.Cleanup(srv.Close)
	return f, srv
}

func TestExportURL(t *testing.T) {
	cases := map[string]string{
		"http://crowd:8080":                      "http://crowd:8080/v1/segments",
		"http://crowd:8080/":                     "http://crowd:8080/v1/segments",
		"https://example.org/crowd/":             "https://example.org/crowd/v1/segments",
		"http://crowd:8080/v1/segments":          "http://crowd:8080/v1/segments",
		"http://crowd:8080?bbox=52,13,53,14":     "http://crowd:8080/v1/segments?bbox=52,13,53,14",
		"http://crowd:8080/v1/segments?bbox=1,2": "http://crowd:8080/v1/segments?bbox=1,2",
	}
	for in, want := range cases {
		if got, err := ExportURL(in); err != nil || got != want {
			t.Errorf("ExportURL(%q) = %q, %v", in, got, err)
		}
	}
	for _, bad := range []string{"", "crowd:8080", "ftp://crowd/", "file:///tmp/x.json", "http://", "http://[::1"} {
		if _, err := ExportURL(bad); err == nil {
			t.Errorf("ExportURL(%q) accepted", bad)
		}
		if _, err := NewSource(Config{URL: bad}); err == nil {
			t.Errorf("NewSource(%q) accepted", bad)
		}
	}
}

func TestSourceDefaults(t *testing.T) {
	s, err := NewSource(Config{URL: "http://crowd:8080"})
	if err != nil {
		t.Fatal(err)
	}
	if s.Name() != "crowd" || s.Interval() != DefaultInterval || s.cfg.Timeout != DefaultTimeout || s.cfg.Client != http.DefaultClient ||
		len(s.Coverage()) != 1 || s.Coverage()[0] != germany {
		t.Errorf("source = %+v", s)
	}
	if d := s.Details(); len(d) != 0 {
		t.Errorf("details before first fetch = %v", d)
	}
}

func TestSourceFetch(t *testing.T) {
	f, srv := newFake(t)
	s, err := NewSource(Config{URL: srv.URL + "/", UserAgent: "test-agent", Interval: time.Minute, Timeout: time.Second,
		Merge: MergeOptions{Tolerance: DefaultMergeDistance, MaxLength: DefaultMaxLength}})
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()

	// Before the first successful download, an error yields no messages.
	f.serve(http.StatusServiceUnavailable, "")
	if msgs, err := s.Fetch(ctx, t0); msgs != nil || err == nil || !strings.Contains(err.Error(), "HTTP 503") {
		t.Fatalf("fetch = %v, %v", msgs, err)
	}

	// A jam of three tiles and a single congested tile elsewhere; the second one expires
	// earlier.
	single := opts(seg(5, 3, "N", 0.5, "S", 0.5, "QUEUE", 14), func(s *Segment) { s.Expires = t0.Add(5 * time.Minute) })
	f.serveDoc(t, straight(0), straight(1), straight(2), single)
	msgs, err := s.Fetch(ctx, t0)
	if err != nil || len(msgs) != 2 || msgs[0].ID != id(2, 0, "W", "E") || msgs[1].ID != id(5, 3, "N", "S") {
		t.Fatalf("fetch = %v, %v", ids(msgs), err)
	}
	f.mu.Lock()
	r := f.requests[len(f.requests)-1]
	f.mu.Unlock()
	if r.URL.Path != "/v1/segments" || r.Header.Get("User-Agent") != "test-agent" || r.Header.Get("Accept") != "application/json" {
		t.Errorf("request %s, headers %v", r.URL, r.Header)
	}
	d := s.Details()
	if d["downloads"] != 1 || d["fetch_errors"] != 1 || d["segments"] != 4 || d["congested_segments"] != 4 || d["messages"] != 2 ||
		d["joins"] != 2 || d["longest_chain"] != 3 || d["export_age_seconds"] != 0 {
		t.Errorf("details = %v", d)
	}

	// The jam disappears from the export: the source no longer reports it (the store cancels).
	f.serveDoc(t, single)
	if msgs, err = s.Fetch(ctx, t0.Add(2*time.Minute)); err != nil || len(msgs) != 1 || msgs[0].ID != id(5, 3, "N", "S") {
		t.Fatalf("after the jam cleared: %v, %v", ids(msgs), err)
	}

	// The server fails: the previous export is used until its segments expire.
	f.serve(http.StatusInternalServerError, "")
	msgs, err = s.Fetch(ctx, t0.Add(4*time.Minute))
	if err == nil || !strings.Contains(err.Error(), "previous export") || len(msgs) != 1 {
		t.Fatalf("failure with cached export: %v, %v", ids(msgs), err)
	}
	msgs, err = s.Fetch(ctx, t0.Add(6*time.Minute))
	if err == nil || msgs == nil || len(msgs) != 0 {
		t.Fatalf("failure after expiry: %v, %v", msgs, err)
	}
	d = s.Details()
	if d["fetch_errors"] != 3 || d["downloads"] != 2 || d[skipExpired] != 1 || d["export_age_seconds"] != 360 {
		t.Errorf("details = %v", d)
	}
}

func TestSourceErrors(t *testing.T) {
	f, srv := newFake(t)
	s, err := NewSource(Config{URL: srv.URL, Timeout: 50 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	cases := []struct {
		name, body, want string
	}{
		{"invalid JSON", `{"version":1,"segments":[`, "invalid export"},
		{"wrong type", `{"version":1,"segments":{}}`, "invalid export"},
		{"unknown version", `{"version":2,"segments":[]}`, "unsupported export version 2"},
		{"missing version", `{"segments":[]}`, "unsupported export version 0"},
	}
	for _, c := range cases {
		f.serve(http.StatusOK, c.body)
		if msgs, err := s.Fetch(ctx, t0); msgs != nil || err == nil || !strings.Contains(err.Error(), c.want) {
			t.Errorf("%s: %v, %v", c.name, msgs, err)
		}
	}

	// Too large.
	old := maxBody
	maxBody = 10
	f.serve(http.StatusOK, `{"version":1,"segments":[]}`)
	_, err = s.Fetch(ctx, t0)
	maxBody = old
	if err == nil || !strings.Contains(err.Error(), "too large") {
		t.Errorf("large export: %v", err)
	}

	// Timeout.
	f.mu.Lock()
	f.delay = time.Second
	f.mu.Unlock()
	start := time.Now()
	if _, err := s.Fetch(ctx, t0); err == nil || !errors.Is(err, context.DeadlineExceeded) || time.Since(start) > 900*time.Millisecond {
		t.Errorf("timeout: %v after %v", err, time.Since(start))
	}
	f.mu.Lock()
	f.delay = 0
	f.mu.Unlock()

	// Connection refused.
	srv.Close()
	if _, err := s.Fetch(ctx, t0); err == nil {
		t.Error("closed server: no error")
	}
	// Cancelled context.
	cctx, cancel := context.WithCancel(ctx)
	cancel()
	if _, err := s.Fetch(cctx, t0); err == nil {
		t.Error("cancelled context: no error")
	}
	if d := s.Details(); d["fetch_errors"] != 8 || d["downloads"] != 0 {
		t.Errorf("details = %v", d)
	}
}

// errBody fails while the body is read.
type errBody struct{}

func (errBody) Read([]byte) (int, error) { return 0, errors.New("connection reset") }
func (errBody) Close() error             { return nil }

type roundTripper func(*http.Request) (*http.Response, error)

func (f roundTripper) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestSourceBodyError(t *testing.T) {
	client := &http.Client{Transport: roundTripper(func(r *http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: http.StatusOK, Body: errBody{}, Header: http.Header{}, Request: r}, nil
	})}
	s, err := NewSource(Config{URL: "http://crowd", Client: client})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.Fetch(context.Background(), t0); err == nil || !strings.Contains(err.Error(), "connection reset") {
		t.Errorf("fetch = %v", err)
	}
}
