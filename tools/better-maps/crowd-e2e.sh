#!/usr/bin/env bash
# End-to-end test of the anonymous congestion reports without emulator (AP5b):
#
#   simulated trips of the Berlin scenario with a jam on the A100 (Go: crowd-vectors traces)
#     -> C++ reporting client of the app (libs/crowd_traffic, crowd_traffic_tests --filter=CrowdTrafficE2E):
#        segmentation, trimming, release, sampling, randomized response, binary HTTP
#       -> Java OHTTP sender of the app (android/sdk .../crowdtraffic, Bouncy Castle HPKE)
#         -> OHTTP relay (services/crowd-traffic/cmd/ohttp-relay)
#           -> gateway + aggregation (services/crowd-traffic/cmd/crowd-traffic, window 60 s, k = 10)
#
# and checks that /v1/segments shows the jam (and congestion nowhere else, nothing below k), that the
# TraFF feed contains CONGESTION messages and that the gateway accepted every bundle.
#
# Every simulated vehicle has its own client (daily limits, random start trim). The simulation runs in
# 15-minute windows; all bundles are mapped onto the server's current 60-second window, like crowd-sim does.
#
# Requirements: go, curl, a JDK (JAVA_HOME or ~/java/jdk-21*), Bouncy Castle and androidx.annotation jars in
# the Gradle cache (downloaded by any Android build), a C++ build environment (tools/better-maps/bm.sh).
#
# Environment:
#   CROWD_TRAFFIC_TESTS_BIN   prebuilt crowd_traffic_tests; if unset, it is built with bm.sh cpp-test
#   CROWD_E2E_SEED            seed of the simulated trips (default: random)
#   CROWD_E2E_KEEP=1          keep the working directory with logs
set -euo pipefail

REPO_ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
SERVICE="$REPO_ROOT/services/crowd-traffic"
SDK_JAVA="$REPO_ROOT/android/sdk/src/main/java/app/organicmaps/sdk/crowdtraffic"
# Free ports: fixed ports collide with tests running in parallel in other worktrees.
free_port() { python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])'; }
PORT="$(free_port)"
RPORT="$(free_port)"
URL="http://127.0.0.1:${PORT}"
RELAY="http://127.0.0.1:${RPORT}"
WINDOW=60
GRACE=5
SEED="${CROWD_E2E_SEED:-$RANDOM}"

log() { echo "[crowd-e2e] $*" >&2; }
fail() { echo "[crowd-e2e] FEHLER: $*" >&2; exit 1; }

JAVA_HOME="${JAVA_HOME:-$(ls -d "$HOME"/java/jdk-21* 2>/dev/null | head -1)}"
[[ -x "$JAVA_HOME/bin/javac" ]] || fail "JDK nicht gefunden (JAVA_HOME setzen)"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Xlog:os+container=off}"
for tool in go curl python3; do
  command -v "$tool" >/dev/null || fail "$tool nicht gefunden"
done
find_jar() { find "$HOME/.gradle/caches/modules-2/files-2.1/$1" -name "$2" 2>/dev/null | sort -V | tail -1; }
BC_JAR="$(find_jar org.bouncycastle/bcprov-jdk18on 'bcprov-jdk18on-1.81.jar')"
ANN_JAR="$(find_jar androidx.annotation/annotation-jvm 'annotation-jvm-*.jar')"
[[ -f "$BC_JAR" && -f "$ANN_JAR" ]] || fail "Bouncy Castle oder androidx.annotation fehlt im Gradle-Cache (einmal bm.sh android-build ausführen)"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/crowd-e2e.XXXXXX")"
pids=()
cleanup() {
  for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done
  if [[ "${CROWD_E2E_KEEP:-0}" == "1" ]]; then log "Arbeitsverzeichnis: $WORK"; else rm -rf "$WORK"; fi
}
trap cleanup EXIT

log "Baue Gateway, Relay und crowd-vectors"
(cd "$SERVICE" && go build -o "$WORK/crowd-traffic" ./cmd/crowd-traffic && go build -o "$WORK/ohttp-relay" ./cmd/ohttp-relay \
  && go build -o "$WORK/crowd-vectors" ./cmd/crowd-vectors)

log "Baue den Java-Sender der App"
mkdir -p "$WORK/classes"
"$JAVA_HOME/bin/javac" -nowarn -d "$WORK/classes" -cp "$BC_JAR:$ANN_JAR" \
  "$SDK_JAVA/Ohttp.java" "$SDK_JAVA/BinaryHttp.java" "$SDK_JAVA/RelayEndpoints.java" "$SDK_JAVA/CrowdTrafficSender.java" \
  "$REPO_ROOT/tools/better-maps/crowd-e2e/SendRequests.java"

bin="${CROWD_TRAFFIC_TESTS_BIN:-}"
if [[ -z "$bin" ]]; then
  log "Baue crowd_traffic_tests (und führe die Unit-Tests aus)"
  (unset CROWD_E2E_TRACES; "$REPO_ROOT/tools/better-maps/bm.sh" cpp-test crowd_traffic_tests >"$WORK/unit-tests.log" 2>&1) \
    || { tail -40 "$WORK/unit-tests.log" >&2; fail "crowd_traffic_tests fehlgeschlagen"; }
  tail -1 "$WORK/unit-tests.log" >&2
  bin="$(find "$REPO_ROOT/build/desktop" -type f -name crowd_traffic_tests -perm -u+x | head -1)"
