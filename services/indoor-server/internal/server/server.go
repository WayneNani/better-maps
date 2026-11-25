// Package server implements the HTTP interface of the indoor gateway:
//
//	GET /v1/venues?bbox=minLon,minLat,maxLon,maxLat   venues intersecting the box (Bearer token required)
//	GET /v1/venues/{id}                                venue document (Bearer token required)
//	GET /healthz                                       "ok" (no token, no information)
//
// The bbox must be WGS84, not inverted and at most MaxBBoxDeg x MaxBBoxDeg degrees. The list
// holds at most MaxVenues venues (the largest first). Venue documents are served precompressed
// when the client accepts gzip, else decompressed; both carry the same strong ETag (hash of the
// uncompressed JSON) and answer If-None-Match with 304.
//
// Errors are JSON objects {"error": "<code>"}: 400 bad_request, 401 unauthorized, 404 not_found,
// 405 method_not_allowed, 429 rate_limited (with Retry-After).
//
// Privacy: the server logs method, a path class ("venues", "venue", "other"), status, latency,
// bytes and the label of the token; never the token, the bbox, venue ids or the client address.
package server

import (
	"bytes"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"math"
	"net/http"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/WayneNani/better-maps/services/indoor-server/internal/auth"
	"github.com/WayneNani/better-maps/services/indoor-server/internal/dataset"
	"github.com/WayneNani/better-maps/services/indoor-server/internal/ratelimit"
	"github.com/WayneNani/better-maps/services/indoor-server/internal/venues"
)

// API limits (part of the contract with the app).
const (
	MaxBBoxDeg = 1.0
	MaxVenues  = 100
)

// Cache lifetimes for the clients.
const (
	listMaxAge  = 3600
	venueMaxAge = 86400
)

// Config holds the limits of the server.
type Config struct {
	// Per token: RatePerMinute requests per minute on average, up to RateBurst at once.
	RatePerMinute float64
	RateBurst     int
	// Requests without a valid token, all clients together.
	UnauthorizedPerSecond float64
	UnauthorizedBurst     int
}

// DefaultConfig returns the production limits. Opening the map in a new area costs one list
// request and a few venue documents; the app caches both.
func DefaultConfig() Config {
	return Config{RatePerMinute: 120, RateBurst: 60, UnauthorizedPerSecond: 5, UnauthorizedBurst: 20}
}

// Server is the HTTP handler.
type Server struct {
	cfg    Config
	tokens *auth.Store
	data   atomic.Pointer[dataset.Dataset]
	log    *slog.Logger
	perTok *ratelimit.Limiter
	unauth *ratelimit.Limiter
}

// New returns a server serving ds (nil = no venues yet). now may be nil (time.Now).
func New(cfg Config, tokens *auth.Store, ds *dataset.Dataset, logger *slog.Logger, now func() time.Time) *Server {
	if now == nil {
		now = time.Now
	}
	s := &Server{
		cfg:    cfg,
		tokens: tokens,
		log:    logger,
		perTok: ratelimit.New(cfg.RatePerMinute/60, cfg.RateBurst, now),
		unauth: ratelimit.New(cfg.UnauthorizedPerSecond, cfg.UnauthorizedBurst, now),
	}
	s.SetData(ds)
	return s
}

// SetData swaps the served dataset (after a new import).
func (s *Server) SetData(ds *dataset.Dataset) {
	if ds == nil {
		ds = dataset.Empty()
	}
	s.data.Store(ds)
}

// Data returns the served dataset.
func (s *Server) Data() *dataset.Dataset { return s.data.Load() }

// Prune drops idle rate limit buckets; call it now and then.
func (s *Server) Prune() {
	s.perTok.Prune()
	s.unauth.Prune()
}

// Handler returns the HTTP handler.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/venues", s.handleList)
	mux.HandleFunc("/v1/venues/", s.handleVenue)
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
	gzip   bool
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
		if info.gzip {
			attrs = append(attrs, "gzip", true)
		}
		if info.reason != "" {
			attrs = append(attrs, "reason", info.reason)
		}
		s.log.Info("request", attrs...)
	})
}

