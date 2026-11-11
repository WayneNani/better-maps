#!/usr/bin/env bash
# Smoke test against a running gateway: health, auth (401 without and with a wrong token), no OSRM passthrough and,
# with ROUTE_TOKEN set (taken from the environment, never from the command line), a route Bonn -> Nuremberg.
#
# Usage: ROUTE_TOKEN=... scripts/smoke.sh
# Env:   ROUTE_URL (default http://127.0.0.1:$ROUTE_PORT), ROUTE_SMOKE_POINTS (JSON points, default Bonn -> Nuremberg)
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
base="${ROUTE_URL:-http://127.0.0.1:$ROUTE_PORT}"
points="${ROUTE_SMOKE_POINTS:-[[50.7295477,7.1057999],[49.453861,11.0772885]]}"
fail=0
check() { # <name> <expected status> <actual status>
  if [[ "$2" == "$3" ]]; then log "ok   $1 ($3)"; else log "FAIL $1: expected $2, got $3"; fail=1; fi
}
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
# The token goes to curl through a file descriptor, not the command line (process list).
auth_header() { printf 'Authorization: Bearer %s\n' "$1"; }

check healthz 200 "$(status "$base/healthz")"
check "no token" 401 "$(status -X POST -H 'Content-Type: application/json' -d "{\"points\":$points}" "$base/v1/route")"
check "wrong token" 401 "$(status -X POST -H 'Content-Type: application/json' -H @<(auth_header wrong-token-0123456789abcdef) \
  -d "{\"points\":$points}" "$base/v1/route")"
check "no OSRM passthrough" 404 "$(status "$base/route/v1/driving/7.1,50.7;11.0,49.4")"

if [[ -n "${ROUTE_TOKEN:-}" ]]; then
  check "GET route" 405 "$(status -H @<(auth_header "$ROUTE_TOKEN") "$base/v1/route")"
  check "one point" 400 "$(status -X POST -H @<(auth_header "$ROUTE_TOKEN") -H 'Content-Type: application/json' \
    -d '{"points":[[50,7]]}' "$base/v1/route")"
  body="$(curl -sS --compressed -w '\n%{http_code} %{time_total}' -X POST -H @<(auth_header "$ROUTE_TOKEN") \
    -H 'Content-Type: application/json' -d "{\"points\":$points}" "$base/v1/route")"
  read -r code secs <<<"$(tail -1 <<<"$body")"
  check route 200 "$code"
  if [[ "$code" == 200 ]]; then
    python3 -I -c 'import json,sys; r=json.loads(sys.argv[1]); print("[route] %.1f km, %.0f min, geometry %d chars, data %s" % (r["distance_m"]/1000, r["duration_s"]/60, len(r["geometry"]), r.get("data_timestamp")))' \
      "$(head -n -1 <<<"$body")" >&2
  fi
  log "request took ${secs} s"
else
  log "ROUTE_TOKEN not set: route request skipped"
fi
exit $fail
