#!/usr/bin/env bash
# Container smoke test for the TraFF server.
#
# Builds the image, creates a device token, starts a container with that token file, checks that
# requests without the token are rejected, waits until the Autobahn source has delivered data,
# subscribes with a bounding box, polls, changes the subscription and unsubscribes. A local
# DATEX II fixture is mounted into the container and served as second source (file:// feed).
#
# Environment:
#   PODMAN          podman command (default: "podman", or "distrobox-host-exec podman" inside distrobox)
#   SMOKE_PORT      host port (default 18080)
#   SMOKE_ROADS     roads to fetch (default "A7,A9", keeps the test short and the API load low)
#   SMOKE_OFFLINE=1 disable the Autobahn source (protocol test only, no network needed)
#   SMOKE_DATEX2    DATEX II fixture to serve (default internal/server/testdata/datex2_e2e.xml,
#                   empty = no DATEX II source)
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

DATEX2_FIXTURE="${SMOKE_DATEX2-internal/server/testdata/datex2_e2e.xml}"
DATA_DIR=""
TOKEN_DIR=""

cleanup() {
  $PODMAN logs "$NAME" >"${TMPDIR:-/tmp}/traff-smoke-$$.log" 2>&1 || true
  $PODMAN rm -f "$NAME" >/dev/null 2>&1 || true
  [[ -n "$DATA_DIR" ]] && rm -rf "$DATA_DIR"
  [[ -n "$TOKEN_DIR" ]] && rm -rf "$TOKEN_DIR"
}
trap cleanup EXIT

log "Baue Image $IMAGE"
$PODMAN build -q -t "$IMAGE" -f Containerfile . >/dev/null

log "Erzeuge Geräte-Token"
out=$($PODMAN run --rm --network none "$IMAGE" token smoke)
TOKEN=$(sed -n 's/^token=//p' <<<"$out")
TOKEN_DIR=$(mktemp -d "${TMPDIR:-/tmp}/traff-smoke-tokens.XXXXXX")
chmod 755 "$TOKEN_DIR"
sed -n 's/^line=//p' <<<"$out" >"$TOKEN_DIR/tokens"
chmod 644 "$TOKEN_DIR/tokens" # readable for uid 65532 in the container; only hashes
[[ -n "$TOKEN" && -s "$TOKEN_DIR/tokens" ]] || fail "kein Token erzeugt"

