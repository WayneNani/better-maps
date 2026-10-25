package autobahn

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeAPI serves the recorded fixtures like the real Autobahn API, with ETags.
type fakeAPI struct {
	mu         sync.Mutex
	requests   []string
	notMod     int
	fail       map[string]int // path -> HTTP status to return
	roads      string         // body of GET /
	userAgents map[string]bool
}

func newFakeAPI(t *testing.T) (*fakeAPI, *httptest.Server) {
	t.Helper()
	f := &fakeAPI{fail: map[string]int{}, userAgents: map[string]bool{}, roads: `{"roads":["A7","A9","A60 ","A7"]}`}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		f.requests = append(f.requests, r.URL.Path)
		f.userAgents[r.UserAgent()] = true
		status := f.fail[r.URL.Path]
		roads := f.roads
		f.mu.Unlock()
		if status != 0 {
			http.Error(w, "fail", status)
			return
		}
		var body []byte
		if r.URL.Path == "/" {
			body = []byte(roads)
		} else {
			parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
			if len(parts) != 3 || parts[1] != "services" {
				http.NotFound(w, r)
				return
			}
			data, err := os.ReadFile(filepath.Join("testdata", parts[0]+"_"+parts[2]+".json"))
			if err != nil {
				data = []byte(fmt.Sprintf(`{%q:[]}`, parts[2]))
			}
			body = data
		}
		etag := fmt.Sprintf(`W/"%x"`, len(body))
		w.Header().Set("ETag", etag)
		if r.Header.Get("If-None-Match") == etag {
			f.mu.Lock()
			f.notMod++
			f.mu.Unlock()
			w.WriteHeader(http.StatusNotModified)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(body)
	}))
	t.Cleanup(srv.Close)
	return f, srv
}

func (f *fakeAPI) count() (int, int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.requests), f.notMod
}

func TestClientRoadsAndETag(t *testing.T) {
	api, srv := newFakeAPI(t)
	c := NewClient(srv.URL+"/", "test-agent/1", 0)
	roads, err := c.Roads(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if fmt.Sprint(roads) != "[A7 A9 A60]" {
		t.Errorf("roads = %v (must be trimmed and de-duplicated)", roads)
	}
	items, err := c.Items(context.Background(), "A7", ServiceWarning)
	if err != nil || len(items) != 3 {
		t.Fatalf("items = %d, %v", len(items), err)
	}
	// Second request is answered with 304 and served from the cache.
	items2, err := c.Items(context.Background(), "A7", ServiceWarning)
	if err != nil || len(items2) != 3 {
		t.Fatalf("cached items = %d, %v", len(items2), err)
	}
	if n, nm := api.count(); n != 3 || nm != 1 {
		t.Errorf("requests = %d, 304 = %d", n, nm)
	}
	// Road IDs with spaces are trimmed and escaped.
	if _, err := c.Items(context.Background(), "A60 ", ServiceClosure); err != nil {
		t.Fatal(err)
	}
	api.mu.Lock()
	last := api.requests[len(api.requests)-1]
	ua := api.userAgents["test-agent/1"]
	api.mu.Unlock()
	if last != "/A60/services/closure" || !ua {
		t.Errorf("last request %q, user agent seen %v", last, ua)
	}
}

func TestClientErrors(t *testing.T) {
	api, srv := newFakeAPI(t)
	api.fail["/A7/services/warning"] = http.StatusBadGateway
	c := NewClient(srv.URL, "", 0)
	_, err := c.Items(context.Background(), "A7", ServiceWarning)
	var he *HTTPError
	if !errors.As(err, &he) || he.Status != http.StatusBadGateway || !strings.Contains(err.Error(), "502") {
		t.Errorf("err = %v", err)
	}
	api.mu.Lock()
	api.roads = `{"roads":`
	api.mu.Unlock()
	if _, err := c.Roads(context.Background()); err == nil {
		t.Error("broken road list accepted")
	}
	api.mu.Lock()
	api.roads = `[]`
	api.mu.Unlock()
	if _, err := c.Roads(context.Background()); err == nil {
		t.Error("road list of wrong type accepted")
	}

	broken := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, `{"warning":[{"identifier":1}]}`)
	}))
	defer broken.Close()
	if _, err := NewClient(broken.URL, "", 0).Items(context.Background(), "A1", ServiceWarning); err == nil {
		t.Error("invalid item JSON accepted")
	}

	if _, err := NewClient("http://127.0.0.1:1", "", 0).Roads(context.Background()); err == nil {
		t.Error("connection error not reported")
	}
	if _, err := NewClient("http://bad host", "", 0).Roads(context.Background()); err == nil {
		t.Error("invalid URL not reported")
	}
}

