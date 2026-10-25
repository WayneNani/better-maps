package datex2

import (
	"bytes"
	"context"
	"log/slog"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func newTestSource(t *testing.T, fs *feedServer, cfg SourceConfig) (*Source, *bytes.Buffer) {
	t.Helper()
	srv := httptest.NewServer(fs)
	t.Cleanup(srv.Close)
	c, err := NewClient(ClientConfig{Name: cfg.Feed, URL: srv.URL})
	if err != nil {
		t.Fatal(err)
	}
	var logs bytes.Buffer
	return NewSource(cfg, c, slog.New(slog.NewTextHandler(&logs, nil))), &logs
}

func TestSourceFetch(t *testing.T) {
	fs := &feedServer{body: readFixture(t, "synthetic_v2.xml"), etag: `"1"`}
	s, _ := newTestSource(t, fs, SourceConfig{Feed: "syn", Horizon: horizon})
	if s.Name() != "datex2-syn" || len(s.Coverage()) != 1 {
		t.Errorf("name %q coverage %v", s.Name(), s.Coverage())
	}
	msgs, err := s.Fetch(context.Background(), t0)
	if err != nil || len(msgs) != 12 || !strings.HasPrefix(msgs[0].ID, "de.datex2.syn:") {
		t.Fatalf("fetch: %d messages, %v", len(msgs), err)
	}
	// 304: the cached publication is evaluated again, at the new time.
	msgs, err = s.Fetch(context.Background(), t0.Add(5*time.Hour))
	if err != nil || len(msgs) != 12 {
		t.Fatalf("cached fetch: %d messages, %v", len(msgs), err)
	}
	// The valid period of syn-periods.0 that ended at 20:00 is replaced by the next one.
	if p := byID(msgs)["syn-periods.0"]; p.StartTime == nil {
		t.Error("syn-periods.0 missing")
	} else if want := time.Date(2026, 10, 26, 8, 0, 0, 0, time.UTC); !p.StartTime.Equal(want) {
		t.Errorf("next period starts %v", p.StartTime)
	}
	d := s.Details()
	if d["downloads"] != 1 || d["not_modified"] != 1 || d["messages"] != 12 || d["skipped_ended"] != 2 || d["situations"] != 21 {
		t.Errorf("details = %v", d)
	}
	// Errors keep the previous data in the store (nil slice) and are counted.
	fs.mu.Lock()
	fs.status = 500
	fs.mu.Unlock()
	if msgs, err := s.Fetch(context.Background(), t0); err == nil || msgs != nil {
		t.Errorf("failed fetch: %v %v", msgs, err)
	}
	fs.mu.Lock()
	fs.status = 0
	fs.body, fs.etag = []byte("<broken"), `"2"`
	fs.mu.Unlock()
	if msgs, err := s.Fetch(context.Background(), t0); err == nil || msgs != nil || !strings.Contains(err.Error(), "datex2 syn") {
		t.Errorf("parse error: %v %v", msgs, err)
	}
	if d := s.Details(); d["fetch_errors"] != 2 {
		t.Errorf("details = %v", d)
	}
	// The broken document was not committed: the next fetch downloads again.
	fs.set(readFixture(t, "synthetic_v3.xml"), `"2"`)
	if msgs, err := s.Fetch(context.Background(), t0); err != nil || len(msgs) != 7 {
		t.Errorf("recovered: %d %v", len(msgs), err)
	}
}

func TestSourceMinInterval(t *testing.T) {
	fs := &feedServer{body: readFixture(t, "container_v3.xml")}
	s, _ := newTestSource(t, fs, SourceConfig{Feed: "mc", MinInterval: 10 * time.Minute})
	for _, d := range []time.Duration{0, time.Minute, 9 * time.Minute, 10 * time.Minute} {
		if msgs, err := s.Fetch(context.Background(), t0.Add(d)); err != nil || len(msgs) != 1 {
			t.Fatalf("%v: %d %v", d, len(msgs), err)
		}
	}
	fs.mu.Lock()
	defer fs.mu.Unlock()
	if fs.requests != 2 {
		t.Errorf("%d downloads, want 2", fs.requests)
	}
}

func TestSourceDeltaWarningAndInconsistentCache(t *testing.T) {
	doc := strings.Replace(string(readFixture(t, "synthetic_v2.xml")), "allElementUpdate", "singleElementUpdate", 1)
	fs := &feedServer{body: []byte(doc), etag: `"x"`}
	s, logs := newTestSource(t, fs, SourceConfig{Feed: "delta"})
	if _, err := s.Fetch(context.Background(), t0); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(logs.String(), "update_method=singleElementUpdate") {
		t.Errorf("no warning:\n%s", logs)
	}
	// Validators without a cached publication must not happen; the source recovers.
	s2, _ := newTestSource(t, fs, SourceConfig{Feed: "x"})
	s2.client.Commit(&Response{etag: `"x"`})
	if _, err := s2.Fetch(context.Background(), t0); err == nil {
		t.Error("inconsistent cache not reported")
	}
	if msgs, err := s2.Fetch(context.Background(), t0); err != nil || len(msgs) == 0 {
		t.Errorf("no recovery: %v", err)
	}
}
