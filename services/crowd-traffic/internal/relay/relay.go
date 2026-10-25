// Package relay is a minimal Oblivious HTTP relay for development and tests.
//
// In production the relay must be run by a party independent of the gateway operator (e.g. a
// commercial OHTTP relay service or a partner organisation); otherwise OHTTP protects nothing.
// This implementation shows what the relay is supposed to do:
//   - forward the opaque encapsulated request to the configured gateway, with no client
//     information (no X-Forwarded-For, no User-Agent, no cookies),
//   - forward the gateway's key configuration (so that all clients get the same keys),
//   - limit the request rate per client address (the gateway cannot do this, it does not see
//     addresses). The rate limiter state is the only per-client state; it is kept in memory and
//     deleted when a client's bucket is full again. Nothing is logged.
package relay

import (
	"bytes"
	"io"
	"net"
	"net/http"
	"net/netip"
	"sync"
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/ohttp"
)

// Config holds the relay settings.
type Config struct {
	GatewayURL string        // e.g. https://gateway.example/gateway
	KeysURL    string        // e.g. https://gateway.example/ohttp-keys
	Rate       float64       // requests per second per client address
	Burst      int           // bucket size per client address
	MaxBody    int64         // maximum size of an encapsulated request
	KeysTTL    time.Duration // cache lifetime of the key configuration
}

// DefaultConfig returns limits for the reporting client: about 4 bundles per hour are expected,
// 30 per hour are allowed with a burst of 10.
func DefaultConfig() Config {
	return Config{Rate: 30.0 / 3600, Burst: 10, MaxBody: 16 << 10, KeysTTL: 5 * time.Minute}
}

type bucket struct {
	tokens float64
	last   time.Time
}

// Relay is an OHTTP relay resource.
type Relay struct {
	cfg  Config
	HTTP *http.Client
	Now  func() time.Time

	mu      sync.Mutex
	buckets map[netip.Prefix]*bucket
	keys    []byte
	keysAt  time.Time
}

// New creates a relay.
func New(cfg Config) *Relay {
	return &Relay{cfg: cfg, HTTP: &http.Client{Timeout: 10 * time.Second}, Now: time.Now, buckets: map[netip.Prefix]*bucket{}}
}

// clientPrefix groups addresses: IPv4 per address, IPv6 per /64 (one subscriber).
func clientPrefix(remoteAddr string) netip.Prefix {
	host, _, err := net.SplitHostPort(remoteAddr)
	if err != nil {
		host = remoteAddr
	}
	a, err := netip.ParseAddr(host)
	if err != nil {
		return netip.Prefix{}
	}
	a = a.Unmap()
	bits := 32
	if a.Is6() {
		bits = 64
	}
	p, _ := a.Prefix(bits)
	return p
}

func (r *Relay) allow(p netip.Prefix, now time.Time) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	full := float64(r.cfg.Burst)
	// Forget clients whose bucket is full again: their state carries no information any more.
	for k, b := range r.buckets {
		if b.tokens+now.Sub(b.last).Seconds()*r.cfg.Rate >= full {
			delete(r.buckets, k)
		}
	}
	b := r.buckets[p]
	if b == nil {
		b = &bucket{tokens: full, last: now}
		r.buckets[p] = b
	}
	b.tokens = min(full, b.tokens+now.Sub(b.last).Seconds()*r.cfg.Rate)
	b.last = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

// Clients returns the number of client addresses with rate limiter state.
func (r *Relay) Clients() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.buckets)
}

// Handler returns the HTTP handler: POST /relay and GET /ohttp-keys.
func (r *Relay) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /relay", r.handleRelay)
	mux.HandleFunc("GET /ohttp-keys", r.handleKeys)
	return mux
}

func (r *Relay) handleRelay(w http.ResponseWriter, req *http.Request) {
	if req.Header.Get("Content-Type") != ohttp.ContentTypeRequest {
		http.Error(w, "expected "+ohttp.ContentTypeRequest, http.StatusUnsupportedMediaType)
		return
	}
	if !r.allow(clientPrefix(req.RemoteAddr), r.Now()) {
		w.Header().Set("Retry-After", "600")
		http.Error(w, "rate limited", http.StatusTooManyRequests)
		return
	}
	body, err := io.ReadAll(io.LimitReader(req.Body, r.cfg.MaxBody+1))
	if err != nil || int64(len(body)) > r.cfg.MaxBody {
		http.Error(w, "request too large", http.StatusRequestEntityTooLarge)
		return
	}
	// A fresh request: only the media type is forwarded.
	out, err := http.NewRequestWithContext(req.Context(), http.MethodPost, r.cfg.GatewayURL, bytes.NewReader(body))
	if err != nil {
		http.Error(w, "bad gateway", http.StatusBadGateway)
		return
	}
	out.Header.Set("Content-Type", ohttp.ContentTypeRequest)
	out.Header.Set("User-Agent", "") // suppress Go's default User-Agent as well
	resp, err := r.HTTP.Do(out)
	if err != nil {
		http.Error(w, "bad gateway", http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	res, err := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	if err != nil {
		http.Error(w, "bad gateway", http.StatusBadGateway)
		return
	}
	if ct := resp.Header.Get("Content-Type"); ct != "" {
		w.Header().Set("Content-Type", ct)
	}
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(resp.StatusCode)
	_, _ = w.Write(res)
}

func (r *Relay) handleKeys(w http.ResponseWriter, req *http.Request) {
	r.mu.Lock()
	keys, at := r.keys, r.keysAt
	r.mu.Unlock()
	now := r.Now()
	if keys == nil || now.Sub(at) >= r.cfg.KeysTTL {
		out, err := http.NewRequestWithContext(req.Context(), http.MethodGet, r.cfg.KeysURL, nil)
		if err != nil {
			http.Error(w, "bad gateway", http.StatusBadGateway)
			return
		}
		out.Header.Set("User-Agent", "")
		resp, err := r.HTTP.Do(out)
		if err != nil {
			http.Error(w, "bad gateway", http.StatusBadGateway)
			return
		}
		defer resp.Body.Close()
		b, err := io.ReadAll(io.LimitReader(resp.Body, 4096))
		if err != nil || resp.StatusCode != http.StatusOK {
			http.Error(w, "bad gateway", http.StatusBadGateway)
			return
		}
		if _, err := ohttp.ParseKeyConfigs(b); err != nil {
			http.Error(w, "gateway sent invalid keys", http.StatusBadGateway)
			return
		}
		keys = b
		r.mu.Lock()
		r.keys, r.keysAt = b, now
		r.mu.Unlock()
	}
	w.Header().Set("Content-Type", ohttp.ContentTypeKeys)
	w.Header().Set("Cache-Control", "public, max-age=300")
	_, _ = w.Write(keys)
}
