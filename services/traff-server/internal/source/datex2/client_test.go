package datex2

import (
	"bytes"
	"compress/gzip"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func gz(t *testing.T, data []byte) []byte {
	t.Helper()
	var buf bytes.Buffer
	w := gzip.NewWriter(&buf)
	if _, err := w.Write(data); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

// feedServer serves a DATEX II document with ETag/Last-Modified, gzip when accepted, and
// optional basic authentication.
type feedServer struct {
	mu       sync.Mutex
	body     []byte
	etag     string
	user     string
	pass     string
	status   int
	requests int
	notMod   int
	gzipped  int
	agents   []string
}

func (f *feedServer) set(body []byte, etag string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.body, f.etag = body, etag
}

func (f *feedServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.requests++
	f.agents = append(f.agents, r.UserAgent())
	if f.user != "" {
		u, p, ok := r.BasicAuth()
		if !ok || u != f.user || p != f.pass {
			w.Header().Set("WWW-Authenticate", `Basic realm="datex"`)
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
	}
	if f.status != 0 {
		http.Error(w, "fail", f.status)
		return
	}
	lm := "Sun, 25 Oct 2026 15:00:00 GMT"
	w.Header().Set("Last-Modified", lm)
	if f.etag != "" {
		w.Header().Set("ETag", f.etag)
		if r.Header.Get("If-None-Match") == f.etag {
			f.notMod++
			w.WriteHeader(http.StatusNotModified)
			return
		}
	} else if r.Header.Get("If-Modified-Since") == lm {
		f.notMod++
		w.WriteHeader(http.StatusNotModified)
		return
	}
	w.Header().Set("Content-Type", "text/xml")
	body := f.body
	if strings.Contains(r.Header.Get("Accept-Encoding"), "gzip") {
		f.gzipped++
		w.Header().Set("Content-Encoding", "gzip")
		var buf bytes.Buffer
		zw := gzip.NewWriter(&buf)
		_, _ = zw.Write(body)
		_ = zw.Close()
		body = buf.Bytes()
	}
	_, _ = w.Write(body)
}

func readFixture(t *testing.T, name string) []byte {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatal(err)
	}
	return data
}

func getAll(t *testing.T, c *Client) (body []byte, notModified bool, err error) {
	t.Helper()
	resp, err := c.Get(context.Background())
	if err != nil {
		return nil, false, err
	}
	if resp.NotModified() {
		return nil, true, nil
	}
	defer resp.Body.Close()
	body, err = io.ReadAll(resp.Body)
	if err == nil {
		c.Commit(resp)
	}
	return body, false, err
}

func TestClientConditionalAndGzip(t *testing.T) {
	doc := readFixture(t, "synthetic_v2.xml")
	fs := &feedServer{body: doc, etag: `"v1"`}
	srv := httptest.NewServer(fs)
	defer srv.Close()
	c, err := NewClient(ClientConfig{Name: "test", URL: srv.URL + "/feed.xml", UserAgent: "ua/1"})
	if err != nil {
		t.Fatal(err)
	}
	body, nm, err := getAll(t, c)
	if err != nil || nm || !bytes.Equal(body, doc) {
		t.Fatalf("first get: %v %v %d bytes", err, nm, len(body))
	}
	if _, nm, err = getAll(t, c); err != nil || !nm {
		t.Errorf("second get not 304: %v %v", nm, err)
	}
	fs.set(doc, `"v2"`)
	if body, nm, err = getAll(t, c); err != nil || nm || len(body) == 0 {
		t.Errorf("changed document: %v %v", nm, err)
	}
	// Without ETag, If-Modified-Since is used.
	fs.set(doc, "")
	if _, nm, err = getAll(t, c); err != nil || !nm {
		t.Errorf("If-Modified-Since: %v %v", nm, err)
	}
	// Reset forgets the validators.
	c.Reset()
	if _, nm, _ = getAll(t, c); nm {
		t.Error("not modified after Reset")
	}
	fs.mu.Lock()
	defer fs.mu.Unlock()
	if fs.gzipped != 3 || fs.notMod != 2 || fs.agents[0] != "ua/1" {
		t.Errorf("gzip=%d 304=%d agents=%v", fs.gzipped, fs.notMod, fs.agents)
	}
}

func TestClientGzipFileAndPlain(t *testing.T) {
	doc := readFixture(t, "container_v3.xml")
	var payload []byte
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/octet-stream")
		_, _ = w.Write(payload)
	}))
	defer srv.Close()
	c, _ := NewClient(ClientConfig{Name: "gz", URL: srv.URL})
	// A .xml.gz file served without Content-Encoding is recognized by its magic number.
	payload = gz(t, doc)
	if body, _, err := getAll(t, c); err != nil || !bytes.Equal(body, doc) {
		t.Errorf("gzip file: %v", err)
	}
	payload = doc
	if body, _, err := getAll(t, c); err != nil || !bytes.Equal(body, doc) {
		t.Errorf("plain: %v", err)
	}
	payload = []byte{0x1f, 0x8b, 0, 0}
	if _, _, err := getAll(t, c); err == nil {
		t.Error("broken gzip accepted")
	}
}

