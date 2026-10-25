// Package source defines the interface for traffic data sources and a runner that fetches them
// periodically into the message store.
//
// New sources (DATEX II from Mobilithek, crowd-sourced congestion data, ...) implement Source and
// are registered with a Runner. Each source owns the message IDs it produces; IDs should carry a
// source-specific prefix (e.g. "de.autobahn.warning:...") so that they never collide.
//
// The order of registration is the priority: with a Deduplicator, a message is dropped if it
// duplicates an active message of a source registered earlier.
package source

import (
	"context"
	"log/slog"
	"maps"
	"slices"
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

// DetailsReporter is implemented by sources that report additional counters (e.g. skipped
// records by reason). The counters appear in Status.Details.
type DetailsReporter interface {
	Details() map[string]int
}

// Deduplicator removes messages that duplicate messages of other sources.
type Deduplicator interface {
	// Filter returns the messages of msgs that do not duplicate a message in ref, and for each
	// dropped message the ID of the message it duplicates.
	Filter(msgs, ref []traff.Message) (kept []traff.Message, suppressed map[string]string)
}

// Status describes the outcome of the latest fetch of a source.
type Status struct {
	Name        string    `json:"name"`
	LastAttempt time.Time `json:"last_attempt"`
	LastSuccess time.Time `json:"last_success"`
	LastError   string    `json:"last_error,omitempty"`
	// Messages is the number of messages the source delivered in its last successful fetch,
	// including duplicates.
	Messages int `json:"messages"`
	// Duplicates is the number of messages of the last successful fetch that were suppressed
	// as duplicates of a source with higher priority; DuplicatesTotal sums up all fetches.
	Duplicates      int            `json:"duplicates"`
	DuplicatesTotal int            `json:"duplicates_total"`
	Details         map[string]int `json:"details,omitempty"`
}

// Runner periodically fetches all sources into a store.
type Runner struct {
	store    *store.Store
	sources  []Source
	interval time.Duration
	now      func() time.Time
	log      *slog.Logger
	dedup    Deduplicator

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

// maxLoggedDuplicates limits the debug output per fetch.
const maxLoggedDuplicates = 20

// SetClock replaces the clock (for tests). It must be called before Run.
func (r *Runner) SetClock(now func() time.Time) { r.now = now }

// SetDeduplicator enables the merge step; nil disables it. It must be called before Run.
func (r *Runner) SetDeduplicator(d Deduplicator) { r.dedup = d }

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
	for i, s := range r.sources {
		start := r.now()
		msgs, err := s.Fetch(ctx, start)
		total := len(msgs)
		dups := 0
		if msgs != nil && r.dedup != nil && i > 0 {
			var suppressed map[string]string
			msgs, suppressed = r.dedup.Filter(msgs, r.activeMessages(r.sources[:i]))
			dups = len(suppressed)
			if dups > 0 && r.log.Enabled(ctx, slog.LevelDebug) {
				ids := slices.Sorted(maps.Keys(suppressed))
				for _, id := range ids[:min(len(ids), maxLoggedDuplicates)] {
					r.log.Debug("duplicate suppressed", "source", s.Name(), "id", id, "duplicate_of", suppressed[id])
				}
			}
		}
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
			st.Messages = total
			st.Duplicates = dups
			st.DuplicatesTotal += dups
		}
		if dr, ok := s.(DetailsReporter); ok {
			st.Details = dr.Details()
		}
		r.mu.Unlock()
		if msgs == nil {
			if err != nil {
				r.log.Warn("source fetch failed, keeping previous data", "source", s.Name(), "err", err)
			}
			continue
		}
		stats := r.store.Replace(s.Name(), msgs, r.now())
		attrs := []any{"source", s.Name(), "messages", total, "duplicates", dups, "new", stats.New, "changed", stats.Changed,
			"renewed", stats.Renewed, "cancelled", stats.Cancelled, "skipped", stats.Skipped, "duration", r.now().Sub(start).Round(time.Millisecond)}
		if err != nil {
			r.log.Warn("source fetch partially failed", append(attrs, "err", err)...)
		} else {
			r.log.Info("source fetched", attrs...)
		}
	}
	r.store.Purge(r.now())
}

// activeMessages returns the active (not cancelled) messages of the given sources.
func (r *Runner) activeMessages(sources []Source) []traff.Message {
	names := make(map[string]bool, len(sources))
	for _, s := range sources {
		names[s.Name()] = true
	}
	var res []traff.Message
	r.store.ForEach(func(e *store.Entry) {
		if names[e.Source] && !e.Cancelled() {
			res = append(res, e.Msg)
		}
	})
	return res
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
		st := *r.status[s.Name()]
		st.Details = maps.Clone(st.Details)
		res = append(res, st)
	}
	return res
}
