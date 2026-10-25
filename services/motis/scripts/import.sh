#!/usr/bin/env bash
# Preprocesses the input files of a profile with `motis import` (container with memory/CPU limits, nice).
#
# The import writes into <profile>/data.next (seeded with a reflink/hard copy of the current data, so
# unchanged steps such as the street graph are reused). Only when it succeeds is data.next swapped in;
# a running server is restarted with the new data. A failed import leaves the running server untouched.
#
# Usage: MOTIS_PROFILE=berlin scripts/import.sh [--clean]
#   --clean  start from an empty directory instead of reusing unchanged import steps
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_profile

clean=false
[[ "${1:-}" == --clean ]] && clean=true

while read -r name _; do
  [[ -s "$MOTIS_INPUT_DIR/$name" ]] || die "missing input $name, run scripts/fetch-data.sh first"
done < <(profile_inputs)

mkdir -p "$MOTIS_PROFILE_DIR"
cp "$MOTIS_CONFIG" "$MOTIS_PROFILE_DIR/config.yml"

next="$MOTIS_PROFILE_DIR/data.next"
rm -rf "$next"
if ! $clean && [[ -d "$MOTIS_PROFILE_DIR/data" ]]; then
  cp -a --reflink=auto "$MOTIS_PROFILE_DIR/data" "$next"
fi

podman_cmd image exists "ghcr.io/motis-project/motis:$MOTIS_VERSION" ||
  podman_cmd pull "ghcr.io/motis-project/motis:$MOTIS_VERSION"

log "import profile $MOTIS_PROFILE (limits: ${MOTIS_MEMORY} RAM, ${MOTIS_CPUS} CPUs) ..."
logfile="$MOTIS_PROFILE_DIR/import.log"
export MOTIS_IMPORT_DIR=data.next
set +e
compose --profile import run -T --rm --name bettermaps-motis-import import 2>&1 | tee "$logfile"
rc=${PIPESTATUS[0]}
set -e
stats="$(grep -a 'import-stats:' "$logfile" | tail -1 || true)"
if [[ $rc -ne 0 ]] || ! grep -q 'exit=0' <<<"$stats"; then
  die "import failed (exit $rc), log: $logfile; current data left unchanged"
fi

# Record what was imported (used by update.sh) and the measurements (README "Ressourcenbedarf").
(cd "$MOTIS_INPUT_DIR" && while read -r name _; do cat "$name.sha256"; done < <(profile_inputs)) \
  >"$next/inputs.sha256"
sha256sum "$MOTIS_CONFIG" | awk '{print $1 "  config.yml"}' >>"$next/inputs.sha256"
{
  echo "date=$(date -Iseconds) profile=$MOTIS_PROFILE motis=$MOTIS_VERSION memory_limit=$MOTIS_MEMORY cpus=$MOTIS_CPUS"
  echo "${stats#*import-stats: }"
  echo "data_size=$(du -sh "$next" | cut -f1) input_size=$(du -shL "$MOTIS_INPUT_DIR" | cut -f1)"
  echo "clean=$clean"
} | tee "$MOTIS_PROFILE_DIR/import-stats.txt" >&2
{ tr '\n' ' ' <"$MOTIS_PROFILE_DIR/import-stats.txt"; echo; } >>"$MOTIS_PROFILE_DIR/import-history.txt"

was_running=false
if [[ "$(podman_cmd ps --filter name=bettermaps-motis_motis --format '{{.Names}}')" ]]; then
  was_running=true
fi
rm -rf "$MOTIS_PROFILE_DIR/data.prev"
[[ -d "$MOTIS_PROFILE_DIR/data" ]] && mv "$MOTIS_PROFILE_DIR/data" "$MOTIS_PROFILE_DIR/data.prev"
mv "$next" "$MOTIS_PROFILE_DIR/data"
if $was_running; then
  log "restarting server with the new data"
  "$MOTIS_DIR/scripts/run.sh"
fi
rm -rf "$MOTIS_PROFILE_DIR/data.prev"
log "import done: $MOTIS_PROFILE_DIR/data"
