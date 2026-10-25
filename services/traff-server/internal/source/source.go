// Package source defines the interface for traffic data sources and a runner that fetches them
// periodically into the message store.
//
// New sources (DATEX II from Mobilithek, crowd-sourced congestion data, ...) implement Source and
// are registered with a Runner. Each source owns the message IDs it produces; IDs should carry a
// source-specific prefix (e.g. "de.autobahn.warning:...") so that they never collide.
package source

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/store"
	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

// Source produces TraFF messages.
type Source interface {
	// Name is a short, stable identifier of the source, e.g. "autobahn".
	Name() string
	// Coverage returns the area for which the source supplies data. An empty result means that
	// the coverage is unknown or global.
	Coverage() []traff.BBox
	// Fetch returns the complete set of messages currently known to the source. A non-nil error
	// together with a nil slice means that the fetch failed completely and the previous snapshot
	// should be kept. A non-nil error together with a non-nil slice reports a partial failure;
	// the slice is still used as the new snapshot.
	Fetch(ctx context.Context, now time.Time) ([]traff.Message, error)
}

// Status describes the outcome of the latest fetch of a source.
type Status struct {
	Name        string    `json:"name"`
	LastAttempt time.Time `json:"last_attempt"`
	LastSuccess time.Time `json:"last_success"`
	LastError   string    `json:"last_error,omitempty"`
	Messages    int       `json:"messages"`
}

// Runner periodically fetches all sources into a store.
type Runner struct {
	store    *store.Store
	sources  []Source
	interval time.Duration
	now      func() time.Time
	log      *slog.Logger

	mu     sync.Mutex
	status map[string]*Status
}

// NewRunner creates a runner.
func NewRunner(st *store.Store, interval time.Duration, log *slog.Logger, sources ...Source) *Runner {
	r := &Runner{store: st, sources: sources, interval: interval, now: time.Now, log: log, status: map[string]*Status{}}
	for _, s := range sources {
		r.status[s.Name()] = &Status{Name: s.Name()}
	}
	return r
}

// Coverage returns the union of the coverage lists of all sources. If any source has global
// coverage, the result is empty (global).
func (r *Runner) Coverage() []traff.BBox {
	var res []traff.BBox
	for _, s := range r.sources {
		c := s.Coverage()
		if len(c) == 0 {
			return nil
		}
		res = append(res, c...)
	}
	return res
}

// RunOnce fetches every source once and updates the store. Sources are fetched one after the
// other; an error in one source does not affect the others.
func (r *Runner) RunOnce(ctx context.Context) {
	for _, s := range r.sources {
		start := r.now()
		msgs, err := s.Fetch(ctx, start)
		r.mu.Lock()
		st := r.status[s.Name()]
		st.LastAttempt = start
		if err != nil {
			st.LastError = err.Error()
		} else {
			st.LastError = ""
		}
		if msgs != nil {
			st.LastSuccess = start
			st.Messages = len(msgs)
		}
		r.mu.Unlock()
		if msgs == nil {
			if err != nil {
				r.log.Warn("source fetch failed, keeping previous data", "source", s.Name(), "err", err)
			}
			continue
		}
		stats := r.store.Replace(s.Name(), msgs, r.now())
		attrs := []any{"source", s.Name(), "messages", len(msgs), "new", stats.New, "changed", stats.Changed,
			"renewed", stats.Renewed, "cancelled", stats.Cancelled, "skipped", stats.Skipped, "duration", r.now().Sub(start).Round(time.Millisecond)}
		if err != nil {
			r.log.Warn("source fetch partially failed", append(attrs, "err", err)...)
		} else {
			r.log.Info("source fetched", attrs...)
		}
	}
	r.store.Purge(r.now())
}

// Run calls RunOnce immediately and then every interval until ctx is cancelled.
func (r *Runner) Run(ctx context.Context) {
	t := time.NewTicker(r.interval)
	defer t.Stop()
	for {
		r.RunOnce(ctx)
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// Status returns a copy of the status of all sources.
func (r *Runner) Status() []Status {
	r.mu.Lock()
	defer r.mu.Unlock()
	res := make([]Status, 0, len(r.sources))
	for _, s := range r.sources {
		res = append(res, *r.status[s.Name()])
	}
	return res
}
