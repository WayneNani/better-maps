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
	"net/url"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode"

	"github.com/WayneNani/better-maps/services/traff-server/internal/source/crowd"
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

	// DATEX II feeds (empty = DATEX II source disabled).
	Datex2Feeds    []Datex2Feed
	Datex2Interval time.Duration
	Datex2Horizon  time.Duration
	Datex2Timeout  time.Duration

	// Duplicate suppression across sources.
	DedupEnabled         bool
	DedupDistance        int // metres
	DedupSpatialDistance int // metres, 0 = no spatial comparison of messages without road reference

	// Crowd-sourced congestion from services/crowd-traffic (empty URL = disabled).
	CrowdURL           string
	CrowdInterval      time.Duration
	CrowdTimeout       time.Duration
	CrowdMergeDistance int // metres, 0 = one message per tile
	CrowdMaxLength     int // metres, 0 = no limit
}

// Datex2Feed is one configured DATEX II publication. Credentials only come from environment
// variables or files, never from flags (flags are visible in the process list).
type Datex2Feed struct {
	Name     string
	URL      string
	Username string
	Password string
	// PEM files for TLS client authentication (Mobilithek) and an additional CA.
	ClientCert, ClientKey, CACert string
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
	DefaultDatex2Horizon           = 7 * 24 * time.Hour
	DefaultDatex2Timeout           = time.Minute
	DefaultDedupDistance           = 500
	DefaultDedupSpatialDistance    = 250
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
	feeds := fs.String("datex2-feeds", envString(env, "TRAFF_DATEX2_FEEDS", ""), "DATEX II feeds as comma-separated name=URL list, empty = disabled (env TRAFF_DATEX2_FEEDS)")
	fs.DurationVar(&c.Datex2Interval, "datex2-interval", durEnv("TRAFF_DATEX2_INTERVAL", 0), "minimum time between two downloads of a DATEX II feed, 0 = every poll (env TRAFF_DATEX2_INTERVAL)")
	fs.DurationVar(&c.Datex2Horizon, "datex2-horizon", durEnv("TRAFF_DATEX2_HORIZON", DefaultDatex2Horizon), "skip DATEX II records starting later than this, 0 = no limit (env TRAFF_DATEX2_HORIZON)")
	fs.DurationVar(&c.Datex2Timeout, "datex2-timeout", durEnv("TRAFF_DATEX2_TIMEOUT", DefaultDatex2Timeout), "timeout for downloading a DATEX II feed (env TRAFF_DATEX2_TIMEOUT)")
	fs.BoolVar(&c.DedupEnabled, "dedup", boolEnv("TRAFF_DEDUP_ENABLED", true), "suppress messages that duplicate a message of a source with higher priority (env TRAFF_DEDUP_ENABLED)")
	fs.IntVar(&c.DedupDistance, "dedup-distance", intEnv("TRAFF_DEDUP_DISTANCE", DefaultDedupDistance), "maximum distance in metres between duplicates (env TRAFF_DEDUP_DISTANCE)")
	fs.IntVar(&c.DedupSpatialDistance, "dedup-spatial-distance", intEnv("TRAFF_DEDUP_SPATIAL_DISTANCE", DefaultDedupSpatialDistance), "maximum distance in metres for congestion duplicates without road reference, 0 = disabled (env TRAFF_DEDUP_SPATIAL_DISTANCE)")
	fs.StringVar(&c.CrowdURL, "crowd-url", envString(env, "TRAFF_CROWD_URL", ""), "base URL of the crowd-traffic server, empty = disabled (env TRAFF_CROWD_URL)")
	fs.DurationVar(&c.CrowdInterval, "crowd-interval", durEnv("TRAFF_CROWD_INTERVAL", crowd.DefaultInterval), "interval for fetching the crowd source (env TRAFF_CROWD_INTERVAL)")
	fs.DurationVar(&c.CrowdTimeout, "crowd-timeout", durEnv("TRAFF_CROWD_TIMEOUT", crowd.DefaultTimeout), "timeout for downloading the crowd export (env TRAFF_CROWD_TIMEOUT)")
	fs.IntVar(&c.CrowdMergeDistance, "crowd-merge-distance", intEnv("TRAFF_CROWD_MERGE_DISTANCE", crowd.DefaultMergeDistance), "maximum gap in metres between adjacent congested tiles that are merged, 0 = no merging (env TRAFF_CROWD_MERGE_DISTANCE)")
	fs.IntVar(&c.CrowdMaxLength, "crowd-max-length", intEnv("TRAFF_CROWD_MAX_LENGTH", crowd.DefaultMaxLength), "maximum length in metres of a merged crowd message, 0 = no limit (env TRAFF_CROWD_MAX_LENGTH)")

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
	if c.AutobahnRequestInterval < 0 || c.MaxSubscriptions < 0 || c.MaxFilters < 0 || c.Datex2Interval < 0 ||
		c.Datex2Horizon < 0 || c.Datex2Timeout <= 0 || c.DedupDistance <= 0 || c.DedupSpatialDistance < 0 ||
		c.CrowdTimeout <= 0 || c.CrowdMergeDistance < 0 || c.CrowdMaxLength < 0 {
		errs = append(errs, errors.New("negative limits are not allowed"))
	}
	if c.CrowdURL != "" {
		if _, err := crowd.ExportURL(c.CrowdURL); err != nil {
			errs = append(errs, err)
		}
		if c.CrowdInterval < 10*time.Second {
			errs = append(errs, errors.New("crowd interval must be at least 10s"))
		}
		if c.MessageLease < c.CrowdInterval*2 {
			errs = append(errs, errors.New("message lease must be at least twice the crowd interval"))
		}
	}
	var err error
	if c.Datex2Feeds, err = parseFeeds(*feeds, env); err != nil {
		errs = append(errs, err)
	}
	return c, errors.Join(errs...)
}

