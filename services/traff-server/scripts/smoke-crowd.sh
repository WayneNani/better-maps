#!/usr/bin/env bash
# Container smoke test for the crowd source of the TraFF server.
#
# Builds the images of crowd-traffic and traff-server and starts in one pod: the crowd-traffic
# gateway (window 60 s, grace 5 s), the development OHTTP relay and the TraFF server with only
# the crowd source (TRAFF_CROWD_URL, interval 10 s). The simulator (A100 jam scenario) reports
# through the relay. Checks:
#   - the TraFF feed of the traff-server contains the crowd jam as few merged CONGESTION_*
#     messages (fewer than congested tiles in the crowd export), accepted by a TraFF subscription,
#   - /healthz of the traff-server reports the crowd source with its counters,
#   - when the publications expire (65 s after the evaluation with a 60 s window), the
#     traff-server sends cancellations,
#   - the traff-server log contains no client addresses.
#
# Environment:
#   PODMAN      podman command (default: "podman", or "distrobox-host-exec podman" inside distrobox)
#   SMOKE_PORT  host port of the traff-server (default 18082); crowd-traffic uses SMOKE_PORT+1,
#               the relay SMOKE_PORT+2
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
CROWD_DIR="../crowd-traffic"

if [[ -z "${PODMAN:-}" ]]; then
  if command -v podman >/dev/null 2>&1; then
    PODMAN="podman"
  else
    PODMAN="distrobox-host-exec podman"
  fi
fi
PORT="${SMOKE_PORT:-18082}"
CPORT=$((PORT + 1))
RPORT=$((PORT + 2))
POD="traff-crowd-smoke-$$"
URL="http://127.0.0.1:${PORT}"
CROWD="http://127.0.0.1:${CPORT}"
RELAY="http://127.0.0.1:${RPORT}"
LOGDIR="${TMPDIR:-/tmp}"

log() { echo "[smoke-crowd] $*" >&2; }
fail() { echo "[smoke-crowd] FEHLER: $*" >&2; exit 1; }

cleanup() {
  $PODMAN logs "$POD-traff" >"$LOGDIR/traff-crowd-smoke-$$-traff.log" 2>&1 || true
  $PODMAN logs "$POD-crowd" >"$LOGDIR/traff-crowd-smoke-$$-crowd.log" 2>&1 || true
  $PODMAN pod rm -f "$POD" >/dev/null 2>&1 || true
}
trap cleanup EXIT

log "Baue Images"
$PODMAN build -q -t localhost/traff-server:smoke -f Containerfile . >/dev/null
(cd "$CROWD_DIR" && $PODMAN build -q -t localhost/crowd-traffic:smoke -f Containerfile . >/dev/null)

MASTER=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
log "Starte Pod $POD (traff-server :$PORT, crowd-traffic :$CPORT, Relay :$RPORT)"
$PODMAN pod create --name "$POD" -p "127.0.0.1:${PORT}:8081" -p "127.0.0.1:${CPORT}:8080" -p "127.0.0.1:${RPORT}:8090" >/dev/null
$PODMAN run -d --pod "$POD" --name "$POD-crowd" \
  -e CROWD_WINDOW=60s -e CROWD_GRACE=5s -e CROWD_TICK=1s -e CROWD_RATE_BURST=2000 \
  -e CROWD_OHTTP_MASTER_KEY="$MASTER" localhost/crowd-traffic:smoke >/dev/null
$PODMAN run -d --pod "$POD" --name "$POD-relay" --entrypoint /usr/local/bin/ohttp-relay localhost/crowd-traffic:smoke \
  -listen :8090 -gateway http://localhost:8080 -per-hour 100000 -burst 100000 >/dev/null
$PODMAN run -d --pod "$POD" --name "$POD-traff" \
  -e TRAFF_LISTEN=:8081 -e TRAFF_AUTOBAHN_ENABLED=false -e TRAFF_CROWD_URL=http://localhost:8080 \
  -e TRAFF_CROWD_INTERVAL=10s -e TRAFF_LOG_LEVEL=debug localhost/traff-server:smoke >/dev/null

log "Warte auf /healthz beider Server"
for _ in $(seq 1 30); do
  curl -fsS "$CROWD/healthz" >/dev/null 2>&1 && curl -fsS "$URL/healthz" >/dev/null 2>&1 && break
  sleep 1
