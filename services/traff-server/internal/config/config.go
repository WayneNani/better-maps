// Package config reads the server configuration from command-line flags and environment
// variables. Flags take precedence over environment variables, which take precedence over the
// built-in defaults.
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
)

// Config holds all settings.
type Config struct {
	Listen                  string
	PollInterval            time.Duration
	AutobahnEnabled         bool
	AutobahnURL             string
	AutobahnRoads           []string
	AutobahnRequestInterval time.Duration
	UserAgent               string
	SubscriptionTTL         time.Duration
	MessageLease            time.Duration
	MaxSubscriptions        int
	MaxFilters              int
	LogLevel                slog.Level
}

// Defaults.
const (
	DefaultListen                  = ":8080"
	DefaultPollInterval            = 5 * time.Minute
	DefaultAutobahnURL             = "https://verkehr.autobahn.de/o/autobahn"
	DefaultAutobahnRequestInterval = 250 * time.Millisecond
	DefaultUserAgent               = "better-maps-traff-server/0.1 (+https://github.com/WayneNani/better-maps)"
	DefaultSubscriptionTTL         = time.Hour
	DefaultMessageLease            = 30 * time.Minute
	DefaultMaxSubscriptions        = 10000
	DefaultMaxFilters              = 128
)

type envFunc func(string) string

func envString(env envFunc, key, def string) string {
	if v := strings.TrimSpace(env(key)); v != "" {
		return v
	}
	return def
}

// Parse parses args (without program name) and the environment.
func Parse(args []string, env func(string) string, out io.Writer) (Config, error) {
	var c Config
	var errs []error
	durEnv := func(key string, def time.Duration) time.Duration {
		v := env(key)
		if strings.TrimSpace(v) == "" {
			return def
		}
		d, err := time.ParseDuration(strings.TrimSpace(v))
		if err != nil {
			errs = append(errs, fmt.Errorf("%s: %w", key, err))
			return def
		}
		return d
	}
	intEnv := func(key string, def int) int {
		v := env(key)
		if strings.TrimSpace(v) == "" {
			return def
		}
		i, err := strconv.Atoi(strings.TrimSpace(v))
		if err != nil {
			errs = append(errs, fmt.Errorf("%s: %w", key, err))
			return def
		}
		return i
	}
	boolEnv := func(key string, def bool) bool {
		v := env(key)
		if strings.TrimSpace(v) == "" {
			return def
		}
		b, err := strconv.ParseBool(strings.TrimSpace(v))
		if err != nil {
			errs = append(errs, fmt.Errorf("%s: %w", key, err))
			return def
		}
		return b
	}

	fs := flag.NewFlagSet("traff-server", flag.ContinueOnError)
	fs.SetOutput(out)
	fs.StringVar(&c.Listen, "listen", envString(env, "TRAFF_LISTEN", DefaultListen), "listen address (env TRAFF_LISTEN)")
	fs.DurationVar(&c.PollInterval, "poll-interval", durEnv("TRAFF_POLL_INTERVAL", DefaultPollInterval), "interval for fetching the sources (env TRAFF_POLL_INTERVAL)")
	fs.BoolVar(&c.AutobahnEnabled, "autobahn", boolEnv("TRAFF_AUTOBAHN_ENABLED", true), "enable the Autobahn API source (env TRAFF_AUTOBAHN_ENABLED)")
	fs.StringVar(&c.AutobahnURL, "autobahn-url", envString(env, "TRAFF_AUTOBAHN_URL", DefaultAutobahnURL), "base URL of the Autobahn API (env TRAFF_AUTOBAHN_URL)")
	roads := fs.String("autobahn-roads", envString(env, "TRAFF_AUTOBAHN_ROADS", ""), "comma-separated road IDs, empty = all (env TRAFF_AUTOBAHN_ROADS)")
	fs.DurationVar(&c.AutobahnRequestInterval, "autobahn-request-interval", durEnv("TRAFF_AUTOBAHN_REQUEST_INTERVAL", DefaultAutobahnRequestInterval), "minimum pause between two Autobahn API requests (env TRAFF_AUTOBAHN_REQUEST_INTERVAL)")
	fs.StringVar(&c.UserAgent, "user-agent", envString(env, "TRAFF_USER_AGENT", DefaultUserAgent), "User-Agent for upstream requests (env TRAFF_USER_AGENT)")
	fs.DurationVar(&c.SubscriptionTTL, "subscription-ttl", durEnv("TRAFF_SUBSCRIPTION_TTL", DefaultSubscriptionTTL), "subscriptions expire after this time without requests (env TRAFF_SUBSCRIPTION_TTL)")
	fs.DurationVar(&c.MessageLease, "message-lease", durEnv("TRAFF_MESSAGE_LEASE", DefaultMessageLease), "expiration_time granted to messages, renewed while the source reports them (env TRAFF_MESSAGE_LEASE)")
	fs.IntVar(&c.MaxSubscriptions, "max-subscriptions", intEnv("TRAFF_MAX_SUBSCRIPTIONS", DefaultMaxSubscriptions), "maximum number of concurrent subscriptions (env TRAFF_MAX_SUBSCRIPTIONS)")
	fs.IntVar(&c.MaxFilters, "max-filters", intEnv("TRAFF_MAX_FILTERS", DefaultMaxFilters), "maximum number of filter items per request (env TRAFF_MAX_FILTERS)")
	level := fs.String("log-level", envString(env, "TRAFF_LOG_LEVEL", "info"), "log level: debug, info, warn, error (env TRAFF_LOG_LEVEL)")

	if err := fs.Parse(args); err != nil {
		return c, err
	}
	if fs.NArg() > 0 {
		errs = append(errs, fmt.Errorf("unexpected arguments: %v", fs.Args()))
	}
	for _, r := range strings.Split(*roads, ",") {
		if r = strings.TrimSpace(r); r != "" {
			c.AutobahnRoads = append(c.AutobahnRoads, r)
		}
	}
	if err := c.LogLevel.UnmarshalText([]byte(*level)); err != nil {
		errs = append(errs, fmt.Errorf("log level: %w", err))
	}
	if c.PollInterval < 10*time.Second {
		errs = append(errs, errors.New("poll interval must be at least 10s"))
	}
	if c.SubscriptionTTL < time.Minute {
		errs = append(errs, errors.New("subscription TTL must be at least 1m"))
	}
	if c.MessageLease < c.PollInterval*2 {
		errs = append(errs, errors.New("message lease must be at least twice the poll interval"))
	}
	if c.AutobahnRequestInterval < 0 || c.MaxSubscriptions < 0 || c.MaxFilters < 0 {
		errs = append(errs, errors.New("negative limits are not allowed"))
	}
	return c, errors.Join(errs...)
}
