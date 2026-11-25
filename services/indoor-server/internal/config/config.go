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

	"github.com/WayneNani/better-maps/services/indoor-server/internal/server"
)

// Config holds all settings.
type Config struct {
	Listen       string
	DataDir      string
	TokensFile   string
	LogLevel     slog.Level
	Server       server.Config
	ReloadTokens time.Duration // interval for checking the token file for changes, 0 = only on SIGHUP
	ReloadData   time.Duration // interval for checking index.json for changes, 0 = only on SIGHUP
}

// Defaults.
const (
	DefaultListen       = ":8080"
	DefaultReloadTokens = 30 * time.Second
	DefaultReloadData   = time.Minute
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
	fs := flag.NewFlagSet("indoor-gateway", flag.ContinueOnError)
	fs.SetOutput(out)
	fs.StringVar(&c.Listen, "listen", str("INDOOR_LISTEN", DefaultListen), "listen address (env INDOOR_LISTEN)")
	fs.StringVar(&c.DataDir, "data-dir", str("INDOOR_DATA_DIR", ""), "directory with index.json and venues/ from indoor-import (env INDOOR_DATA_DIR)")
	fs.StringVar(&c.TokensFile, "tokens-file", str("INDOOR_TOKENS_FILE", ""), "file with the token hashes of the devices (env INDOOR_TOKENS_FILE)")
	fs.DurationVar(&c.ReloadTokens, "reload-tokens", dur("INDOOR_RELOAD_TOKENS", DefaultReloadTokens), "check the token file for changes this often, 0 = only on SIGHUP (env INDOOR_RELOAD_TOKENS)")
	fs.DurationVar(&c.ReloadData, "reload-data", dur("INDOOR_RELOAD_DATA", DefaultReloadData), "check index.json for changes this often, 0 = only on SIGHUP (env INDOOR_RELOAD_DATA)")
	fs.Float64Var(&c.Server.RatePerMinute, "rate-per-minute", num("INDOOR_RATE_PER_MINUTE", d.RatePerMinute), "requests per minute and device on average, 0 = unlimited (env INDOOR_RATE_PER_MINUTE)")
	burst := fs.Int("rate-burst", int(num("INDOOR_RATE_BURST", float64(d.RateBurst))), "requests per device at once (env INDOOR_RATE_BURST)")
	level := fs.String("log-level", str("INDOOR_LOG_LEVEL", "info"), "log level: debug, info, warn, error (env INDOOR_LOG_LEVEL)")
	if err := fs.Parse(args); err != nil {
		return c, err
	}
	if fs.NArg() > 0 {
		errs = append(errs, fmt.Errorf("unexpected arguments: %v", fs.Args()))
	}

	srv := d
	srv.RatePerMinute = c.Server.RatePerMinute
	srv.RateBurst = *burst
	c.Server = srv

	if c.TokensFile == "" {
		errs = append(errs, errors.New("no token file (-tokens-file / INDOOR_TOKENS_FILE): every request would be rejected"))
	}
	if c.DataDir == "" {
		errs = append(errs, errors.New("no data directory (-data-dir / INDOOR_DATA_DIR)"))
	}
	if srv.RatePerMinute < 0 || (srv.RatePerMinute > 0 && srv.RateBurst < 1) {
		errs = append(errs, errors.New("rate-per-minute must be >= 0 and rate-burst >= 1"))
	}
	if c.ReloadTokens < 0 || c.ReloadData < 0 {
		errs = append(errs, errors.New("reload-tokens and reload-data must be >= 0"))
	}
	if err := c.LogLevel.UnmarshalText([]byte(*level)); err != nil {
		errs = append(errs, fmt.Errorf("log-level: %w", err))
	}
	return c, errors.Join(errs...)
}
