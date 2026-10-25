package relay

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/ohttp"
)

type gateway struct {
	mu      sync.Mutex
	headers []http.Header
	keys    []byte
	keyHits int
}

func (g *gateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if r.URL.Path == "/ohttp-keys" {
		g.keyHits++
		w.Header().Set("Content-Type", ohttp.ContentTypeKeys)
		w.Write(g.keys)
		return
	}
	g.headers = append(g.headers, r.Header.Clone())
	body, _ := io.ReadAll(r.Body)
	w.Header().Set("Content-Type", ohttp.ContentTypeResponse)
	w.Write(append([]byte("echo:"), body...))
}

func setup(t *testing.T, cfg Config) (*Relay, *httptest.Server, *gateway) {
	t.Helper()
	key, _ := ohttp.NewPrivateKey(1, ohttp.DefaultKEM, bytes.Repeat([]byte{1}, 32), ohttp.DefaultSuites)
	g := &gateway{keys: ohttp.MarshalKeyConfigs([]ohttp.KeyConfig{key.Config})}
	gs := httptest.NewServer(g)
	t.Cleanup(gs.Close)
	cfg.GatewayURL, cfg.KeysURL = gs.URL+"/gateway", gs.URL+"/ohttp-keys"
	r := New(cfg)
	rs := httptest.NewServer(r.Handler())
	t.Cleanup(rs.Close)
	return r, rs, g
}

func TestForwardsWithoutClientData(t *testing.T) {
	_, rs, g := setup(t, DefaultConfig())
	req, _ := http.NewRequest(http.MethodPost, rs.URL+"/relay", strings.NewReader("opaque"))
	req.Header.Set("Content-Type", ohttp.ContentTypeRequest)
	req.Header.Set("User-Agent", "secret-agent")
	req.Header.Set("X-Forwarded-For", "203.0.113.7")
	req.Header.Set("Cookie", "id=1")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK || string(b) != "echo:opaque" || resp.Header.Get("Content-Type") != ohttp.ContentTypeResponse {
		t.Fatalf("%d %q", resp.StatusCode, b)
	}
	h := g.headers[0]
	for name := range h {
		switch name {
		case "Content-Type", "Content-Length", "Accept-Encoding":
		default:
			t.Errorf("forwarded header %s: %v", name, h[name])
		}
	}
}

func TestRateLimitPerClient(t *testing.T) {
	cfg := DefaultConfig()
	cfg.Burst = 2
	r, _, _ := setup(t, cfg)
	now := time.Unix(1000, 0)
	a := clientPrefix("192.0.2.1:5555")
	b := clientPrefix("[2001:db8::1]:443")
	b2 := clientPrefix("[2001:db8::2]:443") // same /64
	if !r.allow(a, now) || !r.allow(a, now) || r.allow(a, now) {
		t.Fatal("burst for a")
	}
	if !r.allow(b, now) || !r.allow(b2, now) || r.allow(b, now) {
		t.Fatal("IPv6 /64 is one client")
	}
	if r.Clients() != 2 {
		t.Fatalf("%d clients", r.Clients())
	}
	// After a full refill the state is forgotten.
	later := now.Add(time.Duration(float64(cfg.Burst)/cfg.Rate*float64(time.Second)) + time.Second)
	if !r.allow(clientPrefix("198.51.100.1:1"), later) || r.Clients() != 1 {
		t.Fatalf("state not purged: %d", r.Clients())
	}
	if p := clientPrefix("garbage"); p.IsValid() {
		t.Fatal("garbage address")
	}
	if p := clientPrefix("192.0.2.9"); p != netip.MustParsePrefix("192.0.2.9/32") {
		t.Fatal(p)
	}
}

func TestRelayHTTPErrors(t *testing.T) {
	cfg := DefaultConfig()
	cfg.Burst, cfg.MaxBody = 3, 10
	r, rs, _ := setup(t, cfg)
	post := func(ct, body string) int {
		resp, err := http.Post(rs.URL+"/relay", ct, strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	if c := post("text/plain", "x"); c != http.StatusUnsupportedMediaType {
		t.Errorf("media type: %d", c)
	}
	if c := post(ohttp.ContentTypeRequest, strings.Repeat("x", 11)); c != http.StatusRequestEntityTooLarge {
		t.Errorf("size: %d", c)
	}
	post(ohttp.ContentTypeRequest, "x")
	post(ohttp.ContentTypeRequest, "x")
	if c := post(ohttp.ContentTypeRequest, "x"); c != http.StatusTooManyRequests {
		t.Errorf("rate limit: %d", c)
	}
	// Gateway unreachable.
	r.cfg.GatewayURL = "http://127.0.0.1:1/gateway"
	r.buckets = map[netip.Prefix]*bucket{}
	if c := post(ohttp.ContentTypeRequest, "x"); c != http.StatusBadGateway {
		t.Errorf("unreachable gateway: %d", c)
	}
	r.cfg.GatewayURL = "::"
	if c := post(ohttp.ContentTypeRequest, "x"); c != http.StatusBadGateway {
		t.Errorf("invalid gateway URL: %d", c)
	}
}

func TestKeysAreCachedAndChecked(t *testing.T) {
	r, rs, g := setup(t, DefaultConfig())
	now := time.Unix(5000, 0)
	r.Now = func() time.Time { return now }
	get := func() (int, []byte) {
		resp, err := http.Get(rs.URL + "/ohttp-keys")
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		b, _ := io.ReadAll(resp.Body)
		return resp.StatusCode, b
	}
	for range 3 {
		if c, b := get(); c != http.StatusOK || !bytes.Equal(b, g.keys) {
			t.Fatalf("keys: %d", c)
		}
	}
	if g.keyHits != 1 {
		t.Fatalf("gateway asked %d times", g.keyHits)
	}
	now = now.Add(r.cfg.KeysTTL)
	g.keys = []byte{0, 1, 2}
	if c, _ := get(); c != http.StatusBadGateway {
		t.Fatalf("invalid keys forwarded: %d", c)
	}
	r.cfg.KeysURL = "http://127.0.0.1:1/k"
	if c, _ := get(); c != http.StatusBadGateway {
		t.Fatalf("unreachable: %d", c)
	}
	r.cfg.KeysURL = "::"
	if c, _ := get(); c != http.StatusBadGateway {
		t.Fatalf("invalid URL: %d", c)
	}
}
