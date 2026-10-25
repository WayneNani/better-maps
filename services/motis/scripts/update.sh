#!/usr/bin/env bash
# Weekly update: downloads new input data and re-imports when something changed or the imported timetable
# is getting old (the config loads 21 days starting with the import day). A running server is restarted
# with the new data by import.sh; afterwards the smoke test runs against it.
#
# Usage: MOTIS_PROFILE=berlin scripts/update.sh [--force]   (--force: full re-import from scratch)
# Env:   MOTIS_MAX_AGE_DAYS (default 6) re-import even without new data after this many days.
# See systemd/ for a user timer that runs this weekly.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_profile

force=false
[[ "${1:-}" == --force ]] && force=true

mkdir -p "$MOTIS_CACHE"
exec 9>"$MOTIS_CACHE/.update.lock"
flock -n 9 || die "another update is running"

"$MOTIS_DIR/scripts/fetch-data.sh"

current="$( (cd "$MOTIS_INPUT_DIR" && while read -r name _; do cat "$name.sha256"; done < <(profile_inputs));
            sha256sum "$MOTIS_CONFIG" | awk '{print $1 "  config.yml"}')"
imported="$MOTIS_PROFILE_DIR/data/inputs.sha256"
reason=""
if $force; then
  reason="forced"
elif [[ ! -f "$imported" ]]; then
  reason="no import yet"
elif [[ "$current" != "$(cat "$imported")" ]]; then
  reason="input data or config changed"
elif [[ -n "$(find "$imported" -mtime "+$((${MOTIS_MAX_AGE_DAYS:-6} - 1))")" ]]; then
  reason="import older than ${MOTIS_MAX_AGE_DAYS:-6} days (timetable window)"
fi

if [[ -z "$reason" ]]; then
  log "nothing to do: data unchanged and import is recent"
  exit 0
fi
log "re-import: $reason"
if $force; then nice -n 10 "$MOTIS_DIR/scripts/import.sh" --clean; else nice -n 10 "$MOTIS_DIR/scripts/import.sh"; fi

if curl -fsS -o /dev/null "http://127.0.0.1:$MOTIS_PORT/api/v1/health" 2>/dev/null; then
  "$MOTIS_DIR/scripts/smoke.sh"
fi