func TestClientRateLimit(t *testing.T) {
	_, srv := newFakeAPI(t)
	c := NewClient(srv.URL, "", 40*time.Millisecond)
	start := time.Now()
	for i := 0; i < 4; i++ {
		if _, err := c.Items(context.Background(), "A9", ServiceClosure); err != nil {
			t.Fatal(err)
		}
	}
	if d := time.Since(start); d < 120*time.Millisecond {
		t.Errorf("4 requests took %v, expected at least 3 pauses of 40ms", d)
	}
	// A cancelled context interrupts the pause.
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	c.MinInterval = time.Hour
	if _, err := c.Items(ctx, "A9", ServiceClosure); !errors.Is(err, context.Canceled) {
		t.Errorf("err = %v", err)
	}
}

func discardLogger() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

func TestSourceFetch(t *testing.T) {
	api, srv := newFakeAPI(t)
	tz := berlin(t)
	src := NewSource(NewClient(srv.URL, "", 0), nil, tz, slog.New(slog.NewTextHandler(io.Discard, &slog.HandlerOptions{Level: slog.LevelDebug})))
	if src.Name() != "autobahn" || len(src.Coverage()) != 1 {
		t.Error("name/coverage")
	}
	msgs, err := src.Fetch(context.Background(), fixtureNow)
	if err != nil {
		t.Fatal(err)
	}
	// A7: 3 warnings + 42 closures; A9: 58 roadworks + 17 closures; A60: nothing.
	if len(msgs) != 3+42+58+17 {
		t.Errorf("messages = %d", len(msgs))
	}
	if n, _ := api.count(); n != 1+3*3 {
		t.Errorf("requests = %d", n)
	}

	// One request fails: the items of the previous fetch are reused and the error reported.
	api.mu.Lock()
	api.fail["/A9/services/roadworks"] = http.StatusInternalServerError
	api.mu.Unlock()
	msgs2, err := src.Fetch(context.Background(), fixtureNow)
	if err == nil || !strings.Contains(err.Error(), "1 of 9 requests failed") {
		t.Errorf("partial failure error = %v", err)
	}
	if len(msgs2) != len(msgs) {
		t.Errorf("stale data not reused: %d vs %d", len(msgs2), len(msgs))
	}

	// The road list fails: the previous list is used.
	api.mu.Lock()
	api.fail["/"] = http.StatusServiceUnavailable
	delete(api.fail, "/A9/services/roadworks")
	api.mu.Unlock()
	msgs3, err := src.Fetch(context.Background(), fixtureNow)
	if err == nil || len(msgs3) != len(msgs) {
		t.Errorf("road list failure: %d msgs, err %v", len(msgs3), err)
	}

	// Roads that are no longer listed are forgotten.
	api.mu.Lock()
	delete(api.fail, "/")
	api.roads = `{"roads":["A7"]}`
	api.mu.Unlock()
	msgs4, err := src.Fetch(context.Background(), fixtureNow)
	if err != nil || len(msgs4) != 3+42 {
		t.Errorf("after road removal: %d msgs, err %v", len(msgs4), err)
	}
	if len(src.lastItems) != 3 {
		t.Errorf("stale cache entries: %d", len(src.lastItems))
	}

	// Everything ends: messages are skipped, the result is an empty, non-nil slice.
	msgs5, err := src.Fetch(context.Background(), time.Date(2030, 1, 1, 0, 0, 0, 0, time.UTC))
	if err != nil || msgs5 == nil {
		t.Errorf("far future: %v %v", msgs5, err)
	}
	for _, m := range msgs5 {
		if m.EndTime != nil {
			t.Errorf("ended message %s returned", m.ID)
		}
	}
}

func TestSourceFetchFailures(t *testing.T) {
	api, srv := newFakeAPI(t)
	api.fail["/"] = http.StatusInternalServerError
	src := NewSource(NewClient(srv.URL, "", 0), nil, berlin(t), discardLogger())
	if msgs, err := src.Fetch(context.Background(), fixtureNow); msgs != nil || err == nil {
		t.Errorf("first fetch without road list: %v %v", msgs, err)
	}

	// Fixed road list: the road list endpoint is not used.
	fixed := NewSource(NewClient(srv.URL, "", 0), []string{"A7"}, berlin(t), nil)
	msgs, err := fixed.Fetch(context.Background(), fixtureNow)
	if err != nil || len(msgs) != 45 {
		t.Errorf("fixed roads: %d %v", len(msgs), err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if msgs, err := fixed.Fetch(ctx, fixtureNow); msgs != nil || !errors.Is(err, context.Canceled) {
		t.Errorf("cancelled fetch: %v %v", msgs, err)
	}

	// Duplicate roads produce each message only once.
	dup := NewSource(NewClient(srv.URL, "", 0), []string{"A7", "A7"}, berlin(t), nil)
	msgs, _ = dup.Fetch(context.Background(), fixtureNow)
	if len(msgs) != 45 {
		t.Errorf("duplicates: %d", len(msgs))
	}
}
