// Package dataset loads the venue data written by indoor-import (index.json and
// venues/<id>.json.gz) into memory for the gateway.
package dataset

import (
	"bytes"
	"cmp"
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"os"
	"path/filepath"
	"slices"
	"time"

	"github.com/WayneNani/better-maps/services/indoor-server/internal/venues"
)

// Limits against a broken or hostile data directory.
const (
	maxIndexBytes = 64 << 20
	maxDocBytes   = 64 << 20 // compressed and uncompressed, per venue
)

// Venue is a venue of the index with its precompressed document.
type Venue struct {
	venues.IndexEntry
	ETag       string // strong ETag of the uncompressed document, quoted
	Gzip       []byte // the document, gzip-compressed
	PlainBytes int    // size of the uncompressed document
}

// Dataset is an immutable set of venues.
type Dataset struct {
	DataTimestamp string
	Venues        []*Venue // sorted by number of features (descending), then id
	byID          map[string]*Venue
	IndexModTime  time.Time
	GzipBytes     int64
	PlainBytes    int64
}

// Empty returns a dataset without venues.
func Empty() *Dataset { return &Dataset{byID: map[string]*Venue{}} }

// IndexPath is the file whose modification time tells that the data changed.
func IndexPath(dir string) string { return filepath.Join(dir, "index.json") }

// Load reads dir/index.json and every venue document. Each document is decompressed once to
// verify it and compute its ETag.
func Load(dir string) (*Dataset, error) {
	st, err := os.Stat(IndexPath(dir))
	if err != nil {
		return nil, err
	}
	raw, err := readLimited(IndexPath(dir), maxIndexBytes)
	if err != nil {
		return nil, err
	}
	var idx venues.Index
	if err := json.Unmarshal(raw, &idx); err != nil {
		return nil, fmt.Errorf("index.json: %w", err)
	}
	ds := &Dataset{DataTimestamp: idx.DataTimestamp, byID: make(map[string]*Venue, len(idx.Venues)), IndexModTime: st.ModTime()}
	for _, e := range idx.Venues {
		if !venues.ValidID(e.ID) {
			return nil, fmt.Errorf("index.json: invalid venue id %q", e.ID)
		}
		if _, dup := ds.byID[e.ID]; dup {
			return nil, fmt.Errorf("index.json: duplicate venue id %q", e.ID)
		}
		if !validBBox(e.BBox) {
			return nil, fmt.Errorf("index.json: venue %s: invalid bbox", e.ID)
		}
		gz, err := readLimited(filepath.Join(dir, "venues", e.ID+".json.gz"), maxDocBytes)
		if err != nil {
			return nil, err
		}
		plain, err := gunzip(gz)
		if err != nil {
			return nil, fmt.Errorf("venue %s: %w", e.ID, err)
		}
		if e.Levels == nil {
			e.Levels = []venues.Level{}
		}
		sum := sha256.Sum256(plain)
		v := &Venue{IndexEntry: e, ETag: `"` + hex.EncodeToString(sum[:10]) + `"`, Gzip: gz, PlainBytes: len(plain)}
		ds.Venues = append(ds.Venues, v)
		ds.byID[e.ID] = v
		ds.GzipBytes += int64(len(gz))
		ds.PlainBytes += int64(len(plain))
	}
	slices.SortStableFunc(ds.Venues, func(a, b *Venue) int {
		if n := cmp.Compare(b.Features, a.Features); n != 0 {
			return n
		}
		return cmp.Compare(a.ID, b.ID)
	})
	return ds, nil
}

func validBBox(b [4]float64) bool {
	for _, v := range b {
		if math.IsNaN(v) || math.IsInf(v, 0) {
			return false
		}
	}
	return b[0] <= b[2] && b[1] <= b[3] && b[0] >= -180 && b[2] <= 180 && b[1] >= -90 && b[3] <= 90
}

func readLimited(path string, limit int64) ([]byte, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	b, err := io.ReadAll(io.LimitReader(f, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(b)) > limit {
		return nil, fmt.Errorf("%s: larger than %d bytes", filepath.Base(path), limit)
	}
	return b, nil
}

func gunzip(gz []byte) ([]byte, error) {
	r, err := gzip.NewReader(bytes.NewReader(gz))
	if err != nil {
		return nil, err
	}
	b, err := io.ReadAll(io.LimitReader(r, maxDocBytes+1))
	if err != nil {
		return nil, err
	}
	if len(b) > maxDocBytes {
		return nil, errors.New("document too large")
	}
	return b, nil
}

// Plain returns the uncompressed document of v.
func (v *Venue) Plain() ([]byte, error) { return gunzip(v.Gzip) }

// Get returns the venue with id.
func (d *Dataset) Get(id string) (*Venue, bool) {
	v, ok := d.byID[id]
	return v, ok
}

// Len returns the number of venues.
func (d *Dataset) Len() int { return len(d.Venues) }

// Query returns the venues whose bbox intersects [minLon, minLat, maxLon, maxLat], at most limit,
// the largest (most features) first.
func (d *Dataset) Query(bbox [4]float64, limit int) []*Venue {
	var out []*Venue
	for _, v := range d.Venues { // already sorted largest first
		b := v.BBox
		if b[0] <= bbox[2] && bbox[0] <= b[2] && b[1] <= bbox[3] && bbox[1] <= b[3] {
			out = append(out, v)
			if len(out) == limit {
				break
			}
		}
	}
	return out
}