fi
[[ -x "$bin" ]] || fail "crowd_traffic_tests nicht gefunden"

MASTER=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
log "Starte Gateway auf :$PORT (Fenster ${WINDOW}s, k = 10) und Relay auf :$RPORT"
CROWD_OHTTP_MASTER_KEY="$MASTER" "$WORK/crowd-traffic" -listen "127.0.0.1:$PORT" -window "${WINDOW}s" -grace "${GRACE}s" \
  -tick 1s -rate-burst 5000 -summary-interval 0 >"$WORK/gateway.log" 2>&1 &
pids+=($!)
"$WORK/ohttp-relay" -listen "127.0.0.1:$RPORT" -gateway "$URL" -per-hour 100000 -burst 100000 >"$WORK/relay.log" 2>&1 &
pids+=($!)
for _ in $(seq 1 50); do
  curl -fsS "$URL/healthz" >/dev/null 2>&1 && curl -fsS -o /dev/null "$RELAY/ohttp-keys" 2>/dev/null && break
  sleep 0.2
done
curl -fsS "$URL/healthz" | grep -q '"status":"ok"' || { cat "$WORK/gateway.log" >&2; fail "Gateway startet nicht"; }

log "Simulierte Fahrten (Seed $SEED)"
"$WORK/crowd-vectors" traces -seed "$SEED" -out "$WORK/traces.txt"
log "$(grep -c '^T ' "$WORK/traces.txt") Fahrten, $(grep -vc '^T ' "$WORK/traces.txt") Positionen"

# Enough time left in the server window for client and sender.
now=$(date +%s)
left=$(( WINDOW - now % WINDOW ))
if (( left < 40 )); then
  log "Warte ${left}s auf das nächste Serverfenster"
  sleep $(( left + 1 ))
fi
now=$(date +%s)
cur=$(( now - now % WINDOW ))

log "C++-Client: Bündel für Fenster $cur"
(
  cd "$(dirname "$bin")"
  CROWD_E2E_TRACES="$WORK/traces.txt" CROWD_E2E_OUT="$WORK/requests.hex" CROWD_E2E_WINDOW="$cur" CROWD_E2E_NOW="$now" \
    "$bin" --data_path="$REPO_ROOT/data" --user_resource_path="$REPO_ROOT/data" --filter='CrowdTrafficE2E'
) >"$WORK/client.log" 2>&1 || { cat "$WORK/client.log" >&2; fail "C++-Client fehlgeschlagen"; }
grep -E "Trips:|Client statistics" "$WORK/client.log" | sed 's/^/[crowd-e2e]   /' >&2
sent=$(grep -c . "$WORK/requests.hex")
size=$(head -1 "$WORK/requests.hex" | tr -d '\n' | wc -c)
(( size == 8192 )) || fail "Anfrage nicht auf 4096 Byte aufgefüllt ($((size / 2)) Byte)"

log "Java-Sender: $sent Bündel über das Relay"
"$JAVA_HOME/bin/java" -cp "$WORK/classes:$BC_JAR:$ANN_JAR" SendRequests "$RELAY" "$WORK/requests.hex" \
  >"$WORK/sender.log" 2>"$WORK/sender.err" || { tail -20 "$WORK/sender.err" >&2; cat "$WORK/sender.log" >&2; fail "nicht alle Bündel angenommen"; }
cat "$WORK/sender.log" | sed 's/^/[crowd-e2e]   /' >&2
(( $(date +%s) < cur + WINDOW + GRACE )) || fail "Senden dauerte zu lange (Fenster bereits ausgewertet)"

health=$(curl -fsS "$URL/healthz")
accepted=$(grep -o '"bundles_accepted":[0-9]*' <<<"$health" | cut -d: -f2)
log "Gateway: $accepted Bündel angenommen"
(( accepted == sent )) || fail "Gateway hat $accepted von $sent Bündeln angenommen: $health"

wait_s=$(( cur + WINDOW + GRACE + 3 - $(date +%s) ))
log "Warte ${wait_s}s auf die Auswertung"
sleep "$wait_s"

log "Prüfe /v1/segments gegen das Szenario"
"$WORK/crowd-vectors" check -server "$URL" | sed 's/^/[crowd-e2e]   /' >&2 || fail "Export passt nicht zur Simulation"
feed=$(curl -fsS "$URL/v1/traff")
n=$(grep -c '<message ' <<<"$feed" || true)
log "TraFF-Feed: $n Meldungen"
grep -q 'type="CONGESTION_\(QUEUE\|STATIONARY_TRAFFIC\)"' <<<"$feed" || fail "kein Stau im TraFF-Feed"
grep -q 'road_class="TERTIARY"' <<<"$feed" && fail "Dorfstraße (unter k) im TraFF-Feed"
if grep -v 'listening' "$WORK/gateway.log" "$WORK/relay.log" | grep -E -q '127\.0\.0\.1|speed_bucket|"tile"'; then
  fail "Logs enthalten Adressen oder Meldungsdaten"
fi
log "OK"
