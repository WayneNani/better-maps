// Package server implements the TraFF HTTP transport (TraFF 0.8, Transport and Subscription
// Management, section 6.1): a single URL that accepts XML requests via POST and answers with XML
// responses. TraFF-level errors are reported with HTTP status 200 and a TraFF status code.
//
//	POST /, POST /traff   TraFF requests (Bearer token required)
//	GET  /, GET /traff    short usage text (Bearer token required)
//	GET  /status          state of the store and the sources as JSON (Bearer token required)
//	GET  /healthz         "ok" (no token, no information)
//
// Every path except /healthz needs "Authorization: Bearer <token>" with a device token from the
// token file (see internal/auth); without it the answer is 401 without details, before the request
// body is read. Each token has its own rate limit (429 with Retry-After). Without a token store
// (TRAFF_INSECURE_NO_AUTH, local development only) every request is accepted.
//
// Privacy: handlers never log tokens, client addresses, user agents, subscription IDs or filters
// (bounding boxes). Each request is logged with method, a path class ("traff", "status",
// "other"), status, duration and the label of the token.
package server

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"math"
	"net/http"
	"strconv"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/auth"
	"github.com/WayneNani/better-maps/services/traff-server/internal/ratelimit"
	"github.com/WayneNani/better-maps/services/traff-server/internal/source"
	"github.com/WayneNani/better-maps/services/traff-server/internal/store"
	"github.com/WayneNani/better-maps/services/traff-server/internal/subscription"
	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

// MaxRequestBytes limits the size of a request body.
const MaxRequestBytes = 1 << 20

// Limits are the request limits. Zero values mean unlimited.
type Limits struct {
	// Per token: RatePerMinute requests per minute on average, up to RateBurst at once.
	RatePerMinute float64
	RateBurst     int
	// Requests with a wrong token, all clients together.
	UnauthorizedPerSecond float64
	UnauthorizedBurst     int
}

// DefaultLimits returns the production limits. The app polls every 5 minutes and changes its
// subscription when the maps around the view, the position or the route change: a few requests
// per minute and device at most.
func DefaultLimits() Limits {
	return Limits{RatePerMinute: 60, RateBurst: 30, UnauthorizedPerSecond: 5, UnauthorizedBurst: 20}
}

// Server serves the TraFF API.
type Server struct {
	Store      *store.Store
	Subs       *subscription.Manager
	Coverage   func() []traff.BBox
	Status     func() []source.Status
	MaxFilters int
	Now        func() time.Time
	Log        *slog.Logger
	// Tokens holds the device tokens. nil serves without access control (TRAFF_INSECURE_NO_AUTH).
	Tokens *auth.Store
	Limits Limits

	perTok *ratelimit.Limiter
	unauth *ratelimit.Limiter
}

// Handler returns the HTTP handler with all routes. Call it once.
func (s *Server) Handler() http.Handler {
	s.perTok = ratelimit.New(s.Limits.RatePerMinute/60, s.Limits.RateBurst, s.Now)
	s.unauth = ratelimit.New(s.Limits.UnauthorizedPerSecond, s.Limits.UnauthorizedBurst, s.Now)
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", s.handleHealth)
	mux.Handle("/status", s.protect(http.HandlerFunc(s.handleStatus)))
	mux.Handle("/traff", s.protect(http.HandlerFunc(s.handleTraff)))
	// Unknown paths need a token as well, so that a client without one learns nothing.
	mux.Handle("/", s.protect(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		s.handleTraff(w, r)
	})))
	return s.logRequests(mux)
}

// Prune drops idle rate limit buckets; call it now and then.
func (s *Server) Prune() {
	if s.perTok != nil {
		s.perTok.Prune()
		s.unauth.Prune()
	}
}

func (s *Server) now() time.Time {
	if s.Now != nil {
		return s.Now()
	}
	return time.Now()
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
	return w.ResponseWriter.Write(b)
}

func (s *Server) logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		ri := &requestInfo{device: "-"}
		sw := &statusWriter{ResponseWriter: w}
		next.ServeHTTP(sw, r.WithContext(context.WithValue(r.Context(), ctxKey{}, ri)))
		if r.URL.Path == "/healthz" {
			return
		}
		attrs := []any{"method", r.Method, "path", logPath(r.URL.Path), "status", sw.status,
			"ms", time.Since(start).Milliseconds(), "device", ri.device}
		if ri.reason != "" {
			attrs = append(attrs, "reason", ri.reason)
		}
		s.Log.Info("request", attrs...)
	})
}

