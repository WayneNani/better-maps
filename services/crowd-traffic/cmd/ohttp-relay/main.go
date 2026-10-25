// Command ohttp-relay is a minimal Oblivious HTTP relay for development and tests.
//
// In production the relay must be operated by a party independent of the gateway; see
// docs/CROWD_TRAFFIC_PRIVACY.md.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/logging"
	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/relay"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, os.Args[1:], os.Getenv, os.Stderr, nil); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return
		}
		fmt.Fprintln(os.Stderr, "ohttp-relay:", err)
		os.Exit(1)
	}
}

func run(ctx context.Context, args []string, env func(string) string, stderr io.Writer, ready chan<- string) error {
	def := relay.DefaultConfig()
	or := func(k, d string) string {
		if v := env(k); v != "" {
			return v
		}
		return d
	}
	fs := flag.NewFlagSet("ohttp-relay", flag.ContinueOnError)
	fs.SetOutput(stderr)
	listen := fs.String("listen", or("RELAY_LISTEN", ":8090"), "listen address (env RELAY_LISTEN)")
	gateway := fs.String("gateway", or("RELAY_GATEWAY", "http://127.0.0.1:8080"), "base URL of the crowd-traffic gateway (env RELAY_GATEWAY)")
	perHour := fs.Float64("per-hour", def.Rate*3600, "requests per hour and client address")
	burst := fs.Int("burst", def.Burst, "burst per client address")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *perHour <= 0 || *burst < 1 {
		return errors.New("per-hour and burst must be positive")
	}
	cfg := def
	cfg.GatewayURL, cfg.KeysURL = *gateway+"/gateway", *gateway+"/ohttp-keys"
	cfg.Rate, cfg.Burst = *perHour/3600, *burst
	r := relay.New(cfg)
	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		return err
	}
	hs := &http.Server{
		Handler:           r.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		ErrorLog:          log.New(logging.RedactingWriter{W: stderr}, "http: ", 0),
	}
	fmt.Fprintf(stderr, "ohttp-relay listening on %s, gateway %s (development only)\n", ln.Addr(), *gateway)
	errc := make(chan error, 1)
	go func() { errc <- hs.Serve(ln) }()
	if ready != nil {
		ready <- ln.Addr().String()
	}
	select {
	case err := <-errc:
		return err
	case <-ctx.Done():
		sctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		return hs.Shutdown(sctx)
	}
}
