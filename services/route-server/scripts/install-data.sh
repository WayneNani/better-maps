#!/usr/bin/env bash
# Installs OSRM data that was imported on another machine (scripts/import.sh there, e.g. a workstation with enough
# memory for the whole of Germany) as the served data of $OSRM_REGION, and restarts the server like import.sh does
# (ROUTE_RESTART_CMD for a server managed by systemd/Quadlet).
#
# The directory is a copy of <region>/data from the other machine. It must come from the same OSRM version
# ($OSRM_VERSION) and region; import-stats.txt in it says which. The directory is moved into place (copied first when
# it is on another file system) and is gone afterwards. A failed check leaves the served data untouched.
#
# Usage: OSRM_EXTRACT=europe/germany scripts/install-data.sh <dir>
# Example (workstation -> server):
#   rsync -a --delete ~/.cache/bettermaps-osrm/germany/data/ server:/var/tmp/osrm-germany/
#   ssh server 'sudo OSRM_EXTRACT=europe/germany .../scripts/install-data.sh /var/tmp/osrm-germany'
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
src="${1:?usage: scripts/install-data.sh <dir with the data of $OSRM_REGION>}"
src="${src%/}"

[[ -d "$src" ]] || die "$src is not a directory"
for ext in mldgr partition cells cell_metrics properties; do
  [[ -s "$src/$OSRM_REGION.osrm.$ext" ]] || die "$src/$OSRM_REGION.osrm.$ext missing: no complete MLD import of $OSRM_REGION"
done
stats="$src/import-stats.txt"
[[ -s "$stats" ]] || die "$stats missing (written by import.sh since 2026-11-25); cannot check region and OSRM version"
grep -q "region=$OSRM_REGION " "$stats" || die "$stats: not region $OSRM_REGION"
grep -qF "osrm=$OSRM_VERSION " "$stats" || die "$stats: not OSRM $OSRM_VERSION (the server would not load it)"
grep -q 'exit=0 ' "$stats" || die "$stats: the import did not finish"

mkdir -p "$OSRM_REGION_DIR"
exec 9>"$OSRM_CACHE/.update.lock"
flock -n 9 || die "an update is running"

next="$OSRM_REGION_DIR/data.next"
rm -rf "$next"
if [[ "$(stat -c %d "$src")" == "$(stat -c %d "$OSRM_REGION_DIR")" ]]; then
  mv "$src" "$next"
else
  cp -a "$src" "$next"
  rm -rf "$src"
fi
# Readable for the server container, whoever copied the files.
chmod -R u+rwX,go+rX,go-w "$next"
cp "$next/import-stats.txt" "$OSRM_REGION_DIR/import-stats.txt"
{ tr '\n' ' ' <"$next/import-stats.txt"; echo "installed=$(date -Iseconds)"; } >>"$OSRM_REGION_DIR/import-history.txt"
swap_in_data "$next"
log "installed: $OSRM_REGION_DIR/data ($(head -1 "$OSRM_REGION_DIR/import-stats.txt"))"
