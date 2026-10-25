package server

import (
	"compress/gzip"
	"context"
	"encoding/json"
	"encoding/xml"
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

	"github.com/WayneNani/better-maps/services/traff-server/internal/clientcheck"
	"github.com/WayneNani/better-maps/services/traff-server/internal/dedup"
	"github.com/WayneNani/better-maps/services/traff-server/internal/source"
	"github.com/WayneNani/better-maps/services/traff-server/internal/source/autobahn"
	"github.com/WayneNani/better-maps/services/traff-server/internal/source/datex2"
	"github.com/WayneNani/better-maps/services/traff-server/internal/store"
	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

// fakeAutobahnAPI serves the recorded Autobahn fixtures ("/<road>/services/<service>").
func fakeAutobahnAPI(t *testing.T) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
		if len(parts) != 3 || parts[1] != "services" {
			http.NotFound(w, r)
			return
		}
		data, err := os.ReadFile(filepath.Join(fixtureDir, parts[0]+"_"+parts[2]+".json"))
		if err != nil {
			data = []byte(fmt.Sprintf(`{%q:[]}`, parts[2]))
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(data)
	}))
	t.Cleanup(srv.Close)
	return srv
}

// fakeDatexFeed serves a DATEX II document gzip-compressed with an ETag, like MobiData BW.
type fakeDatexFeed struct {
	mu       sync.Mutex
	file     string
	requests int
	notMod   int
}

func (f *fakeDatexFeed) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.requests++
	etag := `"` + f.file + `"`
	w.Header().Set("ETag", etag)
	if r.Header.Get("If-None-Match") == etag {
		f.notMod++
		w.WriteHeader(http.StatusNotModified)
		return
	}
	data, err := os.ReadFile(filepath.Join("testdata", f.file))
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/xml; charset=utf-8")
	w.Header().Set("Content-Encoding", "gzip")
	zw := gzip.NewWriter(w)
	_, _ = zw.Write(data)
	_ = zw.Close()
}

func (f *fakeDatexFeed) serve(file string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.file = file
}

type twoSources struct {
	*env
	runner *source.Runner
	feed   *fakeDatexFeed
}

// newTwoSources wires the real Autobahn and DATEX II sources (against local fake servers), the
// runner with duplicate suppression, the store and the HTTP handler, as cmd/traff-server does.
func newTwoSources(t *testing.T, withDedup bool) *twoSources {
	t.Helper()
	e := newEnv(t, 0)
	tz, err := time.LoadLocation("Europe/Berlin")
	if err != nil {
		t.Fatal(err)
	}
	ab := autobahn.NewSource(autobahn.NewClient(fakeAutobahnAPI(t).URL, "test", 0), []string{"A7", "A9"}, tz, nil)
	feed := &fakeDatexFeed{file: "datex2_e2e.xml"}
	feedSrv := httptest.NewServer(feed)
	t.Cleanup(feedSrv.Close)
	client, err := datex2.NewClient(datex2.ClientConfig{Name: "e2e", URL: feedSrv.URL + "/situations.xml"})
	if err != nil {
		t.Fatal(err)
	}
	dx := datex2.NewSource(datex2.SourceConfig{Feed: "e2e", Horizon: 7 * 24 * time.Hour}, client, nil)
	log := slog.New(slog.NewTextHandler(e.logs, &slog.HandlerOptions{Level: slog.LevelDebug}))
	r := source.NewRunner(e.store, 5*time.Minute, log, ab, dx)
	r.SetClock(func() time.Time { return e.now })
	if withDedup {
		r.SetDeduplicator(dedup.New(dedup.Config{}))
	}
	e.srv.Coverage, e.srv.Status = r.Coverage, r.Status
	return &twoSources{env: e, runner: r, feed: feed}
}

func (ts *twoSources) health(t *testing.T) (h struct {
	Messages   int             `json:"messages"`
	Duplicates int             `json:"duplicates_suppressed"`
	Sources    []source.Status `json:"sources"`
}) {
	t.Helper()
	resp, err := http.Get(ts.http.URL + "/healthz")
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
	bboxGermany = "47.27 5.87 55.06 15.04"
	idA7Queue   = "de.datex2.e2e:e2e-a7-queue.0"
	idA9Closure = "de.datex2.e2e:e2e-a9-closure.0"
)

