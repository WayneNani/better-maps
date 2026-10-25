package main

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/aggregate"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/ohttp"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/server"
)

type clock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *clock) Now() time.Time { c.mu.Lock(); defer c.mu.Unlock(); return c.t }
func (c *clock) Add(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.t.Add(d)
}

func testServer(t *testing.T, clk *clock, window time.Duration) (*server.Server, *httptest.Server) {
	t.Helper()
	cfg := server.DefaultConfig()
	cfg.Rules.Window, cfg.Aggregation.Window = window, window
	cfg.Rules.Grace, cfg.Aggregation.Grace = time.Second, time.Second
	cfg.RateLimit = 0
	keys, _ := ohttp.NewKeyRing(bytes.Repeat([]byte{5}, 32), time.Hour)
	srv := server.New(cfg, keys, aggregate.New(cfg.Aggregation, clk.Now()), slog.New(slog.NewTextHandler(io.Discard, nil)))
	srv.Now = clk.Now
	hs := httptest.NewServer(srv.Handler())
	t.Cleanup(hs.Close)
	return srv, hs
}

func TestLiveRunWithCheck(t *testing.T) {
	// Start 5 s before the end of a 60 s window: the simulator has to wait for the next one.
	real := time.Now()
	clk := &clock{t: real.Truncate(time.Minute).Add(55 * time.Second)}
	if clk.t.After(real) {
		clk.t = clk.t.Add(-time.Minute)
	}
	srv, hs := testServer(t, clk, time.Minute)
	sleep = func(ctx context.Context, d time.Duration) error {
		clk.Add(d)
		srv.Tick()
		return nil
	}
	out := &bytes.Buffer{}
	args := []string{"-relay", hs.URL + "/gateway", "-server", hs.URL, "-window", "60s", "-grace", "1s", "-check", "-seed", "3"}
	if err := run(context.Background(), args, out, clk.Now); err != nil {
		t.Fatalf("%v\n%s", err, out)
	}
	for _, want := range []string{"waiting", "simulated", "failed", "A100 Ost", "jam segments detected", "check OK"} {
		if !strings.Contains(out.String(), want) {
			t.Errorf("output lacks %q:\n%s", want, out)
		}
	}
	if h := srv.Health(); h.Aggregator.SegmentsAboveK == 0 || h.Memory.Congested == 0 {
		t.Fatalf("health %+v", h)
	}
}

func TestDryRun(t *testing.T) {
	out := &bytes.Buffer{}
	if err := run(context.Background(), []string{"-dry-run"}, out, time.Now); err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"example bundle", `"speed_bucket"`, "binary HTTP request (4096 bytes"} {
		if !strings.Contains(out.String(), want) {
			t.Errorf("output lacks %q:\n%s", want, out)
		}
	}
}

func TestErrors(t *testing.T) {
	ctx := context.Background()
	for _, args := range [][]string{
		{"-window", "1s"},
		{"-concurrency", "0"},
		{"-relay", "nourl"},
		{"-bogus"},
		{"-relay", "http://127.0.0.1:1/gateway"}, // key fetch fails
	} {
		if err := run(ctx, args, io.Discard, time.Now); err == nil {
			t.Errorf("%v accepted", args)
		}
	}
	// Sending fails: the gateway is a server with a different clock (Date check).
	clk := &clock{t: time.Now().Add(time.Hour)}
	_, hs := testServer(t, clk, time.Minute)
	out := &bytes.Buffer{}
	err := run(ctx, []string{"-relay", hs.URL + "/gateway", "-window", "60s"}, out, time.Now)
	if err == nil || !strings.Contains(out.String(), "error:") {
		t.Errorf("send failures not reported: %v\n%s", err, out)
	}
	// Check fails against an empty server.
	clk = &clock{t: time.Now()}
	srv, hs := testServer(t, clk, time.Minute)
	sleep = func(context.Context, time.Duration) error { return nil }
	_ = srv
	if err := run(ctx, []string{"-relay", hs.URL + "/gateway", "-server", hs.URL + "/nothing", "-window", "60s", "-check"}, io.Discard, clk.Now); err == nil {
		t.Error("check against missing export passed")
	}
	if err := getJSON(ctx, "::", nil); err == nil {
		t.Error("invalid URL")
	}
	if err := getJSON(ctx, "http://127.0.0.1:1/", nil); err == nil {
		t.Error("unreachable URL")
	}
	cctx, cancel := context.WithCancel(ctx)
	cancel()
	if err := realSleep(cctx, time.Hour); err == nil {
		t.Error("cancelled sleep")
	}
	if err := realSleep(ctx, time.Millisecond); err != nil {
		t.Error(err)
	}
	if err := realSleep(ctx, -1); err != nil {
		t.Error(err)
	}
}
