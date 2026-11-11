#!/usr/bin/env bash
# Update: downloads a new extract and re-imports when it changed. A running server is restarted with the new data by
# import.sh. Needs free disk space for the new data next to the old one (see README).
#
# Usage: OSRM_EXTRACT=europe/germany scripts/update.sh [--force]
# See systemd/ for a user timer that runs this weekly.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

force=false
[[ "${1:-}" == --force ]] && force=true

mkdir -p "$OSRM_CACHE"
exec 9>"$OSRM_CACHE/.update.lock"
flock -n 9 || die "another update is running"

"$ROUTE_DIR/scripts/fetch-data.sh"

imported="$OSRM_REGION_DIR/data/inputs.sha256"
if ! $force && [[ -f "$imported" ]] && cmp -s "$OSRM_PBF.sha256" "$imported"; then
  log "nothing to do: extract unchanged since the last import"
  exit 0
fi
nice -n 10 "$ROUTE_DIR/scripts/import.sh"
