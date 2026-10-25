// Command crowd-vectors produces interoperability data for the reporting client in the app
// (AP5b, libs/crowd_traffic and the Android OHTTP layer) from the Go reference implementation:
//
//	crowd-vectors cpp   -out <file.hpp>     C++ test vectors (projection, segmentation, buckets,
//	                                        JSON and binary HTTP encoding, complete trip plans)
//	crowd-vectors java  -out <file.java>    OHTTP test vectors (ChaCha20-Poly1305 suite) for the
//	                                        Java unit tests
//	crowd-vectors traces -out <file> [-seed N] [-start unix]
//	                                        map-matched trips of the Berlin scenario (A100 jam) for
//	                                        the end-to-end test tools/better-maps/crowd-e2e.sh
//	crowd-vectors check -server <url>       verifies the JSON export of a server against the
//	                                        Berlin scenario (same rules as crowd-sim -check)
//
// The generated files are checked in; regenerate them after changes to internal/client,
// internal/protocol, internal/bhttp or internal/ohttp.
package main

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	mrand "math/rand/v2"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/bhttp"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/client"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/export"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/ldp"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/ohttp"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/protocol"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/sim"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/tiles"
)

func main() {
	if err := run(os.Args[1:], os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, "crowd-vectors:", err)
		os.Exit(1)
	}
}

func run(args []string, stdout io.Writer) error {
	if len(args) == 0 {
		return errors.New("usage: crowd-vectors cpp|java|traces|check [flags]")
	}
	fs := flag.NewFlagSet("crowd-vectors "+args[0], flag.ContinueOnError)
	out := fs.String("out", "", "output file (default: stdout)")
	seed := fs.Uint64("seed", 1, "random seed of the simulated trips (traces)")
	start := fs.Int64("start", 0, "virtual start of the simulation in Unix seconds (traces, default: now)")
	server := fs.String("server", "http://127.0.0.1:8080", "base URL of the crowd-traffic server (check)")
	if err := fs.Parse(args[1:]); err != nil {
		return err
	}
	var buf bytes.Buffer
	switch args[0] {
	case "cpp":
		writeCpp(&buf)
	case "java":
		if err := writeJava(&buf); err != nil {
			return err
		}
	case "traces":
		t := time.Now()
		if *start != 0 {
			t = time.Unix(*start, 0)
		}
		writeTraces(&buf, t, *seed)
	case "check":
		return check(*server, stdout)
	default:
		return fmt.Errorf("unknown command %q", args[0])
	}
	if *out == "" {
		_, err := stdout.Write(buf.Bytes())
		return err
	}
	return os.WriteFile(*out, buf.Bytes(), 0o644)
}

// fixedRand makes the reference client deterministic: every uniform draw is 0.5 (start trim
// 1250 m, send delay 135 s, randomized response keeps the true bucket) and every IntN is 0 (a
// fixed permutation in the Fisher–Yates shuffle). The C++ tests use the same source.
type fixedRand struct{}

func (fixedRand) Float64() float64 { return 0.5 }
func (fixedRand) IntN(int) int     { return 0 }

func unix(t time.Time) float64 { return float64(t.UnixNano()) / 1e9 }

// f formats a float64 exactly (shortest representation that parses to the same value), so that
// the C++ tests work on the same inputs as the Go code.
func f(x float64) string { return strconv.FormatFloat(x, 'g', -1, 64) }

func side(s tiles.Side) string { return "'" + s.String() + "'" }

func writePoints(w io.Writer, name string, pts []client.Point) {
	fmt.Fprintf(w, "inline constexpr Pt %s[] = {\n", name)
	for _, p := range pts {
		fmt.Fprintf(w, "  {%s, %s, %s, %d},\n", f(p.Lat), f(p.Lon), f(unix(p.Time)), p.Class)
	}
	fmt.Fprintln(w, "};")
}

func writeReports(w io.Writer, name string, reps []protocol.Report) {
	fmt.Fprintf(w, "inline constexpr Rep %s[] = {\n", name)
	for _, r := range reps {
		fmt.Fprintf(w, "  {%d, %d, %d, %s, %d, %s, %d, %d, %d},\n", r.Window, r.Tile.X, r.Tile.Y,
			side(r.Entry.Side), r.Entry.Offset, side(r.Exit.Side), r.Exit.Offset, r.RoadClass, r.SpeedBucket)
	}
	fmt.Fprintln(w, "};")
}

