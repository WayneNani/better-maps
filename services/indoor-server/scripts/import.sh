#!/usr/bin/env bash
# Builds the venue data from the downloaded extracts in two containers with memory/CPU limits, nice and no network:
# filter (osmium tags-filter + merge + export) and import (indoor-import).
#
# The import writes into $INDOOR_CACHE/data.next. Only when it succeeds is data.next swapped in for data; a running
# gateway is restarted with the new data. A failed import leaves the served data untouched. After a successful
# import the extracts are deleted (INDOOR_KEEP_PBF=1 keeps them; the weekly update downloads them anew anyway,
# because Geofabrik publishes a new extract every day).
#
# Usage: INDOOR_REGIONS="europe/germany/hessen europe/germany/nordrhein-westfalen" scripts/import.sh [--build]
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

inputs=()
for r in $(region_names); do
  pbf="$INDOOR_INPUT_DIR/$r-latest.osm.pbf"
  [[ -s "$pbf" ]] || die "missing $pbf, run scripts/fetch-data.sh first"
  inputs+=("$pbf")
done

# Intermediate files are about a quarter of the extracts, the result a few percent; the old data stays until the
# new one is complete.
need_kb=$(( $(du -cLk "${inputs[@]}" | tail -1 | cut -f1) / 2 ))
free_kb=$(df -Pk "$INDOOR_CACHE" | awk 'NR==2 {print $4}')
if [[ "${INDOOR_SKIP_DISK_CHECK:-0}" != 1 ]] && (( free_kb < need_kb )); then
  die "not enough disk space: $((free_kb / 1024)) MB free, ~$((need_kb / 1024)) MB needed (INDOOR_SKIP_DISK_CHECK=1 to try anyway)"
fi

ensure_osmium
if [[ "${1:-}" == --build ]] || ! podman_cmd image exists "$GATEWAY_IMAGE"; then
  build_gateway
fi

work="$INDOOR_CACHE/work" next="$INDOOR_CACHE/data.next"
rm -rf "$work" "$next"
mkdir -p "$work" "$next"
logfile="$INDOOR_CACHE/import.log"
: >"$logfile"

log "filter $(region_names) (limits: $INDOOR_IMPORT_MEMORY RAM, $INDOOR_IMPORT_CPUS CPUs) ..."
start=$SECONDS
set +e
compose --profile import run -T --rm --name bettermaps-indoor-filter filter 2>&1 | tee -a "$logfile" >&2
rc=${PIPESTATUS[0]}
set -e
filter_stats="$(grep -a 'filter-stats:' "$logfile" | tail -1 || true)"
if [[ $rc -ne 0 || -z "$filter_stats" || ! -s "$work/indoor.geojsonseq" ]]; then
  rm -rf "$work" "$next"
  die "filter failed (exit $rc), log: $logfile; current data left unchanged"
fi
log "filtered in $((SECONDS - start)) s: ${filter_stats#*filter-stats: }"

log "building venues ..."
set +e
compose --profile import run -T --rm --name bettermaps-indoor-import import 2>&1 | tee -a "$logfile" >&2
rc=${PIPESTATUS[0]}
set -e
if [[ $rc -ne 0 || ! -s "$next/index.json" ]]; then
  rm -rf "$work" "$next"
  die "import failed (exit $rc), log: $logfile; current data left unchanged"
fi

# Record what was imported (used by update.sh) and the measurements (README "Ressourcenbedarf").
for r in $(region_names); do
  cat "$INDOOR_INPUT_DIR/$r-latest.osm.pbf.sha256" 2>/dev/null || sha256sum "$INDOOR_INPUT_DIR/$r-latest.osm.pbf"
done >"$next/inputs.sha256"
{
  echo "regions=$(region_names | tr ' ' ',') memory_limit=$INDOOR_IMPORT_MEMORY cpus=$INDOOR_IMPORT_CPUS total_seconds=$((SECONDS - start))"
  echo "${filter_stats#*filter-stats: } input_bytes=$(du -cLb "${inputs[@]}" | tail -1 | cut -f1)"
  cat "$next/import-stats.txt"
  echo "data_size=$(du -sh "$next" | cut -f1)"
} >"$INDOOR_CACHE/import-stats.txt"
cp "$INDOOR_CACHE/import-stats.txt" "$next/import-stats.txt"
{ head -12 "$INDOOR_CACHE/import-stats.txt" | tr '\n' ' '; echo; } >>"$INDOOR_CACHE/import-history.txt"
sed -n '1,12p' "$INDOOR_CACHE/import-stats.txt" >&2

# Swap in the new data; restart the gateway only if it is running.
was_running=false
if [[ -n "$(podman_cmd ps -q --filter name=bettermaps-indoor_gateway)" ]]; then was_running=true; fi
rm -rf "$INDOOR_CACHE/data.prev"
[[ -d "$INDOOR_DATA_DIR" ]] && mv "$INDOOR_DATA_DIR" "$INDOOR_CACHE/data.prev"
mv "$next" "$INDOOR_DATA_DIR"
if $was_running; then
  log "restarting the gateway with the new data"
  "$INDOOR_DIR/scripts/run.sh"
fi
rm -rf "$INDOOR_CACHE/data.prev" "$work"

if [[ "$INDOOR_KEEP_PBF" != 1 ]]; then
  for f in "${inputs[@]}"; do rm -f "$f" "$f.etag"; done
  log "extracts deleted (INDOOR_KEEP_PBF=1 keeps them)"
fi
log "import done: $INDOOR_DATA_DIR ($(du -sh "$INDOOR_DATA_DIR" | cut -f1))"
