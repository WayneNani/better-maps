#!/usr/bin/env bash
# Starts (or restarts) the MOTIS server for a profile and waits until the API answers.
#
# Usage: MOTIS_PROFILE=berlin MOTIS_PORT=18090 scripts/run.sh
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_profile
[[ -f "$MOTIS_PROFILE_DIR/data/config.yml" ]] || die "no imported data for $MOTIS_PROFILE, run scripts/import.sh first"

log "starting MOTIS $MOTIS_VERSION, profile $MOTIS_PROFILE on http://$MOTIS_BIND:$MOTIS_PORT"
compose up -d --force-recreate motis >&2

base="http://127.0.0.1:$MOTIS_PORT"
[[ "$MOTIS_BIND" != 0.0.0.0 ]] && base="http://$MOTIS_BIND:$MOTIS_PORT"
for _ in $(seq 1 "${MOTIS_START_TIMEOUT:-120}"); do
  if curl -fsS -o /dev/null "$base/api/v1/health" 2>/dev/null; then
    log "server ready: $base (Android emulator: http://10.0.2.2:$MOTIS_PORT)"
    exit 0
  fi
  sleep 1
done
compose logs --tail 50 motis >&2 || true
die "server did not become ready within ${MOTIS_START_TIMEOUT:-120} s"
