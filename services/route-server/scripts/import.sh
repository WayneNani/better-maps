#!/usr/bin/env bash
# Preprocesses the OSM extract for OSRM (car profile, MLD: osrm-extract, osrm-partition, osrm-customize) in a
# container with memory/CPU limits and nice.
#
# The import writes into <region>/data.next. Only when it succeeds is data.next swapped in; a running server is
# restarted with the new data. A failed import leaves the running server untouched.
#
# Usage: OSRM_EXTRACT=europe/germany scripts/import.sh
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

[[ -s "$OSRM_PBF" ]] || die "missing $OSRM_PBF, run scripts/fetch-data.sh first"

# The result needs about 2.5 times the size of the extract (Germany: 4.6 GB -> ~11 GB); the old data stays until the
# new one is complete.
mkdir -p "$OSRM_REGION_DIR"
need_kb=$(( $(stat -c %s "$OSRM_PBF") / 1024 * 25 / 10 ))
free_kb=$(df -Pk "$OSRM_REGION_DIR" | awk 'NR==2 {print $4}')
if [[ "${OSRM_SKIP_DISK_CHECK:-0}" != 1 ]] && (( free_kb < need_kb )); then
  die "not enough disk space: $((free_kb / 1024 / 1024)) GB free, ~$((need_kb / 1024 / 1024)) GB needed (OSRM_SKIP_DISK_CHECK=1 to try anyway)"
fi

next="$OSRM_REGION_DIR/data.next"
rm -rf "$next"

podman_cmd image exists "ghcr.io/project-osrm/osrm-backend:$OSRM_VERSION" ||
  podman_cmd pull "ghcr.io/project-osrm/osrm-backend:$OSRM_VERSION"

export OSRM_IMPORT_DIR=data.next
logfile="$OSRM_REGION_DIR/import.log"
: >"$logfile"
# Filter first (see compose.yaml, service filter); OSRM_PREFILTER=0 imports the whole extract.
if [[ "${OSRM_PREFILTER:-1}" == 1 ]]; then
  podman_cmd image exists localhost/bettermaps-osmium:latest ||
    podman_cmd build -q -t localhost/bettermaps-osmium:latest -f "$ROUTE_DIR/osmium.Containerfile" "$ROUTE_DIR" >&2
  log "filter $OSRM_REGION (roads, ferries, turn restrictions) ..."
  start=$SECONDS
  if ! compose --profile import run -T --rm --name bettermaps-route-filter filter 2>&1 | tee -a "$logfile"; then
    die "filter failed, log: $logfile"
  fi
  log "filtered in $((SECONDS - start)) s: $(du -h "$next/$OSRM_REGION.osm.pbf" | cut -f1)"
fi

log "import $OSRM_REGION (limits: ${OSRM_IMPORT_MEMORY} RAM, ${OSRM_THREADS} threads) ..."
set +e
compose --profile import run -T --rm --name bettermaps-route-import import 2>&1 | tee -a "$logfile"
rc=${PIPESTATUS[0]}
set -e
stats="$(grep -a 'import-stats:' "$logfile" | tail -1 || true)"
if [[ $rc -ne 0 ]] || ! grep -q 'exit=0' <<<"$stats"; then
  rm -rf "$next"
  die "import failed (exit $rc), log: $logfile; current data left unchanged"
fi

# Record what was imported (used by update.sh) and the measurements (README "Ressourcenbedarf").
cp "$OSRM_PBF.sha256" "$next/inputs.sha256" 2>/dev/null || sha256sum "$OSRM_PBF" >"$next/inputs.sha256"
{
  echo "date=$(date -Iseconds) region=$OSRM_REGION osrm=$OSRM_VERSION memory_limit=$OSRM_IMPORT_MEMORY threads=$OSRM_THREADS"
  echo "${stats#*import-stats: }"
  echo "data_size=$(du -sh "$next" | cut -f1) input_size=$(du -shL "$OSRM_PBF" | cut -f1)"
} | tee "$OSRM_REGION_DIR/import-stats.txt" >&2
{ tr '\n' ' ' <"$OSRM_REGION_DIR/import-stats.txt"; echo; } >>"$OSRM_REGION_DIR/import-history.txt"

was_running=false
[[ -n "$(podman_cmd ps -q --filter name=bettermaps-route_osrm)" ]] && was_running=true
rm -rf "$OSRM_REGION_DIR/data.prev"
[[ -d "$OSRM_REGION_DIR/data" ]] && mv "$OSRM_REGION_DIR/data" "$OSRM_REGION_DIR/data.prev"
mv "$next" "$OSRM_REGION_DIR/data"
if $was_running; then
  log "restarting server with the new data"
  "$ROUTE_DIR/scripts/run.sh"
fi
rm -rf "$OSRM_REGION_DIR/data.prev"
log "import done: $OSRM_REGION_DIR/data"
