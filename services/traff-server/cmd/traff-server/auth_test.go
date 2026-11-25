package main

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/auth"
	"github.com/WayneNani/better-maps/services/traff-server/internal/config"
)

func TestTokenCommand(t *testing.T) {
	var out bytes.Buffer
	if err := run([]string{"token", "pixel-9"}, func(string) string { return "" }, &out, io.Discard); err != nil {
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
		if err := run(args, noEnv, io.Discard, io.Discard); err == nil {
			t.Errorf("%v accepted", args)
		}
	}
}

func TestRefusesToStartWithoutTokenFile(t *testing.T) {
	err := run([]string{"-autobahn=false"}, func(string) string { return "" }, io.Discard, io.Discard)
	if err == nil || !strings.Contains(err.Error(), "TRAFF_TOKENS_FILE") || !strings.Contains(err.Error(), "TRAFF_INSECURE_NO_AUTH") {
		t.Fatalf("err = %v", err)
	}
	err = run([]string{"-autobahn=false"}, func(k string) string {
		return map[string]string{"TRAFF_TOKENS_FILE": "/nonexistent/tokens"}[k]
	}, io.Discard, io.Discard)
	if err == nil {
		t.Fatal("missing token file accepted")
	}
	// A token pasted into the file instead of its hash is reported without echoing it.
	file := filepath.Join(t.TempDir(), "tokens")
	os.WriteFile(file, []byte("pixel "+strings.Repeat("s", 43)+"\n"), 0o600)
	err = run([]string{"-autobahn=false"}, func(k string) string { return map[string]string{"TRAFF_TOKENS_FILE": file}[k] }, io.Discard, io.Discard)
	if err == nil || strings.Contains(err.Error(), "sss") {
		t.Fatalf("err = %v", err)
	}
}

func TestInsecureModeWarns(t *testing.T) {
	cfg, err := config.Parse([]string{"-autobahn=false"}, noEnv, io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	var logs bytes.Buffer
	a, err := build(cfg, slog.New(slog.NewTextHandler(&logs, nil)))
	if err != nil {
		t.Fatal(err)
	}
	if a.tokens != nil || a.srv.Tokens != nil || !strings.Contains(logs.String(), "level=WARN") ||
		!strings.Contains(logs.String(), "WITHOUT access control") {
		t.Errorf("no warning:\n%s", logs.String())
	}
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

func TestServeWithTokensAndReload(t *testing.T) {
	tokenA, tokenB := strings.Repeat("a", 43), strings.Repeat("b", 43)
	file := filepath.Join(t.TempDir(), "tokens")
	os.WriteFile(file, []byte(auth.HashLine("pixel", tokenA)+"\n"), 0o600)
	envs := map[string]string{"TRAFF_TOKENS_FILE": file, "TRAFF_RELOAD_TOKENS": "10ms", "TRAFF_RATE_PER_MINUTE": "600"}
	cfg, err := config.Parse([]string{"-autobahn=false"}, func(k string) string { return envs[k] }, io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	var logs syncBuffer
	a, err := build(cfg, slog.New(slog.NewTextHandler(&logs, nil)))
	if err != nil {
		t.Fatal(err)
	}
	ts := httptest.NewServer(a.srv.Handler())
	defer ts.Close()

	post := func(token string) int {
		t.Helper()
		req, _ := http.NewRequest(http.MethodPost, ts.URL+"/", strings.NewReader(`<request operation="GET_CAPABILITIES"/>`))
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	if code := post(""); code != http.StatusUnauthorized {
		t.Errorf("without token = %d", code)
	}
	if code := post(tokenA); code != 200 {
		t.Errorf("token A = %d", code)
	}
	if code := post(tokenB); code != http.StatusUnauthorized {
		t.Errorf("token B before reload = %d", code)
	}
	resp, err := http.Get(ts.URL + "/healthz")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 || string(body) != "ok\n" {
		t.Errorf("/healthz = %d %q", resp.StatusCode, body)
	}

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { a.maintainTokens(ctx); close(done) }()
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

	// Adding a device and revoking another one takes effect without a restart.
	later := time.Now().Add(time.Minute)
	os.WriteFile(file, []byte(auth.HashLine("tablet", tokenB)+"\n"), 0o600)
	os.Chtimes(file, later, later)
	// (Polling with the new token would run into the limit for wrong tokens.)
	waitFor("token reload", func() bool { return strings.Contains(logs.String(), `reason="file changed"`) })
	if code := post(tokenB); code != 200 {
		t.Errorf("new token B = %d", code)
	}
	if code := post(tokenA); code != http.StatusUnauthorized {
		t.Errorf("revoked token A = %d", code)
	}

	// A broken file keeps the previous tokens.
	os.WriteFile(file, []byte("broken"), 0o600)
	os.Chtimes(file, later.Add(time.Minute), later.Add(time.Minute))
	waitFor("token error", func() bool { return strings.Contains(logs.String(), "keeping the previous tokens") })
	if code := post(tokenB); code != 200 {
		t.Errorf("token B after broken file = %d", code)
	}

	// SIGHUP reloads.
	os.WriteFile(file, []byte(auth.HashLine("pixel", tokenA)+"\n"), 0o600)
	syscall.Kill(os.Getpid(), syscall.SIGHUP)
	waitFor("SIGHUP", func() bool { return strings.Contains(logs.String(), "reason=SIGHUP") })
	if post(tokenA) != 200 || post(tokenB) != http.StatusUnauthorized {
		t.Error("SIGHUP did not reload the tokens")
	}

	for _, secret := range []string{tokenA, tokenB, "127.0.0.1"} {
		if strings.Contains(logs.String(), secret) {
			t.Errorf("log contains %q:\n%s", secret, logs.String())
		}
	}
	if !strings.Contains(logs.String(), "device=pixel") || !strings.Contains(logs.String(), "device=tablet") {
		t.Errorf("device labels not logged:\n%s", logs.String())
	}
}
