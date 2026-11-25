package dataset

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/WayneNani/better-maps/services/indoor-server/internal/venues"
)

// Write creates a data directory with the given venues (documents are minimal JSON).
func write(t *testing.T, dir string, idx venues.Index, docs map[string]string) {
	t.Helper()
	os.MkdirAll(filepath.Join(dir, "venues"), 0o755)
	b, _, err := venues.Marshal(idx)
	if err != nil {
		t.Fatal(err)
	}
	os.WriteFile(filepath.Join(dir, "index.json"), b, 0o644)
	for id, doc := range docs {
		_, gz, _ := venues.Marshal(rawDoc(doc))
		os.WriteFile(filepath.Join(dir, "venues", id+".json.gz"), gz, 0o644)
	}
}

type rawDoc string

func (j rawDoc) MarshalJSON() ([]byte, error) { return []byte(j), nil }

func entry(id string, features int, bbox [4]float64) venues.IndexEntry {
	return venues.IndexEntry{ID: id, Kind: "other", BBox: bbox, Features: features}
}

func TestLoadAndQuery(t *testing.T) {
	dir := t.TempDir()
	write(t, dir, venues.Index{DataTimestamp: "2026-11-24T21:21:02Z", Venues: []venues.IndexEntry{
		entry("w1", 10, [4]float64{8.56, 50.04, 8.58, 50.06}),
		entry("w2", 30, [4]float64{8.57, 50.05, 8.59, 50.07}),
		entry("cw3", 5, [4]float64{6.95, 50.94, 6.96, 50.95}),
	}}, map[string]string{"w1": `{"id":"w1"}`, "w2": `{"id":"w2"}`, "cw3": `{"id":"cw3"}`})
	ds, err := Load(dir)
	if err != nil {
		t.Fatal(err)
	}
	if ds.Len() != 3 || ds.DataTimestamp != "2026-11-24T21:21:02Z" || ds.Venues[0].ID != "w2" || ds.IndexModTime.IsZero() {
		t.Fatalf("%+v", ds)
	}
	v, ok := ds.Get("w1")
	if !ok || v.PlainBytes != len(`{"id":"w1"}`) || len(v.ETag) != 22 || !strings.HasPrefix(v.ETag, `"`) || v.Levels == nil {
		t.Fatalf("%+v", v)
	}
	if p, err := v.Plain(); err != nil || string(p) != `{"id":"w1"}` {
		t.Fatal(string(p), err)
	}
	w2, _ := ds.Get("w2")
	if v.ETag == w2.ETag {
		t.Fatal("same etag for different documents")
	}
	ids := func(vs []*Venue) string {
		var s []string
		for _, v := range vs {
			s = append(s, v.ID)
		}
		return strings.Join(s, ",")
	}
	if got := ids(ds.Query([4]float64{8.5, 50, 8.65, 50.08}, 100)); got != "w2,w1" {
		t.Fatal(got)
	}
	if got := ids(ds.Query([4]float64{8.5, 50, 8.65, 50.08}, 1)); got != "w2" {
		t.Fatal(got)
	}
	if got := ids(ds.Query([4]float64{8.585, 50.065, 8.6, 50.08}, 100)); got != "w2" {
		t.Fatal(got)
	}
	if got := ids(ds.Query([4]float64{6.96, 50.95, 6.97, 50.96}, 100)); got != "cw3" { // touching corner
		t.Fatal(got)
	}
	if got := ids(ds.Query([4]float64{0, 0, 1, 1}, 100)); got != "" {
		t.Fatal(got)
	}
	if _, ok := ds.Get("w9"); ok || Empty().Len() != 0 {
		t.Fatal("unknown id")
	}
}

func TestLoadErrors(t *testing.T) {
	good := [4]float64{8, 50, 8.1, 50.1}
	cases := map[string]func(dir string){
		"no index": func(dir string) {},
		"bad json": func(dir string) { os.WriteFile(filepath.Join(dir, "index.json"), []byte("{"), 0o644) },
		"bad id": func(dir string) {
			write(t, dir, venues.Index{Venues: []venues.IndexEntry{entry("../x", 1, good)}}, nil)
		},
		"duplicate": func(dir string) {
			write(t, dir, venues.Index{Venues: []venues.IndexEntry{entry("w1", 1, good), entry("w1", 1, good)}}, map[string]string{"w1": "{}"})
		},
		"bbox": func(dir string) {
			write(t, dir, venues.Index{Venues: []venues.IndexEntry{entry("w1", 1, [4]float64{8, 50, 7, 51})}}, map[string]string{"w1": "{}"})
		},
		"missing doc": func(dir string) {
			write(t, dir, venues.Index{Venues: []venues.IndexEntry{entry("w1", 1, good)}}, nil)
		},
		"not gzip": func(dir string) {
			write(t, dir, venues.Index{Venues: []venues.IndexEntry{entry("w1", 1, good)}}, nil)
			os.WriteFile(filepath.Join(dir, "venues", "w1.json.gz"), []byte("{}"), 0o644)
		},
		"truncated gzip": func(dir string) {
			write(t, dir, venues.Index{Venues: []venues.IndexEntry{entry("w1", 1, good)}}, map[string]string{"w1": `{"a":"` + strings.Repeat("x", 1000) + `"}`})
			p := filepath.Join(dir, "venues", "w1.json.gz")
			b, _ := os.ReadFile(p)
			os.WriteFile(p, b[:len(b)-10], 0o644)
		},
	}
	for name, setup := range cases {
		dir := t.TempDir()
		setup(dir)
		if _, err := Load(dir); err == nil {
			t.Errorf("%s: loaded", name)
		}
	}
	for _, b := range [][4]float64{{0, 0, 0, 0}, {-180, -90, 180, 90}} {
		if !validBBox(b) {
			t.Errorf("%v invalid", b)
		}
	}
	for _, b := range [][4]float64{{-181, 0, 0, 0}, {0, -91, 0, 0}, {0, 0, 181, 0}, {0, 0, 0, 91}} {
		if validBBox(b) {
			t.Errorf("%v valid", b)
		}
	}
}
