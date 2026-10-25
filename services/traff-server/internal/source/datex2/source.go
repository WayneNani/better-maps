package datex2

import (
	"context"
	"fmt"
	"log/slog"
	"maps"
	"sync"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

// Germany, slightly padded. All feeds handled here are German.
var germany = traff.BBox{MinLat: 47.2, MinLon: 5.8, MaxLat: 55.1, MaxLon: 15.1}

// SourcePrefix is the prefix of the source names ("datex2-<feed>").
const SourcePrefix = "datex2-"

// Source is one DATEX II feed. The parsed publication is kept between fetches, so that a
// "304 Not Modified" answer (or a minimum interval that has not yet passed) only re-evaluates
// the validity of the known records.
type Source struct {
	feed        string
	client      *Client
	minInterval time.Duration
	horizon     time.Duration
	log         *slog.Logger

	mu        sync.Mutex
	pub       *Publication
	lastFetch time.Time
	counters  map[string]int // downloads, not_modified, fetch_errors (cumulative)
	mapped    Stats          // result of the last mapping run
}

// SourceConfig configures a Source.
type SourceConfig struct {
	// Feed is the feed name; it is part of the source name and of all message IDs.
	Feed string
	// MinInterval is the minimum time between two downloads. Fetch calls in between reuse the
	// last publication. 0 downloads on every call.
	MinInterval time.Duration
	// Horizon skips records that start later than now+Horizon (0 = no limit).
	Horizon time.Duration
}

// NewSource creates a source for one feed.
func NewSource(cfg SourceConfig, client *Client, log *slog.Logger) *Source {
	return &Source{feed: cfg.Feed, client: client, minInterval: cfg.MinInterval, horizon: cfg.Horizon, log: log, counters: map[string]int{}}
}

// Name implements source.Source.
func (s *Source) Name() string { return SourcePrefix + s.feed }

// Coverage implements source.Source.
func (s *Source) Coverage() []traff.BBox { return []traff.BBox{germany} }

// Fetch implements source.Source. Download and parse errors are returned with a nil slice, so
// that the runner keeps the previous messages of this feed; other sources are not affected.
func (s *Source) Fetch(ctx context.Context, now time.Time) ([]traff.Message, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.pub == nil || s.minInterval <= 0 || now.Sub(s.lastFetch) >= s.minInterval {
		if err := s.download(ctx); err != nil {
			s.counters["fetch_errors"]++
			return nil, err
		}
		s.lastFetch = now
	}
	msgs, st := Map(s.pub, Options{Feed: s.feed, Now: now, Horizon: s.horizon})
	s.mapped = st
	return msgs, nil
}

func (s *Source) download(ctx context.Context) error {
	resp, err := s.client.Get(ctx)
	if err != nil {
		return err
	}
	if resp.NotModified() && s.pub != nil {
		s.counters["not_modified"]++
		return nil
	}
	if resp.NotModified() {
		// Cannot happen: validators are only committed together with a publication.
		s.client.Reset()
		return fmt.Errorf("datex2 %s: not modified, but no publication cached", s.feed)
	}
	pub, err := Parse(resp.Body)
	resp.Body.Close()
	if err != nil {
		return fmt.Errorf("datex2 %s: %w", s.feed, err)
	}
	s.client.Commit(resp)
	s.counters["downloads"]++
	if s.log != nil && pub.UpdateMethod != "" && pub.UpdateMethod != "allElementUpdate" && pub.UpdateMethod != "snapshotPull" {
		s.log.Warn("datex2 feed is not a full snapshot; records missing from a publication are treated as cancelled",
			"feed", s.feed, "update_method", pub.UpdateMethod)
	}
	s.pub = pub
	return nil
}

// Details returns counters of the last mapping run (situations, records, messages, skipped
// records by reason) and of the downloads. It implements source.DetailsReporter.
func (s *Source) Details() map[string]int {
	s.mu.Lock()
	defer s.mu.Unlock()
	res := maps.Clone(s.counters)
	maps.Copy(res, s.mapped)
	return res
}
