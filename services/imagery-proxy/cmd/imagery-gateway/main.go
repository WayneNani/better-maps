// Command imagery-gateway is the authenticating front of the self-hosted aerial imagery proxy
// (MapProxy): it checks the device tokens, limits the rate, caches the tiles on disk and answers
// which attributions apply to a map view.
//
//	imagery-gateway [flags]        serve (see README.md for the configuration)
//	imagery-gateway token <label>  print a new random device token and its line for the token file
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

	"github.com/WayneNani/better-maps/services/imagery-proxy/internal/auth"
	"github.com/WayneNani/better-maps/services/imagery-proxy/internal/config"
	"github.com/WayneNani/better-maps/services/imagery-proxy/internal/logging"
	"github.com/WayneNani/better-maps/services/imagery-proxy/internal/server"
	"github.com/WayneNani/better-maps/services/imagery-proxy/internal/sources"
	"github.com/WayneNani/better-maps/services/imagery-proxy/internal/tilecache"
	"github.com/WayneNani/better-maps/services/imagery-proxy/internal/upstream"
)

func main() {
	if err := run(os.Args[1:], os.Getenv, os.Stdout, os.Stderr); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return
		}
		fmt.Fprintln(os.Stderr, "imagery-gateway:", err)
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

	srcs, err := sources.Load(cfg.SourcesFile)
	if err != nil {
		return err
	}
	client, err := upstream.NewClient(cfg.UpstreamURL, cfg.UpstreamLayer, cfg.UpstreamGrid, cfg.Server.UpstreamTimeout)
	if err != nil {
		return err
	}
	var cache server.Cache
	if cfg.CacheDir != "" {
		c, err := tilecache.Open(cfg.CacheDir, cfg.CacheMaxBytes, cfg.CacheMaxAge, nil)
		if err != nil {
			return err
		}
		st := c.Stats()
		logger.Info("tile cache", "tiles", st.Tiles, "mb", st.Bytes>>20, "max_mb", cfg.CacheMaxBytes>>20,
			"max_age", cfg.CacheMaxAge.String())
		cache = c
	}
	srv := server.New(cfg.Server, store, client, cache, srcs, logger, nil)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go maintain(ctx, cfg, store, srv, logger)

	httpSrv := &http.Server{
		Addr:              cfg.Listen,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      cfg.Server.QueueTimeout + cfg.Server.UpstreamTimeout + 15*time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    8 << 10,
		// net/http logs client addresses in some error messages; strip them.
		ErrorLog: log.New(logging.RedactingWriter{W: stderr}, "http: ", 0),
	}
	errCh := make(chan error, 1)
	go func() {
		logger.Info("listening", "addr", cfg.Listen, "tokens", tokens.Len(), "sources", len(srcs.Sources),
			"max_zoom", cfg.Server.MaxZoom, "rate_per_minute", cfg.Server.RatePerMinute, "rate_burst", cfg.Server.RateBurst)
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
		return errors.New("usage: imagery-gateway token <label> (label: A-Z a-z 0-9 . _ -, at most 32)")
	}
	token, err := auth.NewToken()
	if err != nil {
		return err
	}
	fmt.Fprintf(stdout, "token=%s\nline=%s\n", token, auth.HashLine(args[0], token))
	return nil
}
