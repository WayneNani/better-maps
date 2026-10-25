package client

import (
	"bytes"
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/aggregate"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/bhttp"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/ohttp"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/protocol"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/server"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/tiles"
)

func oneReport(now time.Time) []protocol.Report {
	return []protocol.Report{{
		Window: now.Truncate(15 * time.Minute).Unix(), Tile: tiles.Tile{X: 70420, Y: 43010},
		Entry: protocol.Edge{Side: tiles.West, Offset: 1}, Exit: protocol.Edge{Side: tiles.East, Offset: 2},
		RoadClass: protocol.Primary, SpeedBucket: 3,
	}}
}

func TestSenderAgainstGateway(t *testing.T) {
	now := time.Now()
	keys, _ := ohttp.NewKeyRing(bytes.Repeat([]byte{3}, 32), time.Hour)
	cfg := server.DefaultConfig()
	srv := server.New(cfg, keys, aggregate.New(cfg.Aggregation, now), slog.New(slog.NewTextHandler(io.Discard, nil)))
	hs := httptest.NewServer(srv.Handler())
	defer hs.Close()
	s := &Sender{RelayURL: hs.URL + "/gateway", KeysURL: hs.URL + "/ohttp-keys"}
	if err := s.Send(context.Background(), oneReport(now)); err != nil {
		t.Fatal(err)
	}
	if srv.Health().Aggregator.ReportsAccepted != 1 {
		t.Fatal("report not counted")
	}
}

func TestSenderErrors(t *testing.T) {
	key, _ := ohttp.NewPrivateKey(1, ohttp.DefaultKEM, bytes.Repeat([]byte{4}, 32), ohttp.DefaultSuites)
	var mode string
	h := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/keys" && mode == "keys404":
			http.NotFound(w, r)
		case r.URL.Path == "/keys" && mode == "keysbad":
			w.Header().Set("Content-Type", ohttp.ContentTypeKeys)
			w.Write([]byte{0, 1})
		case r.URL.Path == "/keys" && mode == "keysunsupported":
			c := key.Config
			c.Suites = []ohttp.SymmetricSuite{{KDF: 0x55, AEAD: 0x55}}
			w.Header().Set("Content-Type", ohttp.ContentTypeKeys)
			w.Write(ohttp.MarshalKeyConfigs([]ohttp.KeyConfig{c}))
		case r.URL.Path == "/keys":
			w.Header().Set("Content-Type", ohttp.ContentTypeKeys)
			w.Write(ohttp.MarshalKeyConfigs([]ohttp.KeyConfig{key.Config}))
		case mode == "relay500":
			http.Error(w, "boom", http.StatusInternalServerError)
		case mode == "stale":
			w.Header().Set("Content-Type", "application/problem+json")
			w.WriteHeader(http.StatusBadRequest)
			w.Write([]byte(`{"type":"https://iana.org/assignments/http-problem-types#ohttp-key"}`))
		default:
			body, _ := io.ReadAll(r.Body)
			_, sctx, err := ohttp.DecapsulateRequest(body, func(uint8) (*ohttp.PrivateKey, bool) { return key, true })
			if err != nil {
				t.Errorf("gateway: %v", err)
				return
			}
			inner := (&bhttp.Response{Status: 204}).Marshal()
			if mode == "innerbad" {
				inner = []byte{0x05}
			}
			enc, _ := sctx.EncapsulateResponse(inner, strings.NewReader(strings.Repeat("n", 32)))
			if mode == "corrupt" {
				enc[len(enc)-1] ^= 1
			}
			w.Header().Set("Content-Type", ohttp.ContentTypeResponse)
			w.Write(enc)
		}
	})
	hs := httptest.NewServer(h)
	defer hs.Close()
	now := time.Now()
	for _, m := range []string{"keys404", "keysbad", "keysunsupported", "relay500", "stale", "innerbad", "corrupt", "ok"} {
		mode = m
		s := &Sender{RelayURL: hs.URL + "/relay", KeysURL: hs.URL + "/keys", HTTP: hs.Client(), Authority: "a"}
		err := s.Send(context.Background(), oneReport(now))
		if (m == "ok") != (err == nil) {
			t.Errorf("%s: err = %v", m, err)
		}
		var stale *staleKeyError
		if m == "stale" && !errors.As(err, &stale) {
			t.Errorf("stale: %v", err)
		}
	}
	// Unreachable endpoints and invalid URLs.
	s := &Sender{RelayURL: "http://127.0.0.1:1/x", KeysURL: "http://127.0.0.1:1/k"}
	if err := s.Send(context.Background(), oneReport(now)); err == nil {
		t.Error("unreachable key server")
	}
	s = &Sender{KeysURL: "::"}
	if err := s.FetchKeys(context.Background()); err == nil {
		t.Error("invalid URL")
	}
	s = &Sender{RelayURL: "http://127.0.0.1:1/x", config: &key.Config}
	if err := s.Send(context.Background(), oneReport(now)); err == nil {
		t.Error("unreachable relay")
	}
	s = &Sender{RelayURL: "::", config: &key.Config}
	if err := s.Send(context.Background(), oneReport(now)); err == nil {
		t.Error("invalid relay URL")
	}
	bad := key.Config
	bad.PublicKey = []byte{1}
	s = &Sender{RelayURL: hs.URL, config: &bad}
	if err := s.Send(context.Background(), oneReport(now)); err == nil {
		t.Error("invalid key accepted")
	}
	// Oversized bundles are refused locally.
	many := make([]protocol.Report, 200)
	for i := range many {
		many[i] = oneReport(now)[0]
	}
	if _, err := EncodeRequest(many, now, "a"); err == nil {
		t.Error("oversized bundle encoded")
	}
}

func TestEncodeRequestPadding(t *testing.T) {
	b, err := EncodeRequest(oneReport(time.Now()), time.Now(), DefaultAuthority)
	if err != nil || len(b) != protocol.PadTo {
		t.Fatalf("len %d, %v", len(b), err)
	}
	req, err := bhttp.UnmarshalRequest(b, protocol.MaxBodyBytes)
	if err != nil || req.Path != protocol.ReportPath || req.Header.Get("date") == "" || req.Authority != DefaultAuthority {
		t.Fatalf("request %+v, %v", req, err)
	}
}
