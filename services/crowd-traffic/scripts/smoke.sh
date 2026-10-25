#!/usr/bin/env bash
# Container smoke test for the crowd-traffic server.
#
# Builds the image, starts gateway and development relay in one pod (window 60 s, grace 5 s),
# runs the simulator (A100 jam scenario) through the relay and checks:
#   - the container runs as non-root, /healthz and /ohttp-keys work,
#   - the export contains congestion only in the simulated jam and nothing below k,
#   - the TraFF feed contains CONGESTION_* messages,
#   - the logs contain no client addresses, user agents or report data,
#   - a replayed request is rejected.
#
# Environment:
#   PODMAN      podman command (default: "podman", or "distrobox-host-exec podman" inside distrobox)
#   SMOKE_PORT  host port of the gateway (default 18081); the relay uses SMOKE_PORT+9
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [[ -z "${PODMAN:-}" ]]; then
  if command -v podman >/dev/null 2>&1; then
    PODMAN="podman"
  else
    PODMAN="distrobox-host-exec podman"
  fi
fi
PORT="${SMOKE_PORT:-18081}"
RPORT=$((PORT + 9))
IMAGE="localhost/crowd-traffic:smoke"
POD="crowd-smoke-$$"
URL="http://127.0.0.1:${PORT}"
RELAY="http://127.0.0.1:${RPORT}"
LOGDIR="${TMPDIR:-/tmp}"

log() { echo "[smoke] $*" >&2; }
fail() { echo "[smoke] FEHLER: $*" >&2; exit 1; }

cleanup() {
  $PODMAN logs "$POD-gw" >"$LOGDIR/crowd-smoke-$$-gateway.log" 2>&1 || true
  $PODMAN logs "$POD-relay" >"$LOGDIR/crowd-smoke-$$-relay.log" 2>&1 || true
  $PODMAN pod rm -f "$POD" >/dev/null 2>&1 || true
}
trap cleanup EXIT

log "Baue Image $IMAGE"
$PODMAN build -q -t "$IMAGE" -f Containerfile . >/dev/null

MASTER=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
log "Starte Pod $POD (Gateway :$PORT, Relay :$RPORT)"
$PODMAN pod create --name "$POD" -p "127.0.0.1:${PORT}:8080" -p "127.0.0.1:${RPORT}:8090" >/dev/null
$PODMAN run -d --pod "$POD" --name "$POD-gw" \
  -e CROWD_WINDOW=60s -e CROWD_GRACE=5s -e CROWD_TICK=1s -e CROWD_LOG_LEVEL=debug \
  -e CROWD_SUMMARY_INTERVAL=10s -e CROWD_RATE_BURST=2000 -e CROWD_OHTTP_MASTER_KEY="$MASTER" "$IMAGE" >/dev/null
$PODMAN run -d --pod "$POD" --name "$POD-relay" --entrypoint /usr/local/bin/ohttp-relay "$IMAGE" \
  -listen :8090 -gateway http://localhost:8080 -per-hour 100000 -burst 100000 >/dev/null

uid=$($PODMAN inspect -f '{{.Config.User}}' "$POD-gw")
[[ "$uid" == "65532:65532" ]] || fail "Container läuft nicht als non-root (User: $uid)"

log "Warte auf /healthz"
for _ in $(seq 1 30); do
  curl -fsS "$URL/healthz" >/dev/null 2>&1 && break
  sleep 1
done
health=$(curl -fsS "$URL/healthz") || fail "healthz nicht erreichbar"
grep -q '"status":"ok"' <<<"$health" || fail "healthz: $health"

log "Schlüssel über das Relay"
ct=$(curl -fsS -o /dev/null -w '%{content_type}' "$RELAY/ohttp-keys")
[[ "$ct" == "application/ohttp-keys" ]] || fail "ohttp-keys: $ct"

log "Simulator (A100-Stau) über das Relay, mit Prüfung des Exports"
go run ./cmd/crowd-sim -relay "$RELAY/relay" -server "$URL" -window 60s -grace 5s -check -seed "$$" \
  || fail "Simulator meldet Abweichungen"

feed=$(curl -fsS "$URL/v1/traff")
n=$(grep -c '<message ' <<<"$feed" || true)
log "TraFF-Feed: $n Meldungen"
(( n > 0 )) || fail "TraFF-Feed leer"
grep -q 'type="CONGESTION_\(QUEUE\|STATIONARY_TRAFFIC\)"' <<<"$feed" || fail "kein Stau im Feed"
grep -q 'road_class="TERTIARY"' <<<"$feed" && fail "Dorfstraße (unter k) im Feed"

log "Replay wird abgewiesen"
go run ./cmd/crowd-sim -relay "$RELAY/relay" -replay-check || fail "Replay nicht abgewiesen"

health=$(curl -fsS "$URL/healthz")
log "healthz: $health"
grep -q '"replays":1' <<<"$health" || fail "Replay nicht gezählt"

sleep 11 # one summary line
log "Prüfe Logs auf Client-Daten"
logs=$($PODMAN logs "$POD-gw" 2>&1; $PODMAN logs "$POD-relay" 2>&1)
if grep -E -q '10\.[0-9]+\.[0-9]+\.[0-9]+|127\.0\.0\.1|Go-http-client|curl/|speed_bucket|"tile"' <<<"$logs"; then
  grep -E '10\.[0-9]+\.[0-9]+\.[0-9]+|127\.0\.0\.1|Go-http-client|curl/|speed_bucket|"tile"' <<<"$logs" >&2
  fail "Log enthält IP-Adressen, User-Agents oder Meldungsdaten"
fi
grep -q 'msg=summary' <<<"$logs" || fail "keine Zusammenfassung im Log"

log "OK"
