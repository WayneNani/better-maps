// Command crowd-sim simulates vehicles on a few Berlin roads (with a jam on the A100), runs them
// through the reference client (trimming, sampling, randomized response, padding) and sends the
// bundles via OHTTP to a crowd-traffic gateway, directly or through a relay.
//
// Live mode maps all simulated windows onto the current server window, so that a test server
// with a short window (e.g. -window 60s) evaluates them within a minute. With -check the
// simulator waits for the evaluation and verifies the export.
//
//	crowd-sim -dry-run                                       # print statistics and an example bundle
//	crowd-sim -relay http://127.0.0.1:8090/relay -server http://127.0.0.1:8080 -window 60s -grace 5s -check
package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	mrand "math/rand/v2"
	"net/http"
	"os"
	"os/signal"
	"sort"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/bhttp"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/client"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/export"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/ohttp"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/protocol"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/sim"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, os.Args[1:], os.Stdout, time.Now); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return
		}
		fmt.Fprintln(os.Stderr, "crowd-sim:", err)
		os.Exit(1)
	}
}

type options struct {
	relay, keys, server string
	window, grace       time.Duration
	seed                uint64
	dryRun, check       bool
	replayCheck         bool
	concurrency         int
}

func parse(args []string, out io.Writer) (options, error) {
	var o options
	fs := flag.NewFlagSet("crowd-sim", flag.ContinueOnError)
	fs.SetOutput(out)
	fs.StringVar(&o.relay, "relay", "http://127.0.0.1:8080/gateway", "URL receiving encapsulated requests (relay, or the gateway itself)")
	fs.StringVar(&o.keys, "keys", "", "URL of the key configuration (default: next to -relay, /ohttp-keys)")
	fs.StringVar(&o.server, "server", "http://127.0.0.1:8080", "base URL of the crowd-traffic server (exports, for -check)")
	fs.DurationVar(&o.window, "window", protocol.WindowSeconds*time.Second, "window length of the server")
	fs.DurationVar(&o.grace, "grace", 5*time.Minute, "grace period of the server (for -check)")
	fs.Uint64Var(&o.seed, "seed", 1, "random seed of the simulation (the OHTTP layer always uses crypto/rand)")
	fs.BoolVar(&o.dryRun, "dry-run", false, "only print statistics and an example bundle")
	fs.BoolVar(&o.check, "check", false, "wait for the evaluation and verify the export")
	fs.BoolVar(&o.replayCheck, "replay-check", false, "only send one encapsulated request twice and verify that the replay is rejected")
	fs.IntVar(&o.concurrency, "concurrency", 8, "parallel requests")
	if err := fs.Parse(args); err != nil {
		return o, err
	}
	if o.window < 10*time.Second || o.concurrency < 1 {
		return o, errors.New("window must be at least 10s and concurrency at least 1")
	}
	if o.keys == "" {
		i := strings.LastIndex(o.relay, "/")
		if i < len("http://") {
			return o, errors.New("cannot derive -keys from -relay")
		}
		o.keys = o.relay[:i] + "/ohttp-keys"
	}
	return o, nil
}

