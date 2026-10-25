package export

import (
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/aggregate"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/clientcheck"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/protocol"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/tiles"
)

var now = time.Date(2026, 10, 26, 8, 20, 0, 0, time.UTC)

func pub(x int, state aggregate.State, confident bool, speed float64) aggregate.Publication {
	k := protocol.Key{Tile: tiles.Tile{X: x, Y: 43010}, Entry: tiles.West, Exit: tiles.East, Class: protocol.Motorway}
	p := aggregate.Publication{
		Key: k, WindowStart: now.Add(-20 * time.Minute), WindowEnd: now.Add(-5 * time.Minute), Reports: 17,
		Speed: speed, RefSpeed: 110, Ratio: speed / 110, SlowShare: 0.9, SlowShareSE: 0.07,
		State: state, Confident: confident, FirstSeen: now.Add(-15 * time.Minute), Published: now,
		Expires: now.Add(20 * time.Minute),
	}
	p.From.Lat, p.From.Lon = k.Tile.EdgePoint(tiles.West, 100)
	p.To.Lat, p.To.Lon = k.Tile.EdgePoint(tiles.East, 120)
	return p
}

func sample() []aggregate.Publication {
	return []aggregate.Publication{
		pub(70420, aggregate.Stationary, true, 6.4),
		pub(70421, aggregate.Queue, true, 22),
		pub(70422, aggregate.Slow, true, 45),
		pub(70423, aggregate.Heavy, true, 70),
		pub(70424, aggregate.Queue, false, 30), // not confident
		pub(70425, aggregate.FreeFlow, true, 100),
	}
}

func TestTraffFeedIsAcceptedByClient(t *testing.T) {
	b, err := TraffFeed(sample(), nil)
	if err != nil {
		t.Fatal(err)
	}
	s := string(b)
	if !strings.HasPrefix(s, "<?xml") || !strings.Contains(s, "<feed>") {
		t.Fatalf("feed:\n%s", s)
	}
	body := strings.TrimPrefix(s, strings.SplitN(s, "\n", 2)[0]+"\n")
	res, err := clientcheck.ParseResponse([]byte(`<response status="OK">` + body + `</response>`))
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Rejected) != 0 || len(res.SkippedEvents) != 0 {
		t.Fatalf("rejected %v, skipped %v", res.Rejected, res.SkippedEvents)
	}
	want := []string{"CONGESTION_STATIONARY_TRAFFIC", "CONGESTION_QUEUE", "CONGESTION_SLOW_TRAFFIC", "CONGESTION_HEAVY_TRAFFIC"}
	if len(res.Accepted) != len(want) {
		t.Fatalf("accepted %d messages, want %d:\n%s", len(res.Accepted), len(want), s)
	}
	for i, m := range res.Accepted {
		if m.Events[0] != want[i] || !strings.HasPrefix(m.ID, IDPrefix+"17/") || m.Cancellation {
			t.Errorf("message %d: %+v", i, m)
		}
	}
	for _, frag := range []string{
		`id="org.bettermaps.crowd:17/70420/43010/W-E/MOTORWAY"`,
		`receive_time="2026-10-26T08:05:00Z"`, `update_time="2026-10-26T08:20:00Z"`,
		`expiration_time="2026-10-26T08:40:00Z"`, `directionality="ONE_DIRECTION"`, `road_class="MOTORWAY"`,
		`speed="6"`, `<from>+52.`, `<to>+52.`,
	} {
		if !strings.Contains(s, frag) {
			t.Errorf("feed lacks %s", frag)
		}
	}
}

func TestTraffFeedEmptyAndBBox(t *testing.T) {
	b, err := TraffFeed(nil, nil)
	if err != nil || !strings.Contains(string(b), "<feed></feed>") {
		t.Fatalf("empty feed: %s, %v", b, err)
	}
	lat, lon := tiles.Tile{X: 70420, Y: 43010}.Center()
	box := &BBox{MinLat: lat - 0.001, MinLon: lon - 0.002, MaxLat: lat + 0.001, MaxLon: lon + 0.001}
	b, _ = TraffFeed(sample(), box)
	if n := strings.Count(string(b), "<message "); n != 1 {
		t.Fatalf("bbox feed has %d messages:\n%s", n, b)
	}
	paris := &BBox{MinLat: 48.8, MinLon: 2.2, MaxLat: 48.9, MaxLon: 2.4}
	b, _ = TraffFeed(sample(), paris)
	if strings.Contains(string(b), "<message ") {
		t.Fatal("messages outside bbox")
	}
}

func TestJSON(t *testing.T) {
	b, err := JSON(sample(), nil, aggregate.DefaultConfig(), now)
	if err != nil {
		t.Fatal(err)
	}
	var doc Document
	if err := json.Unmarshal(b, &doc); err != nil {
		t.Fatal(err)
	}
	if doc.Version != 1 || doc.K != 10 || doc.WindowSeconds != 900 || doc.Epsilon != 3 || len(doc.Segments) != 6 {
		t.Fatalf("doc = %+v", doc)
	}
	s := doc.Segments[0]
	if s.ID != IDPrefix+"17/70420/43010/W-E/MOTORWAY" || s.Tile != [3]int{17, 70420, 43010} || s.Entry != "W" ||
		s.Exit != "E" || s.State != "STATIONARY_TRAFFIC" || !s.Congested || s.TraffEvent != "CONGESTION_STATIONARY_TRAFFIC" ||
		s.SpeedKmh != 6.4 || s.RefSource != "road_class_default" || s.Reports != 17 {
		t.Fatalf("segment = %+v", s)
	}
	if free := doc.Segments[5]; free.Congested || free.TraffEvent != "" || free.State != "FREE_FLOW" {
		t.Fatalf("free = %+v", free)
	}
	if unsure := doc.Segments[4]; unsure.Congested || unsure.Confident {
		t.Fatalf("unsure = %+v", unsure)
	}
	p := sample()
	p[0].RefObserved = true
	b, _ = JSON(p[:1], &BBox{MinLat: 0, MinLon: 0, MaxLat: 1, MaxLon: 1}, aggregate.DefaultConfig(), now)
	if !strings.Contains(string(b), `"segments": []`) {
		t.Fatalf("bbox json: %s", b)
	}
	b, _ = JSON(p[:1], nil, aggregate.DefaultConfig(), now)
	if !strings.Contains(string(b), `"ref_source": "observed"`) {
		t.Fatalf("observed json: %s", b)
	}
}
