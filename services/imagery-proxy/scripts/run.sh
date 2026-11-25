#!/usr/bin/env bash
# Builds the images (if needed), starts (or restarts) MapProxy and the gateway and waits until the gateway answers.
#
# Usage: IMAGERY_PORT=18200 scripts/run.sh [--build]
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
[[ -f "$IMAGERY_TOKENS_FILE" ]] || die "no token file $IMAGERY_TOKENS_FILE, create one with scripts/new-token.sh <device>"
mkdir -p "$IMAGERY_CACHE/tiles"
chmod 700 "$IMAGERY_CACHE"

if [[ "${1:-}" == --build ]] || ! podman_cmd image exists localhost/bettermaps-imagery-gateway:latest ||
   ! podman_cmd image exists localhost/bettermaps-mapproxy:latest; then
  build_images
fi

log "starting MapProxy $MAPPROXY_VERSION and the gateway on http://$IMAGERY_BIND:$IMAGERY_PORT"
compose up -d --force-recreate mapproxy gateway >&2

base="http://127.0.0.1:$IMAGERY_PORT"
[[ "$IMAGERY_BIND" != 0.0.0.0 ]] && base="http://$IMAGERY_BIND:$IMAGERY_PORT"
for _ in $(seq 1 "${IMAGERY_START_TIMEOUT:-60}"); do
  # MapProxy is ready when it answers its (local, upstream-free) WMTS capabilities.
  if curl -fsS -o /dev/null "$base/healthz" 2>/dev/null &&
     podman_cmd exec bettermaps-imagery_mapproxy_1 python3 -c \
       'import urllib.request; urllib.request.urlopen("http://127.0.0.1:8080/wmts/1.0.0/WMTSCapabilities.xml", timeout=2)' \
       >/dev/null 2>&1; then
    log "server ready: $base (Android emulator: http://10.0.2.2:$IMAGERY_PORT)"
    exit 0
  fi
  sleep 1
done
compose logs --tail 50 mapproxy gateway >&2 || true
die "server did not become ready within ${IMAGERY_START_TIMEOUT:-60} s"
