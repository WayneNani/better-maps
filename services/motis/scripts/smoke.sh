#!/usr/bin/env bash
# Smoke test against a running server: /api/v6/plan (Berlin Hbf -> Alexanderplatz) and /api/v6/stoptimes.
#
# Usage: scripts/smoke.sh [--expect-realtime]
# Env:   MOTIS_URL (default http://127.0.0.1:$MOTIS_PORT)
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
exec python3 -I "$MOTIS_DIR/scripts/motis_api_check.py" smoke --base "${MOTIS_URL:-http://127.0.0.1:$MOTIS_PORT}" "$@"
