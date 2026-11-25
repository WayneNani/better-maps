// Package server implements the HTTP interface of the imagery gateway:
//
//	GET /v1/tiles/{z}/{x}/{y}.jpg     aerial tile, WebMercator XYZ (Bearer token required)
//	GET /v1/attribution[?bbox=...&z=] attributions of the sources (Bearer token required)
//	GET /healthz                      "ok" (no token, no information)
//
// /v1/attribution without bbox lists all sources; with bbox=minLon,minLat,maxLon,maxLat (WGS84)
// and optionally z (zoom, default 22) only those visible there:
//
//	{"sources": [{"id": "nw", "name": "...", "attribution": "...", "license": "...",
//	              "license_url": "...", "min_zoom": 12, "bbox": [5.8, 50.3, 9.5, 52.6]}, ...]}
//
// Errors are JSON objects {"error": "<code>"}: 400 bad_request, 401 unauthorized, 404 not_found,
// 405, 429 rate_limited (with Retry-After), 502 backend_error, 503 overloaded.
//
// Privacy: the server logs method, a path class ("tile", "attribution", "other"), status,
// latency, cache hit/miss and the label of the token; never the token, the tile coordinates, the
// bbox or the client address.
package server

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"math"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/WayneNani/better-maps/services/imagery-proxy/internal/auth"
	"github.com/WayneNani/better-maps/services/imagery-proxy/internal/ratelimit"
	"github.com/WayneNani/better-maps/services/imagery-proxy/internal/sources"
	"github.com/WayneNani/better-maps/services/imagery-proxy/internal/tilecache"
	"github.com/WayneNani/better-maps/services/imagery-proxy/internal/upstream"
)

// TileSource renders tiles (the MapProxy client; a fake in tests).
type TileSource interface {
	Tile(ctx context.Context, z, x, y int) ([]byte, error)
}

// Cache stores rendered tiles (tilecache.Cache; may be nil).
type Cache interface {
	Get(k tilecache.Key) ([]byte, bool)
	Put(k tilecache.Key, data []byte) error
}

// Config holds the limits of the server.
type Config struct {
	MinZoom, MaxZoom int
	// Per token: RatePerMinute tiles per minute on average, up to RateBurst at once.
	RatePerMinute float64
	RateBurst     int
	// Requests without a valid token, all clients together.
	UnauthorizedPerSecond float64
	UnauthorizedBurst     int
	// Concurrent tile renderings in MapProxy (each may ask a state server); further cache misses
	// wait up to QueueTimeout, then 503.
	MaxConcurrent int
	QueueTimeout  time.Duration
	// Timeout of one rendering.
	UpstreamTimeout time.Duration
	// Cache-Control max-age of tiles for the clients.
	ClientMaxAge time.Duration
}

// DefaultConfig returns the production limits. A phone screen shows 20-40 tiles; zooming and
// panning for a minute needs a few hundred.
func DefaultConfig() Config {
	return Config{
		MinZoom:               0,
		MaxZoom:               19,
		RatePerMinute:         600,
		RateBurst:             300,
		UnauthorizedPerSecond: 5,
		UnauthorizedBurst:     20,
		MaxConcurrent:         4,
		QueueTimeout:          10 * time.Second,
		UpstreamTimeout:       30 * time.Second,
		ClientMaxAge:          7 * 24 * time.Hour,
	}
}

// Server is the HTTP handler.
type Server struct {
	cfg     Config
	tokens  *auth.Store
	tiles   TileSource
	cache   Cache
	sources *sources.Set
	log     *slog.Logger
	perTok  *ratelimit.Limiter
	unauth  *ratelimit.Limiter
	slots   chan struct{}
	flight  upstream.Group[tilecache.Key, []byte]
	now     func() time.Time
}

// New returns a server. cache may be nil (no caching), now may be nil (time.Now).
func New(cfg Config, tokens *auth.Store, tiles TileSource, cache Cache, srcs *sources.Set, logger *slog.Logger,
	now func() time.Time) *Server {
	if now == nil {
		now = time.Now
	}
	if cfg.MaxConcurrent <= 0 {
		cfg.MaxConcurrent = 1
	}
	return &Server{
		cfg:     cfg,
		tokens:  tokens,
		tiles:   tiles,
		cache:   cache,
		sources: srcs,
		log:     logger,
		perTok:  ratelimit.New(cfg.RatePerMinute/60, cfg.RateBurst, now),
		unauth:  ratelimit.New(cfg.UnauthorizedPerSecond, cfg.UnauthorizedBurst, now),
		slots:   make(chan struct{}, cfg.MaxConcurrent),
		now:     now,
	}
}

