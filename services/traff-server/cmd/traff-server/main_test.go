package main

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"strings"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/config"
	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

func noEnv(string) string { return "" }

func TestRunConfigErrors(t *testing.T) {
	var out bytes.Buffer
	if err := run([]string{"-poll-interval", "1s"}, noEnv, &out); err == nil {
		t.Error("invalid config accepted")
	}
	if err := run([]string{"-listen", "256.0.0.1:99999", "-autobahn=false"}, noEnv, &out); err == nil {
		t.Error("invalid listen address accepted")
	}
}

func TestServeAndShutdown(t *testing.T) {
	cfg, err := config.Parse([]string{"-listen", "127.0.0.1:0", "-autobahn=false"}, noEnv, io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	var logs bytes.Buffer
	a, err := build(cfg, slog.New(slog.NewTextHandler(&logs, nil)))
	if err != nil {
		t.Fatal(err)
	}
	a.janitorEvery = 5 * time.Millisecond
	// An idle subscription that the janitor has to expire.
	a.subs.Subscribe(traff.FilterList{}, time.Now().Add(-2*time.Hour))

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- a.serve(ctx, io.Discard) }()
	deadline := time.Now().Add(2 * time.Second)
	for a.subs.Count() != 0 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Errorf("serve = %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("serve did not shut down")
	}
	if a.subs.Count() != 0 || !strings.Contains(logs.String(), "subscriptions expired") {
		t.Errorf("janitor did not run:\n%s", logs.String())
	}
}

func TestBuildWithAutobahn(t *testing.T) {
	cfg, err := config.Parse([]string{"-autobahn-roads", "A7"}, noEnv, io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	a, err := build(cfg, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	if len(a.runner.Coverage()) != 1 || len(a.runner.Status()) != 1 {
		t.Errorf("autobahn source not registered")
	}
}

func TestBuildWithDatex2(t *testing.T) {
	envs := map[string]string{"TRAFF_DATEX2_FEEDS": "bw=https://example.org/a.xml,local=file:///tmp/x.xml"}
	cfg, err := config.Parse([]string{"-autobahn-roads", "A7"}, func(k string) string { return envs[k] }, io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	a, err := build(cfg, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	st := a.runner.Status()
	if len(st) != 3 || st[0].Name != "autobahn" || st[1].Name != "datex2-bw" || st[2].Name != "datex2-local" {
		t.Errorf("sources = %+v", st)
	}

	// Unreadable client certificates are reported at start-up.
	envs["TRAFF_DATEX2_CLIENT_CERT"], envs["TRAFF_DATEX2_CLIENT_KEY"] = "/nonexistent/c.pem", "/nonexistent/k.pem"
	cfg, err = config.Parse([]string{"-autobahn=false", "-dedup=false"}, func(k string) string { return envs[k] }, io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := build(cfg, slog.New(slog.NewTextHandler(io.Discard, nil))); err == nil || !strings.Contains(err.Error(), "client certificate") {
		t.Errorf("build = %v", err)
	}
}

func TestBuildWithCrowd(t *testing.T) {
	envs := map[string]string{"TRAFF_CROWD_URL": "http://crowd-traffic:8080", "TRAFF_DATEX2_FEEDS": "bw=https://example.org/a.xml"}
	cfg, err := config.Parse([]string{"-autobahn-roads", "A7", "-dedup-spatial-distance", "0"}, func(k string) string { return envs[k] }, io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	a, err := build(cfg, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	// The crowd source has the lowest priority.
	st := a.runner.Status()
	if len(st) != 3 || st[0].Name != "autobahn" || st[1].Name != "datex2-bw" || st[2].Name != "crowd" {
		t.Errorf("sources = %+v", st)
	}
	// An invalid URL that slipped past the configuration is reported by build.
	cfg.CrowdURL = "ftp://crowd"
	if _, err := build(cfg, slog.New(slog.NewTextHandler(io.Discard, nil))); err == nil {
		t.Error("invalid crowd URL accepted")
	}
}
