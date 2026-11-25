package main

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/indoor-server/internal/auth"
	"github.com/WayneNani/better-maps/services/indoor-server/internal/config"
	"github.com/WayneNani/better-maps/services/indoor-server/internal/dataset"
	"github.com/WayneNani/better-maps/services/indoor-server/internal/server"
	"github.com/WayneNani/better-maps/services/indoor-server/internal/venues"
)

func noEnv(string) string { return "" }

func TestTokenCommand(t *testing.T) {
	var out bytes.Buffer
	if err := run(context.Background(), []string{"token", "pixel-9"}, noEnv, &out, io.Discard); err != nil {
		t.Fatal(err)
	}
	var token, line string
	for _, l := range strings.Split(strings.TrimSpace(out.String()), "\n") {
		if v, ok := strings.CutPrefix(l, "token="); ok {
			token = v
		}
		if v, ok := strings.CutPrefix(l, "line="); ok {
			line = v
		}
	}
	store, err := auth.Parse(strings.NewReader(line))
	if err != nil {
		t.Fatal(err)
	}
	if label, ok := store.Lookup(token); !ok || label != "pixel-9" {
		t.Fatalf("generated token not accepted: %q %v", label, ok)
	}
	if strings.Contains(line, token) {
		t.Fatal("token file line contains the token")
	}
}

func TestTokenCommandRejectsBadLabels(t *testing.T) {
	for _, args := range [][]string{{"token"}, {"token", "a b"}, {"token", "x", "y"}, {"token", strings.Repeat("l", 33)}} {
		if err := run(context.Background(), args, noEnv, io.Discard, io.Discard); err == nil {
			t.Errorf("%v accepted", args)
		}
	}
}

func TestServeNeedsTokenFile(t *testing.T) {
	err := run(context.Background(), nil, noEnv, io.Discard, io.Discard)
	if err == nil || !strings.Contains(err.Error(), "token file") {
		t.Fatalf("err = %v", err)
	}
	err = run(context.Background(), nil, func(k string) string {
		return map[string]string{"INDOOR_TOKENS_FILE": "/nonexistent/tokens", "INDOOR_DATA_DIR": "/nonexistent"}[k]
	}, io.Discard, io.Discard)
	if err == nil {
		t.Fatal("missing token file accepted")
	}
}

// writeData writes a data directory with n venues.
func writeData(t *testing.T, dir string, n int) {
	t.Helper()
	os.MkdirAll(filepath.Join(dir, "venues"), 0o755)
	idx := venues.Index{DataTimestamp: "2026-11-24T21:21:02Z"}
	for i := 0; i < n; i++ {
		id := "w" + string(rune('1'+i))
		idx.Venues = append(idx.Venues, venues.IndexEntry{ID: id, Kind: "other", BBox: [4]float64{8, 50, 8.1, 50.1}, Features: 1})
		_, gz, _ := venues.Marshal(map[string]string{"id": id})
		os.WriteFile(filepath.Join(dir, "venues", id+".json.gz"), gz, 0o644)
	}
	b, _, _ := venues.Marshal(idx)
	os.WriteFile(filepath.Join(dir, "index.json"), b, 0o644)
}

type syncBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *syncBuffer) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.String()
}

