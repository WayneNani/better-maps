package autobahn

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// DefaultBaseURL is the production endpoint of the Autobahn API.
const DefaultBaseURL = "https://verkehr.autobahn.de/o/autobahn"

// maxBody limits the size of a single API response.
const maxBody = 32 << 20

// Client is a polite HTTP client for the Autobahn API. Requests are serialized and spaced by at
// least MinInterval; responses are revalidated with If-None-Match / If-Modified-Since.
type Client struct {
	BaseURL     string
	HTTP        *http.Client
	UserAgent   string
	MinInterval time.Duration

	mu    sync.Mutex // serializes requests
	last  time.Time
	cache map[string]cached
}

type cached struct {
	etag, lastModified string
	body               []byte
}

// NewClient creates a client with sensible defaults.
func NewClient(baseURL, userAgent string, minInterval time.Duration) *Client {
	return &Client{
		BaseURL:     strings.TrimRight(baseURL, "/"),
		HTTP:        &http.Client{Timeout: 30 * time.Second},
		UserAgent:   userAgent,
		MinInterval: minInterval,
		cache:       map[string]cached{},
	}
}

// HTTPError is returned for unexpected HTTP status codes.
type HTTPError struct {
	Path   string
	Status int
}

func (e *HTTPError) Error() string { return fmt.Sprintf("autobahn: GET %s: HTTP %d", e.Path, e.Status) }

func (c *Client) wait(ctx context.Context) error {
	if c.MinInterval <= 0 || c.last.IsZero() {
		return nil
	}
	d := time.Until(c.last.Add(c.MinInterval))
	if d <= 0 {
		return nil
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

// get fetches path (relative to BaseURL). A 304 answer returns the cached body.
func (c *Client) get(ctx context.Context, path string) ([]byte, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := c.wait(ctx); err != nil {
		return nil, err
	}
	defer func() { c.last = time.Now() }()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.BaseURL+path, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	if c.UserAgent != "" {
		req.Header.Set("User-Agent", c.UserAgent)
	}
	prev, hasPrev := c.cache[path]
	if hasPrev {
		if prev.etag != "" {
			req.Header.Set("If-None-Match", prev.etag)
		}
		if prev.lastModified != "" {
			req.Header.Set("If-Modified-Since", prev.lastModified)
		}
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, fmt.Errorf("autobahn: GET %s: %w", path, err)
	}
	defer resp.Body.Close()
	switch {
	case resp.StatusCode == http.StatusNotModified && hasPrev:
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
		return prev.body, nil
	case resp.StatusCode != http.StatusOK:
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
		return nil, &HTTPError{Path: path, Status: resp.StatusCode}
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxBody))
	if err != nil {
		return nil, fmt.Errorf("autobahn: GET %s: %w", path, err)
	}
	etag, lm := resp.Header.Get("ETag"), resp.Header.Get("Last-Modified")
	if etag != "" || lm != "" {
		c.cache[path] = cached{etag: etag, lastModified: lm, body: body}
	} else {
		delete(c.cache, path)
	}
	return body, nil
}

// Roads returns the list of road IDs, trimmed and without duplicates.
func (c *Client) Roads(ctx context.Context) ([]string, error) {
	body, err := c.get(ctx, "/")
	if err != nil {
		return nil, err
	}
	var rl RoadList
	if err := json.Unmarshal(body, &rl); err != nil {
		return nil, fmt.Errorf("autobahn: road list: %w", err)
	}
	seen := map[string]bool{}
	var roads []string
	for _, r := range rl.Roads {
		r = strings.TrimSpace(r)
		if r == "" || seen[r] {
			continue
		}
		seen[r] = true
		roads = append(roads, r)
	}
	return roads, nil
}

// Items returns the items of one service for one road.
func (c *Client) Items(ctx context.Context, road, service string) ([]Item, error) {
	path := "/" + url.PathEscape(strings.TrimSpace(road)) + "/services/" + url.PathEscape(service)
	body, err := c.get(ctx, path)
	if err != nil {
		return nil, err
	}
	items, err := DecodeItems(body, service)
	if err != nil {
		return nil, fmt.Errorf("autobahn: %s: %w", path, err)
	}
	return items, nil
}
