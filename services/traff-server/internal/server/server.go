// Package server implements the TraFF HTTP transport (TraFF 0.8, Transport and Subscription
// Management, section 6.1): a single URL that accepts XML requests via POST and answers with XML
// responses. TraFF-level errors are reported with HTTP status 200 and a TraFF status code.
//
// Privacy: handlers never log client addresses, user agents, subscription IDs or filters.
package server

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/source"
	"github.com/WayneNani/better-maps/services/traff-server/internal/store"
	"github.com/WayneNani/better-maps/services/traff-server/internal/subscription"
	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

// MaxRequestBytes limits the size of a request body.
const MaxRequestBytes = 1 << 20

// Server serves the TraFF API.
type Server struct {
	Store      *store.Store
	Subs       *subscription.Manager
	Coverage   func() []traff.BBox
	Status     func() []source.Status
	MaxFilters int
	Now        func() time.Time
	Log        *slog.Logger
}

// Handler returns the HTTP handler with all routes.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", s.handleHealth)
	mux.HandleFunc("/traff", s.handleTraff)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		s.handleTraff(w, r)
	})
	return mux
}

func (s *Server) now() time.Time {
	if s.Now != nil {
		return s.Now()
	}
	return time.Now()
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	active, cancelled := s.Store.Counts()
	body := struct {
		Status        string          `json:"status"`
		Messages      int             `json:"messages"`
		Cancellations int             `json:"cancellations"`
		Subscriptions int             `json:"subscriptions"`
		Sources       []source.Status `json:"sources,omitempty"`
	}{Status: "ok", Messages: active, Cancellations: cancelled, Subscriptions: s.Subs.Count()}
	if s.Status != nil {
		body.Sources = s.Status()
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
