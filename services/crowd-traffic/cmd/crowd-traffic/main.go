// Command crowd-traffic is the privacy-preserving aggregation server for crowd-sourced
// congestion reports: OHTTP gateway, in-memory aggregation and TraFF/JSON export.
//
// See README.md and docs/CROWD_TRAFFIC_PRIVACY.md.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/aggregate"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/config"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/logging"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/ohttp"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/server"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, os.Args[1:], os.Getenv, os.Stderr, nil); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return
		}
		fmt.Fprintln(os.Stderr, "crowd-traffic:", err)
		os.Exit(1)
	}
}

// run starts the server and blocks until ctx is cancelled. If ready is not nil, the listening
// address is sent to it once the server accepts connections.
func run(ctx context.Context, args []string, env func(string) string, stderr io.Writer, ready chan<- string) error {
	cfg, err := config.Parse(args, env, stderr)
	if err != nil {
		return err
	}
	logger := slog.New(slog.NewTextHandler(stderr, &slog.HandlerOptions{Level: cfg.LogLevel}))
	if cfg.MasterKeyGenerated {
		logger.Warn("no OHTTP master key configured (CROWD_OHTTP_MASTER_KEY); using a random key, clients must refetch keys after every restart")
	}
	keys, err := ohttp.NewKeyRing(cfg.MasterKey, cfg.KeyPeriod)
	if err != nil {
		return err
	}
	agg := aggregate.New(cfg.Server.Aggregation, time.Now())
	srv := server.New(cfg.Server, keys, agg, logger)

	ln, err := net.Listen("tcp", cfg.Listen)
	if err != nil {
		return err
	}
	hs := &http.Server{
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       2 * time.Minute,
		MaxHeaderBytes:    16 << 10,
		// net/http logs client addresses in some error messages; they are removed.
		ErrorLog: log.New(logging.RedactingWriter{W: stderr}, "http: ", 0),
	}
	logger.Info("crowd-traffic listening", "addr", ln.Addr().String(),
		"window", cfg.Server.Aggregation.Window, "grace", cfg.Server.Aggregation.Grace, "k", cfg.Server.Aggregation.K,
		"key_id", keys.Configs(time.Now())[0].KeyID)

	errc := make(chan error, 1)
	go func() { errc <- hs.Serve(ln) }()
	if ready != nil {
		ready <- ln.Addr().String()
	}

	tick := time.NewTicker(cfg.TickInterval)
	defer tick.Stop()
	var summary <-chan time.Time
	if cfg.SummaryInterval > 0 {
		t := time.NewTicker(cfg.SummaryInterval)
		defer t.Stop()
		summary = t.C
	}
	for {
		select {
		case <-tick.C:
			srv.Tick()
		case <-summary:
			srv.LogSummary()
		case err := <-errc:
			return err
		case <-ctx.Done():
			logger.Info("shutting down")
			sctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			return hs.Shutdown(sctx)
		}
	}
}