func TestEndToEndTwoSources(t *testing.T) {
	ts := newTwoSources(t, true)
	ts.runner.RunOnce(context.Background())

	h := ts.health(t)
	if h.Duplicates != 2 || len(h.Sources) != 2 || h.Sources[1].Name != "datex2-e2e" || h.Sources[1].Duplicates != 2 ||
		h.Sources[1].Messages != 7 || h.Sources[1].Details["downloads"] != 1 || h.Sources[0].LastError != "" || h.Sources[1].LastError != "" {
		t.Fatalf("health = %+v", h)
	}

	// Subscribe for all of Germany: both sources, no duplicates.
	res, data := ts.traff(subscribeReq(bboxGermany))
	golden(t, "two_sources_subscribe.xml", data)
	if res.Status != "OK" {
		t.Fatalf("subscribe = %s", res.Status)
	}
	all := map[string]bool{}
	for _, m := range res.Accepted {
		all[m.ID] = true
	}
	for id := range res.Rejected {
		all[id] = true
	}
	var nAutobahn, nDatex int
	for id := range all {
		switch {
		case strings.HasPrefix(id, "de.autobahn."):
			nAutobahn++
		case strings.HasPrefix(id, "de.datex2.e2e:"):
			nDatex++
		default:
			t.Errorf("unexpected message %s", id)
		}
	}
	if nAutobahn == 0 || nDatex != 5 {
		t.Errorf("%d Autobahn and %d DATEX II messages", nAutobahn, nDatex)
	}
	for _, id := range []string{idA7Queue, idA9Closure} {
		if all[id] {
			t.Errorf("duplicate %s in feed", id)
		}
	}
	for _, id := range []string{"de.datex2.e2e:e2e-a7-opposite.0", "de.datex2.e2e:e2e-a7-accident.0", "de.datex2.e2e:e2e-a9-closure-later.0",
		"de.datex2.e2e:e2e-b2-roadworks.0", "de.datex2.e2e:e2e-hamburg.0"} {
		if !all[id] {
			t.Errorf("%s missing", id)
		}
	}
	// No message of the feed is a duplicate of another one by the heuristic.
	checkNoDuplicates(t, ts.store)
	for id, reason := range res.Rejected {
		if reason != "no event known to the client" {
			t.Errorf("client rejects %s: %s", id, reason)
		}
	}

	// Next fetch: the roadworks disappear from the DATEX II feed, the breakdown becomes a fire.
	ts.now = t0.Add(5 * time.Minute)
	ts.feed.serve("datex2_e2e_update.xml")
	ts.runner.RunOnce(context.Background())
	res, data = ts.traff(`<request operation="POLL" subscription_id="SUB0001"/>`)
	golden(t, "two_sources_poll.xml", data)
	// The fire (INCIDENT only) is well-formed but not yet understood by the client.
	got := acceptedIDs(res)
	if len(got) != 1 || !got["de.datex2.e2e:e2e-b2-roadworks.0"].Cancellation || res.Rejected["de.datex2.e2e:e2e-hamburg.0"] != "no event known to the client" {
		t.Errorf("delta = %+v, rejected %v", res.Accepted, res.Rejected)
	}

	// Unchanged feed: 304, empty poll.
	ts.now = t0.Add(10 * time.Minute)
	ts.runner.RunOnce(context.Background())
	if res, _ = ts.traff(`<request operation="POLL" subscription_id="SUB0001"/>`); len(res.Accepted)+len(res.Rejected) != 0 {
		t.Errorf("unchanged poll = %+v", res)
	}
	ts.feed.mu.Lock()
	if ts.feed.notMod != 1 {
		t.Errorf("%d not-modified answers", ts.feed.notMod)
	}
	ts.feed.mu.Unlock()

	// A failing DATEX II feed keeps its messages and does not affect the Autobahn source.
	ts.feed.serve("missing.xml")
	ts.now = t0.Add(15 * time.Minute)
	ts.runner.RunOnce(context.Background())
	h = ts.health(t)
	if h.Sources[1].LastError == "" || h.Sources[0].LastError != "" || h.Sources[1].Details["fetch_errors"] != 1 {
		t.Errorf("health after failure = %+v", h)
	}
	if e := ts.store.Get("de.datex2.e2e:e2e-hamburg.0"); e == nil || e.Cancelled() {
		t.Error("messages of the failing feed were dropped")
	}

	// One-shot poll for Hamburg sees both sources.
	res, _ = ts.traff(`<request operation="POLL"><filter_list><filter bbox="` + bboxHamburg + `"/></filter_list></request>`)
	if _, ok := res.Rejected["de.datex2.e2e:e2e-a7-accident.0"]; !ok || len(res.Accepted) == 0 {
		t.Errorf("Hamburg one-shot lacks the DATEX II accident: %v %v", res.Accepted, res.Rejected)
	}
}

