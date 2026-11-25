// Command indoor-gateway serves the indoor venues built by indoor-import to the app.
//
//	indoor-gateway [flags]        serve (see README.md for the configuration)
//	indoor-gateway token <label>  print a new random device token and its line for the token file
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

	"github.com/WayneNani/better-maps/services/indoor-server/internal/auth"
	"github.com/WayneNani/better-maps/services/indoor-server/internal/config"
	"github.com/WayneNani/better-maps/services/indoor-server/internal/dataset"
	"github.com/WayneNani/better-maps/services/indoor-server/internal/logging"
	"github.com/WayneNani/better-maps/services/indoor-server/internal/server"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, os.Args[1:], os.Getenv, os.Stdout, os.Stderr); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return
		}
		fmt.Fprintln(os.Stderr, "indoor-gateway:", err)
		os.Exit(1)
	}
}

func run(ctx context.Context, args []string, env func(string) string, stdout, stderr io.Writer) error {
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

	// Without data the gateway still starts (empty venue lists) and picks the data up later.
	ds, err := dataset.Load(cfg.DataDir)
	if err != nil {
		logger.Error("no venue data loaded, serving none until it appears", "error", err)
		ds = nil
	} else {
		logData(logger, "data loaded", ds, "startup")
	}
	srv := server.New(cfg.Server, store, ds, logger, nil)

	go maintain(ctx, cfg, store, srv, logger)

	httpSrv := &http.Server{
		Addr:              cfg.Listen,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    8 << 10,
		// net/http logs client addresses in some error messages; strip them.
		ErrorLog: log.New(logging.RedactingWriter{W: stderr}, "http: ", 0),
	}
	errCh := make(chan error, 1)
	go func() {
		logger.Info("listening", "addr", cfg.Listen, "tokens", tokens.Len(),
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

func logData(logger *slog.Logger, msg string, ds *dataset.Dataset, reason string) {
	logger.Info(msg, "reason", reason, "venues", ds.Len(), "data_timestamp", ds.DataTimestamp,
		"gzip_bytes", ds.GzipBytes, "plain_bytes", ds.PlainBytes)
}

// maintain reloads the token file and the venue data on SIGHUP or when they changed, and prunes
// idle rate limit buckets. A broken token file or data directory keeps the previous state.
func maintain(ctx context.Context, cfg config.Config, store *auth.Store, srv *server.Server, logger *slog.Logger) {
	hup := make(chan os.Signal, 1)
	signal.Notify(hup, syscall.SIGHUP)
	defer signal.Stop(hup)
	tokenTick := ticker(cfg.ReloadTokens)
	defer tokenTick.Stop()
	dataTick := ticker(cfg.ReloadData)
	defer dataTick.Stop()
	lastTokens := modTime(cfg.TokensFile)
	lastData := srv.Data().IndexModTime
	reloadTokens := func(reason string) {
		tokens, err := auth.Load(cfg.TokensFile)
		if err != nil {
			logger.Error("token file not reloaded, keeping the previous tokens", "error", err)
			return
		}
		store.Replace(tokens)
		logger.Info("tokens reloaded", "reason", reason, "tokens", tokens.Len())
	}
	reloadData := func(reason string) {
		ds, err := dataset.Load(cfg.DataDir)
		if err != nil {
			logger.Error("venue data not reloaded, keeping the previous data", "error", err)
			return
		}
		srv.SetData(ds)
		logData(logger, "data reloaded", ds, reason)
	}
	for {
		select {
		case <-ctx.Done():
			return
		case <-hup:
			lastTokens = modTime(cfg.TokensFile)
			reloadTokens("SIGHUP")
			lastData = modTime(dataset.IndexPath(cfg.DataDir))
			reloadData("SIGHUP")
		case <-tokenTick.C:
			srv.Prune()
			if cfg.ReloadTokens > 0 {
				if m := modTime(cfg.TokensFile); !m.Equal(lastTokens) {
					lastTokens = m
					reloadTokens("file changed")
				}
			}
		case <-dataTick.C:
			if cfg.ReloadData <= 0 {
				continue
			}
			if m := modTime(dataset.IndexPath(cfg.DataDir)); !m.IsZero() && !m.Equal(lastData) {
				lastData = m
				reloadData("index.json changed")
			}
		}
	}
}

// ticker ticks every d, every minute for d <= 0 (the token ticker also prunes the rate limits;
// the handlers check whether reloading is enabled).
func ticker(d time.Duration) *time.Ticker {
	if d <= 0 {
		d = time.Minute
	}
	return time.NewTicker(d)
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
		return errors.New("usage: indoor-gateway token <label> (label: A-Z a-z 0-9 . _ -, at most 32)")
	}
	token, err := auth.NewToken()
	if err != nil {
		return err
	}
	fmt.Fprintf(stdout, "token=%s\nline=%s\n", token, auth.HashLine(args[0], token))
	return nil
}
