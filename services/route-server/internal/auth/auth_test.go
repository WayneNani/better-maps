package auth

import (
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const tokenA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
const tokenB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

func TestParseAndLookup(t *testing.T) {
	file := "# devices\n" + HashLine("pixel", tokenA) + "  # phone\n\n" + HashLine("tablet", tokenB) + "\n"
	s, err := Parse(strings.NewReader(file))
	if err != nil {
		t.Fatal(err)
	}
	if s.Len() != 2 {
		t.Fatalf("Len = %d", s.Len())
	}
	for token, want := range map[string]string{tokenA: "pixel", tokenB: "tablet"} {
		got, ok := s.Lookup(token)
		if !ok || got != want {
			t.Errorf("Lookup(%s) = %q, %v; want %q", token[:1], got, ok, want)
		}
	}
	for _, bad := range []string{"", "short", strings.Repeat("c", 43), tokenA + "x", strings.Repeat("a", 300)} {
		if label, ok := s.Lookup(bad); ok {
			t.Errorf("Lookup(%q) accepted as %q", bad, label)
		}
	}
}

func TestParseErrorsDoNotEchoValues(t *testing.T) {
	cases := map[string]string{
		"pixel " + tokenA:             "sha256:",
		"pixel":                       "expected",
		"pi xel sha256:00":            "expected",
		"bad/label sha256:" + hex64(): "invalid label",
		"pixel sha256:abc":            "64 hex",
		"pixel sha256:" + hex64() + "\n" + "pixel sha256:" + hex64(): "duplicate",
	}
	for in, want := range cases {
		_, err := Parse(strings.NewReader(in))
		if err == nil {
			t.Errorf("Parse(%q) succeeded", in)
			continue
		}
		if !strings.Contains(err.Error(), want) {
			t.Errorf("Parse(%q) = %v, want %q", in, err, want)
		}
		if strings.Contains(err.Error(), tokenA) {
			t.Errorf("error echoes the token: %v", err)
		}
	}
}

func hex64() string { return strings.Repeat("0f", 32) }

func TestEmptyStoreRejectsEverything(t *testing.T) {
	if _, ok := NewStore().Lookup(tokenA); ok {
		t.Fatal("empty store accepted a token")
	}
}

func TestReplace(t *testing.T) {
	s := NewStore()
	other, err := Parse(strings.NewReader(HashLine("pixel", tokenA)))
	if err != nil {
		t.Fatal(err)
	}
	s.Replace(other)
	if _, ok := s.Lookup(tokenA); !ok {
		t.Fatal("token not known after Replace")
	}
}

func TestLoad(t *testing.T) {
	path := filepath.Join(t.TempDir(), "tokens")
	if err := os.WriteFile(path, []byte(HashLine("pixel", tokenA)+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	s, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if label, ok := s.Lookup(tokenA); !ok || label != "pixel" {
		t.Fatalf("Lookup = %q, %v", label, ok)
	}
	if _, err := Load(filepath.Join(t.TempDir(), "missing")); err == nil {
		t.Fatal("Load of a missing file succeeded")
	}
}

func TestBearerToken(t *testing.T) {
	cases := map[string]string{
		"Bearer " + tokenA:  tokenA,
		"bearer  " + tokenA: tokenA,
		"Basic " + tokenA:   "",
		"Bearer":            "",
		"Bearer   ":         "",
		"":                  "",
		tokenA:              "",
	}
	for header, want := range cases {
		r := httptest.NewRequest("POST", "/v1/route", nil)
		if header != "" {
			r.Header.Set("Authorization", header)
		}
		got, err := BearerToken(r)
		if got != want || (want == "") != (err != nil) {
			t.Errorf("BearerToken(%q) = %q, %v; want %q", header, got, err, want)
		}
	}
}

func TestNewToken(t *testing.T) {
	a, err := NewToken()
	if err != nil {
		t.Fatal(err)
	}
	b, _ := NewToken()
	if a == b || len(a) != 43 || len(a) < MinTokenLength {
		t.Fatalf("tokens %q %q", a, b)
	}
	s, err := Parse(strings.NewReader(HashLine("x", a)))
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := s.Lookup(a); !ok {
		t.Fatal("generated token not accepted")
	}
}
