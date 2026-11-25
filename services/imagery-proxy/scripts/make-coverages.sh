#!/usr/bin/env bash
# Regenerates config/coverage/*.geojson from data/borders (see make-coverages.py). Runs in the MapProxy image
# (shapely), without network.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
repo="$(cd "$IMAGERY_DIR/../.." && pwd)"
podman_cmd run --rm --network none --userns keep-id --entrypoint python3 \
  -v "$repo/data/borders:/repo/data/borders:ro,z" \
  -v "$IMAGERY_DIR/scripts:/scripts:ro,z" \
  -v "$IMAGERY_DIR/config/coverage:/repo/services/imagery-proxy/config/coverage:z" \
  "ghcr.io/mapproxy/mapproxy/mapproxy:$MAPPROXY_VERSION" /scripts/make-coverages.py /repo
