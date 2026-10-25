package main

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestRun(t *testing.T) {
	gw := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/ohttp-keys")
		w.Write([]byte{0, 3, 1, 2, 3}) // invalid on purpose: the relay must refuse it
	}))
	defer gw.Close()
	ctx, cancel := context.WithCancel(context.Background())
	ready := make(chan string, 1)
	done := make(chan error, 1)
	go func() {
		done <- run(ctx, []string{"-listen", "127.0.0.1:0", "-gateway", gw.URL}, func(string) string { return "" }, io.Discard, ready)
	}()
	var addr string
	select {
	case addr = <-ready:
	case err := <-done:
		t.Fatal(err)
	case <-time.After(5 * time.Second):
		t.Fatal("timeout")
	}
	resp, err := http.Get("http://" + addr + "/ohttp-keys")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusBadGateway {
		t.Fatalf("status %d", resp.StatusCode)
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	for _, args := range [][]string{{"-burst", "0"}, {"-listen", "256.1.1.1:1"}, {"-bogus"}} {
		if err := run(context.Background(), args, func(string) string { return "" }, io.Discard, nil); err == nil {
			t.Errorf("%v accepted", args)
		}
	}
}
