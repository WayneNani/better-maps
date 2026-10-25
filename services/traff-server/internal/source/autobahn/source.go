package autobahn

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

// Germany, slightly padded. Used as coverage area of the source.
var germany = traff.BBox{MinLat: 47.2, MinLon: 5.8, MaxLat: 55.1, MaxLon: 15.1}

// Source fetches roadworks, warnings and closures for all (or selected) motorways.
type Source struct {
	client   *Client
	roads    []string // fixed road list; empty = ask the API
	services []string
	tz       *time.Location
	log      *slog.Logger

	mu        sync.Mutex
	lastRoads []string
	lastItems map[string][]Item // "road|service" -> items of the last successful fetch
}

// NewSource creates a source. roads may be empty to fetch all roads listed by the API.
func NewSource(client *Client, roads []string, tz *time.Location, log *slog.Logger) *Source {
	return &Source{client: client, roads: roads, services: DefaultServices, tz: tz, log: log, lastItems: map[string][]Item{}}
}

// Name implements source.Source.
func (s *Source) Name() string { return "autobahn" }

// Coverage implements source.Source.
func (s *Source) Coverage() []traff.BBox { return []traff.BBox{germany} }

// Fetch implements source.Source. Failures of single road/service requests do not abort the
// fetch: the items of the last successful request for that road/service are used instead, and an
// error summarizing the failures is returned together with the messages.
func (s *Source) Fetch(ctx context.Context, now time.Time) ([]traff.Message, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	roads := s.roads
	var errs []error
	if len(roads) == 0 {
		r, err := s.client.Roads(ctx)
		switch {
		case err == nil:
			s.lastRoads = r
			roads = r
		case len(s.lastRoads) > 0:
			errs = append(errs, err)
			roads = s.lastRoads
		default:
			return nil, err
		}
	}

	keep := map[string]bool{}
	failed := 0
	var msgs []traff.Message
	seen := map[string]bool{}
	skipped := map[string]int{}
	for _, road := range roads {
		for _, svc := range s.services {
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			key := road + "|" + svc
			keep[key] = true
			items, err := s.client.Items(ctx, road, svc)
			if err != nil {
				failed++
				if len(errs) < 5 {
					errs = append(errs, err)
				}
				items = s.lastItems[key]
			} else {
				s.lastItems[key] = items
			}
			for i := range items {
				m, err := MapItem(road, svc, &items[i], now, s.tz)
				if err != nil {
					skipped[err.Error()]++
					continue
				}
				if seen[m.ID] {
					continue
				}
				seen[m.ID] = true
				msgs = append(msgs, m)
			}
		}
	}
	for key := range s.lastItems {
		if !keep[key] {
			delete(s.lastItems, key)
		}
	}
	if len(skipped) > 0 && s.log != nil {
		attrs := make([]any, 0, 2*len(skipped))
		reasons := make([]string, 0, len(skipped))
		for r := range skipped {
			reasons = append(reasons, r)
		}
		sort.Strings(reasons)
		for _, r := range reasons {
			attrs = append(attrs, strings.ReplaceAll(r, " ", "_"), skipped[r])
		}
		s.log.Debug("autobahn items skipped", attrs...)
	}
	if msgs == nil {
		msgs = []traff.Message{}
	}
	if failed > 0 || len(errs) > 0 {
		return msgs, fmt.Errorf("autobahn: %d of %d requests failed: %w", failed, len(roads)*len(s.services), errors.Join(errs...))
	}
	return msgs, nil
}