func TestDecompress(t *testing.T) {
	doc := []byte("<x/>")
	rc, err := decompress(io.NopCloser(bytes.NewReader(gz(t, gz(t, doc)))), "gzip")
	if err != nil {
		t.Fatal(err)
	}
	if got, _ := io.ReadAll(rc); !bytes.Equal(got, doc) {
		t.Errorf("double gzip = %q", got)
	}
	if _, err := decompress(io.NopCloser(bytes.NewReader(doc)), "gzip"); err == nil {
		t.Error("invalid gzip with Content-Encoding accepted")
	}
	if _, err := decompress(io.NopCloser(bytes.NewReader(doc)), "br"); err == nil {
		t.Error("unsupported encoding accepted")
	}
	if rc, err := decompress(io.NopCloser(bytes.NewReader(doc)), "identity"); err != nil {
		t.Error(err)
	} else if err := rc.Close(); err != nil {
		t.Error(err)
	}
	// Size limit.
	lr := &limitedReader{r: strings.NewReader("abcdef"), n: 4}
	if data, err := io.ReadAll(lr); !errors.Is(err, errTooLarge) || string(data) != "abcd" {
		t.Errorf("limit: %q %v", data, err)
	}
}

func TestClientBasicAuthAndErrors(t *testing.T) {
	fs := &feedServer{body: readFixture(t, "latin1_v2.xml"), user: "alice", pass: "s3cret"}
	srv := httptest.NewServer(fs)
	defer srv.Close()

	c, _ := NewClient(ClientConfig{Name: "auth", URL: srv.URL})
	_, _, err := getAll(t, c)
	var he *HTTPError
	if !errors.As(err, &he) || he.Status != http.StatusUnauthorized || !strings.Contains(err.Error(), "datex2 auth: HTTP 401") {
		t.Errorf("without credentials: %v", err)
	}
	c, _ = NewClient(ClientConfig{Name: "auth", URL: srv.URL, Username: "alice", Password: "s3cret"})
	if _, _, err := getAll(t, c); err != nil {
		t.Errorf("with credentials: %v", err)
	}
	fs.mu.Lock()
	fs.status = http.StatusServiceUnavailable
	fs.mu.Unlock()
	if _, _, err := getAll(t, c); !errors.As(err, &he) || he.Status != 503 {
		t.Errorf("503: %v", err)
	}

	// Connection errors name the feed, but not the URL (which may contain a token).
	l, _ := net.Listen("tcp", "127.0.0.1:0")
	addr := l.Addr().String()
	l.Close()
	c, _ = NewClient(ClientConfig{Name: "down", URL: "http://" + addr + "/feed?token=SECRET"})
	_, _, err = getAll(t, c)
	if err == nil || strings.Contains(err.Error(), "SECRET") || !strings.Contains(err.Error(), "datex2 down") {
		t.Errorf("connection error: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := c.Get(ctx); err == nil {
		t.Error("cancelled context")
	}
	// Invalid request (control character in user agent).
	c, _ = NewClient(ClientConfig{Name: "bad", URL: srv.URL, UserAgent: "a\nb"})
	if _, err := c.Get(context.Background()); err == nil {
		t.Error("invalid header accepted")
	}
}

func TestClientRedirectDropsCredentials(t *testing.T) {
	var gotAuth []string
	var mu sync.Mutex
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		gotAuth = append(gotAuth, r.Header.Get("Authorization"))
		mu.Unlock()
		_, _ = w.Write([]byte("<x/>"))
	}))
	defer target.Close()
	// 127.0.0.1 vs localhost: different hosts.
	targetURL := strings.Replace(target.URL, "127.0.0.1", "localhost", 1)
	redirects := 0
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/loop" {
			redirects++
			http.Redirect(w, r, "/loop", http.StatusFound)
			return
		}
		if r.URL.Path == "/same" {
			http.Redirect(w, r, target.URL+"/", http.StatusFound)
			return
		}
		http.Redirect(w, r, targetURL+"/", http.StatusFound)
	}))
	defer origin.Close()
	c, _ := NewClient(ClientConfig{Name: "r", URL: origin.URL + "/x", Username: "u", Password: "p"})
	if _, _, err := getAll(t, c); err != nil {
		t.Fatal(err)
	}
	c, _ = NewClient(ClientConfig{Name: "r", URL: origin.URL + "/loop", Username: "u", Password: "p"})
	if _, _, err := getAll(t, c); err == nil || redirects < 5 {
		t.Errorf("redirect loop: %v (%d)", err, redirects)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(gotAuth) != 1 || gotAuth[0] != "" {
		t.Errorf("credentials forwarded to another host: %q", gotAuth)
	}
}

func TestClientFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "pub.xml.gz")
	if err := os.WriteFile(path, gz(t, readFixture(t, "synthetic_v3.xml")), 0o644); err != nil {
		t.Fatal(err)
	}
	c, err := NewClient(ClientConfig{Name: "file", URL: "file://" + path})
	if err != nil {
		t.Fatal(err)
	}
	if body, nm, err := getAll(t, c); err != nil || nm || !bytes.HasPrefix(body, []byte("<?xml")) {
		t.Fatalf("file: %v %v", nm, err)
	}
	if _, nm, err := getAll(t, c); err != nil || !nm {
		t.Errorf("unchanged file: %v %v", nm, err)
	}
	later := time.Now().Add(time.Minute)
	if err := os.Chtimes(path, later, later); err != nil {
		t.Fatal(err)
	}
	if _, nm, err := getAll(t, c); err != nil || nm {
		t.Errorf("touched file: %v %v", nm, err)
	}
	// Opaque form ("file:relative/path") and errors.
	c, _ = NewClient(ClientConfig{Name: "file", URL: "file:" + path})
	if _, _, err := getAll(t, c); err != nil {
		t.Errorf("opaque file URL: %v", err)
	}
	c, _ = NewClient(ClientConfig{Name: "file", URL: "file://" + filepath.Join(dir, "missing.xml")})
	if _, _, err := getAll(t, c); err == nil {
		t.Error("missing file accepted")
	}
	c, _ = NewClient(ClientConfig{Name: "file", URL: "file://" + dir})
	if _, _, err := getAll(t, c); err == nil {
		t.Error("directory accepted")
	}
	bad := filepath.Join(dir, "bad.gz")
	_ = os.WriteFile(bad, []byte{0x1f, 0x8b, 1}, 0o644)
	c, _ = NewClient(ClientConfig{Name: "file", URL: "file://" + bad})
	if _, _, err := getAll(t, c); err == nil {
		t.Error("broken gzip file accepted")
	}
}

// writePEM writes a certificate (and optionally its key) as PEM files.
func writePEM(t *testing.T, dir, name string, der []byte, key *ecdsa.PrivateKey) (certFile, keyFile string) {
	t.Helper()
	certFile = filepath.Join(dir, name+".crt")
	if err := os.WriteFile(certFile, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o600); err != nil {
		t.Fatal(err)
	}
	if key != nil {
		kb, err := x509.MarshalECPrivateKey(key)
		if err != nil {
			t.Fatal(err)
		}
		keyFile = filepath.Join(dir, name+".key")
		if err := os.WriteFile(keyFile, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: kb}), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	return certFile, keyFile
}

func newCert(t *testing.T, tmpl *x509.Certificate, parent *x509.Certificate, parentKey *ecdsa.PrivateKey) ([]byte, *ecdsa.PrivateKey, *x509.Certificate) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	if parent == nil {
		parent, parentKey = tmpl, key
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, parent, &key.PublicKey, parentKey)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return der, key, cert
}