// straight returns points from (lat1, lon1) to (lat2, lon2) every stepS seconds at kmh.
func straight(lat1, lon1, lat2, lon2, kmh float64, t0 time.Time, class protocol.RoadClass) []client.Point {
	d := tiles.DistanceM(lat1, lon1, lat2, lon2)
	n := int(d/(kmh/3.6)) + 1
	pts := make([]client.Point, 0, n+1)
	for i := 0; i <= n; i++ {
		f := float64(i) / float64(n)
		pts = append(pts, client.Point{
			Lat: lat1 + f*(lat2-lat1), Lon: lon1 + f*(lon2-lon1),
			Time:  t0.Add(time.Duration(float64(i) * d / float64(n) / (kmh / 3.6) * float64(time.Second))),
			Class: class,
		})
	}
	return pts
}

// segmentationCases are traces with known shapes: real trips of the simulator and synthetic
// ones with sparse points (several tiles per step), turns, U-turns and class changes.
func segmentationCases(t0 time.Time) []struct {
	name string
	pts  []client.Point
} {
	sc := sim.Berlin()
	a100East := sc.Roads[0].Drive(t0, 85)
	a100West := sc.Roads[1].Drive(t0, 90)
	b96 := sc.Roads[2].Drive(t0, 45)

	// Sparse diagonal: one point every 30 s at 100 km/h, crossing several tiles per step.
	var sparse []client.Point
	for i, p := range straight(52.50, 13.30, 52.45, 13.45, 100, t0, protocol.Primary) {
		if i%30 == 0 {
			sparse = append(sparse, p)
		}
	}
	// Northwards then westwards (negative directions), class change halfway.
	turn := straight(52.47, 13.40, 52.49, 13.40, 50, t0, protocol.Secondary)
	last := turn[len(turn)-1]
	turn = append(turn[:len(turn)-1], straight(last.Lat, last.Lon, 52.49, 13.37, 50, last.Time, protocol.Tertiary)...)
	// U-turn inside a tile: enter from the west, turn around and leave through the west edge.
	tl := tiles.At(52.52, 13.40)
	wLat, wLon := tl.EdgePoint(tiles.West, 128)
	cLat, cLon := tl.Center()
	uturn := straight(wLat, wLon-0.002, cLat, cLon, 30, t0, protocol.Primary)
	last = uturn[len(uturn)-1]
	uturn = append(uturn[:len(uturn)-1], straight(last.Lat, last.Lon, wLat+0.0003, wLon-0.002, 30, last.Time, protocol.Primary)...)
	// Unclassified stretch in the middle (gap of the map matching).
	gap := straight(52.40, 13.20, 52.40, 13.26, 70, t0, protocol.Trunk)
	for i := len(gap) / 3; i < len(gap)/2; i++ {
		gap[i].Class = 0
	}
	return []struct {
		name string
		pts  []client.Point
	}{
		{"a100_east_jam", a100East}, {"a100_west", a100West}, {"b96", b96},
		{"sparse_diagonal", sparse}, {"turn_class_change", turn}, {"uturn", uturn}, {"gap", gap},
	}
}

