package server

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/clientcheck"
	"github.com/WayneNani/better-maps/services/traff-server/internal/source"
	"github.com/WayneNani/better-maps/services/traff-server/internal/source/autobahn"
	"github.com/WayneNani/better-maps/services/traff-server/internal/store"
	"github.com/WayneNani/better-maps/services/traff-server/internal/subscription"
	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

var update = flag.Bool("update", false, "rewrite golden files")

var t0 = time.Date(2026, 10, 25, 16, 0, 0, 0, time.UTC)

const fixtureDir = "../source/autobahn/testdata"

// fixtureMessages maps recorded Autobahn API responses, like the Autobahn source does.
func fixtureMessages(t *testing.T, now time.Time, names ...string) []traff.Message {
	t.Helper()
	tz, err := time.LoadLocation("Europe/Berlin")
	if err != nil {
		t.Fatal(err)
	}
	var res []traff.Message
	for _, name := range names {
		data, err := os.ReadFile(filepath.Join(fixtureDir, name+".json"))
		if err != nil {
			t.Fatal(err)
		}
		road, svc, _ := strings.Cut(name, "_")
		items, err := autobahn.DecodeItems(data, svc)
		if err != nil {
			t.Fatal(err)
		}
		for i := range items {
			if m, err := autobahn.MapItem(road, svc, &items[i], now, tz); err == nil {
				res = append(res, m)
			}
		}
	}
	return res
}

type env struct {
	t     *testing.T
	store *store.Store
	subs  *subscription.Manager
	srv   *Server
	http  *httptest.Server
	now   time.Time
	logs  *bytes.Buffer
}

var germany = []traff.BBox{{MinLat: 47.2, MinLon: 5.8, MaxLat: 55.1, MaxLon: 15.1}}

func newEnv(t *testing.T, maxSubs int) *env {
	t.Helper()
	e := &env{t: t, now: t0, logs: &bytes.Buffer{}}
	e.store = store.New(30*time.Minute, time.Hour)
	e.subs = subscription.NewManager(e.store, time.Hour, maxSubs)
	n := 0
	e.subs.SetIDGenerator(func() (string, error) {
		n++
		return fmt.Sprintf("SUB%04d", n), nil
	})
	e.srv = &Server{
		Store: e.store, Subs: e.subs, MaxFilters: 8,
		Coverage: func() []traff.BBox { return germany },
		Status:   func() []source.Status { return []source.Status{{Name: "autobahn", Messages: 1}} },
		Now:      func() time.Time { return e.now },
		Log:      slog.New(slog.NewTextHandler(e.logs, &slog.HandlerOptions{Level: slog.LevelDebug})),
	}
	e.http = httptest.NewServer(e.srv.Handler())
	t.Cleanup(e.http.Close)
	return e
}

func (e *env) post(path, body string) (int, []byte) {
	e.t.Helper()
	req, _ := http.NewRequest(http.MethodPost, e.http.URL+path, strings.NewReader(body))
	req.Header.Set("Content-Type", "application/xml")
	req.Header.Set("User-Agent", "CoMaps/2026.10 secret-agent")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if resp.StatusCode == http.StatusOK && resp.Header.Get("Content-Type") != "application/xml; charset=utf-8" {
		e.t.Errorf("content type %q", resp.Header.Get("Content-Type"))
	}
	return resp.StatusCode, data
}

// traff posts a TraFF request and returns the response as the CoMaps client sees it.
func (e *env) traff(body string) (*clientcheck.Result, []byte) {
	e.t.Helper()
	code, data := e.post("/", body)
	if code != http.StatusOK {
		e.t.Fatalf("HTTP %d for %s: %s", code, body, data)
	}
	res, err := clientcheck.ParseResponse(data)
	if err != nil {
		e.t.Fatalf("client would reject response: %v\n%s", err, data)
	}
	return res, data
}