func TestServeAndShutdown(t *testing.T) {
	dir := t.TempDir()
	tokens := filepath.Join(dir, "tokens")
	os.WriteFile(tokens, []byte(""), 0o600)
	data := filepath.Join(dir, "data")
	env := func(k string) string {
		return map[string]string{"INDOOR_TOKENS_FILE": tokens, "INDOOR_DATA_DIR": data, "INDOOR_LISTEN": "127.0.0.1:0"}[k]
	}
	for _, n := range []int{0, 2} { // without data (starts anyway) and with
		if n > 0 {
			writeData(t, data, n)
		}
		ctx, cancel := context.WithCancel(context.Background())
		var logs syncBuffer
		done := make(chan error, 1)
		go func() { done <- run(ctx, nil, env, io.Discard, &logs) }()
		deadline := time.Now().Add(5 * time.Second)
		for !strings.Contains(logs.String(), "listening") && time.Now().Before(deadline) {
			time.Sleep(5 * time.Millisecond)
		}
		cancel()
		if err := <-done; err != nil {
			t.Fatal(err)
		}
		want := "no venue data loaded"
		if n > 0 {
			want = "venues=2"
		}
		if !strings.Contains(logs.String(), want) || !strings.Contains(logs.String(), "token file has no tokens") {
			t.Fatalf("logs: %s", logs.String())
		}
	}
	// Port in use.
	env2 := func(k string) string {
		return map[string]string{"INDOOR_TOKENS_FILE": tokens, "INDOOR_DATA_DIR": data, "INDOOR_LISTEN": "256.0.0.1:1"}[k]
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err := run(ctx, nil, env2, io.Discard, io.Discard); err == nil {
		t.Fatal("bad listen address accepted")
	}
}

func TestMaintainReloadsDataAndTokens(t *testing.T) {
	dir := t.TempDir()
	tokens := filepath.Join(dir, "tokens")
	os.WriteFile(tokens, []byte(""), 0o600)
	data := filepath.Join(dir, "data")
	writeData(t, data, 1)
	ds, err := dataset.Load(data)
	if err != nil {
		t.Fatal(err)
	}
	store := auth.NewStore()
	var logs syncBuffer
	logger := slog.New(slog.NewTextHandler(&logs, nil))
	srv := server.New(server.DefaultConfig(), store, ds, logger, nil)
	cfg := config.Config{TokensFile: tokens, DataDir: data, ReloadTokens: 10 * time.Millisecond, ReloadData: 10 * time.Millisecond}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { maintain(ctx, cfg, store, srv, logger); close(done) }()
	defer func() { cancel(); <-done }()

	waitFor := func(what string, cond func() bool) {
		t.Helper()
		deadline := time.Now().Add(5 * time.Second)
		for !cond() {
			if time.Now().After(deadline) {
				t.Fatalf("timeout waiting for %s; logs: %s", what, logs.String())
			}
			time.Sleep(5 * time.Millisecond)
		}
	}
	// New data: a later mtime on index.json.
	writeData(t, data, 3)
	later := time.Now().Add(time.Minute)
	os.Chtimes(filepath.Join(data, "index.json"), later, later)
	waitFor("data reload", func() bool { return srv.Data().Len() == 3 })

	// Broken data keeps the previous set.
	os.WriteFile(filepath.Join(data, "index.json"), []byte("{"), 0o644)
	later = later.Add(time.Minute)
	os.Chtimes(filepath.Join(data, "index.json"), later, later)
	waitFor("error log", func() bool { return strings.Contains(logs.String(), "keeping the previous data") })
	if srv.Data().Len() != 3 {
		t.Fatal("broken data replaced the previous set")
	}

	// Tokens.
	token := strings.Repeat("t", 43)
	os.WriteFile(tokens, []byte(auth.HashLine("pixel", token)+"\n"), 0o600)
	os.Chtimes(tokens, later, later)
	waitFor("token reload", func() bool { _, ok := store.Lookup(token); return ok })
	os.WriteFile(tokens, []byte("broken"), 0o600)
	os.Chtimes(tokens, later.Add(time.Minute), later.Add(time.Minute))
	waitFor("token error", func() bool { return strings.Contains(logs.String(), "keeping the previous tokens") })
	if _, ok := store.Lookup(token); !ok {
		t.Fatal("broken token file replaced the tokens")
	}

	// SIGHUP reloads both.
	writeData(t, data, 2)
	os.WriteFile(tokens, []byte(""), 0o600)
	syscall.Kill(os.Getpid(), syscall.SIGHUP)
	waitFor("SIGHUP", func() bool { return srv.Data().Len() == 2 && store.Len() == 0 })
}
