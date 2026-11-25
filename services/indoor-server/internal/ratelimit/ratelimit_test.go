package ratelimit

import (
	"sync"
	"testing"
	"time"
)

type clock struct{ t time.Time }

func (c *clock) now() time.Time { return c.t }

func TestBurstThenRate(t *testing.T) {
	c := &clock{t: time.Unix(0, 0)}
	l := New(0.5, 3, c.now) // 30 per minute, burst 3
	for i := 0; i < 3; i++ {
		if ok, _ := l.Allow("a"); !ok {
			t.Fatalf("request %d of the burst rejected", i)
		}
	}
	ok, wait := l.Allow("a")
	if ok || wait != 2*time.Second {
		t.Fatalf("4th request: ok=%v wait=%v, want rejected, 2s", ok, wait)
	}
	// Other keys have their own bucket.
	if ok, _ := l.Allow("b"); !ok {
		t.Fatal("other key rejected")
	}
	c.t = c.t.Add(1999 * time.Millisecond)
	if ok, _ := l.Allow("a"); ok {
		t.Fatal("allowed before the next token")
	}
	c.t = c.t.Add(2 * time.Millisecond)
	if ok, _ := l.Allow("a"); !ok {
		t.Fatal("rejected after refill")
	}
	// Refill is capped at the burst.
	c.t = c.t.Add(time.Hour)
	for i := 0; i < 3; i++ {
		if ok, _ := l.Allow("a"); !ok {
			t.Fatalf("request %d after a long pause rejected", i)
		}
	}
	if ok, _ := l.Allow("a"); ok {
		t.Fatal("burst exceeded after a long pause")
	}
}

func TestDisabled(t *testing.T) {
	l := New(0, 0, nil)
	for i := 0; i < 1000; i++ {
		if ok, _ := l.Allow("a"); !ok {
			t.Fatal("disabled limiter rejected")
		}
	}
}

func TestPrune(t *testing.T) {
	c := &clock{t: time.Unix(0, 0)}
	l := New(1, 2, c.now)
	l.Allow("a")
	l.Allow("b")
	l.Allow("b")
	c.t = c.t.Add(1 * time.Second) // a is full again, b has 1 of 2
	l.Prune()
	if l.Len() != 1 {
		t.Fatalf("Len = %d after Prune, want 1", l.Len())
	}
	c.t = c.t.Add(time.Second)
	l.Prune()
	if l.Len() != 0 {
		t.Fatalf("Len = %d, want 0", l.Len())
	}
}

func TestConcurrent(t *testing.T) {
	l := New(0.001, 50, nil)
	var wg sync.WaitGroup
	var mu sync.Mutex
	allowed := 0
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 20; j++ {
				if ok, _ := l.Allow("k"); ok {
					mu.Lock()
					allowed++
					mu.Unlock()
				}
			}
		}()
	}
	wg.Wait()
	if allowed != 50 {
		t.Fatalf("allowed %d of 160, want 50", allowed)
	}
}