func golden(t *testing.T, name string, got []byte) {
	t.Helper()
	path := filepath.Join("testdata", name)
	if *update {
		if err := os.MkdirAll("testdata", 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, got, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	want, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("%v (run go test -update to create)", err)
	}
	if !bytes.Equal(got, want) {
		t.Errorf("response differs from golden file %s (run go test -update after review):\n%s", path, got)
	}
}

func acceptedIDs(r *clientcheck.Result) map[string]clientcheck.Message {
	m := map[string]clientcheck.Message{}
	for _, a := range r.Accepted {
		m[a.ID] = a
	}
	return m
}

// Bounding boxes as the client sends them: "minLat minLon maxLat maxLon" of MWM borders.
const (
	bboxHamburg = "53.3 9.7 53.7 10.3"
	bboxBavaria = "47.27 8.97 50.56 13.84"
	bboxFrance  = "48.7 2.2 49.0 2.5"
)

func subscribeReq(bboxes ...string) string {
	var b strings.Builder
	b.WriteString("<request operation=\"SUBSCRIBE\">\n<filter_list>\n")
	for _, x := range bboxes {
		fmt.Fprintf(&b, "<filter bbox=%q/>\n", x)
	}
	b.WriteString("</filter_list>\n</request>")
	return b.String()
}

func TestEndToEndLifecycle(t *testing.T) {
	e := newEnv(t, 0)
	initial := fixtureMessages(t, t0, "A7_warning", "A7_closure", "A8_warning", "A9_closure", "A9_roadworks")
	e.store.Replace("autobahn", initial, t0)

	// Capabilities.
	res, data := e.traff(`<request operation="GET_CAPABILITIES"/>`)
	if res.Status != "OK" {
		t.Errorf("capabilities status %s", res.Status)
	}
	golden(t, "capabilities.xml", data)

	// Subscribe exactly like HttpTraffSource::Subscribe() does.
	res, data = e.traff(subscribeReq(bboxBavaria))
	golden(t, "subscribe.xml", data)
	if res.Status != "OK" || res.SubscriptionID != "SUB0001" || res.Timeout != 3600 || !res.HasFeed {
		t.Fatalf("subscribe response %+v", res)
	}
	if len(res.Accepted) == 0 {
		t.Fatal("client would not accept any message")
	}
	for id, reason := range res.Rejected {
		// Roadworks without speed limit or closure only carry event types the current client
		// does not know yet; everything else must be accepted.
		if !strings.HasPrefix(id, "de.autobahn.roadworks:") || reason != "no event known to the client" {
			t.Errorf("client rejects %s: %s", id, reason)
		}
	}
	for _, m := range res.Accepted {
		b := e.store.Get(m.ID).BBox
		if !b.Intersects(traff.BBox{MinLat: 47.27, MinLon: 8.97, MaxLat: 50.56, MaxLon: 13.84}) {
			t.Errorf("message %s outside the filter", m.ID)
		}
	}
	first := len(res.Accepted) + len(res.Rejected)

	// Poll without changes: empty feed.
	e.now = t0.Add(5 * time.Minute)
	res, data = e.traff(`<request operation="POLL" subscription_id="SUB0001"/>`)
	golden(t, "poll_empty.xml", data)
	if res.Status != "OK" || !res.HasFeed || len(res.Accepted)+len(res.Rejected) != 0 {
		t.Errorf("unchanged poll = %+v", res)
	}

	// Next fetch: one A8 warning disappears, another changes.
	var next []traff.Message
	var removed, changed string
	for _, m := range initial {
		if strings.HasPrefix(m.ID, "de.autobahn.warning:") && m.Location.RoadRef == "A 8" && m.Location.From.Coord.Lat < 48 {
			if removed == "" {
				removed = m.ID
				continue
			}
			if changed == "" {
				changed = m.ID
				m.Events = []traff.Event{{Class: "CONGESTION", Type: "CONGESTION_SLOW_TRAFFIC"}}
			}
		}
		next = append(next, m)
	}
	if removed == "" || changed == "" {
		t.Fatal("fixture lacks A8 warnings near Salzburg")
	}
	e.store.Replace("autobahn", next, e.now)
	res, data = e.traff(`<request operation="POLL" subscription_id="SUB0001"/>`)
	golden(t, "poll_delta.xml", data)
	got := acceptedIDs(res)
	if len(got) != 2 || !got[removed].Cancellation || got[changed].Events[0] != "CONGESTION_SLOW_TRAFFIC" {
		t.Errorf("delta = %+v", res.Accepted)
	}

	// Change the subscription like HttpTraffSource::ChangeSubscription(): add Hamburg.
	res, _ = e.traff(`<request operation="SUBSCRIPTION_CHANGE" subscription_id="SUB0001">
<filter_list>
<filter bbox="` + bboxBavaria + `"/>
<filter bbox="` + bboxHamburg + `"/>
</filter_list>
</request>`)
	if res.Status != "OK" || res.SubscriptionID != "SUB0001" || len(res.Accepted) == 0 {
		t.Fatalf("change = %+v", res)
	}
	for _, m := range res.Accepted {
		b := e.store.Get(m.ID).BBox
		if !b.Intersects(traff.BBox{MinLat: 53.3, MinLon: 9.7, MaxLat: 53.7, MaxLon: 10.3}) {
			t.Errorf("change feed contains %s, which is not in the added area", m.ID)
		}
	}

	// Heartbeat, unsubscribe, then the subscription is unknown.
	if res, _ = e.traff(`<request operation="HEARTBEAT" subscription_id="SUB0001"/>`); res.Status != "OK" {
		t.Errorf("heartbeat = %s", res.Status)
	}
	if res, _ = e.traff(`<request operation="UNSUBSCRIBE" subscription_id="SUB0001"/>`); res.Status != "OK" {
		t.Errorf("unsubscribe = %s", res.Status)
	}
	if res, _ = e.traff(`<request operation="POLL" subscription_id="SUB0001"/>`); res.Status != "SUBSCRIPTION_UNKNOWN" {
		t.Errorf("poll after unsubscribe = %s", res.Status)
	}

	// One-shot poll (subscriptionless) returns the complete matching set.
	res, _ = e.traff(`<request operation="POLL"><filter_list><filter bbox="` + bboxBavaria + `"/></filter_list></request>`)
	if res.Status != "OK" || res.SubscriptionID != "" || len(res.Accepted)+len(res.Rejected) != first-1 {
		t.Errorf("one-shot poll: %s, %d messages (first feed had %d)", res.Status, len(res.Accepted)+len(res.Rejected), first)
	}

	// Privacy: nothing identifying ends up in the log.
	logs := e.logs.String()
	for _, secret := range []string{"127.0.0.1", "secret-agent", "SUB0001", "47.27", "53.3"} {
		if strings.Contains(logs, secret) {
			t.Errorf("log contains %q:\n%s", secret, logs)
		}
	}
	if !strings.Contains(logs, "operation=SUBSCRIBE") {
		t.Errorf("debug log lacks request summary:\n%s", logs)
	}
}

func TestSubscriptionExpiresAfterInactivity(t *testing.T) {
	e := newEnv(t, 0)
	e.store.Replace("autobahn", fixtureMessages(t, t0, "A7_warning"), t0)
	res, _ := e.traff(subscribeReq(bboxHamburg))
	e.now = t0.Add(61 * time.Minute)
	if res, _ = e.traff(`<request operation="POLL" subscription_id="` + res.SubscriptionID + `"/>`); res.Status != "SUBSCRIPTION_UNKNOWN" {
		t.Errorf("poll after TTL = %s", res.Status)
	}
}

func TestErrorStatuses(t *testing.T) {
	e := newEnv(t, 0)
	sub, _ := e.traff(subscribeReq(bboxHamburg))
	id := sub.SubscriptionID
	manyFilters := subscribeReq(strings.Split(strings.Repeat(bboxHamburg+",", 9), ",")[:9]...)
	cases := []struct {
		name, body, status string
	}{
		{"malformed", `<request operation="SUBSCRIBE"`, "INVALID"},
		{"wrong root", `<feed/>`, "INVALID"},
		{"unknown operation", `<request operation="DANCE"/>`, "INVALID"},
		{"subscribe without filter list", `<request operation="SUBSCRIBE"/>`, "INVALID"},
		{"subscribe with id", `<request operation="SUBSCRIBE" subscription_id="x"><filter_list/></request>`, "INVALID"},
		{"subscribe bad bbox", `<request operation="SUBSCRIBE"><filter_list><filter bbox="1 2"/></filter_list></request>`, "INVALID"},
		{"too many filters", manyFilters, "INVALID"},
		{"subscribe outside coverage", subscribeReq(bboxFrance), "NOT_COVERED"},
		{"subscribe partially covered", subscribeReq(bboxHamburg, bboxFrance), "PARTIALLY_COVERED"},
		{"subscribe empty filter list", `<request operation="SUBSCRIBE"><filter_list/></request>`, "OK"},
		{"change without id", `<request operation="SUBSCRIPTION_CHANGE"><filter_list/></request>`, "INVALID"},
		{"change without filter", `<request operation="SUBSCRIPTION_CHANGE" subscription_id="` + id + `"/>`, "INVALID"},
		{"change unknown", `<request operation="SUBSCRIPTION_CHANGE" subscription_id="nope"><filter_list/></request>`, "SUBSCRIPTION_UNKNOWN"},
		{"change not covered", `<request operation="SUBSCRIPTION_CHANGE" subscription_id="` + id + `"><filter_list><filter bbox="` + bboxFrance + `"/></filter_list></request>`, "NOT_COVERED"},
		{"change not covered unknown", `<request operation="SUBSCRIPTION_CHANGE" subscription_id="nope"><filter_list><filter bbox="` + bboxFrance + `"/></filter_list></request>`, "SUBSCRIPTION_UNKNOWN"},
		{"change partially covered", `<request operation="SUBSCRIPTION_CHANGE" subscription_id="` + id + `"><filter_list><filter bbox="` + bboxHamburg + `"/><filter bbox="` + bboxFrance + `"/></filter_list></request>`, "PARTIALLY_COVERED"},
		{"poll unknown", `<request operation="POLL" subscription_id="nope"/>`, "SUBSCRIPTION_UNKNOWN"},
		{"one-shot poll without filter", `<request operation="POLL"/>`, "INVALID"},
		{"heartbeat without id", `<request operation="HEARTBEAT"/>`, "INVALID"},
		{"heartbeat unknown", `<request operation="HEARTBEAT" subscription_id="nope"/>`, "SUBSCRIPTION_UNKNOWN"},
		{"unsubscribe without id", `<request operation="UNSUBSCRIBE"/>`, "INVALID"},
		{"unsubscribe unknown", `<request operation="UNSUBSCRIBE" subscription_id="nope"/>`, "OK"},
		{"push", `<request operation="FEED"><feed/></request>`, "PUSH_REJECTED"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			res, data := e.traff(c.body)
			if res.Status != c.status {
				t.Errorf("status = %s, want %s\n%s", res.Status, c.status, data)
			}
		})
	}
	// The subscription survived the failed change.
	if res, _ := e.traff(`<request operation="POLL" subscription_id="` + id + `"/>`); res.Status != "OK" {
		t.Errorf("subscription lost after failed change: %s", res.Status)
	}
}

