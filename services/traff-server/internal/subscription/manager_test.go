package subscription

import (
	"errors"
	"fmt"
	"regexp"
	"sync"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/store"
	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

var t0 = time.Date(2026, 10, 25, 16, 0, 0, 0, time.UTC)

func msg(id string, lat, lon float64, rc traff.RoadClass, typ string) traff.Message {
	return traff.Message{
		ID: id,
		Location: &traff.Location{
			Directionality: traff.OneDirection, RoadClass: rc,
			From: traff.NewPoint(lat, lon), To: traff.NewPoint(lat+0.01, lon+0.01),
		},
		Events: []traff.Event{{Class: "CONGESTION", Type: typ}},
	}
}

func bbox(minLat, minLon, maxLat, maxLon float64) *traff.BBox {
	return &traff.BBox{MinLat: minLat, MinLon: minLon, MaxLat: maxLat, MaxLon: maxLon}
}

func ids(msgs []traff.Message) []string {
	res := make([]string, len(msgs))
	for i, m := range msgs {
		res[i] = m.ID
		if m.Cancellation {
			res[i] += "(x)"
		}
	}
	return res
}

func eq(t *testing.T, what string, got []traff.Message, want ...string) {
	t.Helper()
	g := ids(got)
	if fmt.Sprint(g) != fmt.Sprint(want) {
		if len(g) == 0 && len(want) == 0 {
			return
		}
		t.Errorf("%s = %v, want %v", what, g, want)
	}
}

func setup() (*store.Store, *Manager) {
	st := store.New(30*time.Minute, time.Hour)
	st.Replace("src", []traff.Message{
		msg("berlin-a", 52.5, 13.4, traff.RoadClassMotorway, "CONGESTION_QUEUE"),
		msg("berlin-p", 52.4, 13.3, traff.RoadClassPrimary, "CONGESTION_QUEUE"),
		msg("munich", 48.1, 11.5, traff.RoadClassMotorway, "CONGESTION_QUEUE"),
		msg("hamburg-x", 53.5, 10.0, "", "CONGESTION_QUEUE"),
	}, t0)
	return st, NewManager(st, time.Hour, 0)
}

func TestSubscribeAndDeltaFeeds(t *testing.T) {
	st, m := setup()
	berlin := traff.FilterList{{BBox: bbox(52.3, 13.0, 52.7, 13.8)}}
	id, feed, err := m.Subscribe(berlin, t0)
	if err != nil {
		t.Fatal(err)
	}
	if !regexp.MustCompile(`^[0-9a-f]{32}$`).MatchString(id) {
		t.Errorf("subscription ID %q is not 128 bit hex", id)
	}
	eq(t, "initial feed", feed, "berlin-a", "berlin-p")

	// Nothing changed: empty delta.
	feed, err = m.Poll(id, t0.Add(time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	eq(t, "unchanged poll", feed)

	// Change one Berlin message, add one, remove one, change Munich (not subscribed).
	t1 := t0.Add(5 * time.Minute)
	st.Replace("src", []traff.Message{
		msg("berlin-a", 52.5, 13.4, traff.RoadClassMotorway, "CONGESTION_STATIONARY_TRAFFIC"),
		msg("berlin-new", 52.6, 13.5, traff.RoadClassTrunk, "CONGESTION_QUEUE"),
		msg("munich", 48.1, 11.5, traff.RoadClassMotorway, "CONGESTION_STATIONARY_TRAFFIC"),
		msg("hamburg-x", 53.5, 10.0, "", "CONGESTION_QUEUE"),
	}, t1)
	feed, _ = m.Poll(id, t1)
	eq(t, "delta feed", feed, "berlin-a", "berlin-new", "berlin-p(x)")
	if feed[0].Events[0].Type != "CONGESTION_STATIONARY_TRAFFIC" {
		t.Errorf("delta carries old version: %+v", feed[0])
	}

	// The cancellation is delivered only once.
	feed, _ = m.Poll(id, t1.Add(time.Minute))
	eq(t, "after delta", feed)

	// A subscriber that never received berlin-p does not get its cancellation.
	_, feed, _ = m.Subscribe(berlin, t1)
	eq(t, "late subscriber", feed, "berlin-a", "berlin-new")
}

func TestRoadClassFilter(t *testing.T) {
	_, m := setup()
	fl := traff.FilterList{
		{BBox: bbox(52.3, 13.0, 52.7, 13.8), MinRoadClass: traff.RoadClassMotorway},
		{BBox: bbox(53, 9, 54, 11), MinRoadClass: traff.RoadClassMotorway},
	}
	_, feed, _ := m.Subscribe(fl, t0)
	eq(t, "motorway-only feed", feed, "berlin-a", "hamburg-x") // hamburg-x has no road class
}

func TestChangeSubscription(t *testing.T) {
	st, m := setup()
	id, _, _ := m.Subscribe(traff.FilterList{{BBox: bbox(52.3, 13.0, 52.7, 13.8)}}, t0)

	// Widen the filter to Munich: only Munich is new.
	both := traff.FilterList{{BBox: bbox(52.3, 13.0, 52.7, 13.8)}, {BBox: bbox(48, 11, 48.5, 12)}}
	feed, err := m.Change(id, both, t0.Add(time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	eq(t, "after widening", feed, "munich")

	// Narrow to Munich only, then remove berlin-a at the source: the subscriber no longer
	// follows Berlin and gets no cancellation for it.
	feed, _ = m.Change(id, traff.FilterList{{BBox: bbox(48, 11, 48.5, 12)}}, t0.Add(2*time.Minute))
	eq(t, "after narrowing", feed)
	st.Replace("src", []traff.Message{
		msg("berlin-p", 52.4, 13.3, traff.RoadClassPrimary, "CONGESTION_QUEUE"),
		msg("munich", 48.1, 11.5, traff.RoadClassMotorway, "CONGESTION_QUEUE"),
	}, t0.Add(3*time.Minute))
	feed, _ = m.Poll(id, t0.Add(3*time.Minute))
	eq(t, "poll after narrowing", feed)

	// Widening again re-sends Berlin messages that are still active.
	feed, _ = m.Change(id, both, t0.Add(4*time.Minute))
	eq(t, "re-widened", feed, "berlin-p")

	if _, err := m.Change("nope", both, t0); !errors.Is(err, ErrUnknown) {
		t.Errorf("Change(unknown) = %v", err)
	}
}

func TestTTLAndUnsubscribe(t *testing.T) {
	_, m := setup()
	fl := traff.FilterList{{BBox: bbox(52.3, 13.0, 52.7, 13.8)}}
	id, _, _ := m.Subscribe(fl, t0)

	// Activity keeps the subscription alive.
	if err := m.Heartbeat(id, t0.Add(50*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Poll(id, t0.Add(100*time.Minute)); err != nil {
		t.Fatalf("subscription expired despite heartbeat: %v", err)
	}
	// More than the TTL without activity: gone.
	if _, err := m.Poll(id, t0.Add(161*time.Minute)); !errors.Is(err, ErrUnknown) {
		t.Errorf("Poll after TTL = %v", err)
	}
	if m.Count() != 0 {
		t.Errorf("expired subscription still counted")
	}
	if err := m.Heartbeat(id, t0.Add(162*time.Minute)); !errors.Is(err, ErrUnknown) {
		t.Errorf("Heartbeat after TTL = %v", err)
	}

	// Expire() removes idle subscriptions in the background.
	id1, _, _ := m.Subscribe(fl, t0)
	id2, _, _ := m.Subscribe(fl, t0.Add(30*time.Minute))
	if n := m.Expire(t0.Add(61 * time.Minute)); n != 1 {
		t.Errorf("Expire removed %d", n)
	}
	if _, err := m.Poll(id1, t0.Add(61*time.Minute)); !errors.Is(err, ErrUnknown) {
		t.Error("id1 should be expired")
	}
	if _, err := m.Poll(id2, t0.Add(61*time.Minute)); err != nil {
		t.Error("id2 should still be alive")
	}

	m.Unsubscribe(id2)
	m.Unsubscribe("never-existed")
	if _, err := m.Poll(id2, t0.Add(62*time.Minute)); !errors.Is(err, ErrUnknown) {
		t.Error("unsubscribed ID still works")
	}
	if m.TTL() != time.Hour {
		t.Errorf("TTL = %v", m.TTL())
	}
}

func TestLimitsAndIDs(t *testing.T) {
	st, _ := setup()
	m := NewManager(st, time.Hour, 2)
	seq := []string{"dup", "dup", "other", "third"}
	m.SetIDGenerator(func() (string, error) {
		id := seq[0]
		seq = seq[1:]
		return id, nil
	})
	id1, _, err := m.Subscribe(nil, t0)
	id2, _, err2 := m.Subscribe(nil, t0)
	if err != nil || err2 != nil || id1 != "dup" || id2 != "other" {
		t.Errorf("IDs = %q %q (%v %v), collision must be retried", id1, id2, err, err2)
	}
	if _, _, err := m.Subscribe(nil, t0); !errors.Is(err, ErrRejected) {
		t.Errorf("third subscription = %v, want ErrRejected", err)
	}

	m2 := NewManager(st, time.Hour, 0)
	m2.SetIDGenerator(func() (string, error) { return "", errors.New("no entropy") })
	if _, _, err := m2.Subscribe(nil, t0); err == nil {
		t.Error("ID generator error not propagated")
	}

	a, _ := RandomID()
	b, _ := RandomID()
	if a == b || len(a) != 32 {
		t.Errorf("RandomID = %q, %q", a, b)
	}
}

func TestEmptyFilterMatchesNothing(t *testing.T) {
	_, m := setup()
	_, feed, err := m.Subscribe(traff.FilterList{}, t0)
	if err != nil || len(feed) != 0 {
		t.Errorf("empty filter feed = %v, %v", ids(feed), err)
	}
}

func TestPurgedMessagesAreForgotten(t *testing.T) {
	st, m := setup()
	id, feed, _ := m.Subscribe(traff.FilterList{{BBox: bbox(52.3, 13.0, 52.7, 13.8)}}, t0)
	eq(t, "initial", feed, "berlin-a", "berlin-p")
	// The source stops reporting; leases run out and everything is purged (including
	// tombstones) before the subscriber polls again.
	st.Replace("src", []traff.Message{}, t0.Add(time.Minute))
	st.Purge(t0.Add(3 * time.Hour))
	st.Replace("src", []traff.Message{msg("munich", 48.1, 11.5, traff.RoadClassMotorway, "CONGESTION_QUEUE")}, t0.Add(3*time.Hour))
	feed, err := m.Poll(id, t0.Add(50*time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	eq(t, "after purge", feed)
	m.mu.Lock()
	s := m.subs[id]
	m.mu.Unlock()
	if len(s.sent) != 0 {
		t.Errorf("sent map still has %d entries", len(s.sent))
	}
}

func TestSnapshot(t *testing.T) {
	st, _ := setup()
	st.Replace("src", []traff.Message{
		msg("berlin-a", 52.5, 13.4, traff.RoadClassMotorway, "CONGESTION_QUEUE"),
	}, t0.Add(time.Minute))
	got := Snapshot(st, traff.FilterList{{BBox: bbox(52.3, 13.0, 52.7, 13.8)}})
	eq(t, "snapshot without tombstones", got, "berlin-a")
}

func TestConcurrentUse(t *testing.T) {
	st, m := setup()
	fl := traff.FilterList{{BBox: bbox(47, 5, 55, 15)}}
	var wg sync.WaitGroup
	for w := 0; w < 8; w++ {
		wg.Add(1)
		go func(w int) {
			defer wg.Done()
			id, _, err := m.Subscribe(fl, t0)
			if err != nil {
				t.Error(err)
				return
			}
			for i := 0; i < 30; i++ {
				now := t0.Add(time.Duration(i) * time.Second)
				if _, err := m.Poll(id, now); err != nil {
					t.Error(err)
				}
				if i%10 == 0 {
					_, _ = m.Change(id, fl, now)
				}
			}
			m.Unsubscribe(id)
		}(w)
	}
	wg.Add(1)
	go func() {
		defer wg.Done()
		for i := 0; i < 30; i++ {
			st.Replace("src", []traff.Message{
				msg("berlin-a", 52.5, 13.4, traff.RoadClassMotorway, []string{"CONGESTION_QUEUE", "CONGESTION_SLOW_TRAFFIC"}[i%2]),
			}, t0.Add(time.Duration(i)*time.Second))
			m.Expire(t0)
		}
	}()
	wg.Wait()
	if m.Count() != 0 {
		t.Errorf("Count = %d", m.Count())
	}
}
