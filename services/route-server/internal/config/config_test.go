package config

import (
	"io"
	"log/slog"
	"strings"
	"testing"
	"time"
)

func envOf(m map[string]string) func(string) string { return func(k string) string { return m[k] } }

func TestDefaults(t *testing.T) {
	c, err := Parse(nil, envOf(map[string]string{"ROUTE_TOKENS_FILE": "/config/tokens"}), io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	if c.Listen != ":8080" || c.OSRMURL != "http://127.0.0.1:5000" || c.OSRMTimeout != 10*time.Second ||
		c.TokensFile != "/config/tokens" || c.LogLevel != slog.LevelInfo || c.ReloadTokens != 30*time.Second {
		t.Fatalf("%+v", c)
	}
	if c.Server.MaxPoints != 25 || c.Server.RatePerMinute != 30 || c.Server.RateBurst != 60 || c.Server.MaxConcurrent != 8 ||
		c.Server.MaxRequestBytes != 4096 {
		t.Fatalf("%+v", c.Server)
	}
}

func TestEnvAndFlags(t *testing.T) {
	env := envOf(map[string]string{
		"ROUTE_TOKENS_FILE": "/a", "ROUTE_OSRM_URL": "http://osrm:5000", "ROUTE_MAX_POINTS": "10",
		"ROUTE_RATE_PER_MINUTE": "12", "ROUTE_RATE_BURST": "40", "ROUTE_LOG_LEVEL": "debug", "ROUTE_OSRM_TIMEOUT": "3s",
	})
	c, err := Parse([]string{"-max-points", "5", "-tokens-file", "/b"}, env, io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	if c.OSRMURL != "http://osrm:5000" || c.TokensFile != "/b" || c.Server.MaxPoints != 5 || c.Server.RatePerMinute != 12 ||
		c.Server.RateBurst != 40 || c.LogLevel != slog.LevelDebug || c.OSRMTimeout != 3*time.Second {
		t.Fatalf("%+v", c)
	}
}

func TestErrors(t *testing.T) {
	cases := map[string]map[string]string{
		"no token file":         {},
		"max-points":            {"ROUTE_TOKENS_FILE": "/a", "ROUTE_MAX_POINTS": "1"},
		"burst":                 {"ROUTE_TOKENS_FILE": "/a", "ROUTE_RATE_BURST": "0"},
		"ROUTE_OSRM_TIMEOUT":    {"ROUTE_TOKENS_FILE": "/a", "ROUTE_OSRM_TIMEOUT": "soon"},
		"log-level":             {"ROUTE_TOKENS_FILE": "/a", "ROUTE_LOG_LEVEL": "loud"},
		"ROUTE_RATE_PER_MINUTE": {"ROUTE_TOKENS_FILE": "/a", "ROUTE_RATE_PER_MINUTE": "many"},
	}
	for want, env := range cases {
		_, err := Parse(nil, envOf(env), io.Discard)
		if err == nil || !strings.Contains(err.Error(), want) {
			t.Errorf("%s: err = %v", want, err)
		}
	}
	if _, err := Parse([]string{"extra"}, envOf(map[string]string{"ROUTE_TOKENS_FILE": "/a"}), io.Discard); err == nil {
		t.Error("extra argument accepted")
	}
}
