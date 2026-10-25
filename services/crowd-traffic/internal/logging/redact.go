// Package logging provides privacy helpers for log output (copied from services/traff-server).
package logging

import (
	"io"
	"regexp"
)

// IP addresses (v4, v6, optionally with port) as they appear in net/http error messages such as
// "http: TLS handshake error from 192.0.2.1:54321: EOF".
// IPv6 is matched in bracketed form ("[2001:db8::1]:443", as printed by net/http), as full
// eight-group address or in compressed form containing "::". Clock times like 15:04:05 are not
// matched.
var reIP = regexp.MustCompile(`\[[0-9a-fA-F:.%a-zA-Z]*:[0-9a-fA-F:.%a-zA-Z]*\](?::\d+)?` +
	`|\b(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\b` +
	`|[0-9a-fA-F:]*::[0-9a-fA-F:.]*` +
	`|\b(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?\b`)

// RedactIPs replaces IP addresses in s with "[redacted]".
func RedactIPs(s string) string { return reIP.ReplaceAllString(s, "[redacted]") }

// RedactingWriter wraps a writer and removes IP addresses from everything written to it. Use it
// as output of the log.Logger given to http.Server.ErrorLog, which would otherwise log client
// addresses.
type RedactingWriter struct{ W io.Writer }

// Write implements io.Writer.
func (r RedactingWriter) Write(p []byte) (int, error) {
	if _, err := r.W.Write([]byte(RedactIPs(string(p)))); err != nil {
		return 0, err
	}
	return len(p), nil
}
