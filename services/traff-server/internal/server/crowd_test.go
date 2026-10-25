package server

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/dedup"
	"github.com/WayneNani/better-maps/services/traff-server/internal/source"
	"github.com/WayneNani/better-maps/services/traff-server/internal/source/autobahn"
	"github.com/WayneNani/better-maps/services/traff-server/internal/source/crowd"
	"github.com/WayneNani/better-maps/services/traff-server/internal/store"
)

// crowdFixture is an export recorded from crowd-traffic after a crowd-sim run (A 100 jam).
const crowdFixture = "../source/crowd/testdata/a100_segments.json"

// fakeCrowd serves a crowd-traffic export (GET /v1/segments). The export is kept as generic JSON
// so that every field of the recording is served unchanged; times are shifted to the test clock.
type fakeCrowd struct {
	mu     sync.Mutex
	doc    map[string]any
	status int
}

func newFakeCrowd(t *testing.T, generated time.Time) *fakeCrowd {
	t.Helper()
	data, err := os.ReadFile(crowdFixture)
	if err != nil {
		t.Fatal(err)
	}
	f := &fakeCrowd{status: http.StatusOK}
	if err := json.Unmarshal(data, &f.doc); err != nil {
		t.Fatal(err)
	}
	// Shift all timestamps so that the export was generated at the given time. The recording was
	// made with a window of 60 s, so its segments expire 65 s after the evaluation; production
	// uses 15 min + 5 min grace, i.e. 20 min.
	orig, err := time.Parse(time.RFC3339, f.doc["generated"].(string))
	if err != nil {
		t.Fatal(err)
	}
	shift := generated.Sub(orig)
	f.doc["generated"] = generated.Format(time.RFC3339)
	for _, s := range f.segments() {
		for _, k := range []string{"window_start", "window_end", "first_seen", "updated", "expires"} {
			v, err := time.Parse(time.RFC3339, s[k].(string))
			if err != nil {
				t.Fatal(err)
			}
			s[k] = v.Add(shift).Format(time.RFC3339)
		}
		updated, _ := time.Parse(time.RFC3339, s["updated"].(string))
		s["expires"] = updated.Add(20 * time.Minute).Format(time.RFC3339)
	}
	return f
}

func (f *fakeCrowd) segments() []map[string]any {
	var res []map[string]any
	for _, s := range f.doc["segments"].([]any) {
		res = append(res, s.(map[string]any))
	}
	return res
}

// setFree marks the segments with the given IDs (without prefix) as free-flowing.
func (f *fakeCrowd) setFree(keys ...string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, s := range f.segments() {
		for _, k := range keys {
			if s["id"] == "org.bettermaps.crowd:"+k {
				s["congested"], s["state"], s["traff_event"], s["speed_kmh"] = false, "FREE_FLOW", "", 92.0
			}
		}
	}
}

func (f *fakeCrowd) setStatus(code int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.status = code
}

func (f *fakeCrowd) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if r.URL.Path != crowd.ExportPath {
		http.NotFound(w, r)
		return
	}
	if f.status != http.StatusOK {
		http.Error(w, "unavailable", f.status)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(f.doc)
}

// fakeA100 serves the Autobahn API for the A 100 only: a jam warning that covers the crowd jam,
// switched on and off by the test.
type fakeA100 struct {
	mu  sync.Mutex
	jam bool
}

func (f *fakeA100) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	svc := r.URL.Path[strings.LastIndex(r.URL.Path, "/")+1:]
	w.Header().Set("Content-Type", "application/json")
	if r.URL.Path == "/A100/services/warning" && f.jam {
		data, _ := os.ReadFile(filepath.Join("testdata", "A100_warning.json"))
		_, _ = w.Write(data)
		return
	}
	fmt.Fprintf(w, `{%q:[]}`, svc)
}

func (f *fakeA100) set(jam bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.jam = jam
}

type crowdEnv struct {
	*env
	runner  *source.Runner
	crowd   *fakeCrowd
	a100    *fakeA100
	generic *store.Store
}

// newCrowdEnv wires the real Autobahn source (A 100 only) and the real crowd source against
// local fake servers, with duplicate suppression, as cmd/traff-server does.
func newCrowdEnv(t *testing.T) *crowdEnv {
	t.Helper()
	e := newEnv(t, 0)
	tz, err := time.LoadLocation("Europe/Berlin")
	if err != nil {
		t.Fatal(err)
	}
	a100 := &fakeA100{}
	abSrv := httptest.NewServer(a100)
	t.Cleanup(abSrv.Close)
	ab := autobahn.NewSource(autobahn.NewClient(abSrv.URL, "test", 0), []string{"A100"}, tz, nil)
	fc := newFakeCrowd(t, t0.Add(-time.Minute))
	crowdSrv := httptest.NewServer(fc)
	t.Cleanup(crowdSrv.Close)
	cs, err := crowd.NewSource(crowd.Config{URL: crowdSrv.URL, Timeout: 5 * time.Second,
		Merge: crowd.MergeOptions{Tolerance: crowd.DefaultMergeDistance, MaxLength: crowd.DefaultMaxLength}})
	if err != nil {
		t.Fatal(err)
	}
	log := slog.New(slog.NewTextHandler(e.logs, &slog.HandlerOptions{Level: slog.LevelDebug}))
	r := source.NewRunner(e.store, 5*time.Minute, log, ab, cs)
	r.SetClock(func() time.Time { return e.now })
	r.SetDeduplicator(dedup.New(dedup.Config{}))
	e.srv.Coverage, e.srv.Status = r.Coverage, r.Status
	return &crowdEnv{env: e, runner: r, crowd: fc, a100: a100}
}

