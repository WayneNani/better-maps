#!/usr/bin/env bash
# Update: downloads the extracts and re-imports when one of them changed. A running gateway is restarted with the
# new data by import.sh. Since import.sh deletes the extracts (unless INDOOR_KEEP_PBF=1), every run downloads them
# again (Hessen + NRW: ~1.1 GB); Geofabrik publishes daily, so a weekly run finds new data anyway.
#
# Usage: INDOOR_REGIONS="..." scripts/update.sh [--force]
# See systemd/ for a user timer that runs this weekly.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

force=false
[[ "${1:-}" == --force ]] && force=true

mkdir -p "$INDOOR_CACHE"
exec 9>"$INDOOR_CACHE/.update.lock"
flock -n 9 || die "another update is running"

"$INDOOR_DIR/scripts/fetch-data.sh"

current="$(for r in $(region_names); do cat "$INDOOR_INPUT_DIR/$r-latest.osm.pbf.sha256" 2>/dev/null || true; done)"
imported="$INDOOR_DATA_DIR/inputs.sha256"
if ! $force && [[ -f "$imported" && -n "$current" ]] && [[ "$current" == "$(cat "$imported")" ]]; then
  log "nothing to do: extracts unchanged since the last import"
  if [[ "$INDOOR_KEEP_PBF" != 1 ]]; then
    for r in $(region_names); do rm -f "$INDOOR_INPUT_DIR/$r-latest.osm.pbf" "$INDOOR_INPUT_DIR/$r-latest.osm.pbf.etag"; done
  fi
  exit 0
fi
nice -n 10 "$INDOOR_DIR/scripts/import.sh"