// Prune drops idle rate limit buckets; call it now and then.
func (s *Server) Prune() {
	s.perTok.Prune()
	s.unauth.Prune()
}

// Handler returns the HTTP handler.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/tiles/", s.handleTile)
	mux.HandleFunc("/v1/attribution", s.handleAttribution)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			w.Header().Set("Allow", "GET, HEAD")
			writeError(w, http.StatusMethodNotAllowed, "method_not_allowed")
			return
		}
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		io.WriteString(w, "ok\n")
	})
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) { writeError(w, http.StatusNotFound, "not_found") })
	return s.logRequests(mux)
}

type ctxKey struct{}

// requestInfo is filled by the handlers for the log line.
type requestInfo struct {
	device string
	reason string
	cache  string
}

type statusWriter struct {
	http.ResponseWriter
	status int
	bytes  int
}

func (w *statusWriter) WriteHeader(code int) {
	if w.status == 0 {
		w.status = code
	}
	w.ResponseWriter.WriteHeader(code)
}

func (w *statusWriter) Write(b []byte) (int, error) {
	if w.status == 0 {
		w.status = http.StatusOK
	}
	n, err := w.ResponseWriter.Write(b)
	w.bytes += n
	return n, err
}

func (s *Server) logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		info := &requestInfo{device: "-"}
		sw := &statusWriter{ResponseWriter: w}
		next.ServeHTTP(sw, r.WithContext(context.WithValue(r.Context(), ctxKey{}, info)))
		if r.URL.Path == "/healthz" {
			return
		}
		attrs := []any{"method", r.Method, "path", logPath(r.URL.Path), "status", sw.status,
			"ms", time.Since(start).Milliseconds(), "device", info.device, "bytes", sw.bytes}
		if info.cache != "" {
			attrs = append(attrs, "cache", info.cache)
		}
		if info.reason != "" {
			attrs = append(attrs, "reason", info.reason)
		}
		// Tiles are many: successful ones only at debug level.
		if logPath(r.URL.Path) == "tile" && sw.status == http.StatusOK {
			s.log.Debug("request", attrs...)
			return
		}
		s.log.Info("request", attrs...)
	})
}

// logPath keeps tile coordinates and whatever a client puts into unknown paths out of the log.
func logPath(p string) string {
	switch {
	case strings.HasPrefix(p, "/v1/tiles/"):
		return "tile"
	case p == "/v1/attribution":
		return "attribution"
	}
	return "other"
}

func info(r *http.Request) *requestInfo {
	if i, ok := r.Context().Value(ctxKey{}).(*requestInfo); ok {
		return i
	}
	return &requestInfo{}
}

// authorize checks method, token and rate limit; false if a response was written.
func (s *Server) authorize(w http.ResponseWriter, r *http.Request, ri *requestInfo) bool {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed")
		return false
	}
	// Authentication first: without a valid token nothing else is looked at.
	label, ok := "", false
	if token, err := auth.BearerToken(r); err == nil {
		if allowed, _ := s.unauth.Allow("probe"); !allowed {
			ri.reason = "unauthorized_flood"
			writeRetry(w, time.Second)
			return false
		}
		label, ok = s.tokens.Lookup(token)
		if ok {
			// A valid token must not use up the budget of failed attempts.
			s.unauth.Refund("probe")
		}
	}
	if !ok {
		ri.reason = "auth"
		w.Header().Set("WWW-Authenticate", `Bearer realm="imagery"`)
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return false
	}
	ri.device = label
	if allowed, wait := s.perTok.Allow(label); !allowed {
		ri.reason = "rate_limit"
		writeRetry(w, wait)
		return false
	}
	return true
}

// ParseTilePath parses "/v1/tiles/{z}/{x}/{y}.jpg".
func ParseTilePath(p string, minZoom, maxZoom int) (tilecache.Key, bool) {
	rest, ok := strings.CutPrefix(p, "/v1/tiles/")
	if !ok {
		return tilecache.Key{}, false
	}
	rest, ok = strings.CutSuffix(rest, ".jpg")
	if !ok {
		return tilecache.Key{}, false
	}
	parts := strings.Split(rest, "/")
	if len(parts) != 3 {
		return tilecache.Key{}, false
	}
	var v [3]int
	for i, part := range parts {
		if part == "" || len(part) > 7 || strings.TrimLeft(part, "0123456789") != "" {
			return tilecache.Key{}, false
		}
		n, err := strconv.Atoi(part)
		if err != nil {
			return tilecache.Key{}, false
		}
		v[i] = n
	}
	z, x, y := v[0], v[1], v[2]
	if z < minZoom || z > maxZoom || z > 30 {
		return tilecache.Key{}, false
	}
	if n := 1 << z; x >= n || y >= n {
		return tilecache.Key{}, false
	}
	return tilecache.Key{Z: z, X: x, Y: y}, true
}

