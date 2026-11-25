// Command indoor-import builds the venue data served by indoor-gateway from the GeoJSONSeq that
// osmium export wrote (see scripts/import.sh and compose.yaml, service "import"):
//
//	indoor-import -input indoor.geojsonseq -out data.next [-timestamps timestamps.txt] [-regions dir]
//
// It writes <out>/index.json, <out>/venues/<id>.json.gz (gzip level 9) and <out>/import-stats.txt.
// -timestamps: lines "<region> <RFC 3339 time>" (the replication timestamps of the extracts);
// the oldest is the data timestamp. -regions: directory with the Geofabrik "<region>.poly"
// outlines, only for the venue counts per region in the statistics.
package main

import (
	"bufio"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/WayneNani/better-maps/services/indoor-server/internal/levels"
	"github.com/WayneNani/better-maps/services/indoor-server/internal/regions"
	"github.com/WayneNani/better-maps/services/indoor-server/internal/venues"
)

func main() {
	if err := run(os.Args[1:], os.Stdout, os.Stderr, time.Now); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return
		}
		fmt.Fprintln(os.Stderr, "indoor-import:", err)
		os.Exit(1)
	}
}

func run(args []string, stdout, stderr io.Writer, now func() time.Time) error {
	start := now()
	fs := flag.NewFlagSet("indoor-import", flag.ContinueOnError)
	fs.SetOutput(stderr)
	input := fs.String("input", "", "GeoJSONSeq of osmium export, - = stdin")
	out := fs.String("out", "", "output directory (created; must not contain an index.json)")
	tsFile := fs.String("timestamps", "", "file with lines \"<region> <timestamp>\" (optional)")
	regionsDir := fs.String("regions", "", "directory with <region>.poly files for the statistics (optional)")
	minVenues := fs.Int("min-venues", 1, "fail if fewer venues result (protects the served data from a broken export)")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *input == "" || *out == "" || fs.NArg() > 0 {
		return errors.New("usage: indoor-import -input <file> -out <dir> [-timestamps <file>] [-regions <dir>]")
	}
	if _, err := os.Stat(filepath.Join(*out, "index.json")); err == nil {
		return fmt.Errorf("%s already contains an index.json", *out)
	}

	inputTimes, err := readTimestamps(*tsFile)
	if err != nil {
		return err
	}
	dataTS := now().UTC()
	for _, t := range inputTimes {
		if t.time.Before(dataTS) {
			dataTS = t.time
		}
	}
	if len(inputTimes) == 0 {
		fmt.Fprintln(stderr, "indoor-import: no input timestamps, using the current time")
	}
	stamp := dataTS.UTC().Format("2006-01-02T15:04:05Z")

	var in io.Reader = os.Stdin
	if *input != "-" {
		f, err := os.Open(*input)
		if err != nil {
			return err
		}
		defer f.Close()
		in = f
	}
	b := venues.NewBuilder(venues.DefaultOptions())
	unusable, err := venues.ReadGeoJSONSeq(in, func(r *venues.Raw) error { b.Add(r); return nil })
	if err != nil {
		return fmt.Errorf("reading %s: %w", *input, err)
	}
	readDone := now()
	vs := b.Build()
	if len(vs) < *minVenues {
		return fmt.Errorf("only %d venues (minimum %d): data left unchanged", len(vs), *minVenues)
	}

	if err := os.MkdirAll(filepath.Join(*out, "venues"), 0o755); err != nil {
		return err
	}
	idx := venues.Index{DataTimestamp: stamp, Venues: make([]venues.IndexEntry, 0, len(vs))}
	type sized struct {
		e         venues.IndexEntry
		plain, gz int
	}
	var all []sized
	var totalPlain, totalGz int64
	for _, v := range vs {
		doc, entry := venues.Encode(v, stamp, venues.DefaultSimplifyM)
		plain, gz, err := venues.Marshal(doc)
		if err != nil {
			return err
		}
		if err := os.WriteFile(filepath.Join(*out, "venues", entry.ID+".json.gz"), gz, 0o644); err != nil {
			return err
		}
		idx.Venues = append(idx.Venues, entry)
		all = append(all, sized{entry, len(plain), len(gz)})
		totalPlain += int64(len(plain))
		totalGz += int64(len(gz))
	}
	indexJSON, _, err := venues.Marshal(idx)
	if err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(*out, "index.json"), indexJSON, 0o644); err != nil {
		return err
	}

	// Statistics.
	var sb strings.Builder
	st := b.Stats
	fmt.Fprintf(&sb, "date=%s data_timestamp=%s duration_s=%.1f read_s=%.1f\n", start.UTC().Format(time.RFC3339), stamp,
		now().Sub(start).Seconds(), readDone.Sub(start).Seconds())
	for _, t := range inputTimes {
		fmt.Fprintf(&sb, "input %s timestamp=%s\n", t.region, t.time.UTC().Format(time.RFC3339))
	}
	fmt.Fprintf(&sb, "features_read=%d unusable=%d indoor_features=%d indoor_areas=%d bad_level=%d duplicate_lines=%d containers=%d station_nodes=%d\n",
		st.Read, unusable, st.Features, st.Seeds, st.BadLevel, st.DuplicateLines, st.Containers, st.Stations)
	fmt.Fprintf(&sb, "clusters=%d split_clusters=%d unattached=%d doors_without_level=%d\n",
		st.Clusters, st.SplitClusters, st.Unattached, st.DoorsWithoutLevel)
	fmt.Fprintf(&sb, "venues=%d skipped_small=%d (features %d) venue_features=%d\n",
		st.Venues, st.SkippedSmall, st.SkippedFeatures, st.VenueFeatures)
	fmt.Fprintf(&sb, "size_gz_total=%d size_plain_total=%d index_bytes=%d\n", totalGz, totalPlain, len(indexJSON))
	if *regionsDir != "" {
		counts, err := venuesPerRegion(*regionsDir, vs)
		if err != nil {
			return err
		}
		sb.WriteString(counts)
	}
	fmt.Fprintf(&sb, "memory_peak_bytes=%s maxrss_bytes=%d go_sys_bytes=%d\n", cgroupPeak(), maxRSS(), goSys())
	slices.SortStableFunc(all, func(a, c sized) int { return c.e.Features - a.e.Features })
	sb.WriteString("largest venues (id kind features levels gz_bytes plain_bytes name):\n")
	for _, s := range all[:min(len(all), 25)] {
		var lv []string
		for _, l := range s.e.Levels {
			lv = append(lv, levels.Format(l.Level))
		}
		fmt.Fprintf(&sb, "  %s %s %d [%s] %d %d %q\n", s.e.ID, s.e.Kind, s.e.Features, strings.Join(lv, ","), s.gz, s.plain, s.e.Name)
	}
	if err := os.WriteFile(filepath.Join(*out, "import-stats.txt"), []byte(sb.String()), 0o644); err != nil {
		return err
	}
	fmt.Fprint(stdout, sb.String())
	return nil
}

