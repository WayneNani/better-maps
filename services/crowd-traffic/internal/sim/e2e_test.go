package sim

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"math/rand/v2"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/aggregate"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/client"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/clientcheck"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/export"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/ohttp"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/protocol"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/relay"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/server"
)

var t0 = time.Date(2026, 10, 26, 7, 0, 0, 0, time.UTC)

type clock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *clock) Now() time.Time { c.mu.Lock(); defer c.mu.Unlock(); return c.t }
func (c *clock) Set(t time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if t.After(c.t) {
		c.t = t
	}
}

// End-to-end: simulated trips → reference client (trimming, sampling, noise, OHTTP) → relay →
// gateway → aggregation → TraFF/JSON export. The export must contain congestion exactly where
// the simulation has a jam, and nothing for segments with fewer than k reports.
func TestEndToEnd(t *testing.T) { runEndToEnd(t, 2026) }

func runEndToEnd(t *testing.T, seed uint64) {
	clk := &clock{t: t0}
	logs := &bytes.Buffer{}
	log := slog.New(slog.NewTextHandler(logs, &slog.HandlerOptions{Level: slog.LevelDebug}))
	keys, _ := ohttp.NewKeyRing(bytes.Repeat([]byte{7}, 32), 7*24*time.Hour)
	cfg := server.DefaultConfig()
	srv := server.New(cfg, keys, aggregate.New(cfg.Aggregation, t0), log)
	srv.Now = clk.Now
	gw := httptest.NewServer(srv.Handler())
	defer gw.Close()
	rcfg := relay.DefaultConfig()
	rcfg.GatewayURL, rcfg.KeysURL = gw.URL+"/gateway", gw.URL+"/ohttp-keys"
	rcfg.Burst = 1 << 20 // all simulated clients share 127.0.0.1
	rl := relay.New(rcfg)
	rl.Now = clk.Now
	rs := httptest.NewServer(rl.Handler())
	defer rs.Close()
	sender := &client.Sender{RelayURL: rs.URL + "/relay", KeysURL: rs.URL + "/ohttp-keys", Now: clk.Now}

	sc := Berlin()
	rnd := rand.New(rand.NewPCG(seed, 10))
	ccfg := client.DefaultConfig()
	bundles := Plan(sc.Trips(t0, ccfg.Window, rnd), ccfg, rnd)
	if len(bundles) < 100 {
		t.Fatalf("only %d bundles", len(bundles))
	}
	perRoad := map[string]int{}
	ctx := context.Background()
	snapshots := []snapshot{}
	nextEval := t0.Add(ccfg.Window + cfg.Aggregation.Grace)
	evaluate := func(until time.Time) {
		for !nextEval.After(until) {
			clk.Set(nextEval)
			srv.Tick()
			snapshots = append(snapshots, takeSnapshot(t, gw.URL))
			nextEval = nextEval.Add(ccfg.Window)
		}
	}
	for _, b := range bundles {
		evaluate(b.SendAt)
		clk.Set(b.SendAt)
		if err := sender.Send(ctx, b.Reports); err != nil {
			t.Fatalf("send at %v: %v", b.SendAt, err)
		}
		perRoad[b.Road] += len(b.Reports)
	}
	last := bundles[len(bundles)-1].SendAt
	evaluate(last.Add(ccfg.Window))
	t.Logf("bundles %d, reports per road %v, %d evaluations", len(bundles), perRoad, len(snapshots))

	h := srv.Health()
	if h.Gateway.BundlesAccepted != uint64(len(bundles)) || h.Aggregator.ReportsLate != 0 {
		t.Fatalf("health %+v", h)
	}
	t.Logf("aggregator %+v", h.Aggregator)
	checkSnapshots(t, snapshots, sc)

	// After all publications have expired, nothing but the long-term references remain.
	clk.Set(last.Add(2 * time.Hour))
	srv.Tick()
	if m := srv.Health().Memory; m.OpenWindows != 0 || m.OpenSegments != 0 || m.Publications != 0 || m.References == 0 {
		t.Fatalf("memory after expiry %+v", m)
	}
	if feed := get(t, gw.URL+"/v1/traff"); strings.Contains(feed, "<message") {
		t.Fatal("expired messages in feed")
	}
	for _, bad := range []string{"127.0.0.1", "Go-http-client", "speed_bucket", "\"tile\""} {
		if strings.Contains(logs.String(), bad) {
			t.Errorf("log contains %q", bad)
		}
	}
}

func get(t *testing.T, url string) string {
	t.Helper()
	resp, err := http.Get(url)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return string(b)
}

type snapshot struct {
	doc  export.Document
	feed string
}

func takeSnapshot(t *testing.T, base string) snapshot {
	t.Helper()
	var sn snapshot
	if err := json.Unmarshal([]byte(get(t, base+"/v1/segments")), &sn.doc); err != nil {
		t.Fatal(err)
	}
	sn.feed = get(t, base+"/v1/traff")
	return sn
}

