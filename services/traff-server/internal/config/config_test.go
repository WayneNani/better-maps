package config

import (
	"errors"
	"flag"
	"io"
	"log/slog"
	"strings"
	"testing"
	"time"
)

func env(m map[string]string) func(string) string { return func(k string) string { return m[k] } }

func TestDefaults(t *testing.T) {
	c, err := Parse(nil, env(nil), io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	if c.Listen != DefaultListen || c.PollInterval != 5*time.Minute || !c.AutobahnEnabled || c.AutobahnURL != DefaultAutobahnURL ||
		c.AutobahnRoads != nil || c.SubscriptionTTL != time.Hour || c.MessageLease != 30*time.Minute ||
		c.MaxSubscriptions != DefaultMaxSubscriptions || c.MaxFilters != DefaultMaxFilters || c.LogLevel != slog.LevelInfo ||
		c.AutobahnRequestInterval != DefaultAutobahnRequestInterval || c.UserAgent != DefaultUserAgent {
		t.Errorf("defaults = %+v", c)
	}
}

func TestEnvironmentAndFlags(t *testing.T) {
	e := env(map[string]string{
		"TRAFF_LISTEN":                    ":9000",
		"TRAFF_POLL_INTERVAL":             "2m",
		"TRAFF_AUTOBAHN_ROADS":            "A1, A7 ,,A9",
		"TRAFF_SUBSCRIPTION_TTL":          "3h",
		"TRAFF_MAX_SUBSCRIPTIONS":         "5",
		"TRAFF_AUTOBAHN_ENABLED":          "false",
		"TRAFF_LOG_LEVEL":                 "debug",
		"TRAFF_AUTOBAHN_REQUEST_INTERVAL": "1s",
	})
	c, err := Parse([]string{"-listen", "127.0.0.1:8081", "-max-filters", "3"}, e, io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	if c.Listen != "127.0.0.1:8081" || c.PollInterval != 2*time.Minute || strings.Join(c.AutobahnRoads, "|") != "A1|A7|A9" ||
		c.SubscriptionTTL != 3*time.Hour || c.MaxSubscriptions != 5 || c.MaxFilters != 3 || c.AutobahnEnabled ||
		c.LogLevel != slog.LevelDebug || c.AutobahnRequestInterval != time.Second {
		t.Errorf("config = %+v", c)
	}
}

func TestInvalidConfig(t *testing.T) {
	cases := []struct {
		args []string
		env  map[string]string
	}{
		{env: map[string]string{"TRAFF_POLL_INTERVAL": "soon"}},
		{env: map[string]string{"TRAFF_MAX_FILTERS": "many"}},
		{env: map[string]string{"TRAFF_AUTOBAHN_ENABLED": "perhaps"}},
		{args: []string{"-poll-interval", "1s"}},
		{args: []string{"-subscription-ttl", "10s"}},
		{args: []string{"-message-lease", "5m"}},
		{args: []string{"-max-filters", "-1"}},
		{args: []string{"-log-level", "loud"}},
		{args: []string{"extra"}},
		{args: []string{"-unknown-flag"}},
	}
	for _, c := range cases {
		if _, err := Parse(c.args, env(c.env), io.Discard); err == nil {
			t.Errorf("Parse(%v, %v) accepted", c.args, c.env)
		}
	}
	if _, err := Parse([]string{"-h"}, env(nil), io.Discard); !errors.Is(err, flag.ErrHelp) {
		t.Errorf("-h = %v", err)
	}
}
