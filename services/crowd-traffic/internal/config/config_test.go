package config

import (
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func envOf(m map[string]string) func(string) string { return func(k string) string { return m[k] } }

func TestDefaults(t *testing.T) {
	c, err := Parse(nil, envOf(nil), io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	if c.Listen != ":8080" || c.Server.Aggregation.Window != 15*time.Minute || c.Server.Rules.Window != 15*time.Minute ||
		c.Server.Aggregation.K != 10 || c.Server.Rules.Coverage == nil || !c.MasterKeyGenerated || len(c.MasterKey) != 32 ||
		c.KeyPeriod != 7*24*time.Hour || c.LogLevel != slog.LevelInfo {
		t.Fatalf("defaults: %+v", c)
	}
}

func TestEnvAndFlags(t *testing.T) {
	key := strings.Repeat("ab", 32)
	env := envOf(map[string]string{
		"CROWD_LISTEN": ":9000", "CROWD_WINDOW": "60s", "CROWD_GRACE": "5s", "CROWD_K": "5",
		"CROWD_COVERAGE": "world", "CROWD_OHTTP_MASTER_KEY": key, "CROWD_RATE_LIMIT": "10",
		"CROWD_LOG_LEVEL": "debug",
	})
	c, err := Parse([]string{"-listen", ":9100", "-k", "7"}, env, io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	if c.Listen != ":9100" || c.Server.Aggregation.Window != time.Minute || c.Server.Rules.Grace != 5*time.Second ||
		c.Server.Aggregation.K != 7 || c.Server.Rules.Coverage != nil || c.MasterKeyGenerated || c.MasterKey[0] != 0xab ||
		c.Server.RateLimit != 10 || c.LogLevel != slog.LevelDebug {
		t.Fatalf("parsed: %+v", c)
	}
	f := filepath.Join(t.TempDir(), "key")
	os.WriteFile(f, []byte(strings.Repeat("cd", 32)+"\n"), 0o600)
	c, err = Parse([]string{"-ohttp-master-key-file", f, "-coverage", "50,10,51,11"}, envOf(nil), io.Discard)
	if err != nil || c.MasterKey[0] != 0xcd || c.Server.Rules.Coverage.MaxLat != 51 {
		t.Fatalf("key file: %v %+v", err, c)
	}
}

func TestErrors(t *testing.T) {
	cases := []struct {
		args []string
		env  map[string]string
	}{
		{[]string{"-window", "7s"}, nil},
		{[]string{"-window", "17m"}, nil},
		{[]string{"-grace", "20m"}, nil},
		{[]string{"-k", "1"}, nil},
		{[]string{"-key-period", "1m"}, nil},
		{[]string{"-tick", "0s"}, nil},
		{[]string{"-coverage", "1,2,3"}, nil},
		{[]string{"-coverage", "5,5,1,1"}, nil},
		{[]string{"-coverage", "a,b,c,d"}, nil},
		{[]string{"-log-level", "loud"}, nil},
		{[]string{"extra"}, nil},
		{[]string{"-ohttp-master-key-file", "/nonexistent"}, nil},
		{nil, map[string]string{"CROWD_OHTTP_MASTER_KEY": "abcd"}},
		{nil, map[string]string{"CROWD_WINDOW": "soon"}},
		{nil, map[string]string{"CROWD_K": "many"}},
		{[]string{"-unknown"}, nil},
	}
	for _, c := range cases {
		if _, err := Parse(c.args, envOf(c.env), io.Discard); err == nil {
			t.Errorf("%v %v: no error", c.args, c.env)
		}
	}
}
