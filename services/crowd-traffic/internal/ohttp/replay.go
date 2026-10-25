package ohttp

import (
	"crypto/sha256"
	"sync"
	"time"
)

// ReplayCache remembers the encapsulated KEM keys (enc) of accepted requests for a time window
// (RFC 9458, Section 6.5.1). enc is a fresh random value per request, so it identifies a request
// but carries no information about the client. Only a truncated hash is stored.
//
// Together with the Date check of the gateway (requests older than the window are rejected),
// every request can be accepted at most once.
type ReplayCache struct {
	ttl time.Duration
	max int

	mu      sync.Mutex
	entries map[[16]byte]time.Time
}

// NewReplayCache creates a cache whose entries expire after ttl. At most max entries are kept;
// when the cache is full, new requests are refused (fail closed) until entries expire.
func NewReplayCache(ttl time.Duration, max int) *ReplayCache {
	return &ReplayCache{ttl: ttl, max: max, entries: map[[16]byte]time.Time{}}
}

// ReplayResult is the outcome of ReplayCache.Check.
type ReplayResult int

// Possible results of ReplayCache.Check.
const (
	Fresh ReplayResult = iota
	Replayed
	CacheFull
)

// Check records enc and reports whether it was seen before within the window.
func (c *ReplayCache) Check(enc []byte, now time.Time) ReplayResult {
	h := sha256.Sum256(enc)
	var id [16]byte
	copy(id[:], h[:16])
	c.mu.Lock()
	defer c.mu.Unlock()
	if exp, ok := c.entries[id]; ok && now.Before(exp) {
		return Replayed
	}
	if len(c.entries) >= c.max {
		c.purgeLocked(now)
		if len(c.entries) >= c.max {
			return CacheFull
		}
	}
	c.entries[id] = now.Add(c.ttl)
	return Fresh
}

// Purge removes expired entries.
func (c *ReplayCache) Purge(now time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.purgeLocked(now)
}

func (c *ReplayCache) purgeLocked(now time.Time) {
	for id, exp := range c.entries {
		if !now.Before(exp) {
			delete(c.entries, id)
		}
	}
}

// Len returns the number of entries.
func (c *ReplayCache) Len() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.entries)
}
