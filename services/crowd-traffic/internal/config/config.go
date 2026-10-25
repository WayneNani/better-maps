// Package config reads the crowd-traffic server configuration from command-line flags and
// environment variables (CROWD_*). Flags take precedence over the environment, which takes
// precedence over the defaults.
package config

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/protocol"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/server"
)

// Config holds all settings.
type Config struct {
	Listen    string
	Server    server.Config
	MasterKey []byte // OHTTP master secret (32 bytes)
	// MasterKeyGenerated is true if no master key was configured and a random one was created;
	// the keys then change with every restart.
	MasterKeyGenerated bool
	KeyPeriod          time.Duration
	TickInterval       time.Duration
	SummaryInterval    time.Duration
	LogLevel           slog.Level
}

// Parse parses args (without program name) and the environment.
func Parse(args []string, env func(string) string, out io.Writer) (Config, error) {
	var errs []error
	str := func(key, def string) string {
		if v := strings.TrimSpace(env(key)); v != "" {
			return v
		}
		return def
	}
	dur := func(key string, def time.Duration) time.Duration {
		v := str(key, "")
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
	num := func(key string, def float64) float64 {
		v := str(key, "")
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

	c := Config{Server: server.DefaultConfig()}
	s := &c.Server
	fs := flag.NewFlagSet("crowd-traffic", flag.ContinueOnError)
	fs.SetOutput(out)
	fs.StringVar(&c.Listen, "listen", str("CROWD_LISTEN", ":8080"), "listen address (env CROWD_LISTEN)")
	window := fs.Duration("window", dur("CROWD_WINDOW", s.Aggregation.Window), "aggregation window; only change for tests, clients use 15m (env CROWD_WINDOW)")
	grace := fs.Duration("grace", dur("CROWD_GRACE", s.Aggregation.Grace), "evaluation delay after the end of a window (env CROWD_GRACE)")
	k := fs.Int("k", int(num("CROWD_K", float64(s.Aggregation.K))), "minimum number of reports per segment and window (env CROWD_K)")
	coverage := fs.String("coverage", str("CROWD_COVERAGE", "47.2,5.8,55.1,15.1"), `accepted area "minLat,minLon,maxLat,maxLon" or "world" (env CROWD_COVERAGE)`)
	keyFile := fs.String("ohttp-master-key-file", str("CROWD_OHTTP_MASTER_KEY_FILE", ""), "file with the hex-encoded 32-byte OHTTP master secret (env CROWD_OHTTP_MASTER_KEY_FILE; or CROWD_OHTTP_MASTER_KEY with the value itself)")
	fs.DurationVar(&c.KeyPeriod, "key-period", dur("CROWD_KEY_PERIOD", 7*24*time.Hour), "OHTTP key rotation period (env CROWD_KEY_PERIOD)")
	fs.DurationVar(&s.DateSkew, "date-skew", dur("CROWD_DATE_SKEW", s.DateSkew), "accepted clock difference of the Date header (env CROWD_DATE_SKEW)")
	fs.Float64Var(&s.RateLimit, "rate-limit", num("CROWD_RATE_LIMIT", s.RateLimit), "global gateway requests per second, 0 = unlimited (env CROWD_RATE_LIMIT)")
	fs.IntVar(&s.RateBurst, "rate-burst", int(num("CROWD_RATE_BURST", float64(s.RateBurst))), "burst of the global rate limit (env CROWD_RATE_BURST)")
	fs.DurationVar(&c.TickInterval, "tick", dur("CROWD_TICK", 5*time.Second), "interval of window evaluation (env CROWD_TICK)")
	fs.DurationVar(&c.SummaryInterval, "summary-interval", dur("CROWD_SUMMARY_INTERVAL", 15*time.Minute), "interval of the counter summary in the log, 0 = off (env CROWD_SUMMARY_INTERVAL)")
	level := fs.String("log-level", str("CROWD_LOG_LEVEL", "info"), "log level: debug, info, warn, error (env CROWD_LOG_LEVEL)")
	if err := fs.Parse(args); err != nil {
		return c, err
	}
	if fs.NArg() > 0 {
		errs = append(errs, fmt.Errorf("unexpected arguments: %v", fs.Args()))
	}
	if err := c.LogLevel.UnmarshalText([]byte(*level)); err != nil {
		errs = append(errs, fmt.Errorf("log level: %w", err))
	}

	if *window < 10*time.Second || *window%time.Second != 0 || (24*time.Hour)%*window != 0 {
		errs = append(errs, errors.New("window must be whole seconds, at least 10s and divide a day"))
	}
	if *grace < 0 || *grace >= *window {
		errs = append(errs, errors.New("grace must be non-negative and shorter than the window"))
	}
	if *k < 2 {
		errs = append(errs, errors.New("k must be at least 2"))
	}
	if c.KeyPeriod < time.Hour {
		errs = append(errs, errors.New("key period must be at least 1h"))
	}
	if c.TickInterval <= 0 || s.DateSkew <= 0 || s.RateLimit < 0 || s.RateBurst < 1 {
		errs = append(errs, errors.New("tick, date skew and rate burst must be positive"))
	}
	s.Aggregation.Window, s.Rules.Window = *window, *window
	s.Aggregation.Grace, s.Rules.Grace = *grace, *grace
	s.Aggregation.K = *k

	if strings.EqualFold(*coverage, "world") {
		s.Rules.Coverage = nil
	} else if b, err := parseBox(*coverage); err != nil {
		errs = append(errs, fmt.Errorf("coverage: %w", err))
	} else {
		s.Rules.Coverage = b
	}

	key := str("CROWD_OHTTP_MASTER_KEY", "")
	if *keyFile != "" {
		b, err := os.ReadFile(*keyFile)
		if err != nil {
			errs = append(errs, fmt.Errorf("master key file: %w", err))
		}
		key = strings.TrimSpace(string(b))
	}
	if key == "" {
		c.MasterKey = make([]byte, 32)
		_, _ = rand.Read(c.MasterKey)
		c.MasterKeyGenerated = true
	} else if b, err := hex.DecodeString(key); err != nil || len(b) < 32 {
		errs = append(errs, errors.New("master key must be at least 32 bytes, hex-encoded"))
	} else {
		c.MasterKey = b
	}
	return c, errors.Join(errs...)
}

func parseBox(s string) (*protocol.BBox, error) {
	f := strings.Split(s, ",")
	if len(f) != 4 {
		return nil, errors.New("need minLat,minLon,maxLat,maxLon")
	}
	var v [4]float64
	for i, x := range f {
		var err error
		if v[i], err = strconv.ParseFloat(strings.TrimSpace(x), 64); err != nil {
			return nil, err
		}
	}
	if v[0] >= v[2] || v[1] >= v[3] {
		return nil, errors.New("minimum must be below maximum")
	}
	return &protocol.BBox{MinLat: v[0], MinLon: v[1], MaxLat: v[2], MaxLon: v[3]}, nil
}