func checkSnapshots(t *testing.T, snaps []snapshot, sc Scenario) {
	t.Helper()
	ids := func(m map[protocol.Key]bool) map[string]bool {
		res := map[string]bool{}
		for k := range m {
			res[export.MessageID(k)] = true
		}
		return res
	}
	roadOf := map[string]string{}
	var jamInside, jamTouch map[string]bool
	for _, r := range sc.Roads {
		in, touch, all := r.SegmentZones()
		for id := range ids(all) {
			roadOf[id] = r.Name
		}
		if len(r.Jams) > 0 {
			jamInside, jamTouch = ids(in), ids(touch)
		}
	}
	published := map[string]int{} // per road, segment-window publications
	insideSeen := map[string]bool{}
	insidePublished, insideCongested, congestedTotal := 0, 0, 0
	for _, sn := range snaps {
		congested := map[string]bool{}
		for _, s := range sn.doc.Segments {
			if s.Reports < sn.doc.K {
				t.Errorf("segment %s published with %d reports", s.ID, s.Reports)
			}
			road := roadOf[s.ID]
			if road == "" {
				t.Errorf("published segment %s is on no simulated road", s.ID)
			}
			if road == "Dorfstraße Schönerlinde" {
				t.Errorf("village road segment %s published (fewer than k vehicles)", s.ID)
			}
			if !s.Updated.Equal(sn.doc.Generated) {
				continue // still alive from an earlier evaluation; counted there
			}
			published[road]++
			if s.Congested {
				congested[s.ID] = true
				congestedTotal++
				if !jamTouch[s.ID] {
					t.Errorf("congestion outside the simulated jam: %+v", s)
				}
			}
			if jamInside[s.ID] {
				insideSeen[s.ID] = true
				insidePublished++
				if s.Congested {
					insideCongested++
					if s.State != "STATIONARY_TRAFFIC" && s.State != "QUEUE" {
						t.Errorf("jam at 8 km/h classified as %s (%.1f km/h)", s.State, s.SpeedKmh)
					}
				}
			}
		}
		for _, s := range sn.doc.Segments {
			if s.Congested {
				congested[s.ID] = true
			}
		}
		// The TraFF feed contains exactly the congested segments and is accepted by the client.
		body := strings.SplitN(sn.feed, "\n", 2)[1]
		res, err := clientcheck.ParseResponse([]byte(`<response status="OK">` + body + `</response>`))
		if err != nil || len(res.Rejected) != 0 {
			t.Fatalf("feed rejected: %v %v", err, res.Rejected)
		}
		if len(res.Accepted) != len(congested) {
			t.Fatalf("feed has %d messages, %d congested segments", len(res.Accepted), len(congested))
		}
		for _, m := range res.Accepted {
			if !congested[m.ID] {
				t.Errorf("feed message %s not congested", m.ID)
			}
		}
	}
	t.Logf("publications per road %v; jam: %d of %d segments seen, %d publications, %d congested; congested total %d",
		published, len(insideSeen), len(jamInside), insidePublished, insideCongested, congestedTotal)
	if len(insideSeen) < len(jamInside)*3/4 || insideCongested < insidePublished*9/10 || insideCongested == 0 {
		t.Errorf("jam detection too weak")
	}
	if published["A100 West"] == 0 || published["B96 Tempelhofer Damm Süd"] == 0 {
		t.Errorf("free-flowing roads not published: %v", published)
	}
}

func TestScenarioGeometry(t *testing.T) {
	sc := Berlin()
	for _, r := range sc.Roads {
		in, touch, all := r.SegmentZones()
		if len(all) == 0 {
			t.Fatalf("%s: no segments", r.Name)
		}
		if len(r.Jams) > 0 && (len(in) < 5 || len(touch) < len(in)) {
			t.Fatalf("%s: jam zone %d/%d", r.Name, len(in), len(touch))
		}
		for k := range all {
			if !protocol.Germany.Contains(k.Tile.Center()) {
				t.Fatalf("%s: tile outside coverage", r.Name)
			}
		}
	}
	if l := sc.Roads[0].Length(); l < 6000 || l > 9000 {
		t.Fatalf("A100 length %.0f", l)
	}
	// Speeds of a driven trip: approach at 50, jam at 8 km/h.
	pts := sc.Roads[0].Drive(t0, 90)
	dur := pts[len(pts)-1].Time.Sub(pts[0].Time)
	if dur < 15*time.Minute || dur > 25*time.Minute {
		t.Fatalf("trip duration %v", dur)
	}
	pl := newPolyline([][2]float64{{52, 13}, {52.01, 13}}, []protocol.RoadClass{protocol.Primary})
	if lat, _, c := pl.at(-5); lat != 52 || c != protocol.Primary {
		t.Fatal("at < 0")
	}
	if lat, _, _ := pl.at(1e9); lat != 52.01 {
		t.Fatal("at > length")
	}
}