func TestSubscriptionLimitsAndInternalErrors(t *testing.T) {
	e := newEnv(t, 1)
	if res, _ := e.traff(subscribeReq(bboxHamburg)); res.Status != "OK" {
		t.Fatal(res.Status)
	}
	if res, _ := e.traff(subscribeReq(bboxHamburg)); res.Status != "SUBSCRIPTION_REJECTED" {
		t.Errorf("second subscription = %s", res.Status)
	}
	e2 := newEnv(t, 0)
	e2.subs.SetIDGenerator(func() (string, error) { return "", errors.New("no randomness") })
	if res, _ := e2.traff(subscribeReq(bboxHamburg)); res.Status != "INTERNAL_ERROR" {
		t.Errorf("ID failure = %s", res.Status)
	}
	// Without coverage information every area is accepted.
	e2.srv.Coverage = nil
	e2.subs.SetIDGenerator(subscription.RandomID)
	if res, _ := e2.traff(subscribeReq(bboxFrance)); res.Status != "OK" {
		t.Errorf("no coverage = %s", res.Status)
	}
}

func TestHTTPLevel(t *testing.T) {
	e := newEnv(t, 0)
	e.store.Replace("autobahn", fixtureMessages(t, t0, "A7_warning"), t0)

	resp, err := http.Get(e.http.URL + "/")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 || !strings.Contains(string(body), "TraFF") {
		t.Errorf("GET / = %d %s", resp.StatusCode, body)
	}

	req, _ := http.NewRequest(http.MethodPut, e.http.URL+"/", nil)
	resp, _ = http.DefaultClient.Do(req)
	resp.Body.Close()
	if resp.StatusCode != http.StatusMethodNotAllowed || resp.Header.Get("Allow") == "" {
		t.Errorf("PUT / = %d", resp.StatusCode)
	}

	resp, _ = http.Get(e.http.URL + "/nothing")
	resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("GET /nothing = %d", resp.StatusCode)
	}

	// The /traff alias accepts requests as well.
	if code, data := e.post("/traff", `<request operation="GET_CAPABILITIES"/>`); code != 200 || !bytes.Contains(data, []byte("capabilities")) {
		t.Errorf("/traff = %d %s", code, data)
	}

	// Oversized request bodies are rejected at the HTTP level.
	big := `<request operation="POLL">` + strings.Repeat("<x/>", MaxRequestBytes/4) + `</request>`
	if code, _ := e.post("/", big); code != http.StatusRequestEntityTooLarge {
		t.Errorf("big request = %d", code)
	}

	// Health check.
	e.traff(subscribeReq(bboxHamburg))
	resp, err = http.Get(e.http.URL + "/healthz")
	if err != nil {
		t.Fatal(err)
	}
	var health struct {
		Status        string
		Messages      int
		Subscriptions int
		Sources       []source.Status
	}
	if err := json.NewDecoder(resp.Body).Decode(&health); err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if health.Status != "ok" || health.Messages != 3 || health.Subscriptions != 1 || len(health.Sources) != 1 {
		t.Errorf("health = %+v", health)
	}
	resp, _ = http.Post(e.http.URL+"/healthz", "text/plain", nil)
	resp.Body.Close()
	if resp.StatusCode != http.StatusMethodNotAllowed {
		t.Errorf("POST /healthz = %d", resp.StatusCode)
	}
	e.srv.Status = nil
	resp, _ = http.Get(e.http.URL + "/healthz")
	resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Errorf("health without status func = %d", resp.StatusCode)
	}
}

func TestDefaultNow(t *testing.T) {
	s := &Server{}
	if d := time.Since(s.now()); d < 0 || d > time.Minute {
		t.Errorf("now() = %v", s.now())
	}
}