func (ce *crowdEnv) health(t *testing.T) (h struct {
	Messages   int             `json:"messages"`
	Duplicates int             `json:"duplicates_suppressed"`
	Sources    []source.Status `json:"sources"`
}) {
	t.Helper()
	resp, err := http.Get(ce.http.URL + "/healthz")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if err := json.NewDecoder(resp.Body).Decode(&h); err != nil {
		t.Fatal(err)
	}
	return h
}

const (
	bboxBerlin  = "52.3 13.1 52.7 13.7"
	idCrowdJam  = "de.crowd:17/70416/43018/W-E/MOTORWAY"
	idCrowdHead = "de.crowd:17/70414/43018/W-E/MOTORWAY"
	idA100Jam   = "de.autobahn.warning:E2E--a100-jam"
)

func TestEndToEndCrowd(t *testing.T) {
	ce := newCrowdEnv(t)
	ctx := context.Background()
	poll := `<request operation="POLL" subscription_id="SUB0001"/>`

	// 1. Crowd data only: the twelve congested tiles of the A 100 arrive as one message.
	ce.runner.RunOnce(ctx)
	res, data := ce.traff(subscribeReq(bboxBerlin))
	golden(t, "crowd_subscribe.xml", data)
	got := acceptedIDs(res)
	if res.Status != "OK" || len(got) != 1 || len(res.Rejected) != 0 || got[idCrowdJam].Events[0] != "CONGESTION_STATIONARY_TRAFFIC" {
		t.Fatalf("subscribe = %+v", res)
	}
	h := ce.health(t)
	if len(h.Sources) != 2 || h.Sources[1].Name != "crowd" || h.Sources[1].LastError != "" || h.Sources[1].Messages != 1 ||
		h.Sources[1].Details["congested_segments"] != 12 || h.Sources[1].Details["joins"] != 11 || h.Sources[1].Details["segments"] != 105 ||
		h.Sources[1].Details["downloads"] != 1 || h.Sources[1].Details["export_age_seconds"] != 60 {
		t.Errorf("health = %+v", h)
	}

	// 2. The Autobahn API reports the same jam: the official message wins.
	ce.a100.set(true)
	ce.now = t0.Add(2 * time.Minute)
	ce.runner.RunOnce(ctx)
	res, _ = ce.traff(poll)
	got = acceptedIDs(res)
	if len(got) != 2 || !got[idCrowdJam].Cancellation || got[idA100Jam].Cancellation {
		t.Fatalf("official jam: %+v", res.Accepted)
	}
	if h = ce.health(t); h.Duplicates != 1 || h.Sources[1].Duplicates != 1 || h.Sources[1].Messages != 1 {
		t.Errorf("health = %+v", h)
	}
	if !strings.Contains(ce.logs.String(), `msg="duplicate suppressed" source=crowd id=`+idCrowdJam+" duplicate_of="+idA100Jam) {
		t.Errorf("no duplicate log:\n%s", ce.logs.String())
	}
	checkNoDuplicates(t, ce.store)

	// 3. The official warning ends while the crowd still sees the jam: the crowd message is back.
	ce.a100.set(false)
	ce.now = t0.Add(4 * time.Minute)
	ce.runner.RunOnce(ctx)
	res, _ = ce.traff(poll)
	got = acceptedIDs(res)
	if len(got) != 2 || got[idCrowdJam].Cancellation || !got[idA100Jam].Cancellation {
		t.Fatalf("official jam ended: %+v", res.Accepted)
	}

	// 4. The tail of the jam dissolves: same message (the head is unchanged), new extent.
	ce.crowd.setFree("17/70406/43015/N-E/MOTORWAY", "17/70407/43015/W-S/MOTORWAY", "17/70407/43016/N-E/MOTORWAY")
	ce.now = t0.Add(6 * time.Minute)
	ce.runner.RunOnce(ctx)
	res, _ = ce.traff(poll)
	got = acceptedIDs(res)
	if len(got) != 1 || got[idCrowdJam].Cancellation {
		t.Fatalf("tail dissolved: %+v", res.Accepted)
	}
	e := ce.store.Get(idCrowdJam)
	if e.Msg.Location.From.Coord.String() != "+52.468740 +13.381348" {
		t.Errorf("from = %v", e.Msg.Location.From.Coord)
	}

	// 5. The head dissolves: the message is cancelled, the rest of the jam gets a new ID.
	ce.crowd.setFree("17/70416/43018/W-E/MOTORWAY", "17/70415/43018/W-E/MOTORWAY")
	ce.now = t0.Add(8 * time.Minute)
	ce.runner.RunOnce(ctx)
	res, data = ce.traff(poll)
	golden(t, "crowd_poll.xml", data)
	got = acceptedIDs(res)
	if len(got) != 2 || !got[idCrowdJam].Cancellation || got[idCrowdHead].Cancellation {
		t.Fatalf("head dissolved: %+v", res.Accepted)
	}

	// 6. The crowd server fails: the last export stays valid until its segments expire.
	ce.crowd.setStatus(http.StatusBadGateway)
	ce.now = t0.Add(10 * time.Minute)
	ce.runner.RunOnce(ctx)
	if res, _ = ce.traff(poll); len(res.Accepted)+len(res.Rejected) != 0 {
		t.Errorf("poll during outage = %+v", res)
	}
	h = ce.health(t)
	if !strings.Contains(h.Sources[1].LastError, "HTTP 502") || h.Sources[1].Details["fetch_errors"] != 1 || h.Sources[0].LastError != "" {
		t.Errorf("health during outage = %+v", h)
	}

	// 7. Twenty minutes after the evaluation the crowd data has expired: the jam is cancelled
	// although the crowd server is still down.
	ce.now = t0.Add(20 * time.Minute)
	ce.runner.RunOnce(ctx)
	res, _ = ce.traff(poll)
	got = acceptedIDs(res)
	if len(got) != 1 || !got[idCrowdHead].Cancellation {
		t.Errorf("after expiry: %+v", res.Accepted)
	}

	// 8. The server is back with a fresh export: the jam reappears.
	ce.crowd.setStatus(http.StatusOK)
	ce.crowd.mu.Lock()
	ce.crowd.doc["generated"] = t0.Add(21 * time.Minute).Format(time.RFC3339)
	for _, s := range ce.crowd.segments() {
		s["expires"] = t0.Add(41 * time.Minute).Format(time.RFC3339)
	}
	ce.crowd.mu.Unlock()
	ce.now = t0.Add(22 * time.Minute)
	ce.runner.RunOnce(ctx)
	res, _ = ce.traff(poll)
	if got = acceptedIDs(res); len(got) != 1 || got[idCrowdHead].Cancellation {
		t.Errorf("after recovery: %+v", res.Accepted)
	}

	// No response contained data of the client, and every response was accepted (ce.traff).
	if logs := ce.logs.String(); strings.Contains(logs, "127.0.0.1") || strings.Contains(logs, "SUB0001") {
		t.Errorf("log contains client data:\n%s", logs)
	}
}

