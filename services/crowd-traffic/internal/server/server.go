// Package server implements the HTTP interface of the crowd-traffic service:
//
//	GET  /ohttp-keys   OHTTP key configurations (application/ohttp-keys)
//	POST /gateway      OHTTP gateway (message/ohttp-req → message/ohttp-res); the only target
//	                   resource behind it is POST /v1/reports
//	GET  /v1/traff     TraFF feed of congested segments
//	GET  /v1/segments  JSON export of all published segments
//	GET  /healthz      aggregate counters
//
// Reports can only be submitted through the gateway. The server never reads the client address,
// headers other than Content-Type, or any other connection metadata, and it does not log
// requests.
package server

import (
	"crypto/rand"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"mime"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/aggregate"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/bhttp"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/export"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/ohttp"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/protocol"
)

// Problem types used in (encapsulated) error responses.
const (
	ProblemOHTTPKey = "https://iana.org/assignments/http-problem-types#ohttp-key"
	ProblemDate     = "https://iana.org/assignments/http-problem-types#date"
	ProblemReplay   = "urn:better-maps:crowd-traffic:replay"
	ProblemInvalid  = "urn:better-maps:crowd-traffic:invalid-bundle"
)

// Config holds the server settings.
type Config struct {
	Rules           protocol.Rules
	Aggregation     aggregate.Config
	DateSkew        time.Duration // accepted difference between the Date header and the server clock
	MaxRequestBytes int64         // limit of the encapsulated request
	RateLimit       float64       // gateway requests per second (global, 0 = unlimited)
	RateBurst       int
	ReplayCapacity  int
}

// DefaultConfig returns the production settings.
func DefaultConfig() Config {
	return Config{
		Rules:           protocol.DefaultRules(),
		Aggregation:     aggregate.DefaultConfig(),
		DateSkew:        5 * time.Minute,
		MaxRequestBytes: 16 << 10,
		RateLimit:       200,
		RateBurst:       400,
		ReplayCapacity:  2_000_000,
	}
}

// Counters are aggregate gateway counters.
type Counters struct {
	Requests        atomic.Uint64
	BadRequests     atomic.Uint64 // wrong method, media type or size
	RateLimited     atomic.Uint64
	UnknownKey      atomic.Uint64
	DecryptErrors   atomic.Uint64
	Replays         atomic.Uint64
	DateRejected    atomic.Uint64
	BadInner        atomic.Uint64 // malformed binary HTTP or wrong target
	BundlesAccepted atomic.Uint64

	mu       sync.Mutex
	rejected map[protocol.Reason]uint64
}

func (c *Counters) reject(r protocol.Reason) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.rejected == nil {
		c.rejected = map[protocol.Reason]uint64{}
	}
	c.rejected[r]++
}

// Rejected returns a copy of the rejection counters per reason.
func (c *Counters) Rejected() map[protocol.Reason]uint64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	res := make(map[protocol.Reason]uint64, len(c.rejected))
	for k, v := range c.rejected {
		res[k] = v
	}
	return res
}

// Server is the HTTP server of the service.
type Server struct {
	cfg     Config
	keys    *ohttp.KeyRing
	agg     *aggregate.Aggregator
	replay  *ohttp.ReplayCache
	limiter *tokenBucket
	log     *slog.Logger
	started time.Time

	// Now and Rand can be replaced in tests.
	Now  func() time.Time
	Rand io.Reader

	Counters Counters
}

// New creates a server.
func New(cfg Config, keys *ohttp.KeyRing, agg *aggregate.Aggregator, log *slog.Logger) *Server {
	s := &Server{
		cfg:    cfg,
		keys:   keys,
		agg:    agg,
		replay: ohttp.NewReplayCache(2*cfg.DateSkew, cfg.ReplayCapacity),
		log:    log,
		Now:    time.Now,
		Rand:   rand.Reader,
	}
	if cfg.RateLimit > 0 {
		s.limiter = newTokenBucket(cfg.RateLimit, cfg.RateBurst)
	}
	s.started = s.Now()
	return s
}

// Tick runs periodic maintenance: window evaluation and expiry of replay entries.
func (s *Server) Tick() {
	now := s.Now()
	s.agg.Tick(now)
	s.replay.Purge(now)
}

// Handler returns the HTTP handler.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /ohttp-keys", s.handleKeys)
	mux.HandleFunc("POST /gateway", s.handleGateway)
	mux.HandleFunc("GET /v1/traff", s.handleTraff)
	mux.HandleFunc("GET /v1/segments", s.handleSegments)
	mux.HandleFunc("GET /healthz", s.handleHealth)
	return mux
}

func (s *Server) handleKeys(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", ohttp.ContentTypeKeys)
	w.Header().Set("Cache-Control", "public, max-age=3600")
	_, _ = w.Write(ohttp.MarshalKeyConfigs(s.keys.Configs(s.Now())))
}

func writeProblem(w http.ResponseWriter, status int, typ, title string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"type": typ, "title": title})
}

