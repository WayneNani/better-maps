#!/usr/bin/env bash
# Builds the gateway image (if needed), starts (or restarts) OSRM and the gateway and waits until the gateway answers
# and OSRM has loaded the data.
#
# Usage: OSRM_EXTRACT=europe/germany ROUTE_PORT=18100 scripts/run.sh [--build]
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
[[ -f "$OSRM_REGION_DIR/data/$OSRM_REGION.osrm.mldgr" ]] || die "no imported data for $OSRM_REGION, run scripts/import.sh first"
[[ -f "$ROUTE_TOKENS_FILE" ]] || die "no token file $ROUTE_TOKENS_FILE, create one with scripts/new-token.sh <device>"

if [[ "${1:-}" == --build ]] || ! podman_cmd image exists localhost/bettermaps-route-gateway:latest; then
  log "building the gateway image"
  podman_cmd build -q -t localhost/bettermaps-route-gateway:latest -f "$ROUTE_DIR/Containerfile" "$ROUTE_DIR" >&2
fi

log "starting OSRM $OSRM_VERSION ($OSRM_REGION) and the gateway on http://$ROUTE_BIND:$ROUTE_PORT"
compose up -d --force-recreate osrm gateway >&2

base="http://127.0.0.1:$ROUTE_PORT"
[[ "$ROUTE_BIND" != 0.0.0.0 ]] && base="http://$ROUTE_BIND:$ROUTE_PORT"
for _ in $(seq 1 "${ROUTE_START_TIMEOUT:-180}"); do
  if curl -fsS -o /dev/null "$base/healthz" 2>/dev/null &&
     compose logs osrm 2>/dev/null | grep -q "running and waiting for requests"; then
    log "server ready: $base (Android emulator: http://10.0.2.2:$ROUTE_PORT)"
    exit 0
  fi
  sleep 1
done
compose logs --tail 50 osrm gateway >&2 || true
die "server did not become ready within ${ROUTE_START_TIMEOUT:-180} s"