// checkNoDuplicates verifies that no two active messages of different sources are duplicates.
func checkNoDuplicates(t *testing.T, st *store.Store) {
	t.Helper()
	var entries []*store.Entry
	st.ForEach(func(e *store.Entry) {
		if !e.Cancelled() {
			entries = append(entries, e)
		}
	})
	m := dedup.New(dedup.Config{})
	for i, a := range entries {
		for _, b := range entries[i+1:] {
			if a.Source != b.Source && m.Duplicate(&a.Msg, &b.Msg) {
				t.Errorf("duplicates in feed: %s and %s", a.Msg.ID, b.Msg.ID)
			}
		}
	}
}

func TestEndToEndTwoSourcesWithoutDedup(t *testing.T) {
	ts := newTwoSources(t, false)
	ts.runner.RunOnce(context.Background())
	res, _ := ts.traff(subscribeReq(bboxGermany))
	all := acceptedIDs(res)
	for _, id := range []string{idA7Queue, idA9Closure} {
		if _, ok := all[id]; !ok {
			if _, rej := res.Rejected[id]; !rej {
				t.Errorf("%s missing without dedup", id)
			}
		}
	}
	if h := ts.health(t); h.Duplicates != 0 {
		t.Errorf("duplicates = %d", h.Duplicates)
	}
}

// TestEveryResponseAcceptedByClient runs all operations against the two-source setup and checks
// every response with the client rules.
func TestEveryResponseAcceptedByClient(t *testing.T) {
	ts := newTwoSources(t, true)
	ts.runner.RunOnce(context.Background())
	reqs := []string{
		`<request operation="GET_CAPABILITIES"/>`,
		subscribeReq(bboxBavaria, bboxHamburg),
		`<request operation="POLL" subscription_id="SUB0001"/>`,
		`<request operation="SUBSCRIPTION_CHANGE" subscription_id="SUB0001"><filter_list><filter bbox="` + bboxGermany + `"/></filter_list></request>`,
		`<request operation="HEARTBEAT" subscription_id="SUB0001"/>`,
		`<request operation="POLL"><filter_list><filter min_road_class="MOTORWAY" bbox="` + bboxGermany + `"/></filter_list></request>`,
		`<request operation="UNSUBSCRIBE" subscription_id="SUB0001"/>`,
	}
	for _, r := range reqs {
		code, data := ts.post("/", r)
		if code != http.StatusOK {
			t.Fatalf("%s: HTTP %d", r, code)
		}
		res, err := clientcheck.ParseResponse(data)
		if err != nil {
			t.Fatalf("%s: client rejects response: %v", r, err)
		}
		if res.Status != "OK" && res.Status != "PARTIALLY_COVERED" {
			t.Errorf("%s: %s", r, res.Status)
		}
		for id, reason := range res.Rejected {
			if reason != "no event known to the client" {
				t.Errorf("%s: client rejects %s: %s", r, id, reason)
			}
		}
		// Road class filter: only motorways.
		if strings.Contains(r, "min_road_class") {
			var feed struct {
				Messages []traff.Message `xml:"feed>message"`
			}
			if err := xml.Unmarshal(data, &feed); err != nil {
				t.Fatal(err)
			}
			for _, m := range feed.Messages {
				if m.Location != nil && m.Location.RoadClass != "" && m.Location.RoadClass != traff.RoadClassMotorway {
					t.Errorf("%s has road class %s", m.ID, m.Location.RoadClass)
				}
			}
		}
	}
	if logs := ts.logs.String(); strings.Contains(logs, "127.0.0.1") || strings.Contains(logs, "SUB0001") {
		t.Errorf("log contains client data:\n%s", logs)
	}
	if !strings.Contains(ts.logs.String(), "duplicate suppressed") {
		t.Errorf("no duplicate log entry:\n%s", ts.logs.String())
	}
}