func run(ctx context.Context, args []string, out io.Writer, now func() time.Time) error {
	o, err := parse(args, out)
	if err != nil {
		return err
	}
	if o.replayCheck {
		return replayCheck(ctx, o, out, now)
	}
	sc := sim.Berlin()
	rnd := mrand.New(mrand.NewPCG(o.seed, 0x5eed))
	ccfg := client.DefaultConfig()
	virtualStart := now().Truncate(ccfg.Window)
	bundles := sim.Plan(sc.Trips(virtualStart, ccfg.Window, rnd), ccfg, rnd)
	if len(bundles) == 0 {
		return errors.New("simulation produced no bundles")
	}
	perRoad := map[string]int{}
	for _, b := range bundles {
		perRoad[b.Road] += len(b.Reports)
	}
	roads := make([]string, 0, len(perRoad))
	for r := range perRoad {
		roads = append(roads, r)
	}
	sort.Strings(roads)
	fmt.Fprintf(out, "simulated %d bundles\n", len(bundles))
	for _, r := range roads {
		fmt.Fprintf(out, "  %-28s %5d reports\n", r, perRoad[r])
	}

	if o.dryRun {
		example := bundles[len(bundles)/2]
		body, _ := json.MarshalIndent(protocol.Bundle{Version: protocol.Version, Reports: example.Reports}, "", "  ")
		fmt.Fprintf(out, "example bundle (%s, send at %s):\n%s\n", example.Road, example.SendAt.UTC().Format(time.RFC3339), body)
		req, err := client.EncodeRequest(example.Reports, example.SendAt, client.DefaultAuthority)
		if err != nil {
			return err
		}
		fmt.Fprintf(out, "binary HTTP request (%d bytes, first 96): %s…\n", len(req), hex.EncodeToString(req[:96]))
		return nil
	}

	// Map all virtual windows onto one server window with enough time left to send.
	margin := min(o.window/4, 30*time.Second)
	cur := now().Truncate(o.window)
	if cur.Add(o.window).Sub(now()) < margin {
		wait := cur.Add(o.window).Sub(now()) + time.Second
		fmt.Fprintf(out, "waiting %v for the next window\n", wait.Round(time.Second))
		if err := sleep(ctx, wait); err != nil {
			return err
		}
		cur = now().Truncate(o.window)
	}
	for _, b := range bundles {
		for i := range b.Reports {
			b.Reports[i].Window = cur.Unix()
		}
	}
	sender := &client.Sender{RelayURL: o.relay, KeysURL: o.keys, HTTP: &http.Client{Timeout: 10 * time.Second}}
	if err := sender.FetchKeys(ctx); err != nil {
		return fmt.Errorf("fetching keys: %w", err)
	}
	sent, failed := sendAll(ctx, sender, bundles, o.concurrency)
	fmt.Fprintf(out, "sent %d bundles for window %s, %d failed\n", sent, cur.UTC().Format(time.RFC3339), len(failed))
	for i, err := range failed {
		if i == 3 {
			break
		}
		fmt.Fprintf(out, "  error: %v\n", err)
	}
	if len(failed) > 0 {
		return fmt.Errorf("%d bundles failed", len(failed))
	}
	if !o.check {
		return nil
	}

	wait := cur.Add(o.window + o.grace + 2*time.Second).Sub(now())
	fmt.Fprintf(out, "waiting %v for the evaluation\n", wait.Round(time.Second))
	if err := sleep(ctx, wait); err != nil {
		return err
	}
	var doc export.Document
	if err := getJSON(ctx, o.server+"/v1/segments", &doc); err != nil {
		return err
	}
	res := sim.Check(doc, sc)
	fmt.Fprint(out, res)
	if !res.OK() {
		return errors.New("export does not match the simulation")
	}
	fmt.Fprintln(out, "check OK")
	return nil
}

// sleep waits for d; tests replace it.
var sleep = realSleep

func realSleep(ctx context.Context, d time.Duration) error {
	if d <= 0 {
		return nil
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-t.C:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func sendAll(ctx context.Context, s *client.Sender, bundles []sim.Bundle, n int) (int, []error) {
	var (
		mu     sync.Mutex
		sent   int
		failed []error
		wg     sync.WaitGroup
	)
	ch := make(chan sim.Bundle)
	for range n {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for b := range ch {
				err := s.Send(ctx, b.Reports)
				mu.Lock()
				if err != nil {
					failed = append(failed, err)
				} else {
					sent++
				}
				mu.Unlock()
			}
		}()
	}
	for _, b := range bundles {
		ch <- b
	}
	close(ch)
	wg.Wait()
	return sent, failed
}

func getJSON(ctx context.Context, url string, v any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("%s: status %d", url, resp.StatusCode)
	}
	return json.NewDecoder(resp.Body).Decode(v)
}

// replayCheck sends the same encapsulated request twice. The gateway must process the first one
// and reject the second one as a replay (inside the encapsulated response).
func replayCheck(ctx context.Context, o options, out io.Writer, now func() time.Time) error {
	s := &client.Sender{KeysURL: o.keys}
	if err := s.FetchKeys(ctx); err != nil {
		return err
	}
	cfg := s.KeyConfig()
	plain, err := client.EncodeRequest([]protocol.Report{}, now(), client.DefaultAuthority)
	if err != nil {
		return err
	}
	enc, cctx, err := ohttp.EncapsulateRequest(*cfg, plain, rand.Reader)
	if err != nil {
		return err
	}
	var statuses []int
	for range 2 {
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, o.relay, bytes.NewReader(enc))
		if err != nil {
			return err
		}
		req.Header.Set("Content-Type", ohttp.ContentTypeRequest)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			return err
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			return fmt.Errorf("outer status %d", resp.StatusCode)
		}
		pt, err := cctx.DecapsulateResponse(body)
		if err != nil {
			return err
		}
		inner, err := bhttp.UnmarshalResponse(pt, 1<<16)
		if err != nil {
			return err
		}
		statuses = append(statuses, inner.Status)
		if len(statuses) == 2 && !strings.Contains(string(inner.Body), "replay") {
			return fmt.Errorf("second request not rejected as replay: %d %s", inner.Status, inner.Body)
		}
	}
	fmt.Fprintf(out, "replay check OK (inner status %d, then %d)\n", statuses[0], statuses[1])
	return nil
}
