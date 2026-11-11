package main

import (
	"bytes"
	"io"
	"strings"
	"testing"

	"github.com/WayneNani/better-maps/services/route-server/internal/auth"
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

func TestServeNeedsTokenFile(t *testing.T) {
	err := run(nil, func(string) string { return "" }, io.Discard, io.Discard)
	if err == nil || !strings.Contains(err.Error(), "token file") {
		t.Fatalf("err = %v", err)
	}
	err = run(nil, func(k string) string {
		if k == "ROUTE_TOKENS_FILE" {
			return "/nonexistent/tokens"
		}
		return ""
	}, io.Discard, io.Discard)
	if err == nil {
		t.Fatal("missing token file accepted")
	}
}