func writeCpp(w io.Writer) {
	fmt.Fprintln(w, `// Generated by services/crowd-traffic/cmd/crowd-vectors ("go run ./cmd/crowd-vectors cpp") from the
// Go reference client. Do not edit.
#pragma once

#include <cstddef>
#include <cstdint>

namespace crowd_traffic::go_vectors
{
struct Proj { double lat, lon, px, py; int tileX, tileY; };
struct Pt { double lat, lon, t; int cls; };
struct Trav
{
  int x, y;
  char entry;
  int entryOffset;
  char exit;
  int exitOffset;
  int cls;
  double tIn, tOut, lengthM, distIn, distOut;
};
struct SegCase { char const * name; Pt const * pts; size_t numPts; Trav const * trav; size_t numTrav; };
struct Bucket { double kmh; int bucket; };
struct Rep { int64_t window; int x, y; char entry; int entryOffset; char exit; int exitOffset; int cls; int bucket; };
struct PlanBundle { int64_t window; double sendAt; Rep const * reports; size_t numReports; };
struct PlanCase { char const * name; Pt const * pts; size_t numPts; PlanBundle const * bundles; size_t numBundles; };
struct Distance { double lat1, lon1, lat2, lon2, m; };`)
	fmt.Fprintln(w)

	// Projection.
	fmt.Fprintln(w, "inline constexpr Proj kProjections[] = {")
	for _, p := range [][2]float64{{52.5200, 13.4050}, {52.4782, 13.3420}, {0, 0}, {-33.8688, 151.2093},
		{85.1, -180}, {-85.1, 179.9999}, {47.2, 5.8}, {55.1, 15.1}} {
		px, py := tiles.Project(p[0], p[1])
		t := tiles.At(p[0], p[1])
		fmt.Fprintf(w, "  {%s, %s, %s, %s, %d, %d},\n", f(p[0]), f(p[1]), f(px), f(py), t.X, t.Y)
	}
	fmt.Fprintln(w, "};")
	fmt.Fprintln(w, "inline constexpr Distance kDistances[] = {")
	for _, d := range [][4]float64{{52.52, 13.405, 52.4782, 13.342}, {0, 0, 0, 1}, {52.5, 13.4, 52.5, 13.4}, {10, 20, -30, -170}} {
		fmt.Fprintf(w, "  {%s, %s, %s, %s, %s},\n", f(d[0]), f(d[1]), f(d[2]), f(d[3]), f(tiles.DistanceM(d[0], d[1], d[2], d[3])))
	}
	fmt.Fprintln(w, "};")

	// Segmentation.
	t0 := time.Date(2026, 10, 26, 15, 40, 17, 0, time.UTC)
	cases := segmentationCases(t0)
	for i, c := range cases {
		writePoints(w, fmt.Sprintf("kSegPts%d", i), c.pts)
		trav := client.Segment(c.pts)
		fmt.Fprintf(w, "inline constexpr Trav kSegTrav%d[] = {\n", i)
		for _, t := range trav {
			fmt.Fprintf(w, "  {%d, %d, %s, %d, %s, %d, %d, %s, %s, %s, %s, %s},\n", t.Tile.X, t.Tile.Y,
				side(t.Entry.Side), t.Entry.Offset, side(t.Exit.Side), t.Exit.Offset, t.Class, f(unix(t.TIn)), f(unix(t.TOut)),
				f(t.LengthM), f(t.DistIn), f(t.DistOut))
		}
		if len(trav) == 0 {
			fmt.Fprintln(w, "  {0, 0, 'N', 0, 'N', 0, 0, 0, 0, 0, 0, 0},")
		}
		fmt.Fprintln(w, "};")
	}
	fmt.Fprintln(w, "inline constexpr SegCase kSegCases[] = {")
	for i, c := range cases {
		fmt.Fprintf(w, "  {%q, kSegPts%d, %d, kSegTrav%d, %d},\n", c.name, i, len(c.pts), i, len(client.Segment(c.pts)))
	}
	fmt.Fprintln(w, "};")

	// Speed buckets.
	fmt.Fprintln(w, "inline constexpr Bucket kBuckets[] = {")
	for _, v := range []float64{0, 5, 9.999, 10, 19.99, 20, 34.9, 35, 54.99, 55, 79.9, 80, 109.9, 110, 180, 300} {
		fmt.Fprintf(w, "  {%v, %d},\n", v, protocol.BucketOf(v))
	}
	fmt.Fprintln(w, "};")

	// Wire format: the example of the client contract (docs/CROWD_TRAFFIC_PRIVACY.md, 13.4).
	example := []protocol.Report{
		{Window: 1793029500, Tile: tiles.Tile{X: 70394, Y: 43010}, Entry: protocol.Edge{Side: tiles.West, Offset: 201},
			Exit: protocol.Edge{Side: tiles.East, Offset: 239}, RoadClass: protocol.Motorway, SpeedBucket: 0},
		{Window: 1793029500, Tile: tiles.Tile{X: 70395, Y: 43011}, Entry: protocol.Edge{Side: tiles.North, Offset: 109},
			Exit: protocol.Edge{Side: tiles.East, Offset: 21}, RoadClass: protocol.Motorway, SpeedBucket: 5},
	}
	writeReports(w, "kExampleReports", example)
	body, _ := json.Marshal(protocol.Bundle{Version: protocol.Version, Reports: example})
	fmt.Fprintf(w, "inline constexpr char kExampleJson[] = R\"(%s)\";\n", body)
	date := time.Date(2026, 10, 26, 16, 1, 30, 0, time.UTC)
	fmt.Fprintf(w, "inline constexpr int64_t kExampleDate = %d;\n", date.Unix())
	req, err := client.EncodeRequest(example, date, client.DefaultAuthority)
	if err != nil {
		panic(err)
	}
	end := len(req)
	for end > 0 && req[end-1] == 0 {
		end--
	}
	// The encoding ends with the empty trailer section (one zero byte), followed by padding.
	fmt.Fprintf(w, "inline constexpr char kExampleBhttpHex[] = \"%s\";\n", hex.EncodeToString(req[:end+1]))
	fmt.Fprintf(w, "inline constexpr size_t kExampleBhttpSize = %d;\n", len(req))

	// Complete trips through the client rules with deterministic randomness. Trips that are
	// also segmentation cases reuse their point arrays.
	sc := sim.Berlin()
	plans := []struct {
		name string
		pts  []client.Point
		seg  int
	}{
		{"a100_east_jam", cases[0].pts, 0},
		{"a100_west", sc.Roads[1].Drive(t0.Add(7*time.Minute), 92), -1},
		{"b96", sc.Roads[2].Drive(t0.Add(-9*time.Minute), 48), -1},
		{"dorfstrasse", sc.Roads[3].Drive(t0, 7), -1},
		{"sparse_diagonal", cases[3].pts, 3},
		{"gap", cases[6].pts, 6},
	}
	rr := ldp.New(protocol.NumBuckets, protocol.Epsilon)
	counts := make([]int, len(plans))
	for i, p := range plans {
		if p.seg < 0 {
			writePoints(w, fmt.Sprintf("kPlanPts%d", i), p.pts)
		}
		bundles := client.PlanTrip(p.pts, client.DefaultConfig(), rr, fixedRand{})
		counts[i] = len(bundles)
		for j, b := range bundles {
			writeReports(w, fmt.Sprintf("kPlanReps%d_%d", i, j), b.Reports)
		}
		fmt.Fprintf(w, "inline constexpr PlanBundle kPlanBundles%d[] = {\n", i)
		for j, b := range bundles {
			fmt.Fprintf(w, "  {%d, %s, kPlanReps%d_%d, %d},\n", b.Window, f(unix(b.SendAt)), i, j, len(b.Reports))
		}
		if len(bundles) == 0 {
			fmt.Fprintln(w, "  {0, 0, nullptr, 0},")
		}
		fmt.Fprintln(w, "};")
	}
	fmt.Fprintln(w, "inline constexpr PlanCase kPlanCases[] = {")
	for i, p := range plans {
		pts := fmt.Sprintf("kPlanPts%d", i)
		if p.seg >= 0 {
			pts = fmt.Sprintf("kSegPts%d", p.seg)
		}
		fmt.Fprintf(w, "  {%q, %s, %d, kPlanBundles%d, %d},\n", p.name, pts, len(p.pts), i, counts[i])
	}
	fmt.Fprintln(w, "};")
	fmt.Fprintln(w, "}  // namespace crowd_traffic::go_vectors")
}

