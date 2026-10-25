#!/usr/bin/env bash
# End-to-end test of the TraFF integration without emulator (suitable for CI):
#
#   fake Autobahn API (static fixture, python3 http.server)
#     -> TraFF server (services/traff-server, built with go)
#       -> C++ TraFF client (libs/traffxml HttpTraffSource, traffxml_tests --filter=TraffE2E)
#         -> TraFF decoder on a real map (Germany_Berlin.mwm)
#
# The test subscribes, receives the first feed, decodes the messages (a closure and a queue on the
# A100 in Berlin) to map segments, then restarts the server and checks that the client subscribes
# again by itself (SUBSCRIPTION_UNKNOWN handling).
#
# Requirements: go, python3, curl, a C++ build environment (see tools/better-maps/bm.sh) and the
# map Germany_Berlin.mwm (compatible with the map series of this source tree).
#
# Environment:
#   TRAFF_E2E_MAPS_DIR    directory containing Germany_Berlin.mwm (default ~/.cache/bettermaps-maps)
#                         Locally, the map can be copied from the emulator, e.g.
#                         adb pull /sdcard/Android/data/app.comaps.fdroid.debug/files/<version>/Germany_Berlin.mwm
#   TRAFF_E2E_PORT        port of the TraFF server (default 18081)
#   TRAFF_E2E_API_PORT    port of the fake Autobahn API (default 18082)
#   TRAFFXML_TESTS_BIN    prebuilt traffxml_tests binary; if unset, it is built with bm.sh cpp-test
#                         (which also runs the unit tests)
#   TRAFF_E2E_KEEP=1      keep the working directory with server logs
set -euo pipefail

REPO_ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
FIXTURE="$REPO_ROOT/tools/better-maps/traffic-e2e/autobahn"
MAPS_DIR="${TRAFF_E2E_MAPS_DIR:-$HOME/.cache/bettermaps-maps}"
# Freie Ports wählen lassen: feste Ports kollidieren mit parallel laufenden Tests anderer Worktrees.
free_port() { python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])'; }
PORT="${TRAFF_E2E_PORT:-$(free_port)}"
API_PORT="${TRAFF_E2E_API_PORT:-$(free_port)}"
URL="http://127.0.0.1:${PORT}/"

log() { echo "[traffic-e2e] $*" >&2; }
fail() { echo "[traffic-e2e] FEHLER: $*" >&2; exit 1; }

for tool in go python3 curl; do
  command -v "$tool" >/dev/null || fail "$tool nicht gefunden"
done
[[ -f "$MAPS_DIR/Germany_Berlin.mwm" ]] || fail "Karte fehlt: $MAPS_DIR/Germany_Berlin.mwm (TRAFF_E2E_MAPS_DIR setzen)"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/traffic-e2e.XXXXXX")"
api_pid=""
cleanup() {
  [[ -f "$WORK/server.pid" ]] && kill "$(cat "$WORK/server.pid")" 2>/dev/null || true
  [[ -n "$api_pid" ]] && kill "$api_pid" 2>/dev/null || true
  if [[ "${TRAFF_E2E_KEEP:-0}" == "1" ]]; then
    log "Arbeitsverzeichnis: $WORK"
  else
    rm -rf "$WORK"
  fi
}
trap cleanup EXIT

log "Baue TraFF-Server"
(cd "$REPO_ROOT/services/traff-server" && go build -o "$WORK/traff-server" ./cmd/traff-server)

log "Starte Autobahn-API-Attrappe auf Port $API_PORT"
python3 -m http.server "$API_PORT" --bind 127.0.0.1 --directory "$FIXTURE" >"$WORK/api.log" 2>&1 &
api_pid=$!

# Start and restart scripts, the restart script is also called by the C++ test.
cat >"$WORK/start-server.sh" <<EOF
#!/usr/bin/env bash
set -euo pipefail
"$WORK/traff-server" -listen 127.0.0.1:$PORT -autobahn-url http://127.0.0.1:$API_PORT \\
  -autobahn-roads A100 -autobahn-request-interval 10ms -poll-interval 10s -log-level debug \\
  >>"$WORK/server.log" 2>&1 &
echo \$! >"$WORK/server.pid"
for _ in \$(seq 1 100); do
  if curl -fsS "http://127.0.0.1:$PORT/healthz" 2>/dev/null | grep -q '"messages":[1-9]'; then
    exit 0
  fi
  sleep 0.2
done
echo "TraFF-Server liefert keine Meldungen, siehe $WORK/server.log" >&2
exit 1
EOF
cat >"$WORK/restart-server.sh" <<EOF
#!/usr/bin/env bash
set -euo pipefail
pid=\$(cat "$WORK/server.pid")
kill "\$pid"
while kill -0 "\$pid" 2>/dev/null; do sleep 0.1; done
echo "--- restart ---" >>"$WORK/server.log"
exec "$WORK/start-server.sh"
EOF
chmod +x "$WORK/start-server.sh" "$WORK/restart-server.sh"

log "Starte TraFF-Server auf Port $PORT"
"$WORK/start-server.sh" || { cat "$WORK/server.log" >&2; fail "Server startet nicht"; }
log "healthz: $(curl -fsS "http://127.0.0.1:$PORT/healthz")"

bin="${TRAFFXML_TESTS_BIN:-}"
if [[ -z "$bin" ]]; then
  log "Baue traffxml_tests (und führe die Unit-Tests aus)"
  (unset TRAFF_E2E_URL; "$REPO_ROOT/tools/better-maps/bm.sh" cpp-test traffxml_tests >"$WORK/unit-tests.log" 2>&1) \
    || { tail -50 "$WORK/unit-tests.log" >&2; fail "traffxml_tests fehlgeschlagen"; }
  tail -1 "$WORK/unit-tests.log" >&2
  bin="$(find "$REPO_ROOT/build/desktop" -type f -name traffxml_tests -perm -u+x | head -1)"
fi
[[ -x "$bin" ]] || fail "traffxml_tests nicht gefunden"

log "Starte Ende-zu-Ende-Tests gegen $URL"
rc=0
(
  cd "$(dirname "$bin")"
  TRAFF_E2E_URL="$URL" TRAFF_E2E_MAPS_DIR="$MAPS_DIR" TRAFF_E2E_MWMS="Germany_Berlin" \
  TRAFF_E2E_RESTART_CMD="$WORK/restart-server.sh" TRAFF_E2E_EXPECT_FIXTURE=1 \
    "$bin" --data_path="$REPO_ROOT/data" --user_resource_path="$REPO_ROOT/data" --filter='TraffE2E'
) 2>&1 | tee "$WORK/e2e.log" || rc=$?
[[ $rc -eq 0 ]] && grep -q "All tests passed" "$WORK/e2e.log" || { tail -40 "$WORK/server.log" >&2; fail "Ende-zu-Ende-Tests fehlgeschlagen"; }
# make sure the tests did not skip themselves
grep -q "Decoded .* of .* message" "$WORK/e2e.log" || fail "Dekodier-Test wurde nicht ausgeführt"
grep -q "Restarting service" "$WORK/e2e.log" || fail "Neustart-Test wurde nicht ausgeführt"
log "OK"