func (s *Server) handleTile(w http.ResponseWriter, r *http.Request) {
	ri := info(r)
	if !s.authorize(w, r, ri) {
		return
	}
	key, ok := ParseTilePath(r.URL.Path, s.cfg.MinZoom, s.cfg.MaxZoom)
	if !ok {
		ri.reason = "bad_tile"
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	if s.cache != nil {
		if data, ok := s.cache.Get(key); ok {
			ri.cache = "hit"
			s.writeTile(w, r, data)
			return
		}
	}
	ri.cache = "miss"

	data, err, shared := s.flight.Do(key, func() ([]byte, error) {
		// Limit the load on MapProxy and, through it, on the state servers.
		timer := time.NewTimer(s.cfg.QueueTimeout)
		select {
		case s.slots <- struct{}{}:
			timer.Stop()
		case <-timer.C:
			return nil, errOverloaded
		}
		defer func() { <-s.slots }()
		// Not the client's context: other clients may wait for the same tile, and a finished
		// rendering is worth caching anyway.
		ctx, cancel := context.WithTimeout(context.Background(), s.cfg.UpstreamTimeout)
		defer cancel()
		data, err := s.tiles.Tile(ctx, key.Z, key.X, key.Y)
		if err == nil && s.cache != nil {
			if perr := s.cache.Put(key, data); perr != nil {
				s.log.Warn("tile cache", "error", perr.Error())
			}
		}
		return data, err
	})
	if shared {
		ri.cache = "shared"
	}
	switch {
	case err == nil:
		s.writeTile(w, r, data)
	case errors.Is(err, errOverloaded):
		ri.reason = "overloaded"
		w.Header().Set("Retry-After", "2")
		writeError(w, http.StatusServiceUnavailable, "overloaded")
	case errors.Is(err, upstream.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found")
	default:
		// The error never contains coordinates (see upstream.Client).
		ri.reason = "backend"
		s.log.Warn("tile backend", "error", err.Error())
		writeError(w, http.StatusBadGateway, "backend_error")
	}
}

var errOverloaded = errors.New("overloaded")

func (s *Server) writeTile(w http.ResponseWriter, r *http.Request, data []byte) {
	h := w.Header()
	h.Set("Content-Type", "image/jpeg")
	h.Set("Content-Length", strconv.Itoa(len(data)))
	h.Set("Cache-Control", "private, max-age="+strconv.Itoa(int(s.cfg.ClientMaxAge.Seconds())))
	h.Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(http.StatusOK)
	if r.Method != http.MethodHead {
		w.Write(data)
	}
}

type attributionResponse struct {
	Sources []sources.Attribution `json:"sources"`
}

func (s *Server) handleAttribution(w http.ResponseWriter, r *http.Request) {
	ri := info(r)
	if !s.authorize(w, r, ri) {
		return
	}
	q := r.URL.Query()
	year := s.now().Year()
	var list []sources.Attribution
	if bb := q.Get("bbox"); bb != "" {
		box, err := sources.ParseBBox(bb)
		if err != nil {
			ri.reason = "bad_bbox"
			writeError(w, http.StatusBadRequest, "bad_request")
			return
		}
		zoom := 22
		if zs := q.Get("z"); zs != "" {
			z, err := strconv.Atoi(zs)
			if err != nil || z < 0 || z > 30 {
				ri.reason = "bad_zoom"
				writeError(w, http.StatusBadRequest, "bad_request")
				return
			}
			zoom = z
		}
		list = s.sources.For(box, zoom, year)
	} else {
		list = s.sources.All(year)
	}
	if list == nil {
		list = []sources.Attribution{}
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "private, max-age=3600")
	w.WriteHeader(http.StatusOK)
	if r.Method != http.MethodHead {
		json.NewEncoder(w).Encode(attributionResponse{Sources: list})
	}
}

func writeRetry(w http.ResponseWriter, wait time.Duration) {
	secs := int(math.Ceil(wait.Seconds()))
	if secs < 1 {
		secs = 1
	}
	w.Header().Set("Retry-After", strconv.Itoa(secs))
	writeError(w, http.StatusTooManyRequests, "rate_limited")
}

func writeError(w http.ResponseWriter, status int, code string) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]string{"error": code})
}
