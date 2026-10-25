// Command traff-server serves traffic messages in TraFF format over HTTP.
//
// See README.md for configuration and usage.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
	_ "time/tzdata" // the container image has no zoneinfo; Europe/Berlin is needed by the Autobahn source

	"github.com/WayneNani/better-maps/services/traff-server/internal/config"
	"github.com/WayneNani/better-maps/services/traff-server/internal/dedup"
	"github.com/WayneNani/better-maps/services/traff-server/internal/logging"
	"github.com/WayneNani/better-maps/services/traff-server/internal/server"
	"github.com/WayneNani/better-maps/services/traff-server/internal/source"
	"github.com/WayneNani/better-maps/services/traff-server/internal/source/autobahn"
	"github.com/WayneNani/better-maps/services/traff-server/internal/source/crowd"
	"github.com/WayneNani/better-maps/services/traff-server/internal/source/datex2"
	"github.com/WayneNani/better-maps/services/traff-server/internal/store"
	"github.com/WayneNani/better-maps/services/traff-server/internal/subscription"
)

func main() {
	if err := run(os.Args[1:], os.Getenv, os.Stderr); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return
		}
		fmt.Fprintln(os.Stderr, "traff-server:", err)
		os.Exit(1)
	}
}

func run(args []string, env func(string) string, stderr io.Writer) error {
	cfg, err := config.Parse(args, env, stderr)
	if err != nil {
		return err
	}
	logger := slog.New(slog.NewTextHandler(stderr, &slog.HandlerOptions{Level: cfg.LogLevel}))

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	app, err := build(cfg, logger)
	if err != nil {
		return err
	}
	return app.serve(ctx, stderr)
}

type app struct {
	cfg          config.Config
	janitorEvery time.Duration
	log          *slog.Logger
	store        *store.Store
	subs         *subscription.Manager
	runner       *source.Runner
	srv          *server.Server
}

func build(cfg config.Config, logger *slog.Logger) (*app, error) {
	// Cancellations are kept as long as a subscription can stay idle, so every live subscriber
	// sees them.
	st := store.New(cfg.MessageLease, cfg.SubscriptionTTL)
	subs := subscription.NewManager(st, cfg.SubscriptionTTL, cfg.MaxSubscriptions)

	var sources []source.Source
	if cfg.AutobahnEnabled {
		tz, err := time.LoadLocation("Europe/Berlin")
		if err != nil {
			return nil, err
		}
		client := autobahn.NewClient(cfg.AutobahnURL, cfg.UserAgent, cfg.AutobahnRequestInterval)
		sources = append(sources, autobahn.NewSource(client, cfg.AutobahnRoads, tz, logger))
	}
	// DATEX II feeds come after the Autobahn API: on duplicates, the Autobahn message wins.
	for _, f := range cfg.Datex2Feeds {
		client, err := datex2.NewClient(datex2.ClientConfig{
			Name: f.Name, URL: f.URL, UserAgent: cfg.UserAgent, Timeout: cfg.Datex2Timeout,
			Username: f.Username, Password: f.Password,
			ClientCertFile: f.ClientCert, ClientKeyFile: f.ClientKey, CAFile: f.CACert,
		})
		if err != nil {
			return nil, err
		}
		sources = append(sources, datex2.NewSource(datex2.SourceConfig{Feed: f.Name, MinInterval: cfg.Datex2Interval, Horizon: cfg.Datex2Horizon}, client, logger))
	}
	// Crowd-sourced congestion comes last: official messages win over crowd data.
	if cfg.CrowdURL != "" {
		cs, err := crowd.NewSource(crowd.Config{
			URL: cfg.CrowdURL, Interval: cfg.CrowdInterval, Timeout: cfg.CrowdTimeout, UserAgent: cfg.UserAgent,
			Merge: crowd.MergeOptions{Tolerance: float64(cfg.CrowdMergeDistance), MaxLength: float64(cfg.CrowdMaxLength)},
		})
		if err != nil {
			return nil, err
		}
		sources = append(sources, cs)
	}
	runner := source.NewRunner(st, cfg.PollInterval, logger, sources...)
	if cfg.DedupEnabled {
		spatial := float64(cfg.DedupSpatialDistance)
		if spatial == 0 {
			spatial = -1 // disabled
		}
		runner.SetDeduplicator(dedup.New(dedup.Config{MaxDistance: float64(cfg.DedupDistance), SpatialDistance: spatial}))
	}
	srv := &server.Server{
		Store: st, Subs: subs, Coverage: runner.Coverage, Status: runner.Status,
		MaxFilters: cfg.MaxFilters, Log: logger,
	}
	return &app{cfg: cfg, janitorEvery: time.Minute, log: logger, store: st, subs: subs, runner: runner, srv: srv}, nil
}

func (a *app) serve(ctx context.Context, stderr io.Writer) error {
	httpSrv := &http.Server{
		Addr:              a.cfg.Listen,
		Handler:           a.srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    16 << 10,
		// net/http logs client addresses in some error messages; strip them.
		ErrorLog: log.New(logging.RedactingWriter{W: stderr}, "http: ", 0),
	}

	go a.runner.Run(ctx)
	go a.janitor(ctx)

	errCh := make(chan error, 1)
	go func() {
		a.log.Info("listening", "addr", a.cfg.Listen)
		errCh <- httpSrv.ListenAndServe()
	}()
	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
	}
	a.log.Info("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return httpSrv.Shutdown(shutdownCtx)
}

// janitor expires idle subscriptions and purges old messages.
func (a *app) janitor(ctx context.Context) {
	t := time.NewTicker(a.janitorEvery)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case now := <-t.C:
			if n := a.subs.Expire(now); n > 0 {
				a.log.Info("subscriptions expired", "count", n)
			}
			a.store.Purge(now)
		}
	}
}
