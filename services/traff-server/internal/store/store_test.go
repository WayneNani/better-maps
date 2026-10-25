package store

import (
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

var t0 = time.Date(2026, 10, 25, 16, 0, 0, 0, time.UTC)

func msg(id string, lat, lon float64, typ string) traff.Message {
	return traff.Message{
		ID: id,
		Location: &traff.Location{
			Directionality: traff.OneDirection, RoadClass: traff.RoadClassMotorway,
			From: traff.NewPoint(lat, lon), To: traff.NewPoint(lat+0.01, lon+0.01),
		},
		Events: []traff.Event{{Class: "CONGESTION", Type: typ}},
	}
}

func TestReplaceLifecycle(t *testing.T) {
	s := New(30*time.Minute, time.Hour)
	st := s.Replace("src", []traff.Message{msg("a", 50, 10, "CONGESTION_QUEUE"), msg("b", 51, 11, "CONGESTION_QUEUE")}, t0)
	if st.New != 2 || s.Seq() != 2 {
		t.Fatalf("first replace: %+v seq=%d", st, s.Seq())
	}
	a := s.Get("a")
	if a.Version == 0 || !a.Msg.ReceiveTime.Equal(t0) || !a.Msg.ExpirationTime.Equal(t0.Add(30*time.Minute)) {
		t.Errorf("entry a = %+v", a)
	}
	if !a.HasBBox || a.RoadClass != traff.RoadClassMotorway || a.Source != "src" {
		t.Errorf("bookkeeping of a = %+v", a)
	}

	// Same content five minutes later: nothing changes.
	t1 := t0.Add(5 * time.Minute)
	st = s.Replace("src", []traff.Message{msg("a", 50, 10, "CONGESTION_QUEUE"), msg("b", 51, 11, "CONGESTION_QUEUE")}, t1)
	if st.Unchanged != 2 || s.Seq() != 2 || s.Get("a") != a {
		t.Errorf("unchanged replace: %+v seq=%d", st, s.Seq())
	}

	// a changes its event and location, b disappears.
	t2 := t0.Add(10 * time.Minute)
	moved := msg("a", 50.5, 10.5, "CONGESTION_STATIONARY_TRAFFIC")
	st = s.Replace("src", []traff.Message{moved}, t2)
	if st.Changed != 1 || st.Cancelled != 1 {
		t.Fatalf("changing replace: %+v", st)
	}
	a2 := s.Get("a")
	if a2.Version <= a.Version || !a2.Msg.ReceiveTime.Equal(t0) || !a2.Msg.UpdateTime.Equal(t2) {
		t.Errorf("changed entry = %+v", a2)
	}
	want := traff.BBox{MinLat: 50, MinLon: 10, MaxLat: 50.51, MaxLon: 10.51}
	if a2.BBox != want {
		t.Errorf("bbox must only grow: got %v want %v", a2.BBox, want)
	}
	b := s.Get("b")
	if !b.Cancelled() || b.Msg.Location != nil || !b.HasBBox || !b.Msg.ExpirationTime.Equal(t2.Add(time.Hour)) || !b.Msg.ReceiveTime.Equal(t0) {
		t.Errorf("tombstone = %+v", b)
	}
	if active, cancelled := s.Counts(); active != 1 || cancelled != 1 {
		t.Errorf("Counts = %d, %d", active, cancelled)
	}

	// A cancelled message that reappears is new again.
	t3 := t0.Add(15 * time.Minute)
	st = s.Replace("src", []traff.Message{moved, msg("b", 51, 11, "CONGESTION_QUEUE")}, t3)
	if st.New != 1 || st.Unchanged != 1 {
		t.Errorf("reappearing: %+v", st)
	}
	if b := s.Get("b"); b.Cancelled() || !b.Msg.ReceiveTime.Equal(t3) {
		t.Errorf("reappeared entry = %+v", b)
	}

	// A second replace with the tombstone still present does not cancel twice.
	s.Replace("src", []traff.Message{moved}, t3.Add(time.Minute))
	v := s.Get("b").Version
	s.Replace("src", []traff.Message{moved}, t3.Add(2*time.Minute))
	if s.Get("b").Version != v {
		t.Error("tombstone was re-versioned")
	}
}

func TestLeaseRenewal(t *testing.T) {
	s := New(30*time.Minute, time.Hour)
	s.Replace("src", []traff.Message{msg("a", 50, 10, "CONGESTION_QUEUE")}, t0)
	v := s.Get("a").Version
	// 14 minutes later more than half of the lease is left.
	if st := s.Replace("src", []traff.Message{msg("a", 50, 10, "CONGESTION_QUEUE")}, t0.Add(14*time.Minute)); st.Unchanged != 1 {
		t.Errorf("early: %+v", st)
	}
	// 16 minutes later less than half is left: renew.
	t1 := t0.Add(16 * time.Minute)
	if st := s.Replace("src", []traff.Message{msg("a", 50, 10, "CONGESTION_QUEUE")}, t1); st.Renewed != 1 {
		t.Errorf("renewal: %+v", st)
	}
	e := s.Get("a")
	if e.Version <= v || !e.Msg.ExpirationTime.Equal(t1.Add(30*time.Minute)) || !e.Msg.UpdateTime.Equal(t1) || !e.Msg.ReceiveTime.Equal(t0) {
		t.Errorf("renewed entry = %+v", e.Msg)
	}

	// Messages with a far end time do not need renewal.
	long := msg("long", 50, 10, "CONGESTION_QUEUE")
	long.EndTime = traff.TimePtr(t0.Add(30 * 24 * time.Hour))
	s.Replace("src", []traff.Message{long}, t0)
	v = s.Get("long").Version
	s.Replace("src", []traff.Message{long}, t0.Add(25*time.Minute))
	if s.Get("long").Version != v {
		t.Error("message with distant end time was renewed")
	}
}

func TestReplaceSkips(t *testing.T) {
	s := New(30*time.Minute, time.Hour)
	s.Replace("one", []traff.Message{msg("x", 50, 10, "CONGESTION_QUEUE")}, t0)
	cancel := msg("c", 50, 10, "CONGESTION_QUEUE")
	cancel.Cancellation = true
	st := s.Replace("two", []traff.Message{
		msg("x", 52, 12, "CONGESTION_QUEUE"), // owned by "one"
		msg("", 52, 12, "CONGESTION_QUEUE"),  // no ID
		msg("y", 52, 12, "CONGESTION_QUEUE"),
		msg("y", 53, 13, "CONGESTION_QUEUE"), // duplicate
		cancel,                               // sources must not send cancellations
	}, t0)
	if st.Skipped != 4 || st.New != 1 {
		t.Errorf("stats = %+v", st)
	}
	if s.Get("x").Source != "one" || s.Get("x").Msg.Location.From.Coord.Lat != 50 {
		t.Error("message of another source was overwritten")
	}
	// Replacing source "two" with nothing must not touch "one".
	s.Replace("two", nil, t0.Add(time.Minute))
	if s.Get("x").Cancelled() || !s.Get("y").Cancelled() {
		t.Error("cancellation crossed source boundaries")
	}
}

func TestChangeWithoutLocationKeepsBBox(t *testing.T) {
	s := New(30*time.Minute, time.Hour)
	s.Replace("src", []traff.Message{msg("a", 50, 10, "CONGESTION_QUEUE")}, t0)
	noLoc := traff.Message{ID: "a", Events: []traff.Event{{Class: "CONGESTION", Type: "CONGESTION_QUEUE"}}}
	s.Replace("src", []traff.Message{noLoc}, t0.Add(time.Minute))
	e := s.Get("a")
	if !e.HasBBox || e.RoadClass != traff.RoadClassMotorway {
		t.Errorf("bbox/road class lost: %+v", e)
	}
}

func TestPurge(t *testing.T) {
	s := New(30*time.Minute, time.Hour)
	withEnd := msg("end", 50, 10, "CONGESTION_QUEUE")
	withEnd.EndTime = traff.TimePtr(t0.Add(2 * time.Hour))
	s.Replace("src", []traff.Message{msg("a", 50, 10, "CONGESTION_QUEUE"), msg("b", 50, 10, "CONGESTION_QUEUE"), withEnd}, t0)
	s.Replace("src", []traff.Message{msg("a", 50, 10, "CONGESTION_QUEUE"), withEnd}, t0.Add(time.Minute)) // b -> tombstone until t0+61m

	if n := s.Purge(t0.Add(29 * time.Minute)); n != 0 {
		t.Errorf("purged %d too early", n)
	}
	if n := s.Purge(t0.Add(31 * time.Minute)); n != 1 || s.Get("a") != nil { // lease of a expired
		t.Errorf("purge after lease: %d", n)
	}
	if n := s.Purge(t0.Add(62 * time.Minute)); n != 1 || s.Get("b") != nil { // tombstone retention over
		t.Errorf("purge of tombstone: %d", n)
	}
	if s.Get("end") == nil {
		t.Error("message with end time in the future was purged")
	}
	if n := s.Purge(t0.Add(3 * time.Hour)); n != 1 {
		t.Errorf("purge after end time: %d", n)
	}
}

func TestForEachAndConcurrency(t *testing.T) {
	s := New(30*time.Minute, time.Hour)
	var wg sync.WaitGroup
	for w := 0; w < 4; w++ {
		wg.Add(2)
		go func(w int) {
			defer wg.Done()
			for i := 0; i < 50; i++ {
				var msgs []traff.Message
				for j := 0; j < 20; j++ {
					msgs = append(msgs, msg(fmt.Sprintf("%d-%d", w, j), 50, 10+float64(i%3), "CONGESTION_QUEUE"))
				}
				s.Replace(fmt.Sprint("src", w), msgs, t0.Add(time.Duration(i)*time.Minute))
			}
		}(w)
		go func() {
			defer wg.Done()
			for i := 0; i < 50; i++ {
				n := 0
				s.ForEach(func(*Entry) { n++ })
				_ = s.Seq()
			}
		}()
	}
	wg.Wait()
	n := 0
	s.ForEach(func(*Entry) { n++ })
	if n != 80 {
		t.Errorf("entries = %d", n)
	}
}
