package config

import (
	"errors"
	"flag"
	"io"
	"log/slog"
	"os"
	"path/filepath"
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

func TestDatex2AndDedupDefaults(t *testing.T) {
	c, err := Parse(nil, env(nil), io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	if c.Datex2Feeds != nil || c.Datex2Interval != 0 || c.Datex2Horizon != DefaultDatex2Horizon || c.Datex2Timeout != DefaultDatex2Timeout ||
		!c.DedupEnabled || c.DedupDistance != DefaultDedupDistance {
		t.Errorf("defaults = %+v", c)
	}
}

func TestDatex2Feeds(t *testing.T) {
	dir := t.TempDir()
	pwFile := filepath.Join(dir, "pw")
	if err := os.WriteFile(pwFile, []byte("from-file\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	e := env(map[string]string{
		"TRAFF_DATEX2_FEEDS":                    "bw-incidents=https://api.example.org/a.xml, mobilithek=https://mobilithek.info:8443/x?id=1\nlocal=file:///data/feed.xml",
		"TRAFF_DATEX2_INTERVAL":                 "10m",
		"TRAFF_DATEX2_HORIZON":                  "48h",
		"TRAFF_DATEX2_TIMEOUT":                  "2m",
		"TRAFF_DATEX2_BW_INCIDENTS_USERNAME":    " alice ",
		"TRAFF_DATEX2_BW_INCIDENTS_PASSWORD":    "secret",
		"TRAFF_DATEX2_MOBILITHEK_PASSWORD_FILE": pwFile,
		"TRAFF_DATEX2_CLIENT_CERT":              "/run/secrets/cert.pem",
		"TRAFF_DATEX2_CLIENT_KEY":               "/run/secrets/key.pem",
		"TRAFF_DATEX2_LOCAL_CLIENT_CERT":        "/other/cert.pem",
		"TRAFF_DATEX2_LOCAL_CLIENT_KEY":         "/other/key.pem",
		"TRAFF_DATEX2_MOBILITHEK_CA_CERT":       "/run/secrets/ca.pem",
		"TRAFF_DEDUP_ENABLED":                   "false",
		"TRAFF_DEDUP_DISTANCE":                  "750",
	})
	c, err := Parse(nil, e, io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	if len(c.Datex2Feeds) != 3 || c.Datex2Interval != 10*time.Minute || c.Datex2Horizon != 48*time.Hour || c.Datex2Timeout != 2*time.Minute ||
		c.DedupEnabled || c.DedupDistance != 750 {
		t.Fatalf("config = %+v", c)
	}
	bw, mob, local := c.Datex2Feeds[0], c.Datex2Feeds[1], c.Datex2Feeds[2]
	if bw.Name != "bw-incidents" || bw.URL != "https://api.example.org/a.xml" || bw.Username != "alice" || bw.Password != "secret" ||
		bw.ClientCert != "/run/secrets/cert.pem" || bw.CACert != "" {
		t.Errorf("bw = %+v", bw)
	}
	if mob.Password != "from-file" || mob.Username != "" || mob.CACert != "/run/secrets/ca.pem" || mob.URL != "https://mobilithek.info:8443/x?id=1" {
		t.Errorf("mobilithek = %+v", mob)
	}
	if local.ClientCert != "/other/cert.pem" || local.ClientKey != "/other/key.pem" || local.Password != "" {
		t.Errorf("local = %+v", local)
	}
	// Flags take precedence.
	c, err = Parse([]string{"-datex2-feeds", "x=http://example.org/", "-dedup=true", "-dedup-distance", "100"}, e, io.Discard)
	if err != nil || len(c.Datex2Feeds) != 1 || c.Datex2Feeds[0].Name != "x" || !c.DedupEnabled || c.DedupDistance != 100 {
		t.Errorf("flags: %+v %v", c, err)
	}
	if FeedEnvPrefix("bw-roadworks") != "TRAFF_DATEX2_BW_ROADWORKS_" {
		t.Error(FeedEnvPrefix("bw-roadworks"))
	}
}

func TestDatex2FeedErrors(t *testing.T) {
	dir := t.TempDir()
	cases := []map[string]string{
		{"TRAFF_DATEX2_FEEDS": "https://example.org/"},
		{"TRAFF_DATEX2_FEEDS": "Bad=https://example.org/"},
		{"TRAFF_DATEX2_FEEDS": "a=https://example.org/,a=https://example.com/"},
		{"TRAFF_DATEX2_FEEDS": "a=ftp://example.org/"},
		{"TRAFF_DATEX2_FEEDS": "a=https:///x"},
		{"TRAFF_DATEX2_FEEDS": "a=http://bad host/"},
		{"TRAFF_DATEX2_FEEDS": "a=https://example.org/", "TRAFF_DATEX2_A_PASSWORD": "x", "TRAFF_DATEX2_A_PASSWORD_FILE": "/x"},
		{"TRAFF_DATEX2_FEEDS": "a=https://example.org/", "TRAFF_DATEX2_A_PASSWORD_FILE": filepath.Join(dir, "missing")},
		{"TRAFF_DATEX2_FEEDS": "a=https://example.org/", "TRAFF_DATEX2_CLIENT_CERT": "/c.pem"},
		{"TRAFF_DATEX2_INTERVAL": "-1m"},
		{"TRAFF_DATEX2_TIMEOUT": "0s"},
		{"TRAFF_DEDUP_DISTANCE": "0"},
		{"TRAFF_DEDUP_ENABLED": "maybe"},
	}
	for _, c := range cases {
		_, err := Parse(nil, env(c), io.Discard)
		if err == nil {
			t.Errorf("%v accepted", c)
			continue
		}
		// Error messages never contain secrets.
		if strings.Contains(err.Error(), "TRAFF_DATEX2_A_PASSWORD_FILE") && strings.Contains(err.Error(), "missing") {
			t.Errorf("error reveals file path: %v", err)
		}
	}
}