// constReader returns the same byte forever (deterministic randomness for test vectors).
type constReader byte

func (c constReader) Read(p []byte) (int, error) {
	for i := range p {
		p[i] = byte(c)
	}
	return len(p), nil
}

func javaHex(w io.Writer, name string, b []byte) {
	fmt.Fprintf(w, "  static final String %s = \"%s\";\n", name, hex.EncodeToString(b))
}

func writeJava(w io.Writer) error {
	suites := []ohttp.SymmetricSuite{{KDF: 0x0001, AEAD: 0x0003}}
	key, err := ohttp.NewPrivateKey(7, ohttp.DefaultKEM, bytes.Repeat([]byte{0x11}, 32), suites)
	if err != nil {
		return err
	}
	example := []protocol.Report{
		{Window: 1793029500, Tile: tiles.Tile{X: 70394, Y: 43010}, Entry: protocol.Edge{Side: tiles.West, Offset: 201},
			Exit: protocol.Edge{Side: tiles.East, Offset: 239}, RoadClass: protocol.Motorway, SpeedBucket: 0},
	}
	req, err := client.EncodeRequest(example, time.Date(2026, 10, 26, 16, 1, 30, 0, time.UTC), client.DefaultAuthority)
	if err != nil {
		return err
	}
	ephSeed := bytes.Repeat([]byte{0x22}, 32)
	pkE, skE := ohttp.DefaultKEM.Scheme().DeriveKeyPair(ephSeed)
	pkEb, _ := pkE.MarshalBinary()
	skEb, _ := skE.MarshalBinary()
	encReq, _, err := ohttp.EncapsulateRequest(key.Config, req, bytes.NewReader(ephSeed))
	if err != nil {
		return err
	}
	pt, sctx, err := ohttp.DecapsulateRequest(encReq, func(id uint8) (*ohttp.PrivateKey, bool) { return key, id == 7 })
	if err != nil || !bytes.Equal(pt, req) {
		return fmt.Errorf("round trip failed: %v", err)
	}
	if !bytes.Equal(sctx.Enc(), pkEb) {
		return errors.New("ephemeral key mismatch")
	}
	accepted := (&bhttp.Response{Status: 204}).Marshal()
	encAccepted, err := sctx.EncapsulateResponse(accepted, constReader(0x33))
	if err != nil {
		return err
	}
	problem, _ := json.Marshal(map[string]string{"type": "urn:better-maps:crowd-traffic:invalid-bundle", "title": "window_late"})
	rejected := (&bhttp.Response{Status: 400, Header: bhttp.Fields{{Name: "content-type", Value: "application/problem+json"}},
		Body: problem}).Marshal()

	fmt.Fprintln(w, `// Generated by services/crowd-traffic/cmd/crowd-vectors ("go run ./cmd/crowd-vectors java") from the
// Go reference implementation (internal/ohttp, internal/bhttp, internal/client). Do not edit.
package app.organicmaps.sdk.crowdtraffic;

final class OhttpGoVectors
{
  private OhttpGoVectors() {}`)
	fmt.Fprintln(w)
	javaHex(w, "KEY_CONFIGS", ohttp.MarshalKeyConfigs([]ohttp.KeyConfig{key.Config}))
	javaHex(w, "REQUEST", req)
	javaHex(w, "EPHEMERAL_SK", skEb)
	javaHex(w, "EPHEMERAL_PK", pkEb)
	javaHex(w, "ENCAPSULATED_REQUEST", encReq)
	javaHex(w, "RESPONSE_ACCEPTED", accepted)
	javaHex(w, "ENCAPSULATED_RESPONSE_ACCEPTED", encAccepted)
	javaHex(w, "RESPONSE_REJECTED", rejected)
	fmt.Fprintln(w, "}")
	return nil
}

