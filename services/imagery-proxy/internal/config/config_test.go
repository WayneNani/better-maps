package config

import (
	"io"
	"log/slog"
	"strings"
	"testing"
	"time"
)

func envOf(m map[string]string) func(string) string { return func(k string) string { return m[k] } }

var minimal = map[string]string{"IMAGERY_TOKENS_FILE": "/config/tokens", "IMAGERY_SOURCES_FILE": "/config/sources.json"}

func with(extra map[string]string) map[string]string {
	m := map[string]string{}
	for k, v := range minimal {
		m[k] = v
	}
	for k, v := range extra {
		m[k] = v
	}
	return m
}

func TestDefaults(t *testing.T) {
	c, err := Parse(nil, envOf(minimal), io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	if c.Listen != ":8080" || c.UpstreamURL != "http://127.0.0.1:8081" || c.UpstreamLayer != "aerial" ||
		c.UpstreamGrid != "webmercator" || c.TokensFile != "/config/tokens" || c.SourcesFile != "/config/sources.json" ||
		c.CacheDir != "" || c.CacheMaxBytes != 1<<30 || c.CacheMaxAge != 30*24*time.Hour ||
		c.LogLevel != slog.LevelInfo || c.ReloadTokens != 30*time.Second {
		t.Fatalf("%+v", c)
	}
	if c.Server.MaxZoom != 19 || c.Server.RatePerMinute != 600 || c.Server.RateBurst != 300 ||
		c.Server.MaxConcurrent != 4 || c.Server.UpstreamTimeout != 30*time.Second {
		t.Fatalf("%+v", c.Server)
	}
}

func TestEnvAndFlags(t *testing.T) {
	env := envOf(with(map[string]string{
		"IMAGERY_UPSTREAM_URL": "http://mapproxy:8080", "IMAGERY_MAX_ZOOM": "18", "IMAGERY_RATE_PER_MINUTE": "120",
		"IMAGERY_RATE_BURST": "40", "IMAGERY_LOG_LEVEL": "debug", "IMAGERY_UPSTREAM_TIMEOUT": "3s",
		"IMAGERY_CACHE_DIR": "/cache", "IMAGERY_CACHE_MAX_MB": "200", "IMAGERY_CACHE_MAX_AGE": "48h",
		"IMAGERY_MAX_CONCURRENT": "2",
	}))
	c, err := Parse([]string{"-max-zoom", "17", "-tokens-file", "/b"}, env, io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	if c.UpstreamURL != "http://mapproxy:8080" || c.TokensFile != "/b" || c.Server.MaxZoom != 17 ||
		c.Server.RatePerMinute != 120 || c.Server.RateBurst != 40 || c.LogLevel != slog.LevelDebug ||
		c.Server.UpstreamTimeout != 3*time.Second || c.CacheDir != "/cache" || c.CacheMaxBytes != 200<<20 ||
		c.CacheMaxAge != 48*time.Hour || c.Server.MaxConcurrent != 2 {
		t.Fatalf("%+v", c)
	}
}

func TestErrors(t *testing.T) {
	cases := map[string]map[string]string{
		"no token file":            {"IMAGERY_SOURCES_FILE": "/s"},
		"no sources file":          {"IMAGERY_TOKENS_FILE": "/t"},
		"max-zoom":                 with(map[string]string{"IMAGERY_MAX_ZOOM": "23"}),
		"rate-burst":               with(map[string]string{"IMAGERY_RATE_BURST": "0"}),
		"IMAGERY_UPSTREAM_TIMEOUT": with(map[string]string{"IMAGERY_UPSTREAM_TIMEOUT": "soon"}),
		"upstream-timeout":         with(map[string]string{"IMAGERY_UPSTREAM_TIMEOUT": "0s"}),
		"log-level":                with(map[string]string{"IMAGERY_LOG_LEVEL": "loud"}),
		"IMAGERY_RATE_PER_MINUTE":  with(map[string]string{"IMAGERY_RATE_PER_MINUTE": "many"}),
		"max-concurrent":           with(map[string]string{"IMAGERY_MAX_CONCURRENT": "0"}),
		"cache-max-mb":             with(map[string]string{"IMAGERY_CACHE_MAX_MB": "-1"}),
		"cache-max-age":            with(map[string]string{"IMAGERY_CACHE_MAX_AGE": "-1h"}),
		"reload-tokens":            with(map[string]string{"IMAGERY_RELOAD_TOKENS": "-1s"}),
	}
	for want, env := range cases {
		_, err := Parse(nil, envOf(env), io.Discard)
		if err == nil || !strings.Contains(err.Error(), want) {
			t.Errorf("%s: err = %v", want, err)
		}
	}
	if _, err := Parse([]string{"extra"}, envOf(minimal), io.Discard); err == nil {
		t.Error("extra argument accepted")
	}
	if _, err := Parse([]string{"-nope"}, envOf(minimal), io.Discard); err == nil {
		t.Error("unknown flag accepted")
	}
}
