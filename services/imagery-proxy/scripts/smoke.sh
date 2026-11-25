#!/usr/bin/env bash
# Smoke test against a running gateway: health, auth (401 without and with a wrong token), no MapProxy passthrough
# and, with IMAGERY_TOKEN set (taken from the environment, never from the command line), a few tiles (Köln z16,
# München z15, Paris z12 = Sentinel-2) and the attributions of those places. Fetches 3 tiles, cached afterwards.
#
# Usage: IMAGERY_TOKEN=... scripts/smoke.sh [output dir for the tiles]
# Env:   IMAGERY_URL (default http://127.0.0.1:$IMAGERY_PORT)
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
base="${IMAGERY_URL:-http://127.0.0.1:$IMAGERY_PORT}"
out="${1:-}"
fail=0
check() { # <name> <expected status> <actual status>
  if [[ "$2" == "$3" ]]; then log "ok   $1 ($3)"; else log "FAIL $1: expected $2, got $3"; fail=1; fi
}
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
# The token goes to curl through a file descriptor, not the command line (process list).
auth_header() { printf 'Authorization: Bearer %s\n' "$1"; }

check healthz 200 "$(status "$base/healthz")"
check "no token" 401 "$(status "$base/v1/tiles/16/34034/21956.jpg")"
check "wrong token" 401 "$(status -H @<(auth_header wrong-token-0123456789abcdef) "$base/v1/tiles/16/34034/21956.jpg")"
check "no MapProxy passthrough" 404 "$(status "$base/wmts/aerial/webmercator/1/0/0.jpeg")"

if [[ -n "${IMAGERY_TOKEN:-}" ]]; then
  check "tile outside the grid" 404 "$(status -H @<(auth_header "$IMAGERY_TOKEN") "$base/v1/tiles/2/4/0.jpg")"
  # name z x y
  for t in "koeln 16 34034 21956" "muenchen 15 17437 11371" "paris 12 2074 1409"; do
    read -r name z x y <<<"$t"
    dest=/dev/null
    [[ -n "$out" ]] && { mkdir -p "$out"; dest="$out/$name-$z.jpg"; }
    res="$(curl -s -o "$dest" -w '%{http_code} %{content_type} %{size_download} %{time_total}' \
      -H @<(auth_header "$IMAGERY_TOKEN") "$base/v1/tiles/$z/$x/$y.jpg")"
    read -r code ct size secs <<<"$res"
    check "tile $name z$z ($ct, $size B, ${secs}s)" 200 "$code"
  done
  for b in "koeln 6.9,50.9,7.0,51.0 16" "muenchen 11.5,48.1,11.6,48.2 15" "paris 2.3,48.8,2.4,48.9 12"; do
    read -r name bbox z <<<"$b"
    body="$(curl -s -H @<(auth_header "$IMAGERY_TOKEN") "$base/v1/attribution?bbox=$bbox&z=$z")"
    log "attribution $name: $(python3 -I -c 'import json,sys; print(" | ".join(s["attribution"] for s in json.loads(sys.argv[1])["sources"]))' "$body")"
  done
else
  log "IMAGERY_TOKEN not set: tile requests skipped"
fi
exit $fail
