package config

import (
	"io"
	"log/slog"
	"strings"
	"testing"
	"time"
)

func envOf(m map[string]string) func(string) string { return func(k string) string { return m[k] } }

var base = map[string]string{"INDOOR_TOKENS_FILE": "/config/tokens", "INDOOR_DATA_DIR": "/data"}

func with(kv ...string) map[string]string {
	m := map[string]string{}
	for k, v := range base {
		m[k] = v
	}
	for i := 0; i+1 < len(kv); i += 2 {
		m[kv[i]] = kv[i+1]
	}
	return m
}

func TestDefaults(t *testing.T) {
	c, err := Parse(nil, envOf(base), io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	if c.Listen != ":8080" || c.TokensFile != "/config/tokens" || c.DataDir != "/data" || c.LogLevel != slog.LevelInfo ||
		c.ReloadTokens != 30*time.Second || c.ReloadData != time.Minute {
		t.Fatalf("%+v", c)
	}
	if c.Server.RatePerMinute != 120 || c.Server.RateBurst != 60 || c.Server.UnauthorizedPerSecond != 5 || c.Server.UnauthorizedBurst != 20 {
		t.Fatalf("%+v", c.Server)
	}
}

func TestEnvAndFlags(t *testing.T) {
	env := envOf(with("INDOOR_RATE_PER_MINUTE", "12", "INDOOR_RATE_BURST", "40", "INDOOR_LOG_LEVEL", "debug",
		"INDOOR_RELOAD_DATA", "0", "INDOOR_LISTEN", ":9000"))
	c, err := Parse([]string{"-rate-burst", "5", "-tokens-file", "/b", "-data-dir", "/d", "-reload-tokens", "1s"}, env, io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	if c.TokensFile != "/b" || c.DataDir != "/d" || c.Server.RatePerMinute != 12 || c.Server.RateBurst != 5 ||
		c.LogLevel != slog.LevelDebug || c.ReloadData != 0 || c.ReloadTokens != time.Second || c.Listen != ":9000" {
		t.Fatalf("%+v", c)
	}
}

func TestErrors(t *testing.T) {
	cases := map[string]map[string]string{
		"no token file":          {"INDOOR_DATA_DIR": "/d"},
		"no data directory":      {"INDOOR_TOKENS_FILE": "/a"},
		"burst":                  with("INDOOR_RATE_BURST", "0"),
		"INDOOR_RELOAD_DATA":     with("INDOOR_RELOAD_DATA", "soon"),
		"reload":                 with("INDOOR_RELOAD_TOKENS", "-1s"),
		"log-level":              with("INDOOR_LOG_LEVEL", "loud"),
		"INDOOR_RATE_PER_MINUTE": with("INDOOR_RATE_PER_MINUTE", "many"),
	}
	for want, env := range cases {
		_, err := Parse(nil, envOf(env), io.Discard)
		if err == nil || !strings.Contains(err.Error(), want) {
			t.Errorf("%s: err = %v", want, err)
		}
	}
	if _, err := Parse([]string{"extra"}, envOf(base), io.Discard); err == nil {
		t.Error("extra argument accepted")
	}
	if _, err := Parse([]string{"-nope"}, envOf(base), io.Discard); err == nil {
		t.Error("unknown flag accepted")
	}
}
