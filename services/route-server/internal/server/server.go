// Package server implements the HTTP interface of the route gateway:
//
//	POST /v1/route   car route through 2..MaxPoints points (Bearer token required)
//	GET  /healthz    "ok" (no token, no information)
//
// Request:  {"points": [[lat, lon], [lat, lon], ...]}
// Response: {"geometry": "<polyline, precision 6>", "precision": 6, "distance_m": 1234.5,
//
//	"duration_s": 99.9, "data_timestamp": "2026-11-08T21:21:00Z"}
//
// Errors are JSON objects {"error": "<code>"} with the HTTP status: 400 bad_request, 401
// unauthorized, 404 no_route, 405, 413 too_large, 415 unsupported_media_type, 422 no_segment
// (a point is not near a road), 429 rate_limited (with Retry-After), 502 backend_error, 503
// overloaded.
//
// Privacy: the server logs status, latency and the label of the token, never the token, the
// coordinates or the client address.
package server

import (
	"compress/gzip"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"math"
	"mime"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/WayneNani/better-maps/services/route-server/internal/auth"
	"github.com/WayneNani/better-maps/services/route-server/internal/osrm"
	"github.com/WayneNani/better-maps/services/route-server/internal/ratelimit"
)

// Router computes routes (the OSRM client; a fake in tests).
type Router interface {
	Route(ctx context.Context, points []osrm.Point) (osrm.Route, error)
}

// Config holds the limits of the server.
type Config struct {
	MaxPoints       int
	MaxRequestBytes int64
	// Per token: RatePerMinute requests per minute on average, up to RateBurst at once.
	RatePerMinute float64
	RateBurst     int
	// Requests without a valid token, all clients together (stops floods before they cost
	// hashing; brute-forcing a 256-bit token is hopeless anyway).
	UnauthorizedPerSecond float64
	UnauthorizedBurst     int
	// Concurrent requests to OSRM; further requests wait up to QueueTimeout, then 503.
	MaxConcurrent int
	QueueTimeout  time.Duration
}

// DefaultConfig returns the production limits. 30 routes per minute with a burst of 60 per
// device: the charging planner may compute ~40 detour routes for one plan.
func DefaultConfig() Config {
	return Config{
		MaxPoints:             25,
		MaxRequestBytes:       4 << 10,
		RatePerMinute:         30,
		RateBurst:             60,
		UnauthorizedPerSecond: 5,
		UnauthorizedBurst:     20,
		MaxConcurrent:         8,
		QueueTimeout:          2 * time.Second,
	}
}

// Server is the HTTP handler.
type Server struct {
	cfg     Config
	tokens  *auth.Store
	router  Router
	log     *slog.Logger
	perTok  *ratelimit.Limiter
	unauth  *ratelimit.Limiter
	slots   chan struct{}
	nowFunc func() time.Time
}

// New returns a server. now may be nil (time.Now); it drives the rate limits.
func New(cfg Config, tokens *auth.Store, router Router, logger *slog.Logger, now func() time.Time) *Server {
	if now == nil {
		now = time.Now
	}
	if cfg.MaxConcurrent <= 0 {
		cfg.MaxConcurrent = 1
	}
	return &Server{
		cfg:     cfg,
		tokens:  tokens,
		router:  router,
		log:     logger,
		perTok:  ratelimit.New(cfg.RatePerMinute/60, cfg.RateBurst, now),
		unauth:  ratelimit.New(cfg.UnauthorizedPerSecond, cfg.UnauthorizedBurst, now),
		slots:   make(chan struct{}, cfg.MaxConcurrent),
		nowFunc: now,
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
	mux.HandleFunc("/v1/route", s.handleRoute)
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
		if info.reason != "" {
			attrs = append(attrs, "reason", info.reason)
		}
		s.log.Info("request", attrs...)
	})
}

// logPath keeps the log free of whatever a client puts into unknown paths.
func logPath(p string) string {
	if p == "/v1/route" {
		return p
	}
	return "other"
}

func info(r *http.Request) *requestInfo {
	if i, ok := r.Context().Value(ctxKey{}).(*requestInfo); ok {
		return i
	}
	return &requestInfo{}
}

type routeRequest struct {
	Points [][]float64 `json:"points"`
}

type routeResponse struct {
	Geometry      string  `json:"geometry"`
	Precision     int     `json:"precision"`
	DistanceM     float64 `json:"distance_m"`
	DurationS     float64 `json:"duration_s"`
	DataTimestamp string  `json:"data_timestamp,omitempty"`
}

