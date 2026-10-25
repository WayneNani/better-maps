#!/usr/bin/env bash
# Compatibility test: sends the requests of the app (OnlineTransitService) and validates the responses
# against the OpenAPI spec of the app's generated client (android/sdk/motis_openapi.yaml).
#
# Usage: scripts/compat.sh
# Env:   MOTIS_URL (default http://127.0.0.1:$MOTIS_PORT), MOTIS_SPEC (default: spec from the repo)
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
spec="${MOTIS_SPEC:-$MOTIS_DIR/../../android/sdk/motis_openapi.yaml}"
exec python3 -I "$MOTIS_DIR/scripts/motis_api_check.py" compat --base "${MOTIS_URL:-http://127.0.0.1:$MOTIS_PORT}" \
  --spec "$spec" "$@"