// TestEndToEndCrowdRun runs the runner loop with the crowd source on its own short interval.
func TestEndToEndCrowdRun(t *testing.T) {
	fc := newFakeCrowd(t, time.Now().Add(-time.Minute))
	srv := httptest.NewServer(fc)
	t.Cleanup(srv.Close)
	cs, err := crowd.NewSource(crowd.Config{URL: srv.URL, Interval: 10 * time.Millisecond, Merge: crowd.MergeOptions{Tolerance: 25}})
	if err != nil {
		t.Fatal(err)
	}
	st := store.New(30*time.Minute, time.Hour)
	r := source.NewRunner(st, time.Hour, slog.New(slog.NewTextHandler(&strings.Builder{}, nil)), cs)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { r.Run(ctx); close(done) }()
	deadline := time.Now().Add(5 * time.Second)
	for r.Status()[0].Details["downloads"] < 3 {
		if time.Now().After(deadline) {
			t.Fatalf("status = %+v", r.Status())
		}
		time.Sleep(5 * time.Millisecond)
	}
	// The jam dissolves; the next fetch cancels it.
	fc.setFree("17/70406/43015/N-E/MOTORWAY", "17/70407/43015/W-S/MOTORWAY", "17/70407/43016/N-E/MOTORWAY",
		"17/70408/43016/W-S/MOTORWAY", "17/70409/43017/W-E/MOTORWAY", "17/70410/43017/W-E/MOTORWAY", "17/70411/43017/W-S/MOTORWAY",
		"17/70412/43018/W-E/MOTORWAY", "17/70413/43018/W-E/MOTORWAY", "17/70414/43018/W-E/MOTORWAY", "17/70415/43018/W-E/MOTORWAY",
		"17/70416/43018/W-E/MOTORWAY")
	for {
		if e := st.Get(idCrowdJam); e != nil && e.Cancelled() {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("jam not cancelled")
		}
		time.Sleep(5 * time.Millisecond)
	}
	cancel()
	<-done
}
