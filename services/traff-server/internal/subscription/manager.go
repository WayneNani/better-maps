// Package subscription manages TraFF subscriptions: random IDs, filter lists, delta feeds and
// expiry after inactivity.
//
// Subscriptions exist only in memory. Nothing about a subscription (ID, filter, activity) is
// written to disk or logged; after the TTL without any request the subscription is deleted.
package subscription

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"sort"
	"sync"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/store"
	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

// Errors returned by the manager.
var (
	ErrUnknown  = errors.New("subscription unknown")
	ErrRejected = errors.New("subscription rejected")
)

type subscription struct {
	mu           sync.Mutex
	filters      traff.FilterList
	sent         map[string]uint64 // message ID -> version delivered
	lastSeq      uint64
	dirty        bool // a full scan is needed regardless of the store sequence
	lastActivity time.Time
}

// Manager keeps track of all subscriptions.
type Manager struct {
	mu    sync.Mutex
	subs  map[string]*subscription
	store *store.Store
	ttl   time.Duration
	max   int
	newID func() (string, error)
}

// NewManager creates a manager. ttl is the inactivity timeout, max the maximum number of
// concurrent subscriptions (0 = unlimited).
func NewManager(st *store.Store, ttl time.Duration, max int) *Manager {
	return &Manager{subs: map[string]*subscription{}, store: st, ttl: ttl, max: max, newID: RandomID}
}

// SetIDGenerator replaces the ID generator (used by tests to get deterministic IDs).
func (m *Manager) SetIDGenerator(f func() (string, error)) { m.newID = f }

// TTL returns the inactivity timeout.
func (m *Manager) TTL() time.Duration { return m.ttl }

// RandomID returns a random 128-bit subscription ID in hex notation. The ID acts as a bearer
// secret, so it comes from a cryptographically secure generator.
func RandomID() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(b[:]), nil
}

// lookup returns a live subscription and marks activity, or ErrUnknown.
func (m *Manager) lookup(id string, now time.Time) (*subscription, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	s := m.subs[id]
	if s == nil {
		return nil, ErrUnknown
	}
	s.mu.Lock()
	expired := now.Sub(s.lastActivity) > m.ttl
	if !expired {
		s.lastActivity = now
	}
	s.mu.Unlock()
	if expired {
		delete(m.subs, id)
		return nil, ErrUnknown
	}
	return s, nil
}

// Subscribe creates a subscription and returns its ID together with the complete feed of
// matching messages.
func (m *Manager) Subscribe(filters traff.FilterList, now time.Time) (string, []traff.Message, error) {
	s := &subscription{filters: filters, sent: map[string]uint64{}, dirty: true, lastActivity: now}
	m.mu.Lock()
	if m.max > 0 && len(m.subs) >= m.max {
		m.mu.Unlock()
		return "", nil, ErrRejected
	}
	var id string
	for {
		var err error
		if id, err = m.newID(); err != nil {
			m.mu.Unlock()
			return "", nil, err
		}
		if _, taken := m.subs[id]; !taken {
			break
		}
	}
	m.subs[id] = s
	s.mu.Lock()
	m.mu.Unlock()
	defer s.mu.Unlock()
	return id, m.delta(s), nil
}

// Change replaces the filter list of a subscription. The returned feed contains all messages
// that match the new filter and have not been delivered yet, plus changed messages.
func (m *Manager) Change(id string, filters traff.FilterList, now time.Time) ([]traff.Message, error) {
	s, err := m.lookup(id, now)
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.filters = filters
	// Messages that only matched the old filter will not receive further updates.
	for msgID := range s.sent {
		if e := m.store.Get(msgID); e == nil || !e.HasBBox || !filters.Matches(e.BBox, e.RoadClass) {
			delete(s.sent, msgID)
		}
	}
	s.dirty = true
	return m.delta(s), nil
}

// Poll returns all messages that are new or changed since the previous feed of the subscription.
func (m *Manager) Poll(id string, now time.Time) ([]traff.Message, error) {
	s, err := m.lookup(id, now)
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return m.delta(s), nil
}

// Heartbeat refreshes the inactivity timer of a subscription.
func (m *Manager) Heartbeat(id string, now time.Time) error {
	_, err := m.lookup(id, now)
	return err
}

// Unsubscribe deletes a subscription. Unknown IDs are ignored.
func (m *Manager) Unsubscribe(id string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.subs, id)
}

// Expire deletes all subscriptions that have been inactive for longer than the TTL and returns
// how many were deleted.
func (m *Manager) Expire(now time.Time) int {
	m.mu.Lock()
	defer m.mu.Unlock()
	n := 0
	for id, s := range m.subs {
		s.mu.Lock()
		expired := now.Sub(s.lastActivity) > m.ttl
		s.mu.Unlock()
		if expired {
			delete(m.subs, id)
			n++
		}
	}
	return n
}

// Count returns the number of subscriptions.
func (m *Manager) Count() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.subs)
}

// delta computes the next feed for s. The caller must hold s.mu.
func (m *Manager) delta(s *subscription) []traff.Message {
	seq := m.store.Seq()
	if !s.dirty && seq == s.lastSeq {
		return nil
	}
	var out []traff.Message
	hits := 0
	m.store.ForEach(func(e *store.Entry) {
		v, wasSent := s.sent[e.Msg.ID]
		if e.Cancelled() {
			if wasSent {
				out = append(out, e.Msg)
				delete(s.sent, e.Msg.ID)
			}
			return
		}
		if wasSent {
			hits++
		}
		if wasSent && v == e.Version {
			return
		}
		if !e.HasBBox || !s.filters.Matches(e.BBox, e.RoadClass) {
			return
		}
		out = append(out, e.Msg)
		s.sent[e.Msg.ID] = e.Version
	})
	// Forget messages that have been purged from the store in the meantime.
	if len(s.sent) > hits {
		for msgID := range s.sent {
			if m.store.Get(msgID) == nil {
				delete(s.sent, msgID)
			}
		}
	}
	s.lastSeq = seq
	s.dirty = false
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out
}

// Snapshot returns all active messages matching filters, without creating a subscription. This
// implements the (deprecated, but still used for one-shot downloads) subscriptionless poll.
func Snapshot(st *store.Store, filters traff.FilterList) []traff.Message {
	var out []traff.Message
	st.ForEach(func(e *store.Entry) {
		if !e.Cancelled() && e.HasBBox && filters.Matches(e.BBox, e.RoadClass) {
			out = append(out, e.Msg)
		}
	})
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out
}