// logPath keeps whatever a client puts into unknown paths out of the log.
func logPath(p string) string {
	switch p {
	case "/", "/traff":
		return "traff"
	case "/status":
		return "status"
	}
	return "other"
}

func reqInfo(r *http.Request) *requestInfo {
	if i, ok := r.Context().Value(ctxKey{}).(*requestInfo); ok {
		return i
	}
	return &requestInfo{}
}

// protect checks the token and the rate limit of the token before next sees the request.
func (s *Server) protect(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.Tokens == nil {
			next.ServeHTTP(w, r)
			return
		}
		ri := reqInfo(r)
		label, ok := "", false
		if token, err := auth.BearerToken(r); err == nil {
			if allowed, _ := s.unauth.Allow("probe"); !allowed {
				ri.reason = "unauthorized_flood"
				writeRetry(w, time.Second)
				return
			}
			label, ok = s.Tokens.Lookup(token)
			if ok {
				// A valid token must not use up the budget of failed attempts.
				s.unauth.Refund("probe")
			}
		}
		if !ok {
			ri.reason = "auth"
			w.Header().Set("WWW-Authenticate", `Bearer realm="traff"`)
			writePlain(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		ri.device = label
		if allowed, wait := s.perTok.Allow(label); !allowed {
			ri.reason = "rate_limit"
			writeRetry(w, wait)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func writeRetry(w http.ResponseWriter, wait time.Duration) {
	secs := int(math.Ceil(wait.Seconds()))
	if secs < 1 {
		secs = 1
	}
	w.Header().Set("Retry-After", strconv.Itoa(secs))
	writePlain(w, http.StatusTooManyRequests, "too many requests")
}

func writePlain(w http.ResponseWriter, status int, text string) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(text + "\n"))
}

// handleHealth answers "ok" without a token and without any information, for monitoring.
func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write([]byte("ok\n"))
}

// handleStatus reports the state of the store and the sources (token required).
func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	active, cancelled := s.Store.Counts()
	body := struct {
		Status        string `json:"status"`
		Messages      int    `json:"messages"`
		Cancellations int    `json:"cancellations"`
		Subscriptions int    `json:"subscriptions"`
		// Duplicates is the number of messages suppressed in the last fetch of all sources
		// because they duplicate a message of a source with higher priority.
		Duplicates int             `json:"duplicates_suppressed"`
		Sources    []source.Status `json:"sources,omitempty"`
	}{Status: "ok", Messages: active, Cancellations: cancelled, Subscriptions: s.Subs.Count()}
	if s.Status != nil {
		body.Sources = s.Status()
		for _, st := range body.Sources {
			body.Duplicates += st.Duplicates
		}
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(body)
}

const infoText = `TraFF server (TraFF 0.8, HTTP transport).
Send TraFF requests as XML via HTTP POST to this URL, e.g.

  <request operation="SUBSCRIBE"><filter_list><filter bbox="52.3 13.0 52.7 13.8"/></filter_list></request>
`

func (s *Server) handleTraff(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodPost:
	case http.MethodGet, http.MethodHead:
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		_, _ = w.Write([]byte(infoText))
		return
	default:
		w.Header().Set("Allow", "GET, HEAD, POST")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, MaxRequestBytes)
	req, err := traff.ParseRequest(r.Body, s.MaxFilters)
	var resp *traff.Response
	if err != nil {
		var mbe *http.MaxBytesError
		if errors.As(err, &mbe) {
			http.Error(w, "request too large", http.StatusRequestEntityTooLarge)
			return
		}
		resp = &traff.Response{Status: traff.StatusInvalid}
	} else {
		resp = s.Dispatch(req)
	}
	out, err := resp.Marshal()
	if err != nil {
		s.Log.Error("cannot encode response", "err", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	op := "unparseable"
	if req != nil {
		op = req.Operation
	}
	s.Log.Debug("traff request", "operation", op, "status", resp.Status, "messages", feedLen(resp.Feed))
	w.Header().Set("Content-Type", "application/xml; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(out)
}

func feedLen(f *traff.Feed) int {
	if f == nil {
		return 0
	}
	return len(f.Messages)
}

func feed(msgs []traff.Message) *traff.Feed {
	if msgs == nil {
		msgs = []traff.Message{}
	}
	return &traff.Feed{Messages: msgs}
}

// coverageStatus checks a filter list against the coverage of the sources. It returns "" if the
// request is not covered at all.
func (s *Server) coverageStatus(fl traff.FilterList) string {
	if len(fl) == 0 || s.Coverage == nil {
		return traff.StatusOK
	}
	switch fl.Coverage(s.Coverage()) {
	case "none":
		return ""
	case "partial":
		return traff.StatusPartiallyCovered
	}
	return traff.StatusOK
}

// Dispatch executes a parsed request and returns the response.
func (s *Server) Dispatch(req *traff.Request) *traff.Response {
	now := s.now()
	switch req.Operation {
	case traff.OpGetCapabilities:
		return &traff.Response{Status: traff.StatusOK, Capabilities: &traff.Capabilities{MinVersion: traff.MinVersion, TargetVersion: traff.TargetVersion}}

	case traff.OpSubscribe:
		if req.SubscriptionID != "" || !req.HasFilterList {
			return &traff.Response{Status: traff.StatusInvalid}
		}
		status := s.coverageStatus(req.Filters)
		if status == "" {
			return &traff.Response{Status: traff.StatusNotCovered}
		}
		id, msgs, err := s.Subs.Subscribe(req.Filters, now)
		switch {
		case errors.Is(err, subscription.ErrRejected):
			return &traff.Response{Status: traff.StatusSubscriptionRejected}
		case err != nil:
			s.Log.Error("subscribe failed", "err", err)
			return &traff.Response{Status: traff.StatusInternalError}
		}
		return &traff.Response{Status: status, SubscriptionID: id, Timeout: int(s.Subs.TTL().Seconds()), Feed: feed(msgs)}

	case traff.OpSubscriptionChange:
		if req.SubscriptionID == "" || !req.HasFilterList {
			return &traff.Response{Status: traff.StatusInvalid}
		}
		status := s.coverageStatus(req.Filters)
		if status == "" {
			// The previous subscription stays active (TraFF 0.8, section 3.4).
			if err := s.Subs.Heartbeat(req.SubscriptionID, now); err != nil {
				return &traff.Response{Status: traff.StatusSubscriptionUnknown}
			}
			return &traff.Response{Status: traff.StatusNotCovered, SubscriptionID: req.SubscriptionID}
		}
		msgs, err := s.Subs.Change(req.SubscriptionID, req.Filters, now)
		if err != nil {
			return &traff.Response{Status: traff.StatusSubscriptionUnknown}
		}
		return &traff.Response{Status: status, SubscriptionID: req.SubscriptionID, Feed: feed(msgs)}

	case traff.OpPoll:
		if req.SubscriptionID == "" {
			// Subscriptionless ("one-shot") poll: one feed for the given filter.
			if !req.HasFilterList {
				return &traff.Response{Status: traff.StatusInvalid}
			}
			return &traff.Response{Status: traff.StatusOK, Feed: feed(subscription.Snapshot(s.Store, req.Filters))}
		}
		msgs, err := s.Subs.Poll(req.SubscriptionID, now)
		if err != nil {
			return &traff.Response{Status: traff.StatusSubscriptionUnknown}
		}
		return &traff.Response{Status: traff.StatusOK, SubscriptionID: req.SubscriptionID, Feed: feed(msgs)}

	case traff.OpHeartbeat:
		if req.SubscriptionID == "" {
			return &traff.Response{Status: traff.StatusInvalid}
		}
		if err := s.Subs.Heartbeat(req.SubscriptionID, now); err != nil {
			return &traff.Response{Status: traff.StatusSubscriptionUnknown}
		}
		return &traff.Response{Status: traff.StatusOK, SubscriptionID: req.SubscriptionID}

	case traff.OpUnsubscribe:
		if req.SubscriptionID == "" {
			return &traff.Response{Status: traff.StatusInvalid}
		}
		// Unsubscribing from an unknown subscription is a no-op that succeeds (section 3.3).
		s.Subs.Unsubscribe(req.SubscriptionID)
		return &traff.Response{Status: traff.StatusOK}

	case traff.OpFeed:
		return &traff.Response{Status: traff.StatusPushRejected}
	}
	return &traff.Response{Status: traff.StatusInvalid}
}
