// Package ratelimit provides token buckets per key (per device token label).
package ratelimit

import (
	"math"
	"sync"
	"time"
)

// Limiter holds one token bucket per key. A bucket starts full (Burst requests at once, e.g. the
// detour routes of the charging planner) and refills with Rate requests per second.
type Limiter struct {
	rate  float64 // tokens per second
	burst float64
	now   func() time.Time

	mu      sync.Mutex
	buckets map[string]*bucket
}

type bucket struct {
	tokens float64
	last   time.Time
}

// New returns a limiter with rate requests per second and the given burst. now may be nil
// (time.Now).
func New(rate float64, burst int, now func() time.Time) *Limiter {
	if now == nil {
		now = time.Now
	}
	return &Limiter{rate: rate, burst: float64(burst), now: now, buckets: map[string]*bucket{}}
}

// Allow takes one token of key's bucket. If none is left it returns false and the time until the
// next token.
func (l *Limiter) Allow(key string) (bool, time.Duration) {
	if l.rate <= 0 || l.burst <= 0 {
		return true, 0
	}
	now := l.now()
	l.mu.Lock()
	defer l.mu.Unlock()
	b, ok := l.buckets[key]
	if !ok {
		b = &bucket{tokens: l.burst, last: now}
		l.buckets[key] = b
	} else if elapsed := now.Sub(b.last).Seconds(); elapsed > 0 {
		b.tokens = math.Min(l.burst, b.tokens+elapsed*l.rate)
		b.last = now
	}
	if b.tokens >= 1 {
		b.tokens--
		return true, 0
	}
	wait := time.Duration((1 - b.tokens) / l.rate * float64(time.Second))
	return false, wait
}

// Refund returns a token taken by Allow (up to the burst).
func (l *Limiter) Refund(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if b, ok := l.buckets[key]; ok {
		b.tokens = math.Min(l.burst, b.tokens+1)
	}
}

// Prune drops the buckets that are full again (they behave like new ones), so that the map does
// not grow with keys that are no longer used.
func (l *Limiter) Prune() {
	if l.rate <= 0 {
		return
	}
	now := l.now()
	l.mu.Lock()
	defer l.mu.Unlock()
	for k, b := range l.buckets {
		if b.tokens+now.Sub(b.last).Seconds()*l.rate >= l.burst {
			delete(l.buckets, k)
		}
	}
}

// Len returns the number of buckets (for tests).
func (l *Limiter) Len() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.buckets)
}
