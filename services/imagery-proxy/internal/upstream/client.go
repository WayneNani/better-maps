// Package upstream fetches rendered tiles from MapProxy (WMTS REST:
// <base>/wmts/<layer>/<grid>/<z>/<x>/<y>.jpeg) and merges concurrent requests for the same tile.
//
// Errors never contain the tile coordinates (they could end up in a log).
package upstream

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

// MaxTileBytes bounds the size of a tile read from MapProxy.
const MaxTileBytes = 4 << 20

// ErrNotFound means MapProxy has no tile there (outside the grid).
var ErrNotFound = errors.New("upstream: no tile")

// Client is a MapProxy client.
type Client struct {
	base  *url.URL
	layer string
	grid  string
	http  *http.Client
}

// NewClient returns a client for the MapProxy at baseURL.
func NewClient(baseURL, layer, grid string, timeout time.Duration) (*Client, error) {
	u, err := url.Parse(baseURL)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return nil, fmt.Errorf("upstream: invalid URL %q", baseURL)
	}
	if layer == "" || grid == "" || strings.ContainsAny(layer+grid, "/?#") {
		return nil, errors.New("upstream: invalid layer or grid")
	}
	return &Client{base: u, layer: layer, grid: grid, http: &http.Client{
		Timeout: timeout,
		// MapProxy never redirects; don't follow anything elsewhere.
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}}, nil
}

// TileURL returns the MapProxy URL of a tile.
func (c *Client) TileURL(z, x, y int) string {
	u := *c.base
	u.Path = strings.TrimSuffix(u.Path, "/") + "/wmts/" + c.layer + "/" + c.grid + "/" +
		strconv.Itoa(z) + "/" + strconv.Itoa(x) + "/" + strconv.Itoa(y) + ".jpeg"
	u.RawQuery = ""
	return u.String()
}

// Tile fetches one tile.
func (c *Client) Tile(ctx context.Context, z, x, y int) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.TileURL(z, x, y), nil)
	if err != nil {
		return nil, errors.New("upstream: bad request")
	}
	resp, err := c.http.Do(req)
	if err != nil {
		// *url.Error contains the URL (and with it the coordinates): keep only the cause.
		var ue *url.Error
		if errors.As(err, &ue) {
			err = ue.Err
		}
		return nil, fmt.Errorf("upstream: %w", err)
	}
	defer resp.Body.Close()
	switch {
	case resp.StatusCode == http.StatusNotFound:
		io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
		return nil, ErrNotFound
	case resp.StatusCode != http.StatusOK:
		io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
		return nil, fmt.Errorf("upstream: status %d", resp.StatusCode)
	}
	if ct := resp.Header.Get("Content-Type"); !strings.HasPrefix(ct, "image/") {
		return nil, fmt.Errorf("upstream: unexpected content type %q", ct)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, MaxTileBytes+1))
	if err != nil {
		return nil, fmt.Errorf("upstream: read: %w", err)
	}
	if len(data) > MaxTileBytes {
		return nil, errors.New("upstream: tile too large")
	}
	if len(data) == 0 {
		return nil, errors.New("upstream: empty tile")
	}
	return data, nil
}

// Group merges concurrent calls with the same key (a minimal singleflight).
type Group[K comparable, V any] struct {
	mu    sync.Mutex
	calls map[K]*call[V]
}

type call[V any] struct {
	done chan struct{}
	val  V
	err  error
	dups int
}

// Do runs fn once for all concurrent callers with the same key. shared reports whether the
// result was shared with other callers.
func (g *Group[K, V]) Do(key K, fn func() (V, error)) (v V, err error, shared bool) {
	g.mu.Lock()
	if g.calls == nil {
		g.calls = map[K]*call[V]{}
	}
	if c, ok := g.calls[key]; ok {
		c.dups++
		g.mu.Unlock()
		<-c.done
		return c.val, c.err, true
	}
	c := &call[V]{done: make(chan struct{})}
	g.calls[key] = c
	g.mu.Unlock()

	func() {
		defer func() {
			if r := recover(); r != nil {
				c.err = fmt.Errorf("panic: %v", r)
			}
		}()
		c.val, c.err = fn()
	}()

	g.mu.Lock()
	delete(g.calls, key)
	shared = c.dups > 0
	g.mu.Unlock()
	close(c.done)
	return c.val, c.err, shared
}
