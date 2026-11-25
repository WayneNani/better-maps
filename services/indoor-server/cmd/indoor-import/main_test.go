package main

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const fixture = "../../testdata/export-fixture.geojsonseq"

func fixedNow() time.Time { return time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC) }

func TestImportFixture(t *testing.T) {
	dir := t.TempDir()
	out := filepath.Join(dir, "data.next")
	ts := filepath.Join(dir, "timestamps.txt")
	os.WriteFile(ts, []byte("hessen 2026-11-24T21:21:02Z\n\nnordrhein-westfalen 2026-11-23T20:00:00Z\n"), 0o644)
	regDir := filepath.Join(dir, "regions")
	os.Mkdir(regDir, 0o755)
	os.WriteFile(filepath.Join(regDir, "hessen.poly"), []byte("hessen\n1\n 8 49\n 9 49\n 9 51\n 8 51\nEND\nEND\n"), 0o644)
	os.WriteFile(filepath.Join(regDir, "nrw.poly"), []byte("nrw\n1\n 6 50.5\n 7 50.5\n 7 51\nEND\nEND\n"), 0o644)

	var stdout bytes.Buffer
	if err := run([]string{"-input", fixture, "-out", out, "-timestamps", ts, "-regions", regDir}, &stdout, io.Discard, fixedNow); err != nil {
		t.Fatal(err)
	}
	var idx struct {
		DataTimestamp string `json:"data_timestamp"`
		Venues        []struct {
			ID       string `json:"id"`
			Features int    `json:"features"`
		} `json:"venues"`
	}
	b, err := os.ReadFile(filepath.Join(out, "index.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(b, &idx); err != nil {
		t.Fatal(err)
	}
	if idx.DataTimestamp != "2026-11-23T20:00:00Z" || len(idx.Venues) != 1 || idx.Venues[0].ID != "w1" || idx.Venues[0].Features != 28 {
		t.Fatalf("%+v", idx)
	}
	gz, err := os.ReadFile(filepath.Join(out, "venues", "w1.json.gz"))
	if err != nil {
		t.Fatal(err)
	}
	zr, err := gzip.NewReader(bytes.NewReader(gz))
	if err != nil {
		t.Fatal(err)
	}
	doc, _ := io.ReadAll(zr)
	if !strings.Contains(string(doc), `"data_timestamp":"2026-11-23T20:00:00Z"`) {
		t.Fatal(string(doc[:200]))
	}
	stats, _ := os.ReadFile(filepath.Join(out, "import-stats.txt"))
	for _, want := range []string{"venues=1 skipped_small=0", "region hessen venues=1 features=28", "region nrw venues=0",
		"bad_level=1", "input hessen timestamp=2026-11-24T21:21:02Z", "w1 airport 28 [-1,0,1]", "memory_peak_bytes="} {
		if !strings.Contains(string(stats), want) {
			t.Errorf("stats lack %q:\n%s", want, stats)
		}
	}
	if stdout.String() != string(stats) {
		t.Error("stdout differs from the stats file")
	}
	// A second run into the same directory is refused.
	if err := run([]string{"-input", fixture, "-out", out}, io.Discard, io.Discard, fixedNow); err == nil {
		t.Fatal("existing output overwritten")
	}
}

func TestImportErrors(t *testing.T) {
	dir := t.TempDir()
	bad := filepath.Join(dir, "bad.txt")
	os.WriteFile(bad, []byte("hessen yesterday\n"), 0o644)
	cases := [][]string{
		{},
		{"-input", fixture},
		{"-input", fixture, "-out", filepath.Join(dir, "a"), "extra"},
		{"-input", filepath.Join(dir, "missing"), "-out", filepath.Join(dir, "b")},
		{"-input", fixture, "-out", filepath.Join(dir, "c"), "-timestamps", bad},
		{"-input", fixture, "-out", filepath.Join(dir, "d"), "-timestamps", filepath.Join(dir, "missing")},
		{"-input", fixture, "-out", filepath.Join(dir, "e"), "-min-venues", "2"},
		{"-unknown-flag"},
	}
	for _, args := range cases {
		if err := run(args, io.Discard, io.Discard, fixedNow); err == nil {
			t.Errorf("%v accepted", args)
		}
	}
	if _, err := os.Stat(filepath.Join(dir, "e", "index.json")); err == nil {
		t.Error("index written although there were too few venues")
	}
	// Broken poly file.
	regDir := filepath.Join(dir, "regions")
	os.Mkdir(regDir, 0o755)
	os.WriteFile(filepath.Join(regDir, "x.poly"), []byte("x\n"), 0o644)
	if err := run([]string{"-input", fixture, "-out", filepath.Join(dir, "f"), "-regions", regDir}, io.Discard, io.Discard, fixedNow); err == nil {
		t.Error("broken poly accepted")
	}
	// Malformed input.
	in := filepath.Join(dir, "in.geojsonseq")
	os.WriteFile(in, []byte("{oops\n"), 0o644)
	if err := run([]string{"-input", in, "-out", filepath.Join(dir, "g")}, io.Discard, io.Discard, fixedNow); err == nil {
		t.Error("malformed input accepted")
	}
}

func TestNoTimestampsUsesNow(t *testing.T) {
	out := filepath.Join(t.TempDir(), "o")
	var stderr bytes.Buffer
	if err := run([]string{"-input", fixture, "-out", out}, io.Discard, &stderr, fixedNow); err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(filepath.Join(out, "index.json"))
	if !strings.Contains(string(b), `"data_timestamp":"2026-11-25T12:00:00Z"`) || !strings.Contains(stderr.String(), "no input timestamps") {
		t.Fatal(string(b), stderr.String())
	}
}
