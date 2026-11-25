package main

import (
	"bytes"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/imagery-proxy/internal/auth"
)

func TestTokenCommand(t *testing.T) {
	var out bytes.Buffer
	if err := run([]string{"token", "pixel-9"}, func(string) string { return "" }, &out, io.Discard); err != nil {
		t.Fatal(err)
	}
	var token, line string
	for _, l := range strings.Split(strings.TrimSpace(out.String()), "\n") {
		if v, ok := strings.CutPrefix(l, "token="); ok {
			token = v
		}
		if v, ok := strings.CutPrefix(l, "line="); ok {
			line = v
		}
	}
	store, err := auth.Parse(strings.NewReader(line))
	if err != nil {
		t.Fatal(err)
	}
	if label, ok := store.Lookup(token); !ok || label != "pixel-9" {
		t.Fatalf("generated token not accepted: %q %v", label, ok)
	}
	if strings.Contains(line, token) {
		t.Fatal("token file line contains the token")
	}
}

func TestTokenCommandRejectsBadLabels(t *testing.T) {
	for _, args := range [][]string{{"token"}, {"token", "a b"}, {"token", "x", "y"}, {"token", strings.Repeat("l", 33)}} {
		if err := run(args, func(string) string { return "" }, io.Discard, io.Discard); err == nil {
			t.Errorf("%v accepted", args)
		}
	}
}

func TestServeConfigErrors(t *testing.T) {
	dir := t.TempDir()
	tokens := filepath.Join(dir, "tokens")
	os.WriteFile(tokens, []byte(auth.HashLine("pixel", strings.Repeat("t", 43))+"\n"), 0o600)
	env := func(m map[string]string) func(string) string { return func(k string) string { return m[k] } }

	if err := run(nil, env(nil), io.Discard, io.Discard); err == nil || !strings.Contains(err.Error(), "token file") {
		t.Fatalf("err = %v", err)
	}
	cases := []map[string]string{
		{"IMAGERY_TOKENS_FILE": "/nonexistent/tokens", "IMAGERY_SOURCES_FILE": "../../config/sources.json"},
		{"IMAGERY_TOKENS_FILE": tokens, "IMAGERY_SOURCES_FILE": "/nonexistent/sources.json"},
		{"IMAGERY_TOKENS_FILE": tokens, "IMAGERY_SOURCES_FILE": "../../config/sources.json", "IMAGERY_UPSTREAM_URL": "ftp://x"},
		{"IMAGERY_TOKENS_FILE": tokens, "IMAGERY_SOURCES_FILE": "../../config/sources.json", "IMAGERY_CACHE_DIR": tokens + "/sub"},
	}
	for _, c := range cases {
		if err := run(nil, env(c), io.Discard, io.Discard); err == nil {
			t.Errorf("%v accepted", c)
		}
	}
}

func TestModTime(t *testing.T) {
	if !modTime("/nonexistent").IsZero() {
		t.Fatal("modtime of a missing file")
	}
	f := filepath.Join(t.TempDir(), "f")
	os.WriteFile(f, nil, 0o600)
	if time.Since(modTime(f)) > time.Minute {
		t.Fatal("wrong modtime")
	}
}
