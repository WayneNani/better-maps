#!/usr/bin/env bash
# Container smoke test for the TraFF server.
#
# Builds the image, starts a container, waits until the Autobahn source has delivered data,
# subscribes with a bounding box, polls, changes the subscription and unsubscribes.
#
# Environment:
#   PODMAN          podman command (default: "podman", or "distrobox-host-exec podman" inside distrobox)
#   SMOKE_PORT      host port (default 18080)
#   SMOKE_ROADS     roads to fetch (default "A7,A9", keeps the test short and the API load low)
#   SMOKE_OFFLINE=1 disable the Autobahn source (protocol test only, no network needed)
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [[ -z "${PODMAN:-}" ]]; then
  if command -v podman >/dev/null 2>&1; then
    PODMAN="podman"
  else
    PODMAN="distrobox-host-exec podman"
  fi
fi
PORT="${SMOKE_PORT:-18080}"
ROADS="${SMOKE_ROADS:-A7,A9}"
IMAGE="localhost/traff-server:smoke"
NAME="traff-smoke-$$"
URL="http://127.0.0.1:${PORT}"

log() { echo "[smoke] $*" >&2; }
fail() { echo "[smoke] FEHLER: $*" >&2; exit 1; }

cleanup() {
  $PODMAN logs "$NAME" >"${TMPDIR:-/tmp}/traff-smoke-$$.log" 2>&1 || true
  $PODMAN rm -f "$NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

log "Baue Image $IMAGE"
$PODMAN build -q -t "$IMAGE" -f Containerfile . >/dev/null

autobahn=true
[[ "${SMOKE_OFFLINE:-0}" == "1" ]] && autobahn=false
log "Starte Container $NAME auf Port $PORT (Autobahn: $autobahn, Straßen: $ROADS)"
$PODMAN run -d --name "$NAME" -p "127.0.0.1:${PORT}:8080" \
  -e TRAFF_AUTOBAHN_ENABLED="$autobahn" -e TRAFF_AUTOBAHN_ROADS="$ROADS" -e TRAFF_LOG_LEVEL=debug \
  "$IMAGE" >/dev/null

uid=$($PODMAN inspect -f '{{.Config.User}}' "$NAME")
[[ "$uid" == "65532:65532" ]] || fail "Container läuft nicht als non-root (User: $uid)"

log "Warte auf /healthz"
health=""
for _ in $(seq 1 90); do
  if health=$(curl -fsS "$URL/healthz" 2>/dev/null); then
    if [[ "$autobahn" == false ]] || grep -q '"messages":[1-9]' <<<"$health"; then
      break
    fi
  fi
  sleep 1
done
log "healthz: $health"
grep -q '"status":"ok"' <<<"$health" || fail "healthz nicht ok"
if [[ "$autobahn" == true ]]; then
  grep -q '"messages":[1-9]' <<<"$health" || fail "keine Meldungen von der Autobahn-API"
fi

traff() { curl -fsS -X POST -H 'Content-Type: application/xml' --data-binary "$1" "$URL/"; }
attr() { sed -n "s/.*<response[^>]* $1=\"\([^\"]*\)\".*/\1/p" <<<"$2" | head -n1; }

log "GET_CAPABILITIES"
resp=$(traff '<request operation="GET_CAPABILITIES"/>')
grep -q '<capabilities min_version="0.8" target_version="0.8">' <<<"$resp" || fail "capabilities: $resp"

log "SUBSCRIBE (Bayern + Hamburg)"
resp=$(traff '<request operation="SUBSCRIBE">
<filter_list>
<filter bbox="47.27 8.97 50.56 13.84"/>
<filter bbox="53.3 9.7 53.7 10.3"/>
</filter_list>
</request>')
status=$(attr status "$resp")
id=$(attr subscription_id "$resp")
timeout=$(attr timeout "$resp")
count=$(grep -c '<message ' <<<"$resp" || true)
log "status=$status timeout=$timeout Meldungen=$count"
[[ "$status" == "OK" || "$status" == "PARTIALLY_COVERED" ]] || fail "subscribe: $resp"
[[ "$id" =~ ^[0-9a-f]{32}$ ]] || fail "ungültige Subscription-ID"
if [[ "$autobahn" == true ]]; then
  (( count > 0 )) || fail "erster Feed ist leer"
fi

log "POLL"
resp=$(traff "<request operation=\"POLL\" subscription_id=\"$id\"/>")
[[ "$(attr status "$resp")" == "OK" ]] || fail "poll: $resp"
log "Poll-Feed: $(grep -c '<message ' <<<"$resp" || true) Meldungen (erwartet: 0 oder nur Änderungen)"

log "SUBSCRIPTION_CHANGE (zusätzlich Hessen)"
resp=$(traff "<request operation=\"SUBSCRIPTION_CHANGE\" subscription_id=\"$id\">
<filter_list>
<filter bbox=\"47.27 8.97 50.56 13.84\"/>
<filter bbox=\"53.3 9.7 53.7 10.3\"/>
<filter bbox=\"49.39 7.77 51.66 10.24\"/>
</filter_list>
</request>")
[[ "$(attr status "$resp")" == "OK" ]] || fail "change: $resp"
log "Change-Feed: $(grep -c '<message ' <<<"$resp" || true) zusätzliche Meldungen"

log "NOT_COVERED außerhalb Deutschlands"
resp=$(traff '<request operation="SUBSCRIBE"><filter_list><filter bbox="48.7 2.2 49.0 2.5"/></filter_list></request>')
[[ "$(attr status "$resp")" == "NOT_COVERED" ]] || fail "not covered: $resp"

log "UNSUBSCRIBE"
resp=$(traff "<request operation=\"UNSUBSCRIBE\" subscription_id=\"$id\"/>")
[[ "$(attr status "$resp")" == "OK" ]] || fail "unsubscribe: $resp"
resp=$(traff "<request operation=\"POLL\" subscription_id=\"$id\"/>")
[[ "$(attr status "$resp")" == "SUBSCRIPTION_UNKNOWN" ]] || fail "poll nach unsubscribe: $resp"

log "Prüfe Container-Log auf Client-Daten"
logs=$($PODMAN logs "$NAME" 2>&1)
if grep -E -q "$id|10\.[0-9]+\.[0-9]+\.[0-9]+|127\.0\.0\.1|curl/" <<<"$logs"; then
  fail "Log enthält Subscription-ID, IP-Adresse oder User-Agent"
fi
grep -q 'operation=SUBSCRIBE' <<<"$logs" || fail "Debug-Log enthält keine Request-Zusammenfassung"

log "OK"
