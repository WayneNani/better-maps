// Package store keeps the current set of TraFF messages in memory.
//
// Every source periodically hands over a complete snapshot of its messages (Replace). The store
// compares the snapshot with what it already has and assigns a new version (a global, monotonic
// sequence number) to every message that is new or has changed. Messages that disappear from a
// snapshot are turned into cancellation messages ("tombstones"), which are kept for a while so
// that subscribers that received the message learn about its removal.
//
// Timestamps are owned by the store: receive_time is the time the message was first seen,
// update_time the time of the last change, and expiration_time is a lease that is renewed while
// the source keeps reporting the message. Entries are immutable once published; readers may keep
// pointers to them.
package store

import (
	"bytes"
	"crypto/sha256"
	"encoding/xml"
	"sync"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

// Entry is a message together with its bookkeeping data. Entries are never modified after they
// have been stored.
type Entry struct {
	Msg     traff.Message
	Version uint64
	Source  string
	// BBox encloses all reference points this message ever had (TraFF 0.8, section 2.2).
	BBox      traff.BBox
	HasBBox   bool
	RoadClass traff.RoadClass
	hash      [32]byte
}

// Cancelled reports whether the entry is a cancellation (tombstone).
func (e *Entry) Cancelled() bool { return e.Msg.Cancellation }

// UpdateStats summarizes the effect of a Replace call.
type UpdateStats struct {
	New, Changed, Renewed, Unchanged, Cancelled, Skipped int
}

// Store is a thread-safe in-memory message store.
type Store struct {
	mu           sync.RWMutex
	entries      map[string]*Entry
	seq          uint64
	lease        time.Duration
	tombstoneTTL time.Duration
}

// New creates a store. lease is the validity granted to a message each time it is (re)published;
// tombstoneTTL is how long cancellation messages are kept.
func New(lease, tombstoneTTL time.Duration) *Store {
	return &Store{entries: map[string]*Entry{}, lease: lease, tombstoneTTL: tombstoneTTL}
}

func contentHash(m *traff.Message) [32]byte {
	c := m.Clone()
	c.ReceiveTime, c.UpdateTime, c.ExpirationTime = traff.Time{}, traff.Time{}, traff.Time{}
	var buf bytes.Buffer
	// Encoding a Message cannot fail: all fields are plain strings, numbers and pointers.
	_ = xml.NewEncoder(&buf).Encode(&c)
	return sha256.Sum256(buf.Bytes())
}

// Replace installs the complete current message set of source. Messages owned by another source
// are skipped, as are messages without ID or duplicates within msgs.
func (s *Store) Replace(source string, msgs []traff.Message, now time.Time) UpdateStats {
	var st UpdateStats
	nowT := traff.NewTime(now)
	expires := traff.NewTime(now.Add(s.lease))

	s.mu.Lock()
	defer s.mu.Unlock()

	seen := make(map[string]bool, len(msgs))
	for i := range msgs {
		in := &msgs[i]
		if in.ID == "" || seen[in.ID] || in.Cancellation {
			st.Skipped++
			continue
		}
		old := s.entries[in.ID]
		if old != nil && old.Source != source {
			st.Skipped++
			continue
		}
		seen[in.ID] = true
		h := contentHash(in)

		if old != nil && !old.Cancelled() && old.hash == h {
			// Unchanged. Renew the lease once less than half of it is left.
			if old.Msg.EffectiveExpiration().Sub(now) >= s.lease/2 {
				st.Unchanged++
				continue
			}
			e := *old
			e.Msg = old.Msg.Clone()
			e.Msg.UpdateTime = nowT
			e.Msg.ExpirationTime = expires
			s.seq++
			e.Version = s.seq
			s.entries[in.ID] = &e
			st.Renewed++
			continue
		}

		e := &Entry{Msg: in.Clone(), Source: source, hash: h}
		e.Msg.UpdateTime = nowT
		e.Msg.ExpirationTime = expires
		e.Msg.ReceiveTime = nowT
		if e.Msg.Location != nil {
			e.RoadClass = e.Msg.Location.RoadClass
		}
		e.BBox, e.HasBBox = e.Msg.BBox()
		if old != nil && !old.Cancelled() {
			e.Msg.ReceiveTime = old.Msg.ReceiveTime
			if old.HasBBox {
				if e.HasBBox {
					e.BBox = e.BBox.Union(old.BBox)
				} else {
					e.BBox, e.HasBBox = old.BBox, true
				}
			}
			if e.RoadClass == "" {
				e.RoadClass = old.RoadClass
			}
			st.Changed++
		} else {
			st.New++
		}
		s.seq++
		e.Version = s.seq
		s.entries[in.ID] = e
	}

	// Everything this source no longer reports is cancelled.
	for id, old := range s.entries {
		if old.Source != source || old.Cancelled() || seen[id] {
			continue
		}
		e := &Entry{
			Msg: traff.Message{
				ID:             id,
				ReceiveTime:    old.Msg.ReceiveTime,
				UpdateTime:     nowT,
				ExpirationTime: traff.NewTime(now.Add(s.tombstoneTTL)),
				Cancellation:   true,
			},
			Source:    source,
			BBox:      old.BBox,
			HasBBox:   old.HasBBox,
			RoadClass: old.RoadClass,
		}
		s.seq++
		e.Version = s.seq
		s.entries[id] = e
		st.Cancelled++
	}
	return st
}

// Purge removes expired messages and tombstones whose retention time has passed. Expired active
// messages are removed silently: clients expire them on their own.
func (s *Store) Purge(now time.Time) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	n := 0
	for id, e := range s.entries {
		if e.Msg.EffectiveExpiration().Before(now) {
			delete(s.entries, id)
			n++
		}
	}
	return n
}

// Seq returns the current sequence number. It changes whenever any entry changes.
func (s *Store) Seq() uint64 {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.seq
}

// Get returns the entry with the given ID, or nil.
func (s *Store) Get(id string) *Entry {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.entries[id]
}

// ForEach calls fn for every entry while holding a read lock. fn must not call other Store
// methods that acquire the write lock.
func (s *Store) ForEach(fn func(*Entry)) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, e := range s.entries {
		fn(e)
	}
}

// Counts returns the number of active messages and tombstones.
func (s *Store) Counts() (active, cancelled int) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, e := range s.entries {
		if e.Cancelled() {
			cancelled++
		} else {
			active++
		}
	}
	return active, cancelled
}
