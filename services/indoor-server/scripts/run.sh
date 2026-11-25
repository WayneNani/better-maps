#!/usr/bin/env bash
# Builds the gateway image (if needed), starts (or restarts) the gateway and waits until it answers.
#
# Usage: INDOOR_PORT=18300 scripts/run.sh [--build]
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
[[ -s "$INDOOR_DATA_DIR/index.json" ]] || die "no imported data in $INDOOR_DATA_DIR, run scripts/import.sh first"
[[ -f "$INDOOR_TOKENS_FILE" ]] || die "no token file $INDOOR_TOKENS_FILE, create one with scripts/new-token.sh <device>"

if [[ "${1:-}" == --build ]] || ! podman_cmd image exists "$GATEWAY_IMAGE"; then
  build_gateway
fi

log "starting the gateway on http://$INDOOR_BIND:$INDOOR_PORT"
compose up -d --force-recreate gateway >&2

base="http://127.0.0.1:$INDOOR_PORT"
[[ "$INDOOR_BIND" != 0.0.0.0 ]] && base="http://$INDOOR_BIND:$INDOOR_PORT"
for _ in $(seq 1 "${INDOOR_START_TIMEOUT:-60}"); do
  if curl -fsS -o /dev/null "$base/healthz" 2>/dev/null; then
    log "server ready: $base (Android emulator: http://10.0.2.2:$INDOOR_PORT)"
    exit 0
  fi
  sleep 1
done
compose logs --tail 50 gateway >&2 || true
die "server did not become ready within ${INDOOR_START_TIMEOUT:-60} s"