func (s *Server) handleGateway(w http.ResponseWriter, r *http.Request) {
	s.Counters.Requests.Add(1)
	if mt, _, _ := mime.ParseMediaType(r.Header.Get("Content-Type")); mt != ohttp.ContentTypeRequest {
		s.Counters.BadRequests.Add(1)
		http.Error(w, "expected "+ohttp.ContentTypeRequest, http.StatusUnsupportedMediaType)
		return
	}
	if s.limiter != nil && !s.limiter.allow(s.Now()) {
		s.Counters.RateLimited.Add(1)
		w.Header().Set("Retry-After", "1")
		http.Error(w, "rate limited", http.StatusServiceUnavailable)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, s.cfg.MaxRequestBytes+1))
	if err != nil || int64(len(body)) > s.cfg.MaxRequestBytes {
		s.Counters.BadRequests.Add(1)
		http.Error(w, "request too large or unreadable", http.StatusRequestEntityTooLarge)
		return
	}
	now := s.Now()
	pt, sctx, err := ohttp.DecapsulateRequest(body, s.keys.Lookup(now))
	switch {
	case errors.Is(err, ohttp.ErrUnknownKey), errors.Is(err, ohttp.ErrUnsupportedSuite):
		s.Counters.UnknownKey.Add(1)
		writeProblem(w, http.StatusBadRequest, ProblemOHTTPKey, "key identifier unknown")
		return
	case err != nil:
		s.Counters.DecryptErrors.Add(1)
		http.Error(w, "cannot decapsulate request", http.StatusBadRequest)
		return
	}
	inner := s.processInner(pt, sctx.Enc(), now)
	enc, err := sctx.EncapsulateResponse(inner.Marshal(), s.Rand)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", ohttp.ContentTypeResponse)
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(enc)
}

func innerProblem(status int, typ, title string, extra ...bhttp.Field) *bhttp.Response {
	b, _ := json.Marshal(map[string]string{"type": typ, "title": title})
	h := append(bhttp.Fields{{Name: "content-type", Value: "application/problem+json"}}, extra...)
	return &bhttp.Response{Status: status, Header: h, Body: b}
}

// processInner handles a decrypted binary HTTP request and returns the binary HTTP response.
// The plaintext buffer is overwritten with zeros before returning (best effort: the decoded
// bundle is dropped right after counting and never stored).
func (s *Server) processInner(pt, enc []byte, now time.Time) *bhttp.Response {
	defer clear(pt)
	switch s.replay.Check(enc, now) {
	case ohttp.Replayed:
		s.Counters.Replays.Add(1)
		return innerProblem(http.StatusBadRequest, ProblemReplay, "request was already processed")
	case ohttp.CacheFull:
		s.Counters.RateLimited.Add(1)
		return innerProblem(http.StatusServiceUnavailable, "about:blank", "overloaded")
	}
	req, err := bhttp.UnmarshalRequest(pt, protocol.MaxBodyBytes)
	if err != nil {
		s.Counters.BadInner.Add(1)
		return innerProblem(http.StatusBadRequest, "about:blank", "malformed binary HTTP request")
	}
	date, err := http.ParseTime(req.Header.Get("date"))
	if err != nil || date.Sub(now).Abs() > s.cfg.DateSkew {
		s.Counters.DateRejected.Add(1)
		return innerProblem(http.StatusBadRequest, ProblemDate, "date field in request outside of acceptable range",
			bhttp.Field{Name: "date", Value: now.UTC().Format(http.TimeFormat)})
	}
	if req.Path != protocol.ReportPath {
		s.Counters.BadInner.Add(1)
		return innerProblem(http.StatusNotFound, "about:blank", "unknown target")
	}
	if req.Method != http.MethodPost {
		s.Counters.BadInner.Add(1)
		return innerProblem(http.StatusMethodNotAllowed, "about:blank", "method not allowed")
	}
	if mt, _, _ := mime.ParseMediaType(req.Header.Get("content-type")); mt != protocol.ContentType {
		s.Counters.BadInner.Add(1)
		return innerProblem(http.StatusUnsupportedMediaType, "about:blank", "expected "+protocol.ContentType)
	}
	bundle, err := protocol.Decode(req.Body)
	if err == nil {
		err = s.cfg.Rules.Validate(bundle, now)
	}
	if err != nil {
		var ve *protocol.ValidationError
		reason := protocol.ReasonSyntax
		if errors.As(err, &ve) {
			reason = ve.Reason
		}
		s.Counters.reject(reason)
		return innerProblem(http.StatusBadRequest, ProblemInvalid, string(reason))
	}
	s.agg.Add(bundle.Reports)
	s.Counters.BundlesAccepted.Add(1)
	return &bhttp.Response{Status: http.StatusNoContent}
}