// logPath keeps venue ids and whatever a client puts into unknown paths out of the log.
func logPath(p string) string {
	switch {
	case p == "/v1/venues":
		return "venues"
	case strings.HasPrefix(p, "/v1/venues/"):
		return "venue"
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
		w.Header().Set("WWW-Authenticate", `Bearer realm="indoor"`)
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

// ParseBBox parses "minLon,minLat,maxLon,maxLat" (WGS84, not inverted, at most MaxBBoxDeg in
// each direction).
func ParseBBox(s string) ([4]float64, bool) {
	var b [4]float64
	parts := strings.Split(s, ",")
	if len(parts) != 4 {
		return b, false
	}
	for i, p := range parts {
		v, err := strconv.ParseFloat(strings.TrimSpace(p), 64)
		if err != nil || math.IsNaN(v) || math.IsInf(v, 0) {
			return b, false
		}
		b[i] = v
	}
	if b[0] < -180 || b[2] > 180 || b[1] < -90 || b[3] > 90 || b[0] > b[2] || b[1] > b[3] {
		return b, false
	}
	if b[2]-b[0] > MaxBBoxDeg || b[3]-b[1] > MaxBBoxDeg {
		return b, false
	}
	return b, true
}

// listEntry is a venue in the list response: the index entry plus its document's ETag.
type listEntry struct {
	venues.IndexEntry
	ETag string `json:"etag"`
}

type listResponse struct {
	DataTimestamp string      `json:"data_timestamp"`
	Venues        []listEntry `json:"venues"`
}

func (s *Server) handleList(w http.ResponseWriter, r *http.Request) {
	ri := info(r)
	if !s.authorize(w, r, ri) {
		return
	}
	q := r.URL.Query()
	bbox, ok := ParseBBox(q.Get("bbox"))
	if !ok || len(q["bbox"]) != 1 {
		ri.reason = "bad_bbox"
		writeError(w, http.StatusBadRequest, "bad_request")
		return
	}
	ds := s.Data()
	resp := listResponse{DataTimestamp: ds.DataTimestamp, Venues: []listEntry{}}
	for _, v := range ds.Query(bbox, MaxVenues) {
		resp.Venues = append(resp.Venues, listEntry{IndexEntry: v.IndexEntry, ETag: v.ETag})
	}
	body, err := json.Marshal(resp)
	if err != nil { // cannot happen with validated data
		writeError(w, http.StatusInternalServerError, "internal")
		return
	}
	sum := sha256.Sum256(body)
	etag := `"` + hex.EncodeToString(sum[:10]) + `"`
	h := w.Header()
	h.Set("Cache-Control", "private, max-age="+strconv.Itoa(listMaxAge))
	h.Set("ETag", etag)
	h.Set("Vary", "Accept-Encoding")
	if notModified(r, etag) {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	if acceptsGzip(r) {
		var buf bytes.Buffer
		gz := gzip.NewWriter(&buf)
		gz.Write(body)
		gz.Close()
		body = buf.Bytes()
		h.Set("Content-Encoding", "gzip")
		ri.gzip = true
	}
	writeBody(w, r, body)
}

func (s *Server) handleVenue(w http.ResponseWriter, r *http.Request) {
	ri := info(r)
	if !s.authorize(w, r, ri) {
		return
	}
	id := strings.TrimPrefix(r.URL.Path, "/v1/venues/")
	v, ok := (*dataset.Venue)(nil), false
	if venues.ValidID(id) {
		v, ok = s.Data().Get(id)
	}
	if !ok {
		ri.reason = "unknown_venue"
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	h := w.Header()
	h.Set("Cache-Control", "private, max-age="+strconv.Itoa(venueMaxAge))
	h.Set("ETag", v.ETag)
	h.Set("Vary", "Accept-Encoding")
	if notModified(r, v.ETag) {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	body := v.Gzip
	if acceptsGzip(r) {
		h.Set("Content-Encoding", "gzip")
		ri.gzip = true
	} else {
		plain, err := v.Plain()
		if err != nil { // verified when loading
			writeError(w, http.StatusInternalServerError, "internal")
			return
		}
		body = plain
	}
	writeBody(w, r, body)
}

func writeBody(w http.ResponseWriter, r *http.Request, body []byte) {
	h := w.Header()
	h.Set("Content-Type", "application/json")
	h.Set("Content-Length", strconv.Itoa(len(body)))
	h.Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(http.StatusOK)
	if r.Method != http.MethodHead {
		w.Write(body)
	}
}

// notModified reports whether If-None-Match matches etag (weak comparison, as RFC 9110 asks).
func notModified(r *http.Request, etag string) bool {
	for _, field := range r.Header.Values("If-None-Match") {
		for _, t := range strings.Split(field, ",") {
			t = strings.TrimSpace(t)
			if t == "*" || strings.TrimPrefix(t, "W/") == etag {
				return true
			}
		}
	}
	return false
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
