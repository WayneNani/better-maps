package crowd

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"maps"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

// Name is the source name.
const Name = "crowd"

// Defaults.
const (
	DefaultInterval      = 2 * time.Minute
	DefaultTimeout       = 20 * time.Second
	DefaultMergeDistance = 25   // metres
	DefaultMaxLength     = 5000 // metres
)

// ExportPath is the path of the JSON export on the crowd-traffic server.
const ExportPath = "/v1/segments"

// maxBody limits the size of an export (a segment takes about 800 bytes). A variable for tests.
var maxBody int64 = 64 << 20

// Germany, slightly padded: the default coverage of the crowd-traffic server.
var germany = traff.BBox{MinLat: 47.2, MinLon: 5.8, MaxLat: 55.1, MaxLon: 15.1}

// Config configures the source.
type Config struct {
	// URL is the base URL of the crowd-traffic server, e.g. "http://crowd-traffic:8080". The
	// export path /v1/segments is appended unless the URL already ends with it; a query (e.g.
	// "?bbox=…") is kept.
	URL string
	// Interval is the fetch interval (DefaultInterval if zero). The server evaluates its windows
	// every 15 minutes, but the windows of different segments end at different times.
	Interval time.Duration
	// Timeout limits one download (DefaultTimeout if zero).
	Timeout   time.Duration
	UserAgent string
	Merge     MergeOptions
	// Client is the HTTP client (http.DefaultClient if nil).
	Client *http.Client
}

// ExportURL returns the URL of the JSON export for a configured base URL.
func ExportURL(base string) (string, error) {
	u, err := url.Parse(base)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return "", fmt.Errorf("crowd: invalid URL %q (http or https required)", base)
	}
	if !strings.HasSuffix(u.Path, ExportPath) {
		u.Path = strings.TrimRight(u.Path, "/") + ExportPath
	}
	return u.String(), nil
}

// Source fetches the crowd-traffic export. It implements source.Source,
// source.IntervalSource and source.DetailsReporter.
type Source struct {
	cfg Config
	url string

	mu       sync.Mutex
	last     *Document // last valid export
	counters map[string]int
	stats    MapStats
	age      time.Duration // age of the last valid export at the last fetch
}

// NewSource creates a source.
func NewSource(cfg Config) (*Source, error) {
	u, err := ExportURL(cfg.URL)
	if err != nil {
		return nil, err
	}
	if cfg.Interval <= 0 {
		cfg.Interval = DefaultInterval
	}
	if cfg.Timeout <= 0 {
		cfg.Timeout = DefaultTimeout
	}
	if cfg.Client == nil {
		cfg.Client = http.DefaultClient
	}
	return &Source{cfg: cfg, url: u, counters: map[string]int{}}, nil
}

// Name implements source.Source.
func (s *Source) Name() string { return Name }

// Coverage implements source.Source.
func (s *Source) Coverage() []traff.BBox { return []traff.BBox{germany} }

// Interval implements source.IntervalSource.
func (s *Source) Interval() time.Duration { return s.cfg.Interval }

// Fetch implements source.Source. It downloads the export and maps it. If the download fails,
// the last export is used again, without the segments that have expired in the meantime (the
// crowd-traffic server publishes a segment for 20 minutes): a failing crowd server does not
// keep stale jams alive. In that case the error is returned together with the messages. Before
// the first successful download, a failure returns no messages.
func (s *Source) Fetch(ctx context.Context, now time.Time) ([]traff.Message, error) {
	doc, err := s.download(ctx)

	s.mu.Lock()
	defer s.mu.Unlock()
	if err != nil {
		s.counters["fetch_errors"]++
		if s.last == nil {
			return nil, err
		}
		err = fmt.Errorf("%w; using the previous export without expired segments", err)
	} else {
		s.counters["downloads"]++
		s.last = doc
	}
	s.age = max(0, now.Sub(s.last.Generated))
	msgs, st := Map(s.last, now, s.cfg.Merge)
	s.stats = st
	return msgs, err
}

func (s *Source) download(ctx context.Context) (*Document, error) {
	ctx, cancel := context.WithTimeout(ctx, s.cfg.Timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.url, nil)
	if err != nil {
		return nil, fmt.Errorf("crowd: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	if s.cfg.UserAgent != "" {
		req.Header.Set("User-Agent", s.cfg.UserAgent)
	}
	resp, err := s.cfg.Client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("crowd: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
		return nil, fmt.Errorf("crowd: HTTP %d", resp.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, maxBody+1))
	if err != nil {
		return nil, fmt.Errorf("crowd: %w", err)
	}
	if int64(len(data)) > maxBody {
		return nil, errors.New("crowd: export too large")
	}
	var doc Document
	if err := json.Unmarshal(data, &doc); err != nil {
		return nil, fmt.Errorf("crowd: invalid export: %w", err)
	}
	if doc.Version != FormatVersion {
		return nil, fmt.Errorf("crowd: unsupported export version %d (want %d)", doc.Version, FormatVersion)
	}
	return &doc, nil
}

// Details implements source.DetailsReporter: downloads and errors (cumulative), and for the
// last fetch the number of segments in the export, congested segments used, skipped segments by
// reason, messages after merging, merge statistics and the age of the export in seconds.
func (s *Source) Details() map[string]int {
	s.mu.Lock()
	defer s.mu.Unlock()
	res := maps.Clone(s.counters)
	if s.last == nil {
		return res
	}
	maps.Copy(res, s.stats.Skipped)
	res["segments"] = s.stats.Segments
	res["congested_segments"] = s.stats.Congested
	res["messages"] = s.stats.Messages
	res["joins"] = s.stats.Merge.Joins
	res["corner_joins"] = s.stats.Merge.CornerJoins
	res["forks"] = s.stats.Merge.Forks
	res["merges"] = s.stats.Merge.Merges
	res["splits"] = s.stats.Merge.Splits
	res["longest_chain"] = s.stats.Merge.Longest
	res["export_age_seconds"] = int(s.age / time.Second)
	return res
}