type inputTime struct {
	region string
	time   time.Time
}

func readTimestamps(path string) ([]inputTime, error) {
	if path == "" {
		return nil, nil
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	var out []inputTime
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		fields := strings.Fields(sc.Text())
		if len(fields) == 0 {
			continue
		}
		if len(fields) != 2 {
			return nil, fmt.Errorf("%s: expected \"<region> <timestamp>\"", path)
		}
		t, err := time.Parse(time.RFC3339, fields[1])
		if err != nil {
			return nil, fmt.Errorf("%s: %w", path, err)
		}
		out = append(out, inputTime{fields[0], t})
	}
	return out, sc.Err()
}

func venuesPerRegion(dir string, vs []*venues.Venue) (string, error) {
	files, err := filepath.Glob(filepath.Join(dir, "*.poly"))
	if err != nil {
		return "", err
	}
	slices.Sort(files)
	var sb strings.Builder
	for _, file := range files {
		f, err := os.Open(file)
		if err != nil {
			return "", err
		}
		name := strings.TrimSuffix(filepath.Base(file), ".poly")
		reg, err := regions.ParsePoly(name, f)
		f.Close()
		if err != nil {
			return "", fmt.Errorf("%s: %w", file, err)
		}
		n, features := 0, 0
		for _, v := range vs {
			if reg.Contains(v.BBox.Center()) {
				n++
				features += len(v.Features)
			}
		}
		fmt.Fprintf(&sb, "region %s venues=%d features=%d\n", name, n, features)
	}
	return sb.String(), nil
}

// cgroupPeak is the peak memory of the container (cgroup v2), "unknown" outside one.
func cgroupPeak() string {
	b, err := os.ReadFile("/sys/fs/cgroup/memory.peak")
	if err != nil {
		return "unknown"
	}
	v := strings.TrimSpace(string(b))
	if _, err := strconv.ParseInt(v, 10, 64); err != nil {
		return "unknown"
	}
	return v
}

func maxRSS() int64 {
	var ru syscall.Rusage
	if syscall.Getrusage(syscall.RUSAGE_SELF, &ru) != nil {
		return 0
	}
	return ru.Maxrss * 1024 // kilobytes on Linux
}

func goSys() uint64 {
	var ms runtime.MemStats
	runtime.ReadMemStats(&ms)
	return ms.Sys
}