// parseBBox parses "minLat minLon maxLat maxLon" (spaces or commas).
func parseBBox(s string) (*protocol.BBox, error) {
	if s == "" {
		return nil, nil
	}
	f := strings.FieldsFunc(s, func(r rune) bool { return r == ' ' || r == ',' })
	if len(f) != 4 {
		return nil, errors.New("bbox needs four numbers")
	}
	var v [4]float64
	for i, x := range f {
		var err error
		if v[i], err = strconv.ParseFloat(x, 64); err != nil {
			return nil, err
		}
	}
	if v[0] > v[2] || v[1] > v[3] {
		return nil, errors.New("bbox minimum above maximum")
	}
	return &protocol.BBox{MinLat: v[0], MinLon: v[1], MaxLat: v[2], MaxLon: v[3]}, nil
}

func (s *Server) handleTraff(w http.ResponseWriter, r *http.Request) {
	bbox, err := parseBBox(r.URL.Query().Get("bbox"))
	if err != nil {
		http.Error(w, "invalid bbox: "+err.Error(), http.StatusBadRequest)
		return
	}
	b, err := export.TraffFeed(s.agg.Publications(s.Now()), bbox)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/xml; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	_, _ = w.Write(b)
}

func (s *Server) handleSegments(w http.ResponseWriter, r *http.Request) {
	bbox, err := parseBBox(r.URL.Query().Get("bbox"))
	if err != nil {
		http.Error(w, "invalid bbox: "+err.Error(), http.StatusBadRequest)
		return
	}
	now := s.Now()
	b, err := export.JSON(s.agg.Publications(now), bbox, s.cfg.Aggregation, now)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-cache")
	_, _ = w.Write(b)
}

// Health is the /healthz document. It contains only aggregate numbers.
type Health struct {
	Status        string                     `json:"status"`
	UptimeSeconds int64                      `json:"uptime_seconds"`
	Gateway       GatewayHealth              `json:"gateway"`
	Rejected      map[protocol.Reason]uint64 `json:"bundles_rejected"`
	Aggregator    aggregate.Stats            `json:"aggregator"`
	Memory        aggregate.Footprint        `json:"memory"`
	ReplayEntries int                        `json:"replay_cache_entries"`
	KeyID         uint8                      `json:"current_key_id"`
	WindowSeconds int                        `json:"window_seconds"`
	K             int                        `json:"k"`
}

// GatewayHealth holds the gateway counters.
type GatewayHealth struct {
	Requests        uint64 `json:"requests"`
	BundlesAccepted uint64 `json:"bundles_accepted"`
	BadRequests     uint64 `json:"bad_requests"`
	RateLimited     uint64 `json:"rate_limited"`
	UnknownKey      uint64 `json:"unknown_key"`
	DecryptErrors   uint64 `json:"decrypt_errors"`
	Replays         uint64 `json:"replays"`
	DateRejected    uint64 `json:"date_rejected"`
	BadInner        uint64 `json:"bad_inner_requests"`
}

// Health returns the current health document.
func (s *Server) Health() Health {
	now := s.Now()
	c := &s.Counters
	return Health{
		Status:        "ok",
		UptimeSeconds: int64(now.Sub(s.started) / time.Second),
		Gateway: GatewayHealth{
			Requests: c.Requests.Load(), BundlesAccepted: c.BundlesAccepted.Load(), BadRequests: c.BadRequests.Load(),
			RateLimited: c.RateLimited.Load(), UnknownKey: c.UnknownKey.Load(), DecryptErrors: c.DecryptErrors.Load(),
			Replays: c.Replays.Load(), DateRejected: c.DateRejected.Load(), BadInner: c.BadInner.Load(),
		},
		Rejected:      c.Rejected(),
		Aggregator:    s.agg.Stats(),
		Memory:        s.agg.Footprint(),
		ReplayEntries: s.replay.Len(),
		KeyID:         s.keys.Configs(now)[0].KeyID,
		WindowSeconds: int(s.cfg.Aggregation.Window / time.Second),
		K:             s.cfg.Aggregation.K,
	}
}

func (s *Server) handleHealth(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-cache")
	_ = json.NewEncoder(w).Encode(s.Health())
}

// LogSummary writes the aggregate counters to the log.
func (s *Server) LogSummary() {
	h := s.Health()
	s.log.Info("summary",
		"requests", h.Gateway.Requests, "bundles_accepted", h.Gateway.BundlesAccepted,
		"reports_accepted", h.Aggregator.ReportsAccepted, "windows_finalized", h.Aggregator.WindowsFinalized,
		"segments_published", h.Aggregator.SegmentsAboveK, "segments_below_k", h.Aggregator.SegmentsBelowK,
		"publications", h.Memory.Publications, "congested", h.Memory.Congested)
}

// tokenBucket is a global rate limiter. It deliberately has no per-client state: the gateway
// does not know clients. Per-client limits are the job of the relay.
type tokenBucket struct {
	mu     sync.Mutex
	rate   float64
	burst  float64
	tokens float64
	last   time.Time
}

func newTokenBucket(rate float64, burst int) *tokenBucket {
	return &tokenBucket{rate: rate, burst: float64(burst), tokens: float64(burst)}
}

func (b *tokenBucket) allow(now time.Time) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	if !b.last.IsZero() {
		b.tokens = min(b.burst, b.tokens+now.Sub(b.last).Seconds()*b.rate)
	}
	b.last = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}