var reFeedName = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]{0,31}$`)

// FeedEnvPrefix returns the prefix of the per-feed environment variables, e.g.
// "TRAFF_DATEX2_BW_INCIDENTS_" for the feed "bw-incidents".
func FeedEnvPrefix(name string) string {
	return "TRAFF_DATEX2_" + strings.ToUpper(strings.ReplaceAll(name, "-", "_")) + "_"
}

// parseFeeds parses "name=URL" entries separated by commas or white space and reads the
// credentials of each feed from the environment:
//
//	TRAFF_DATEX2_<NAME>_USERNAME, TRAFF_DATEX2_<NAME>_PASSWORD or TRAFF_DATEX2_<NAME>_PASSWORD_FILE
//	TRAFF_DATEX2_<NAME>_CLIENT_CERT, ..._CLIENT_KEY, ..._CA_CERT (fallback: TRAFF_DATEX2_CLIENT_CERT, ...)
//
// Basic-auth credentials are per feed only, so that they are never sent to another server.
func parseFeeds(spec string, env envFunc) ([]Datex2Feed, error) {
	var res []Datex2Feed
	var errs []error
	seen := map[string]bool{}
	for _, item := range strings.FieldsFunc(spec, func(r rune) bool { return r == ',' || unicode.IsSpace(r) }) {
		name, rawURL, ok := strings.Cut(item, "=")
		name = strings.TrimSpace(name)
		if !ok || !reFeedName.MatchString(name) {
			errs = append(errs, fmt.Errorf("datex2 feed %q: expected name=URL with a name of lower-case letters, digits, - and _", item))
			continue
		}
		if seen[name] {
			errs = append(errs, fmt.Errorf("datex2 feed %q: duplicate name", name))
			continue
		}
		seen[name] = true
		u, err := url.Parse(rawURL)
		if err != nil || !(u.Scheme == "http" || u.Scheme == "https" || u.Scheme == "file") || (u.Scheme != "file" && u.Host == "") {
			errs = append(errs, fmt.Errorf("datex2 feed %q: invalid URL (http, https or file required)", name))
			continue
		}
		p := FeedEnvPrefix(name)
		f := Datex2Feed{
			Name:       name,
			URL:        rawURL,
			Username:   strings.TrimSpace(env(p + "USERNAME")),
			Password:   env(p + "PASSWORD"),
			ClientCert: envString(env, p+"CLIENT_CERT", envString(env, "TRAFF_DATEX2_CLIENT_CERT", "")),
			ClientKey:  envString(env, p+"CLIENT_KEY", envString(env, "TRAFF_DATEX2_CLIENT_KEY", "")),
			CACert:     envString(env, p+"CA_CERT", envString(env, "TRAFF_DATEX2_CA_CERT", "")),
		}
		if file := strings.TrimSpace(env(p + "PASSWORD_FILE")); file != "" {
			if f.Password != "" {
				errs = append(errs, fmt.Errorf("datex2 feed %q: set either %sPASSWORD or %sPASSWORD_FILE", name, p, p))
				continue
			}
			data, err := os.ReadFile(file)
			if err != nil {
				errs = append(errs, fmt.Errorf("datex2 feed %q: cannot read password file", name))
				continue
			}
			f.Password = strings.TrimRight(string(data), "\r\n")
		}
		if (f.ClientCert == "") != (f.ClientKey == "") {
			errs = append(errs, fmt.Errorf("datex2 feed %q: client certificate and key must be set together", name))
			continue
		}
		res = append(res, f)
	}
	return res, errors.Join(errs...)
}
