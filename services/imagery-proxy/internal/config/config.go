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

	"github.com/WayneNani/better-maps/services/imagery-proxy/internal/server"
)

// Config holds all settings.
type Config struct {
	Listen        string
	UpstreamURL   string
	UpstreamLayer string
	UpstreamGrid  string
	TokensFile    string
	SourcesFile   string
	CacheDir      string // empty = no disk cache
	CacheMaxBytes int64
	CacheMaxAge   time.Duration
	LogLevel      slog.Level
	Server        server.Config
	ReloadTokens  time.Duration // interval for checking the token file for changes, 0 = only on SIGHUP
}

// Defaults.
const (
	DefaultListen       = ":8080"
	DefaultUpstreamURL  = "http://127.0.0.1:8081"
	DefaultLayer        = "aerial"
	DefaultGrid         = "webmercator"
	DefaultCacheMaxMB   = 1024
	DefaultCacheMaxAge  = 30 * 24 * time.Hour
	DefaultReload       = 30 * time.Second
	maxCacheMB          = 1 << 20
	defaultMaxZoomLimit = 22
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
	fs := flag.NewFlagSet("imagery-gateway", flag.ContinueOnError)
	fs.SetOutput(out)
	fs.StringVar(&c.Listen, "listen", str("IMAGERY_LISTEN", DefaultListen), "listen address (env IMAGERY_LISTEN)")
	fs.StringVar(&c.UpstreamURL, "upstream-url", str("IMAGERY_UPSTREAM_URL", DefaultUpstreamURL), "base URL of MapProxy (env IMAGERY_UPSTREAM_URL)")
	fs.StringVar(&c.UpstreamLayer, "upstream-layer", str("IMAGERY_UPSTREAM_LAYER", DefaultLayer), "MapProxy layer (env IMAGERY_UPSTREAM_LAYER)")
	fs.StringVar(&c.UpstreamGrid, "upstream-grid", str("IMAGERY_UPSTREAM_GRID", DefaultGrid), "MapProxy grid, WebMercator with origin top left (env IMAGERY_UPSTREAM_GRID)")
	fs.DurationVar(&c.Server.UpstreamTimeout, "upstream-timeout", dur("IMAGERY_UPSTREAM_TIMEOUT", d.UpstreamTimeout), "timeout of one tile rendering (env IMAGERY_UPSTREAM_TIMEOUT)")
	fs.StringVar(&c.TokensFile, "tokens-file", str("IMAGERY_TOKENS_FILE", ""), "file with the token hashes of the devices (env IMAGERY_TOKENS_FILE)")
	fs.StringVar(&c.SourcesFile, "sources-file", str("IMAGERY_SOURCES_FILE", ""), "sources with attributions and coverages (env IMAGERY_SOURCES_FILE)")
	fs.StringVar(&c.CacheDir, "cache-dir", str("IMAGERY_CACHE_DIR", ""), "tile cache directory, empty = no cache (env IMAGERY_CACHE_DIR)")
	cacheMB := fs.Float64("cache-max-mb", num("IMAGERY_CACHE_MAX_MB", DefaultCacheMaxMB), "size limit of the tile cache in MB (env IMAGERY_CACHE_MAX_MB)")
	fs.DurationVar(&c.CacheMaxAge, "cache-max-age", dur("IMAGERY_CACHE_MAX_AGE", DefaultCacheMaxAge), "age after which a cached tile is fetched again (env IMAGERY_CACHE_MAX_AGE)")
	fs.DurationVar(&c.ReloadTokens, "reload-tokens", dur("IMAGERY_RELOAD_TOKENS", DefaultReload), "check the token file for changes this often, 0 = only on SIGHUP (env IMAGERY_RELOAD_TOKENS)")
	maxZoom := fs.Int("max-zoom", int(num("IMAGERY_MAX_ZOOM", float64(d.MaxZoom))), "highest zoom level served (env IMAGERY_MAX_ZOOM)")
	fs.Float64Var(&c.Server.RatePerMinute, "rate-per-minute", num("IMAGERY_RATE_PER_MINUTE", d.RatePerMinute), "tiles per minute and device on average, 0 = unlimited (env IMAGERY_RATE_PER_MINUTE)")
	burst := fs.Int("rate-burst", int(num("IMAGERY_RATE_BURST", float64(d.RateBurst))), "tiles per device at once (env IMAGERY_RATE_BURST)")
	concurrent := fs.Int("max-concurrent", int(num("IMAGERY_MAX_CONCURRENT", float64(d.MaxConcurrent))), "concurrent renderings in MapProxy (env IMAGERY_MAX_CONCURRENT)")
	level := fs.String("log-level", str("IMAGERY_LOG_LEVEL", "info"), "log level: debug, info, warn, error (env IMAGERY_LOG_LEVEL)")
	if err := fs.Parse(args); err != nil {
		return c, err
	}
	if fs.NArg() > 0 {
		errs = append(errs, fmt.Errorf("unexpected arguments: %v", fs.Args()))
	}

	srv := d
	srv.RatePerMinute = c.Server.RatePerMinute
	srv.UpstreamTimeout = c.Server.UpstreamTimeout
	srv.MaxZoom = *maxZoom
	srv.RateBurst = *burst
	srv.MaxConcurrent = *concurrent
	c.Server = srv
	c.CacheMaxBytes = int64(*cacheMB * (1 << 20))

	if c.TokensFile == "" {
		errs = append(errs, errors.New("no token file (-tokens-file / IMAGERY_TOKENS_FILE): every request would be rejected"))
	}
	if c.SourcesFile == "" {
		errs = append(errs, errors.New("no sources file (-sources-file / IMAGERY_SOURCES_FILE): attributions are required"))
	}
	if srv.MaxZoom < 0 || srv.MaxZoom > defaultMaxZoomLimit {
		errs = append(errs, fmt.Errorf("max-zoom must be 0..%d, got %d", defaultMaxZoomLimit, srv.MaxZoom))
	}
	if srv.RatePerMinute < 0 || (srv.RatePerMinute > 0 && srv.RateBurst < 1) {
		errs = append(errs, errors.New("rate-per-minute must be >= 0 and rate-burst >= 1"))
	}
	if srv.MaxConcurrent < 1 || srv.MaxConcurrent > 64 {
		errs = append(errs, errors.New("max-concurrent must be 1..64"))
	}
	if srv.UpstreamTimeout <= 0 {
		errs = append(errs, errors.New("upstream-timeout must be positive"))
	}
	if *cacheMB <= 0 || *cacheMB > maxCacheMB {
		errs = append(errs, errors.New("cache-max-mb must be positive (and below 1 TB)"))
	}
	if c.CacheMaxAge < 0 {
		errs = append(errs, errors.New("cache-max-age must be >= 0"))
	}
	if c.ReloadTokens < 0 {
		errs = append(errs, errors.New("reload-tokens must be >= 0"))
	}
	if err := c.LogLevel.UnmarshalText([]byte(*level)); err != nil {
		errs = append(errs, fmt.Errorf("log-level: %w", err))
	}
	return c, errors.Join(errs...)
}
