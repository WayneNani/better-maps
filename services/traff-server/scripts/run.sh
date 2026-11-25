#!/usr/bin/env bash
# Builds the image (if needed), starts (or restarts) the TraFF server and waits until it answers /healthz.
#
# Usage: TRAFF_PORT=18400 scripts/run.sh [--build]
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
[[ -f "$TRAFF_TOKENS_FILE" ]] || die "no token file $TRAFF_TOKENS_FILE, create one with scripts/new-token.sh <device>"
ensure_config

if [[ "${1:-}" == --build ]] || ! podman_cmd image exists "$TRAFF_IMAGE"; then
  build_image
fi

log "starting the server on http://$TRAFF_BIND:$TRAFF_PORT"
compose up -d --force-recreate traff >&2

base="http://127.0.0.1:$TRAFF_PORT"
[[ "$TRAFF_BIND" != 0.0.0.0 ]] && base="http://$TRAFF_BIND:$TRAFF_PORT"
for _ in $(seq 1 "${TRAFF_START_TIMEOUT:-30}"); do
  if curl -fsS -o /dev/null "$base/healthz" 2>/dev/null; then
    log "server ready: $base (Android emulator: http://10.0.2.2:$TRAFF_PORT)"
    exit 0
  fi
  sleep 1
done
compose logs --tail 50 traff >&2 || true
die "server did not become ready within ${TRAFF_START_TIMEOUT:-30} s"