// TestClientCertificate simulates the Mobilithek: the server requires a TLS client certificate
// signed by its CA, and its own certificate is signed by a private CA as well.
func TestClientCertificate(t *testing.T) {
	dir := t.TempDir()
	now := time.Now()
	caTmpl := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "Test CA"}, NotBefore: now.Add(-time.Hour),
		NotAfter: now.Add(time.Hour), IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign}
	caDER, caKey, caCert := newCert(t, caTmpl, nil, nil)
	caFile, _ := writePEM(t, dir, "ca", caDER, nil)

	srvTmpl := &x509.Certificate{SerialNumber: big.NewInt(2), Subject: pkix.Name{CommonName: "server"}, NotBefore: now.Add(-time.Hour),
		NotAfter: now.Add(time.Hour), IPAddresses: []net.IP{net.ParseIP("127.0.0.1")}, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		KeyUsage: x509.KeyUsageDigitalSignature}
	srvDER, srvKey, _ := newCert(t, srvTmpl, caCert, caKey)
	cliTmpl := &x509.Certificate{SerialNumber: big.NewInt(3), Subject: pkix.Name{CommonName: "better-maps"}, NotBefore: now.Add(-time.Hour),
		NotAfter: now.Add(time.Hour), ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth}, KeyUsage: x509.KeyUsageDigitalSignature}
	cliDER, cliKey, _ := newCert(t, cliTmpl, caCert, caKey)
	cliCert, cliKeyFile := writePEM(t, dir, "client", cliDER, cliKey)

	pool := x509.NewCertPool()
	pool.AddCert(caCert)
	var subjects []string
	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		subjects = append(subjects, r.TLS.PeerCertificates[0].Subject.CommonName)
		_, _ = w.Write(readFixture(t, "container_v3.xml"))
	}))
	srv.TLS = &tls.Config{
		Certificates: []tls.Certificate{{Certificate: [][]byte{srvDER}, PrivateKey: srvKey}},
		ClientAuth:   tls.RequireAndVerifyClientCert,
		ClientCAs:    pool,
	}
	srv.Config.ErrorLog = log.New(io.Discard, "", 0) // expected handshake failures
	srv.StartTLS()
	defer srv.Close()

	c, err := NewClient(ClientConfig{Name: "mobilithek", URL: srv.URL, ClientCertFile: cliCert, ClientKeyFile: cliKeyFile, CAFile: caFile})
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := getAll(t, c); err != nil {
		t.Fatalf("with client certificate: %v", err)
	}
	if len(subjects) != 1 || subjects[0] != "better-maps" {
		t.Errorf("server saw %v", subjects)
	}
	// Without client certificate the handshake fails.
	c, _ = NewClient(ClientConfig{Name: "mobilithek", URL: srv.URL, CAFile: caFile})
	if _, _, err := getAll(t, c); err == nil {
		t.Error("request without client certificate succeeded")
	}
	// Without the CA, the server certificate is not trusted.
	c, _ = NewClient(ClientConfig{Name: "mobilithek", URL: srv.URL, ClientCertFile: cliCert, ClientKeyFile: cliKeyFile})
	if _, _, err := getAll(t, c); err == nil {
		t.Error("untrusted server accepted")
	}
}

func TestNewClientErrors(t *testing.T) {
	dir := t.TempDir()
	empty := filepath.Join(dir, "empty.pem")
	_ = os.WriteFile(empty, []byte("no certificates"), 0o600)
	cases := []ClientConfig{
		{Name: "a", URL: "ftp://example.org/x"},
		{Name: "a", URL: "https:///nohost"},
		{Name: "a", URL: "http://bad host/"},
		{Name: "a", URL: "https://example.org", ClientCertFile: filepath.Join(dir, "missing.crt"), ClientKeyFile: filepath.Join(dir, "missing.key")},
		{Name: "a", URL: "https://example.org", CAFile: filepath.Join(dir, "missing.pem")},
		{Name: "a", URL: "https://example.org", CAFile: empty},
	}
	for _, c := range cases {
		if _, err := NewClient(c); err == nil {
			t.Errorf("%+v accepted", c)
		}
	}
	c, err := NewClient(ClientConfig{Name: "ok", URL: "https://example.org/feed"})
	if err != nil || c.http.Timeout != time.Minute {
		t.Errorf("defaults: %v", err)
	}
}
