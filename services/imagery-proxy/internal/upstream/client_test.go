package upstream

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestTileURL(t *testing.T) {
	c, err := NewClient("http://mapproxy:8080/", "aerial", "webmercator", time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if got := c.TileURL(14, 8508, 5489); got != "http://mapproxy:8080/wmts/aerial/webmercator/14/8508/5489.jpeg" {
		t.Fatal(got)
	}
	c, _ = NewClient("http://host/prefix?x=1", "a", "g", time.Second)
	if got := c.TileURL(0, 0, 0); got != "http://host/prefix/wmts/a/g/0/0/0.jpeg" {
		t.Fatal(got)
	}
	for _, bad := range []string{"", "ftp://x", "http://", "::"} {
		if _, err := NewClient(bad, "a", "g", time.Second); err == nil {
			t.Errorf("accepted %q", bad)
		}
	}
	if _, err := NewClient("http://x", "a/b", "g", time.Second); err == nil {
		t.Error("layer with slash accepted")
	}
}

func TestTile(t *testing.T) {
	var status atomic.Int32
	status.Store(200)
	var contentType atomic.Value
	contentType.Store("image/jpeg")
	body := "\xff\xd8jpeg"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/wmts/aerial/webmercator/3/2/1.jpeg" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", contentType.Load().(string))
		w.WriteHeader(int(status.Load()))
		w.Write([]byte(body))
	}))
	defer srv.Close()
	c, _ := NewClient(srv.URL, "aerial", "webmercator", time.Second)
	data, err := c.Tile(context.Background(), 3, 2, 1)
	if err != nil || string(data) != body {
		t.Fatal(string(data), err)
	}
	if _, err := c.Tile(context.Background(), 3, 2, 2); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	status.Store(500)
	_, err = c.Tile(context.Background(), 3, 2, 1)
	if err == nil || !strings.Contains(err.Error(), "500") {
		t.Fatal(err)
	}
	status.Store(200)
	contentType.Store("text/html")
	if _, err := c.Tile(context.Background(), 3, 2, 1); err == nil {
		t.Fatal("html accepted")
	}
}

func TestTileErrorsHideCoordinates(t *testing.T) {
	c, _ := NewClient("http://127.0.0.1:1", "aerial", "webmercator", 200*time.Millisecond)
	_, err := c.Tile(context.Background(), 14, 8508, 5489)
	if err == nil {
		t.Fatal("no error")
	}
	if strings.Contains(err.Error(), "8508") || strings.Contains(err.Error(), "5489") {
		t.Fatalf("error contains coordinates: %v", err)
	}
}

func TestTileTooLargeAndEmpty(t *testing.T) {
	var size atomic.Int64
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/jpeg")
		w.Write(make([]byte, size.Load()))
	}))
	defer srv.Close()
	c, _ := NewClient(srv.URL, "aerial", "webmercator", 5*time.Second)
	size.Store(MaxTileBytes + 1)
	if _, err := c.Tile(context.Background(), 1, 0, 0); err == nil {
		t.Fatal("oversized tile accepted")
	}
	size.Store(0)
	if _, err := c.Tile(context.Background(), 1, 0, 0); err == nil {
		t.Fatal("empty tile accepted")
	}
}

func TestNoRedirects(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "http://example.org/", http.StatusFound)
	}))
	defer srv.Close()
	c, _ := NewClient(srv.URL, "aerial", "webmercator", time.Second)
	if _, err := c.Tile(context.Background(), 1, 0, 0); err == nil {
		t.Fatal("redirect followed")
	}
}

func TestGroup(t *testing.T) {
	var g Group[int, string]
	var calls atomic.Int32
	release := make(chan struct{})
	started := make(chan struct{})
	var wg sync.WaitGroup
	results := make([]string, 4)
	shared := make([]bool, 4)
	wg.Add(1)
	go func() {
		defer wg.Done()
		results[0], _, shared[0] = g.Do(1, func() (string, error) {
			calls.Add(1)
			close(started)
			<-release
			return "v", nil
		})
	}()
	<-started
	for i := 1; i < 4; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			results[i], _, shared[i] = g.Do(1, func() (string, error) { calls.Add(1); return "other", nil })
		}()
	}
	time.Sleep(50 * time.Millisecond)
	close(release)
	wg.Wait()
	if calls.Load() != 1 {
		t.Fatalf("%d calls", calls.Load())
	}
	for i, r := range results {
		if r != "v" || !shared[i] {
			t.Fatalf("%d: %q %v", i, r, shared[i])
		}
	}
	// Panics become errors and don't block the key.
	if _, err, _ := g.Do(2, func() (string, error) { panic("boom") }); err == nil {
		t.Fatal("panic not reported")
	}
	if v, err, sh := g.Do(2, func() (string, error) { return "ok", nil }); v != "ok" || err != nil || sh {
		t.Fatal(v, err, sh)
	}
}