func (s *Server) handleRoute(w http.ResponseWriter, r *http.Request) {
	ri := info(r)
	w.Header().Set("Cache-Control", "no-store")
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed")
		return
	}

	// Authentication first: without a valid token nothing else is looked at.
	label, ok := "", false
	if token, err := auth.BearerToken(r); err == nil {
		if allowed, _ := s.unauth.Allow("probe"); !allowed {
			ri.reason = "unauthorized_flood"
			writeRetry(w, time.Second)
			return
		}
		label, ok = s.tokens.Lookup(token)
		if ok {
			// A valid token must not use up the budget of failed attempts.
			s.unauth.Refund("probe")
		}
	}
	if !ok {
		ri.reason = "auth"
		w.Header().Set("WWW-Authenticate", `Bearer realm="route"`)
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	ri.device = label

	if allowed, wait := s.perTok.Allow(label); !allowed {
		ri.reason = "rate_limit"
		writeRetry(w, wait)
		return
	}

	points, status, code := s.parseRequest(w, r)
	if status != 0 {
		ri.reason = code
		writeError(w, status, code)
		return
	}

	// Limit the load on OSRM.
	timer := time.NewTimer(s.cfg.QueueTimeout)
	select {
	case s.slots <- struct{}{}:
		timer.Stop()
	case <-timer.C:
		ri.reason = "overloaded"
		w.Header().Set("Retry-After", "1")
		writeError(w, http.StatusServiceUnavailable, "overloaded")
		return
	case <-r.Context().Done():
		timer.Stop()
		return
	}
	route, err := s.router.Route(r.Context(), points)
	<-s.slots
	switch {
	case err == nil:
	case errors.Is(err, osrm.ErrNoRoute):
		writeError(w, http.StatusNotFound, "no_route")
		return
	case errors.Is(err, osrm.ErrNoSegment):
		writeError(w, http.StatusUnprocessableEntity, "no_segment")
		return
	default:
		// The error never contains coordinates (see osrm.Client).
		ri.reason = "backend"
		s.log.Warn("routing backend", "error", err.Error())
		writeError(w, http.StatusBadGateway, "backend_error")
		return
	}

	writeJSON(w, r, http.StatusOK, routeResponse{
		Geometry: route.Geometry, Precision: 6, DistanceM: round1(route.DistanceM), DurationS: round1(route.DurationS),
		DataTimestamp: route.DataVersion,
	})
}

func round1(v float64) float64 { return math.Round(v*10) / 10 }

// parseRequest validates the body; status != 0 on errors.
func (s *Server) parseRequest(w http.ResponseWriter, r *http.Request) ([]osrm.Point, int, string) {
	if mt, _, err := mime.ParseMediaType(r.Header.Get("Content-Type")); err != nil || mt != "application/json" {
		return nil, http.StatusUnsupportedMediaType, "unsupported_media_type"
	}
	if r.ContentLength > s.cfg.MaxRequestBytes {
		return nil, http.StatusRequestEntityTooLarge, "too_large"
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, s.cfg.MaxRequestBytes))
	if err != nil {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			return nil, http.StatusRequestEntityTooLarge, "too_large"
		}
		return nil, http.StatusBadRequest, "bad_request"
	}
	var req routeRequest
	dec := json.NewDecoder(strings.NewReader(string(body)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&req); err != nil || dec.More() {
		return nil, http.StatusBadRequest, "bad_request"
	}
	if len(req.Points) < 2 || len(req.Points) > s.cfg.MaxPoints {
		return nil, http.StatusBadRequest, "point_count"
	}
	points := make([]osrm.Point, 0, len(req.Points))
	for _, p := range req.Points {
		if len(p) != 2 || !valid(p[0], 90) || !valid(p[1], 180) {
			return nil, http.StatusBadRequest, "bad_point"
		}
		points = append(points, osrm.Point{Lat: p[0], Lon: p[1]})
	}
	return points, 0, ""
}

func valid(v, limit float64) bool {
	return !math.IsNaN(v) && !math.IsInf(v, 0) && v >= -limit && v <= limit
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
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]string{"error": code})
}

func writeJSON(w http.ResponseWriter, r *http.Request, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Add("Vary", "Accept-Encoding")
	if acceptsGzip(r) {
		w.Header().Set("Content-Encoding", "gzip")
		w.WriteHeader(status)
		gz := gzip.NewWriter(w)
		json.NewEncoder(gz).Encode(v)
		gz.Close()
		return
	}
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func acceptsGzip(r *http.Request) bool {
	for _, part := range strings.Split(r.Header.Get("Accept-Encoding"), ",") {
		name, params, _ := strings.Cut(strings.TrimSpace(part), ";")
		if !strings.EqualFold(strings.TrimSpace(name), "gzip") {
			continue
		}
		if q, ok := strings.CutPrefix(strings.ReplaceAll(params, " ", ""), "q="); ok {
			if v, err := strconv.ParseFloat(q, 64); err == nil && v == 0 {
				return false
			}
		}
		return true
	}
	return false
}
