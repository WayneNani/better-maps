#!/usr/bin/env bash
# Smoke test against a running gateway: health, auth (401 without and with a wrong token), unknown paths and, with
# INDOOR_TOKEN set (taken from the environment, never from the command line), the venue lists of Frankfurt Airport
# and Köln Hbf, the largest venue document (gzip and plain, ETag/304) and the input checks.
#
# Usage: INDOOR_TOKEN=... scripts/smoke.sh
# Env:   INDOOR_URL (default http://127.0.0.1:$INDOOR_PORT)
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
base="${INDOOR_URL:-http://127.0.0.1:$INDOOR_PORT}"
fail=0
check() { # <name> <expected status> <actual status>
  if [[ "$2" == "$3" ]]; then log "ok   $1 ($3)"; else log "FAIL $1: expected $2, got $3"; fail=1; fi
}
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
# The token goes to curl through a file descriptor, not the command line (process list).
auth_header() { printf 'Authorization: Bearer %s\n' "$1"; }

check healthz 200 "$(status "$base/healthz")"
check "no token" 401 "$(status "$base/v1/venues?bbox=8.50,50.00,8.65,50.08")"
check "wrong token" 401 "$(status -H @<(auth_header wrong-token-0123456789abcdef) "$base/v1/venues?bbox=8.50,50.00,8.65,50.08")"
check "unknown path" 404 "$(status "$base/index.json")"

if [[ -z "${INDOOR_TOKEN:-}" ]]; then
  log "INDOOR_TOKEN not set: venue requests skipped"
  exit $fail
fi
check "POST" 405 "$(status -X POST -H @<(auth_header "$INDOOR_TOKEN") "$base/v1/venues?bbox=8.50,50.00,8.65,50.08")"
check "bbox missing" 400 "$(status -H @<(auth_header "$INDOOR_TOKEN") "$base/v1/venues")"
check "bbox inverted" 400 "$(status -H @<(auth_header "$INDOOR_TOKEN") "$base/v1/venues?bbox=8.65,50.00,8.50,50.08")"
check "bbox too large" 400 "$(status -H @<(auth_header "$INDOOR_TOKEN") "$base/v1/venues?bbox=7,50,8.5,50.5")"
check "unknown venue" 404 "$(status -H @<(auth_header "$INDOOR_TOKEN") "$base/v1/venues/w1")"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
summary='import json,sys
d=json.load(open(sys.argv[1]))
print("data %s, %d venues" % (d["data_timestamp"], len(d["venues"])))
for v in d["venues"][:8]:
    print("  %-14s %-8s %6d features, levels %s, default %s: %s" % (v["id"], v["kind"], v["features"],
          ",".join(l["label"] for l in v["levels"]), v["default_level"], v.get("name", "-")))'
first=""
for place in "Frankfurt-Flughafen 8.50,50.00,8.65,50.08" "Köln-Hbf 6.94,50.93,6.97,50.95"; do
  read -r name bbox <<<"$place"
  res="$(curl -s -o "$tmp/list.json" -w '%{http_code} %{size_download} %{time_total}' --compressed \
    -H @<(auth_header "$INDOOR_TOKEN") "$base/v1/venues?bbox=$bbox")"
  read -r code size secs <<<"$res"
  check "venues $name ($size B, ${secs}s)" 200 "$code"
  [[ "$code" == 200 ]] && python3 -I -c "$summary" "$tmp/list.json" >&2
  [[ -z "$first" && "$code" == 200 ]] &&
    first="$(python3 -I -c 'import json,sys; v=json.load(open(sys.argv[1]))["venues"]; print(v[0]["id"] if v else "")' "$tmp/list.json")"
done

if [[ -n "$first" ]]; then
  url="$base/v1/venues/$first"
  res="$(curl -s -o "$tmp/doc.gz" -D "$tmp/h" -w '%{http_code} %{size_download} %{time_total}' -H 'Accept-Encoding: gzip' \
    -H @<(auth_header "$INDOOR_TOKEN") "$url")"
  read -r code gz secs <<<"$res"
  check "venue $first gzip ($gz B, ${secs}s)" 200 "$code"
  grep -qi '^content-encoding: gzip' "$tmp/h" || { log "FAIL no Content-Encoding: gzip"; fail=1; }
  etag="$(sed -n 's/^[Ee][Tt]ag: *//p' "$tmp/h" | tr -d '\r')"
  res="$(curl -s -o "$tmp/doc.json" -w '%{http_code} %{size_download}' -H @<(auth_header "$INDOOR_TOKEN") "$url")"
  read -r code plain <<<"$res"
  check "venue $first plain ($plain B)" 200 "$code"
  check "venue $first If-None-Match" 304 "$(status -H "If-None-Match: $etag" -H @<(auth_header "$INDOOR_TOKEN") "$url")"
  python3 -I -c 'import json,sys,collections
d=json.load(open(sys.argv[1]))
k=collections.Counter(f["k"] for f in d["features"])
print("document %s %r: %d features, %s" % (d["id"], d.get("name"), len(d["features"]), dict(k.most_common(12))))' "$tmp/doc.json" >&2
fi
exit $fail