// writeTraces writes the map-matched trips of the Berlin scenario: a line "T <road>" starts a
// trip, followed by lines "<lat> <lon> <unix seconds> <class>".
func writeTraces(w io.Writer, start time.Time, seed uint64) {
	sc := sim.Berlin()
	rnd := mrand.New(mrand.NewPCG(seed, 0x5eed))
	window := protocol.WindowSeconds * time.Second
	for _, t := range sc.Trips(start.Truncate(window), window, rnd) {
		fmt.Fprintf(w, "T %s\n", strings.ReplaceAll(t.Road, " ", "_"))
		for _, p := range t.Points {
			fmt.Fprintf(w, "%.9f %.9f %.3f %d\n", p.Lat, p.Lon, unix(p.Time), p.Class)
		}
	}
}

func check(server string, out io.Writer) error {
	resp, err := http.Get(server + "/v1/segments")
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("/v1/segments: status %d", resp.StatusCode)
	}
	var doc export.Document
	if err := json.NewDecoder(resp.Body).Decode(&doc); err != nil {
		return err
	}
	res := sim.Check(doc, sim.Berlin())
	fmt.Fprint(out, res)
	if !res.OK() {
		return errors.New("export does not match the simulation")
	}
	fmt.Fprintln(out, "check OK")
	return nil
}
