package source

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/store"
	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

type fakeSource struct {
	name     string
	coverage []traff.BBox
	mu       sync.Mutex
	results  []result
	calls    int
}

type result struct {
	msgs []traff.Message
	err  error
}

func (f *fakeSource) Name() string           { return f.name }
func (f *fakeSource) Coverage() []traff.BBox { return f.coverage }
func (f *fakeSource) Fetch(ctx context.Context, now time.Time) ([]traff.Message, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	r := f.results[min(f.calls, len(f.results)-1)]
	f.calls++
	return r.msgs, r.err
}

func msg(id string) traff.Message {
	return traff.Message{ID: id, Location: &traff.Location{From: traff.NewPoint(50, 10), To: traff.NewPoint(50.1, 10.1)},
		Events: []traff.Event{{Class: "CONGESTION", Type: "CONGESTION_QUEUE"}}}
}

func TestRunOnce(t *testing.T) {
	st := store.New(30*time.Minute, time.Hour)
	var logBuf bytes.Buffer
	log := slog.New(slog.NewTextHandler(&logBuf, nil))
	a := &fakeSource{name: "a", coverage: []traff.BBox{{MinLat: 47, MinLon: 5, MaxLat: 55, MaxLon: 15}}, results: []result{
		{msgs: []traff.Message{msg("a:1"), msg("a:2")}},
		{err: errors.New("down")},                                       // total failure: keep data
		{msgs: []traff.Message{msg("a:1")}, err: errors.New("partial")}, // partial: use data
	}}
	b := &fakeSource{name: "b", coverage: []traff.BBox{{MinLat: 45, MinLon: 5, MaxLat: 46, MaxLon: 6}}, results: []result{{msgs: []traff.Message{msg("b:1")}}}}
	r := NewRunner(st, time.Minute, log, a, b)
	now := time.Date(2026, 10, 25, 16, 0, 0, 0, time.UTC)
	r.now = func() time.Time { return now }

	r.RunOnce(context.Background())
	if act, _ := st.Counts(); act != 3 {
		t.Fatalf("active = %d", act)
	}
	r.RunOnce(context.Background())
	if act, _ := st.Counts(); act != 3 {
		t.Errorf("failed fetch removed data: active = %d", act)
	}
	stat := r.Status()
	if len(stat) != 2 || stat[0].Name != "a" || stat[0].LastError != "down" || stat[0].Messages != 2 || stat[1].LastError != "" {
		t.Errorf("status = %+v", stat)
	}
	r.RunOnce(context.Background())
	if act, cancelled := st.Counts(); act != 2 || cancelled != 1 {
		t.Errorf("after partial fetch: active=%d cancelled=%d", act, cancelled)
	}
	logs := logBuf.String()
	for _, want := range []string{"source fetch failed", "source fetch partially failed", "source fetched"} {
		if !strings.Contains(logs, want) {
			t.Errorf("log lacks %q:\n%s", want, logs)
		}
	}
	if len(r.Coverage()) != 2 {
		t.Errorf("coverage = %v", r.Coverage())
	}
	global := NewRunner(st, time.Minute, log, a, &fakeSource{name: "g", results: []result{{}}})
	if global.Coverage() != nil {
		t.Error("a source with global coverage makes the union global")
	}
}

func TestRunStopsOnCancel(t *testing.T) {
	st := store.New(30*time.Minute, time.Hour)
	a := &fakeSource{name: "a", results: []result{{msgs: []traff.Message{}}}}
	r := NewRunner(st, 10*time.Millisecond, slog.New(slog.NewTextHandler(&bytes.Buffer{}, nil)), a)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { r.Run(ctx); close(done) }()
	time.Sleep(35 * time.Millisecond)
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("Run did not return after cancel")
	}
	a.mu.Lock()
	calls := a.calls
	a.mu.Unlock()
	if calls < 2 {
		t.Errorf("source fetched %d times, expected periodic fetching", calls)
	}
}
