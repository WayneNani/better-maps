package main

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"
)

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

func TestRunServesAndShutsDown(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	ready := make(chan string, 1)
	logs := &syncBuffer{}
	done := make(chan error, 1)
	env := func(k string) string {
		return map[string]string{"CROWD_TICK": "10ms", "CROWD_SUMMARY_INTERVAL": "20ms"}[k]
	}
	go func() { done <- run(ctx, []string{"-listen", "127.0.0.1:0"}, env, logs, ready) }()
	var addr string
	select {
	case addr = <-ready:
	case err := <-done:
		t.Fatal(err)
	case <-time.After(5 * time.Second):
		t.Fatal("server did not start")
	}
	for _, path := range []string{"/healthz", "/ohttp-keys", "/v1/traff", "/v1/segments"} {
		resp, err := http.Get("http://" + addr + path)
		if err != nil {
			t.Fatal(err)
		}
		b, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode != http.StatusOK || len(b) == 0 {
			t.Fatalf("%s: %d", path, resp.StatusCode)
		}
	}
	time.Sleep(50 * time.Millisecond) // let tick and summary run
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	l := logs.String()
	for _, want := range []string{"no OHTTP master key", "crowd-traffic listening", "summary", "shutting down"} {
		if !strings.Contains(l, want) {
			t.Errorf("log lacks %q:\n%s", want, l)
		}
	}
	if strings.Contains(l, "127.0.0.1:") && strings.Count(l, "127.0.0.1:") > 1 {
		t.Errorf("client address in log:\n%s", l)
	}
}

func TestRunErrors(t *testing.T) {
	ctx := context.Background()
	if err := run(ctx, []string{"-k", "0"}, func(string) string { return "" }, io.Discard, nil); err == nil {
		t.Error("invalid config accepted")
	}
	if err := run(ctx, []string{"-listen", "256.0.0.1:1"}, func(string) string { return "" }, io.Discard, nil); err == nil {
		t.Error("invalid listen address accepted")
	}
}