done
health=$(curl -fsS "$URL/healthz") || fail "healthz des traff-servers nicht erreichbar"
grep -q '"name":"crowd"' <<<"$health" || fail "Crowd-Quelle nicht registriert: $health"

traff() { curl -fsS -X POST -H 'Content-Type: application/xml' --data-binary "$1" "$URL/"; }
attr() { sed -n "s/.*<response[^>]* $1=\"\([^\"]*\)\".*/\1/p" <<<"$2" | head -n1; }

log "Abo für Berlin (noch leer)"
resp=$(traff '<request operation="SUBSCRIBE"><filter_list><filter bbox="52.3 13.1 52.7 13.7"/></filter_list></request>')
[[ "$(attr status "$resp")" == "OK" ]] || fail "subscribe: $resp"
id=$(attr subscription_id "$resp")

log "Simulator (A100-Stau) über das Relay"
(cd "$CROWD_DIR" && go run ./cmd/crowd-sim -relay "$RELAY/relay" -server "$CROWD" -window 60s -grace 5s -check -seed "$$") \
  || fail "Simulator meldet Abweichungen"

tiles=$(curl -fsS "$CROWD/v1/traff" | grep -c '<message ' || true)
log "crowd-traffic: $tiles Stau-Kacheln im TraFF-Feed"
(( tiles > 1 )) || fail "zu wenige Stau-Kacheln für den Test"

log "Warte auf die Crowd-Meldungen im traff-server"
resp=""
for _ in $(seq 1 30); do
  resp=$(traff "<request operation=\"POLL\" subscription_id=\"$id\"/>")
  grep -q '<message id="de.crowd:' <<<"$resp" && break
  sleep 1
done
n=$(grep -c '<message id="de.crowd:' <<<"$resp" || true)
log "traff-server: $n Crowd-Meldungen"
grep -o '<message id="de.crowd:[^"]*"' <<<"$resp" >&2 || true
(( n > 0 )) || fail "keine Crowd-Meldungen im Feed: $resp"
(( n < tiles )) || fail "Kacheln wurden nicht zusammengefasst ($n Meldungen aus $tiles Kacheln)"
grep -q 'type="CONGESTION_\(QUEUE\|STATIONARY_TRAFFIC\|SLOW_TRAFFIC\|HEAVY_TRAFFIC\)" speed="[0-9]*"' <<<"$resp" || fail "kein Stau-Ereignis: $resp"
grep -q '<via>' <<<"$resp" || fail "zusammengefasste Meldung ohne via: $resp"
grep -q 'road_class="MOTORWAY"' <<<"$resp" || fail "keine Autobahn-Meldung: $resp"
if grep -q 'road_class="TERTIARY"' <<<"$resp"; then fail "Dorfstraße (unter k) im Feed"; fi

health=$(curl -fsS "$URL/healthz")
log "healthz: $health"
grep -q '"name":"crowd","last_attempt":"[^"]*","last_success":"[^"]*","messages":[1-9]' <<<"$health" || fail "healthz ohne Crowd-Meldungen"
grep -q '"congested_segments":[1-9]' <<<"$health" || fail "healthz ohne Crowd-Zähler"

log "Warte auf das Ende der Veröffentlichung (Aufhebung im traff-server)"
cancelled=0
for _ in $(seq 1 120); do
  resp=$(traff "<request operation=\"POLL\" subscription_id=\"$id\"/>")
  if grep -q 'cancellation="true"' <<<"$resp"; then
    cancelled=$(grep -c 'cancellation="true"' <<<"$resp" || true)
    break
  fi
  sleep 1
done
(( cancelled > 0 )) || fail "keine Aufhebung nach Ablauf der Veröffentlichung"
log "Aufhebungen: $cancelled"

log "Prüfe Log des traff-servers auf Client-Daten"
logs=$($PODMAN logs "$POD-traff" 2>&1)
if grep -E -q "$id|10\.[0-9]+\.[0-9]+\.[0-9]+|127\.0\.0\.1|curl/" <<<"$logs"; then
  fail "Log enthält Subscription-ID, IP-Adresse oder User-Agent"
fi
grep -q 'msg="source fetched" source=crowd' <<<"$logs" || fail "kein Abruf der Crowd-Quelle im Log"

log "OK"