autobahn=true
[[ "${SMOKE_OFFLINE:-0}" == "1" ]] && autobahn=false
datex_args=()
if [[ -n "$DATEX2_FIXTURE" ]]; then
  # The fixture is copied into a temporary directory that is mounted read-only into the container.
  DATA_DIR=$(mktemp -d "${TMPDIR:-/tmp}/traff-smoke-data.XXXXXX")
  chmod 755 "$DATA_DIR"
  install -m 644 "$DATEX2_FIXTURE" "$DATA_DIR/datex2.xml"
  datex_args=(-v "$DATA_DIR:/data:ro,Z" -e TRAFF_DATEX2_FEEDS=local=file:///data/datex2.xml)
fi
log "Starte Container $NAME auf Port $PORT (Autobahn: $autobahn, Straßen: $ROADS, DATEX II: ${DATEX2_FIXTURE:-aus})"
$PODMAN run -d --name "$NAME" -p "127.0.0.1:${PORT}:8080" \
  -v "$TOKEN_DIR:/config:ro,Z" -e TRAFF_TOKENS_FILE=/config/tokens \
  -e TRAFF_AUTOBAHN_ENABLED="$autobahn" -e TRAFF_AUTOBAHN_ROADS="$ROADS" -e TRAFF_LOG_LEVEL=debug \
  "${datex_args[@]}" "$IMAGE" >/dev/null

uid=$($PODMAN inspect -f '{{.Config.User}}' "$NAME")
[[ "$uid" == "65532:65532" ]] || fail "Container läuft nicht als non-root (User: $uid)"

# Wait until every enabled source has completed its first fetch.
ready() {
  if [[ "$autobahn" == true ]] && ! grep -q '"name":"autobahn","last_attempt":"[1-9]' <<<"$1"; then
    return 1
  fi
  if [[ -n "$DATEX2_FIXTURE" ]] && ! grep -q '"name":"datex2-local","last_attempt":"[1-9]' <<<"$1"; then
    return 1
  fi
  [[ "$autobahn" == false && -z "$DATEX2_FIXTURE" ]] || grep -q '"messages":[1-9]' <<<"$1"
}

AUTH=(-H "Authorization: Bearer $TOKEN")

log "Warte auf /healthz"
for _ in $(seq 1 30); do
  curl -fsS "$URL/healthz" >/dev/null 2>&1 && break
  sleep 1
done
[[ "$(curl -fsS "$URL/healthz")" == ok ]] || fail "/healthz antwortet nicht nur mit ok"

log "Ohne oder mit falschem Token: 401"
for h in "" "Authorization: Bearer falsch-falsch-falsch-falsch-falsch" "Authorization: Basic $TOKEN"; do
  code=$(curl -sS -o /dev/null -w '%{http_code}' ${h:+-H "$h"} -X POST --data-binary '<request operation="GET_CAPABILITIES"/>' "$URL/")
  [[ "$code" == 401 ]] || fail "POST / mit '${h%% *}' = $code"
done
code=$(curl -sS -o /dev/null -w '%{http_code}' "$URL/status")
[[ "$code" == 401 ]] || fail "GET /status ohne Token = $code"

log "Warte auf /status (erster Abruf der Quellen)"
health=""
for _ in $(seq 1 90); do
  if health=$(curl -fsS "${AUTH[@]}" "$URL/status" 2>/dev/null) && ready "$health"; then
    break
  fi
  sleep 1
done
log "status: $health"
grep -q '"status":"ok"' <<<"$health" || fail "status nicht ok"
grep -q '"duplicates_suppressed":[0-9]' <<<"$health" || fail "healthz ohne Dublettenzähler"
if [[ "$autobahn" == true ]]; then
  grep -q '"name":"autobahn","last_attempt":"[^"]*","last_success":"[^"]*","messages":[1-9]' <<<"$health" ||
    fail "keine Meldungen von der Autobahn-API"
fi
if [[ -n "$DATEX2_FIXTURE" ]]; then
  grep -q '"name":"datex2-local","last_attempt":"[^"]*","last_success":"[^"]*","messages":[1-9]' <<<"$health" ||
    fail "keine Meldungen aus der DATEX-II-Fixture"
fi

traff() { curl -fsS -X POST "${AUTH[@]}" -H 'Content-Type: application/xml' --data-binary "$1" "$URL/"; }
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

if [[ -n "$DATEX2_FIXTURE" ]]; then
  log "DATEX-II-Meldungen in Deutschland (Einmalabfrage)"
  resp=$(traff '<request operation="POLL"><filter_list><filter bbox="47.27 5.87 55.06 15.04"/></filter_list></request>')
  ndatex=$(grep -c '<message id="de.datex2.local:' <<<"$resp" || true)
  nautobahn=$(grep -c '<message id="de.autobahn.' <<<"$resp" || true)
  log "Meldungen: DATEX II $ndatex, Autobahn $nautobahn"
  (( ndatex > 0 )) || fail "DATEX-II-Meldungen fehlen im Feed: $resp"
  grep -q '<message id="de.datex2.local:e2e-hamburg.0"' <<<"$resp" || fail "Meldung e2e-hamburg fehlt"
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
if grep -E -q "$id|$TOKEN|10\.[0-9]+\.[0-9]+\.[0-9]+|127\.0\.0\.1|curl/|bbox=|47\.27 8\.97|53\.3 9\.7" <<<"$logs"; then
  fail "Log enthält Subscription-ID, Token, Koordinaten, IP-Adresse oder User-Agent"
fi
grep -q 'operation=SUBSCRIBE' <<<"$logs" || fail "Debug-Log enthält keine Request-Zusammenfassung"
grep -q 'device=smoke' <<<"$logs" || fail "Log enthält kein Geräte-Label"
grep -q 'status=401 .*reason=auth' <<<"$logs" || fail "Log enthält keine abgewiesenen Anfragen"

log "OK"
