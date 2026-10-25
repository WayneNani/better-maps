package logging

import (
	"bytes"
	"errors"
	"log"
	"strings"
	"testing"
)

func TestRedactIPs(t *testing.T) {
	cases := map[string]string{
		"http: TLS handshake error from 192.0.2.1:54321: EOF": "http: TLS handshake error from [redacted]: EOF",
		"http: panic serving [2001:db8::1]:443: boom":         "http: panic serving [redacted]: boom",
		"accept from 2001:db8:0:0:0:0:0:1 failed":             "accept from [redacted] failed",
		"peer ::1 closed":                                         "peer [redacted] closed",
		"peer fe80::1ff:fe23:4567:890a closed":                    "peer [redacted] closed",
		"at 15:04:05 nothing happened, 3 messages, version 1.2.3": "at 15:04:05 nothing happened, 3 messages, version 1.2.3",
	}
	for in, want := range cases {
		if got := RedactIPs(in); got != want {
			t.Errorf("RedactIPs(%q) = %q, want %q", in, got, want)
		}
	}
}

type failWriter struct{}

func (failWriter) Write([]byte) (int, error) { return 0, errors.New("disk full") }

func TestRedactingWriter(t *testing.T) {
	var buf bytes.Buffer
	l := log.New(RedactingWriter{W: &buf}, "http: ", 0)
	l.Printf("TLS handshake error from %s: EOF", "198.51.100.7:1234")
	if strings.Contains(buf.String(), "198.51") || !strings.Contains(buf.String(), "[redacted]") {
		t.Errorf("log = %q", buf.String())
	}
	if _, err := (RedactingWriter{W: failWriter{}}).Write([]byte("x")); err == nil {
		t.Error("write error swallowed")
	}
}
