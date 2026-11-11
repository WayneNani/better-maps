// Command route-gateway is the authenticating front of the self-hosted OSRM car router.
//
//	route-gateway [flags]        serve (see README.md for the configuration)
//	route-gateway token <label>  print a new random device token and its line for the token file
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

	"github.com/WayneNani/better-maps/services/route-server/internal/auth"
	"github.com/WayneNani/better-maps/services/route-server/internal/config"
	"github.com/WayneNani/better-maps/services/route-server/internal/logging"
	"github.com/WayneNani/better-maps/services/route-server/internal/osrm"
	"github.com/WayneNani/better-maps/services/route-server/internal/server"
)

func main() {
	if err := run(os.Args[1:], os.Getenv, os.Stdout, os.Stderr); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return
		}
		fmt.Fprintln(os.Stderr, "route-gateway:", err)
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

	tokens, err := auth.Load(cfg.TokensFile)
	if err != nil {
		return err
	}
	if tokens.Len() == 0 {
		logger.Warn("token file has no tokens, every request is rejected", "file", cfg.TokensFile)
	}
	store := auth.NewStore()
	store.Replace(tokens)

	client, err := osrm.NewClient(cfg.OSRMURL, cfg.OSRMTimeout)
	if err != nil {
		return err
	}
	srv := server.New(cfg.Server, store, client, logger, nil)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go maintain(ctx, cfg, store, srv, logger)

	httpSrv := &http.Server{
		Addr:              cfg.Listen,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      cfg.OSRMTimeout + 15*time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    8 << 10,
		// net/http logs client addresses in some error messages; strip them.
		ErrorLog: log.New(logging.RedactingWriter{W: stderr}, "http: ", 0),
	}
	errCh := make(chan error, 1)
	go func() {
		logger.Info("listening", "addr", cfg.Listen, "tokens", tokens.Len(), "max_points", cfg.Server.MaxPoints,
			"rate_per_minute", cfg.Server.RatePerMinute, "rate_burst", cfg.Server.RateBurst)
		errCh <- httpSrv.ListenAndServe()
	}()
	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
	}
	logger.Info("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return httpSrv.Shutdown(shutdownCtx)
}

// maintain reloads the token file on SIGHUP or when it changed, and prunes idle rate limit
// buckets. A broken token file keeps the previous tokens.
func maintain(ctx context.Context, cfg config.Config, store *auth.Store, srv *server.Server, logger *slog.Logger) {
	hup := make(chan os.Signal, 1)
	signal.Notify(hup, syscall.SIGHUP)
	defer signal.Stop(hup)
	interval := cfg.ReloadTokens
	if interval <= 0 {
		interval = time.Minute
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	lastMod := modTime(cfg.TokensFile)
	reload := func(reason string) {
		tokens, err := auth.Load(cfg.TokensFile)
		if err != nil {
			logger.Error("token file not reloaded, keeping the previous tokens", "error", err)
			return
		}
		store.Replace(tokens)
		logger.Info("tokens reloaded", "reason", reason, "tokens", tokens.Len())
	}
	for {
		select {
		case <-ctx.Done():
			return
		case <-hup:
			lastMod = modTime(cfg.TokensFile)
			reload("SIGHUP")
		case <-ticker.C:
			srv.Prune()
			if cfg.ReloadTokens > 0 {
				if m := modTime(cfg.TokensFile); !m.Equal(lastMod) {
					lastMod = m
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
		return errors.New("usage: route-gateway token <label> (label: A-Z a-z 0-9 . _ -, at most 32)")
	}
	token, err := auth.NewToken()
	if err != nil {
		return err
	}
	fmt.Fprintf(stdout, "token=%s\nline=%s\n", token, auth.HashLine(args[0], token))
	return nil
}
