// Command traff-server serves traffic messages in TraFF format over HTTP.
//
//	traff-server [flags]        serve (see README.md for the configuration)
//	traff-server token <label>  print a new random device token and its line for the token file
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

	"github.com/WayneNani/better-maps/services/traff-server/internal/auth"
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
	if err := run(os.Args[1:], os.Getenv, os.Stdout, os.Stderr); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return
		}
		fmt.Fprintln(os.Stderr, "traff-server:", err)
		os.Exit(1)
	}
}

func run(args []string, env func(string) string, stdout, stderr io.Writer) error {
	if len(args) > 0 && args[0] == "token" {
		return newToken(args[1:], stdout)
	}
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
	tokens       *auth.Store // nil without access control
	tokensMod    time.Time   // modification time of the token file before it was loaded
}

func build(cfg config.Config, logger *slog.Logger) (*app, error) {
	var tokens *auth.Store
	var tokensMod time.Time
	if cfg.InsecureNoAuth {
		logger.Warn("TRAFF_INSECURE_NO_AUTH is set: serving WITHOUT access control, for local development only")
	} else {
		// Taken before loading: a change while loading is picked up by the next check.
		tokensMod = modTime(cfg.TokensFile)
		loaded, err := auth.Load(cfg.TokensFile)
		if err != nil {
			return nil, err
		}
		if loaded.Len() == 0 {
			logger.Warn("token file has no tokens, every request is rejected", "file", cfg.TokensFile)
		}
		tokens = auth.NewStore()
		tokens.Replace(loaded)
	}

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
		MaxFilters: cfg.MaxFilters, Log: logger, Tokens: tokens, Limits: limits(cfg),
	}
	return &app{cfg: cfg, janitorEvery: time.Minute, log: logger, store: st, subs: subs, runner: runner, srv: srv,
		tokens: tokens, tokensMod: tokensMod}, nil
}

func limits(cfg config.Config) server.Limits {
	l := server.DefaultLimits()
	l.RatePerMinute, l.RateBurst = cfg.RatePerMinute, cfg.RateBurst
	return l
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
	if a.tokens != nil {
		go a.maintainTokens(ctx)
	}

	errCh := make(chan error, 1)
	go func() {
		if a.tokens != nil {
			a.log.Info("listening", "addr", a.cfg.Listen, "tokens", a.tokens.Len(),
				"rate_per_minute", a.cfg.RatePerMinute, "rate_burst", a.cfg.RateBurst)
		} else {
			a.log.Warn("listening without access control", "addr", a.cfg.Listen)
		}
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

// maintainTokens reloads the token file on SIGHUP or when it changed, and prunes idle rate limit
// buckets. A broken token file keeps the previous tokens.
func (a *app) maintainTokens(ctx context.Context) {
	hup := make(chan os.Signal, 1)
	signal.Notify(hup, syscall.SIGHUP)
	defer signal.Stop(hup)
	every := a.cfg.ReloadTokens
	if every <= 0 {
		every = time.Minute // still prune the rate limits
	}
	tick := time.NewTicker(every)
	defer tick.Stop()
	last := a.tokensMod
	reload := func(reason string) {
		tokens, err := auth.Load(a.cfg.TokensFile)
		if err != nil {
			a.log.Error("token file not reloaded, keeping the previous tokens", "error", err)
			return
		}
		a.tokens.Replace(tokens)
		a.log.Info("tokens reloaded", "reason", reason, "tokens", tokens.Len())
	}
	for {
		select {
		case <-ctx.Done():
			return
		case <-hup:
			last = modTime(a.cfg.TokensFile)
			reload("SIGHUP")
		case <-tick.C:
			a.srv.Prune()
			if a.cfg.ReloadTokens > 0 {
				if m := modTime(a.cfg.TokensFile); !m.Equal(last) {
					last = m
					reload("file changed")
				}
			}
		}
	}
}

func modTime(path string) time.Time {
	st, err := os.Stat(path)
	if err != nil {
		return time.Time{}
	}
	return st.ModTime()
}

func newToken(args []string, stdout io.Writer) error {
	if len(args) != 1 || !auth.ValidLabel(args[0]) {
		return errors.New("usage: traff-server token <label> (label: A-Z a-z 0-9 . _ -, at most 32)")
	}
	token, err := auth.NewToken()
	if err != nil {
		return err
	}
	fmt.Fprintf(stdout, "token=%s\nline=%s\n", token, auth.HashLine(args[0], token))
	return nil
}
