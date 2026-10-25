package client

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/bhttp"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/ohttp"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/protocol"
)

// DefaultAuthority is the authority of the target resource inside the encapsulated request. The
// gateway serves the target itself, so the value is informational.
const DefaultAuthority = "crowd.bettermaps.invalid"

// Sender submits bundles through an OHTTP relay (or, in tests, directly to the gateway).
type Sender struct {
	// RelayURL receives the encapsulated requests (the gateway URL when no relay is used).
	RelayURL string
	// KeysURL serves the gateway key configurations. Clients should fetch it through the relay
	// as well, so that the gateway cannot hand out per-client keys.
	KeysURL   string
	Authority string
	HTTP      *http.Client
	Rand      io.Reader
	Now       func() time.Time

	mu     sync.Mutex
	config *ohttp.KeyConfig
}

// RejectedError is returned when the gateway processed the request but refused it.
type RejectedError struct {
	Status  int
	Problem string // problem type
	Title   string
}

func (e *RejectedError) Error() string {
	return fmt.Sprintf("crowd: request rejected with status %d (%s: %s)", e.Status, e.Problem, e.Title)
}

func (s *Sender) httpClient() *http.Client {
	if s.HTTP != nil {
		return s.HTTP
	}
	return http.DefaultClient
}

func (s *Sender) rand() io.Reader {
	if s.Rand != nil {
		return s.Rand
	}
	return rand.Reader
}

func (s *Sender) now() time.Time {
	if s.Now != nil {
		return s.Now()
	}
	return time.Now()
}

// FetchKeys loads the gateway key configuration and keeps the first supported one.
func (s *Sender) FetchKeys(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.KeysURL, nil)
	if err != nil {
		return err
	}
	resp, err := s.httpClient().Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK || resp.Header.Get("Content-Type") != ohttp.ContentTypeKeys {
		return fmt.Errorf("crowd: key fetch failed with status %d", resp.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if err != nil {
		return err
	}
	cfgs, err := ohttp.ParseKeyConfigs(b)
	if err != nil {
		return err
	}
	for _, c := range cfgs {
		if _, err := c.SelectSuite(); err == nil {
			s.mu.Lock()
			s.config = &c
			s.mu.Unlock()
			return nil
		}
	}
	return ohttp.ErrUnsupportedSuite
}

// KeyConfig returns the key configuration in use (nil before FetchKeys).
func (s *Sender) KeyConfig() *ohttp.KeyConfig {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.config
}

// EncodeRequest builds the binary HTTP request for a bundle: POST /v1/reports with Date and
// Content-Type headers, padded to protocol.PadTo bytes.
func EncodeRequest(reports []protocol.Report, now time.Time, authority string) ([]byte, error) {
	body, err := json.Marshal(protocol.Bundle{Version: protocol.Version, Reports: reports})
	if err != nil {
		return nil, err
	}
	if len(body) > protocol.MaxBodyBytes {
		return nil, errors.New("crowd: bundle too large")
	}
	req := &bhttp.Request{
		Method: http.MethodPost, Scheme: "https", Authority: authority, Path: protocol.ReportPath,
		Header: bhttp.Fields{
			{Name: "date", Value: now.UTC().Format(http.TimeFormat)},
			{Name: "content-type", Value: protocol.ContentType},
		},
		Body: body,
	}
	return req.Marshal(protocol.PadTo), nil
}

// Send submits one bundle. If the gateway does not know the key, the key configuration is
// fetched again and the request is retried once.
func (s *Sender) Send(ctx context.Context, reports []protocol.Report) error {
	for attempt := 0; ; attempt++ {
		s.mu.Lock()
		cfg := s.config
		s.mu.Unlock()
		if cfg == nil {
			if err := s.FetchKeys(ctx); err != nil {
				return err
			}
			continue
		}
		err := s.send(ctx, *cfg, reports)
		var stale *staleKeyError
		if errors.As(err, &stale) && attempt == 0 {
			s.mu.Lock()
			s.config = nil
			s.mu.Unlock()
			continue
		}
		return err
	}
}

type staleKeyError struct{}

func (staleKeyError) Error() string { return "crowd: gateway does not know the key" }

func (s *Sender) send(ctx context.Context, cfg ohttp.KeyConfig, reports []protocol.Report) error {
	authority := s.Authority
	if authority == "" {
		authority = DefaultAuthority
	}
	plain, err := EncodeRequest(reports, s.now(), authority)
	if err != nil {
		return err
	}
	encReq, cctx, err := ohttp.EncapsulateRequest(cfg, plain, s.rand())
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.RelayURL, bytes.NewReader(encReq))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", ohttp.ContentTypeRequest)
	resp, err := s.httpClient().Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	if err != nil {
		return err
	}
	if resp.StatusCode != http.StatusOK || resp.Header.Get("Content-Type") != ohttp.ContentTypeResponse {
		var p struct{ Type string }
		if json.Unmarshal(body, &p) == nil && p.Type == "https://iana.org/assignments/http-problem-types#ohttp-key" {
			return &staleKeyError{}
		}
		return fmt.Errorf("crowd: relay or gateway answered with status %d", resp.StatusCode)
	}
	pt, err := cctx.DecapsulateResponse(body)
	if err != nil {
		return err
	}
	inner, err := bhttp.UnmarshalResponse(pt, 64<<10)
	if err != nil {
		return err
	}
	if inner.Status/100 == 2 {
		return nil
	}
	var p struct{ Type, Title string }
	_ = json.Unmarshal(inner.Body, &p)
	return &RejectedError{Status: inner.Status, Problem: p.Type, Title: p.Title}
}
