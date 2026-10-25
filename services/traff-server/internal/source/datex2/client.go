package datex2

import (
	"bufio"
	"compress/gzip"
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

// MaxBodyBytes limits the decompressed size of a publication. The largest German feed seen so
// far (BASt roadworks on motorways) has about 120 MB.
const MaxBodyBytes = 512 << 20

// ClientConfig configures access to one DATEX II feed. Credentials never appear in logs or error
// messages; errors name the feed instead of the URL, which may contain access tokens.
type ClientConfig struct {
	// Name of the feed, used in error messages.
	Name string
	// URL of the publication: http(s):// or file:// (for local files, e.g. tests).
	URL       string
	UserAgent string
	Timeout   time.Duration
	// Username and Password enable HTTP basic authentication.
	Username, Password string
	// ClientCertFile and ClientKeyFile (PEM) enable TLS client authentication, as used by the
	// Mobilithek for subscriptions.
	ClientCertFile, ClientKeyFile string
	// CAFile (PEM) adds trusted root certificates for the feed server.
	CAFile string
}

// Client fetches a DATEX II publication with conditional requests (If-None-Match,
// If-Modified-Since) and transparent gzip decompression.
type Client struct {
	name      string
	url       *url.URL
	userAgent string
	username  string
	password  string
	http      *http.Client

	etag, lastModified string
	fileModTime        time.Time
}

// Response is the result of Client.Get. Body is nil if the publication has not changed since
// the last call of Commit.
type Response struct {
	Body io.ReadCloser

	etag, lastModified string
	fileModTime        time.Time
}

// NotModified reports whether the server confirmed that the publication is unchanged.
func (r *Response) NotModified() bool { return r.Body == nil }

// NewClient validates the configuration and creates a client.
func NewClient(cfg ClientConfig) (*Client, error) {
	u, err := url.Parse(cfg.URL)
	if err != nil {
		return nil, fmt.Errorf("datex2 %s: invalid URL", cfg.Name)
	}
	switch u.Scheme {
	case "http", "https", "file":
	default:
		return nil, fmt.Errorf("datex2 %s: unsupported URL scheme %q", cfg.Name, u.Scheme)
	}
	if u.Scheme != "file" && u.Host == "" {
		return nil, fmt.Errorf("datex2 %s: URL without host", cfg.Name)
	}
	tlsCfg := &tls.Config{MinVersion: tls.VersionTLS12}
	if cfg.ClientCertFile != "" || cfg.ClientKeyFile != "" {
		cert, err := tls.LoadX509KeyPair(cfg.ClientCertFile, cfg.ClientKeyFile)
		if err != nil {
			return nil, fmt.Errorf("datex2 %s: client certificate: %w", cfg.Name, err)
		}
		tlsCfg.Certificates = []tls.Certificate{cert}
	}
	if cfg.CAFile != "" {
		pem, err := os.ReadFile(cfg.CAFile)
		if err != nil {
			return nil, fmt.Errorf("datex2 %s: CA file: %w", cfg.Name, err)
		}
		pool, err := x509.SystemCertPool()
		if err != nil {
			pool = x509.NewCertPool()
		}
		if !pool.AppendCertsFromPEM(pem) {
			return nil, fmt.Errorf("datex2 %s: CA file contains no certificates", cfg.Name)
		}
		tlsCfg.RootCAs = pool
	}
	timeout := cfg.Timeout
	if timeout <= 0 {
		timeout = time.Minute
	}
	tr := http.DefaultTransport.(*http.Transport).Clone()
	tr.TLSClientConfig = tlsCfg
	tr.DisableCompression = true // gzip is handled in Get, also for .xml.gz files
	return &Client{
		name:      cfg.Name,
		url:       u,
		userAgent: cfg.UserAgent,
		username:  cfg.Username,
		password:  cfg.Password,
		http: &http.Client{
			Timeout:   timeout,
			Transport: tr,
			// Never forward credentials to another host.
			CheckRedirect: func(req *http.Request, via []*http.Request) error {
				if len(via) >= 5 {
					return errors.New("too many redirects")
				}
				if req.URL.Host != via[0].URL.Host {
					req.Header.Del("Authorization")
				}
				return nil
			},
		},
	}, nil
}

// HTTPError reports an unexpected HTTP status.
type HTTPError struct {
	Feed   string
	Status int
}

func (e *HTTPError) Error() string { return fmt.Sprintf("datex2 %s: HTTP %d", e.Feed, e.Status) }

// Get fetches the publication. The caller must close Body and call Commit after the body has
// been processed successfully; only then are the cache validators used for later requests.
func (c *Client) Get(ctx context.Context) (*Response, error) {
	if c.url.Scheme == "file" {
		return c.getFile()
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.url.String(), nil)
	if err != nil {
		return nil, fmt.Errorf("datex2 %s: %w", c.name, err)
	}
	req.Header.Set("Accept", "application/xml, text/xml, */*")
	req.Header.Set("Accept-Encoding", "gzip")
	if c.userAgent != "" {
		req.Header.Set("User-Agent", c.userAgent)
	}
	if c.username != "" || c.password != "" {
		req.SetBasicAuth(c.username, c.password)
	}
	if c.etag != "" {
		req.Header.Set("If-None-Match", c.etag)
	}
	if c.lastModified != "" {
		req.Header.Set("If-Modified-Since", c.lastModified)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		var ue *url.Error
		if errors.As(err, &ue) {
			err = ue.Err // drop the URL, it may contain access tokens
		}
		return nil, fmt.Errorf("datex2 %s: %w", c.name, err)
	}
	switch {
	case resp.StatusCode == http.StatusNotModified && (c.etag != "" || c.lastModified != ""):
		drain(resp.Body)
		return &Response{etag: c.etag, lastModified: c.lastModified}, nil
	case resp.StatusCode != http.StatusOK:
		drain(resp.Body)
		return nil, &HTTPError{Feed: c.name, Status: resp.StatusCode}
	}
	body, err := decompress(resp.Body, resp.Header.Get("Content-Encoding"))
	if err != nil {
		drain(resp.Body)
		return nil, fmt.Errorf("datex2 %s: %w", c.name, err)
	}
	return &Response{Body: body, etag: resp.Header.Get("ETag"), lastModified: resp.Header.Get("Last-Modified")}, nil
}

func (c *Client) getFile() (*Response, error) {
	path := c.url.Path
	if path == "" {
		path = c.url.Opaque
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("datex2 %s: %w", c.name, err)
	}
	st, err := f.Stat()
	if err != nil {
		f.Close()
		return nil, fmt.Errorf("datex2 %s: %w", c.name, err)
	}
	if !c.fileModTime.IsZero() && st.ModTime().Equal(c.fileModTime) {
		f.Close()
		return &Response{fileModTime: c.fileModTime}, nil
	}
	body, err := decompress(f, "")
	if err != nil {
		f.Close()
		return nil, fmt.Errorf("datex2 %s: %w", c.name, err)
	}
	return &Response{Body: body, fileModTime: st.ModTime()}, nil
}

// Commit stores the cache validators of a successfully processed response.
func (c *Client) Commit(r *Response) {
	c.etag, c.lastModified, c.fileModTime = r.etag, r.lastModified, r.fileModTime
}

// Reset forgets the cache validators, so that the next Get fetches the full publication.
func (c *Client) Reset() { c.Commit(&Response{}) }

func drain(r io.ReadCloser) {
	_, _ = io.Copy(io.Discard, io.LimitReader(r, 64<<10))
	r.Close()
}

// readCloser combines a reader with the closer of the underlying stream.
type readCloser struct {
	io.Reader
	closer io.Closer
}

func (r readCloser) Close() error { return r.closer.Close() }

// errTooLarge is returned when a publication exceeds MaxBodyBytes.
var errTooLarge = errors.New("publication exceeds size limit")

type limitedReader struct {
	r io.Reader
	n int64
}

func (l *limitedReader) Read(p []byte) (int, error) {
	if l.n <= 0 {
		return 0, errTooLarge
	}
	if int64(len(p)) > l.n {
		p = p[:l.n]
	}
	n, err := l.r.Read(p)
	l.n -= int64(n)
	return n, err
}

// decompress unwraps gzip, announced by Content-Encoding or recognized by its magic number
// (files such as publication.xml.gz served as application/octet-stream). At most two layers are
// removed.
func decompress(rc io.ReadCloser, contentEncoding string) (io.ReadCloser, error) {
	var r io.Reader = rc
	switch strings.ToLower(strings.TrimSpace(contentEncoding)) {
	case "", "identity":
	case "gzip", "x-gzip":
		zr, err := gzip.NewReader(r)
		if err != nil {
			return nil, err
		}
		r = zr
	default:
		return nil, fmt.Errorf("unsupported content encoding %q", contentEncoding)
	}
	for range 2 {
		br := bufio.NewReader(r)
		r = br
		magic, err := br.Peek(2)
		if err != nil || magic[0] != 0x1f || magic[1] != 0x8b {
			break
		}
		zr, err := gzip.NewReader(br)
		if err != nil {
			return nil, err
		}
		r = zr
	}
	return readCloser{Reader: &limitedReader{r: r, n: MaxBodyBytes}, closer: rc}, nil
}
