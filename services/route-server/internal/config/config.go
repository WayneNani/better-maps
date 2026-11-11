// Package config reads the gateway configuration from command-line flags and environment
// variables. Flags take precedence over environment variables, which take precedence over the
// built-in defaults. Tokens are never configured on the command line (visible in the process
// list), only as a file of hashes.
package config

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"strconv"
	"strings"
	"time"

	"github.com/WayneNani/better-maps/services/route-server/internal/server"
)

// Config holds all settings.
type Config struct {
	Listen       string
	OSRMURL      string
	OSRMTimeout  time.Duration
	TokensFile   string
	LogLevel     slog.Level
	Server       server.Config
	ReloadTokens time.Duration // interval for checking the token file for changes, 0 = only on SIGHUP
}

// Defaults.
const (
	DefaultListen      = ":8080"
	DefaultOSRMURL     = "http://127.0.0.1:5000"
	DefaultOSRMTimeout = 10 * time.Second
	DefaultReload      = 30 * time.Second
)

// Parse parses args (without program name) and the environment.
func Parse(args []string, env func(string) string, out io.Writer) (Config, error) {
	var c Config
	var errs []error
	str := func(key, def string) string {
		if v := strings.TrimSpace(env(key)); v != "" {
			return v
		}
		return def
	}
	num := func(key string, def float64) float64 {
		v := strings.TrimSpace(env(key))
		if v == "" {
			return def
		}
		f, err := strconv.ParseFloat(v, 64)
		if err != nil {
			errs = append(errs, fmt.Errorf("%s: %w", key, err))
			return def
		}
		return f
	}
	dur := func(key string, def time.Duration) time.Duration {
		v := strings.TrimSpace(env(key))
		if v == "" {
			return def
		}
		d, err := time.ParseDuration(v)
		if err != nil {
			errs = append(errs, fmt.Errorf("%s: %w", key, err))
			return def
		}
		return d
	}

	d := server.DefaultConfig()
	fs := flag.NewFlagSet("route-gateway", flag.ContinueOnError)
	fs.SetOutput(out)
	fs.StringVar(&c.Listen, "listen", str("ROUTE_LISTEN", DefaultListen), "listen address (env ROUTE_LISTEN)")
	fs.StringVar(&c.OSRMURL, "osrm-url", str("ROUTE_OSRM_URL", DefaultOSRMURL), "base URL of osrm-routed (env ROUTE_OSRM_URL)")
	fs.DurationVar(&c.OSRMTimeout, "osrm-timeout", dur("ROUTE_OSRM_TIMEOUT", DefaultOSRMTimeout), "timeout of a request to OSRM (env ROUTE_OSRM_TIMEOUT)")
	fs.StringVar(&c.TokensFile, "tokens-file", str("ROUTE_TOKENS_FILE", ""), "file with the token hashes of the devices (env ROUTE_TOKENS_FILE)")
	fs.DurationVar(&c.ReloadTokens, "reload-tokens", dur("ROUTE_RELOAD_TOKENS", DefaultReload), "check the token file for changes this often, 0 = only on SIGHUP (env ROUTE_RELOAD_TOKENS)")
	maxPoints := fs.Int("max-points", int(num("ROUTE_MAX_POINTS", float64(d.MaxPoints))), "maximum points of a route incl. start and finish (env ROUTE_MAX_POINTS)")
	fs.Float64Var(&c.Server.RatePerMinute, "rate-per-minute", num("ROUTE_RATE_PER_MINUTE", d.RatePerMinute), "routes per minute and device on average, 0 = unlimited (env ROUTE_RATE_PER_MINUTE)")
	burst := fs.Int("rate-burst", int(num("ROUTE_RATE_BURST", float64(d.RateBurst))), "routes per device at once (env ROUTE_RATE_BURST)")
	concurrent := fs.Int("max-concurrent", int(num("ROUTE_MAX_CONCURRENT", float64(d.MaxConcurrent))), "concurrent requests to OSRM (env ROUTE_MAX_CONCURRENT)")
	level := fs.String("log-level", str("ROUTE_LOG_LEVEL", "info"), "log level: debug, info, warn, error (env ROUTE_LOG_LEVEL)")
	if err := fs.Parse(args); err != nil {
		return c, err
	}
	if fs.NArg() > 0 {
		errs = append(errs, fmt.Errorf("unexpected arguments: %v", fs.Args()))
	}

	srv := d
	srv.RatePerMinute = c.Server.RatePerMinute
	srv.MaxPoints = *maxPoints
	srv.RateBurst = *burst
	srv.MaxConcurrent = *concurrent
	c.Server = srv

	if c.TokensFile == "" {
		errs = append(errs, errors.New("no token file (-tokens-file / ROUTE_TOKENS_FILE): every request would be rejected"))
	}
	if srv.MaxPoints < 2 || srv.MaxPoints > 100 {
		errs = append(errs, fmt.Errorf("max-points must be 2..100, got %d", srv.MaxPoints))
	}
	if srv.RatePerMinute < 0 || (srv.RatePerMinute > 0 && srv.RateBurst < 1) {
		errs = append(errs, errors.New("rate-per-minute must be >= 0 and rate-burst >= 1"))
	}
	if srv.MaxConcurrent < 1 {
		errs = append(errs, errors.New("max-concurrent must be >= 1"))
	}
	if c.OSRMTimeout <= 0 {
		errs = append(errs, errors.New("osrm-timeout must be positive"))
	}
	if c.ReloadTokens < 0 {
		errs = append(errs, errors.New("reload-tokens must be >= 0"))
	}
	if err := c.LogLevel.UnmarshalText([]byte(*level)); err != nil {
		errs = append(errs, fmt.Errorf("log-level: %w", err))
	}
	return c, errors.Join(errs...)
}
