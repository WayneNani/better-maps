# Shared settings for the indoor-server scripts. Source this file, do not run it.
# All values can be overridden through the environment.

INDOOR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Geofabrik paths of the extracts without "-latest.osm.pbf", space separated. Their file names (last part of the
# path) must differ. Venues on a border between two of them appear once (the filtered extracts are merged).
export INDOOR_REGIONS="${INDOOR_REGIONS:-europe/germany/hessen europe/germany/nordrhein-westfalen}"
# Downloads, intermediate files and the served data live outside the repo.
export INDOOR_CACHE="${INDOOR_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/bettermaps-indoor}"
# Limits of the import containers (osmium filter/export, indoor-import); see README "Ressourcenbedarf".
export INDOOR_IMPORT_MEMORY="${INDOOR_IMPORT_MEMORY:-3g}"
export INDOOR_IMPORT_CPUS="${INDOOR_IMPORT_CPUS:-2}"
# 1 keeps the downloaded extracts after a successful import (default: deleted, the weekly update downloads anew).
export INDOOR_KEEP_PBF="${INDOOR_KEEP_PBF:-0}"
# Gateway: published port, the only one. 127.0.0.1 is enough for a reverse proxy on the same machine and for the
# Android emulator (10.0.2.2 = host loopback).
export INDOOR_PORT="${INDOOR_PORT:-18300}"
export INDOOR_BIND="${INDOOR_BIND:-127.0.0.1}"
export INDOOR_MEMORY="${INDOOR_MEMORY:-256m}"
# Token hashes of the devices (scripts/new-token.sh adds lines). Outside the repo, readable only by you.
export INDOOR_TOKENS_FILE="${INDOOR_TOKENS_FILE:-${XDG_CONFIG_HOME:-$HOME/.config}/bettermaps-indoor/tokens}"
export INDOOR_RATE_PER_MINUTE="${INDOOR_RATE_PER_MINUTE:-120}"
export INDOOR_RATE_BURST="${INDOOR_RATE_BURST:-60}"
export INDOOR_LOG_LEVEL="${INDOOR_LOG_LEVEL:-info}"
export INDOOR_USER_AGENT="${INDOOR_USER_AGENT:-better-maps-indoor-fetch/1.0 (self-hosted indoor map server)}"

INDOOR_INPUT_DIR="$INDOOR_CACHE/input"
INDOOR_DATA_DIR="$INDOOR_CACHE/data"
# Image of the import step (osmium-tool from Debian). Shared with services/route-server, which builds the same
# image from an identical osmium.Containerfile; whichever service builds it first, the other reuses it.
OSMIUM_IMAGE=localhost/bettermaps-osmium:latest
GATEWAY_IMAGE=localhost/bettermaps-indoor-gateway:latest

log() { printf '[indoor %s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

# Region names (last part of each path), e.g. "hessen nordrhein-westfalen".
region_names() {
  local r names=()
  for r in $INDOOR_REGIONS; do names+=("${r##*/}"); done
  echo "${names[*]}"
}

# Podman runs on the host. Inside a distrobox/toolbox container it is reached via distrobox-host-exec.
if command -v podman >/dev/null 2>&1; then
  PODMAN=(podman)
elif command -v distrobox-host-exec >/dev/null 2>&1; then
  PODMAN=(distrobox-host-exec podman)
else
  PODMAN=(podman)
fi

podman_cmd() { "${PODMAN[@]}" "$@"; }

# podman-compose with the compose file of this directory. Environment variables are passed explicitly because
# distrobox-host-exec does not forward the caller's environment.
compose() {
  local env_args=(env "INDOOR_CACHE=$INDOOR_CACHE" "INDOOR_REGION_NAMES=$(region_names)"
                  "INDOOR_IMPORT_MEMORY=$INDOOR_IMPORT_MEMORY" "INDOOR_IMPORT_CPUS=$INDOOR_IMPORT_CPUS"
                  "INDOOR_PORT=$INDOOR_PORT" "INDOOR_BIND=$INDOOR_BIND" "INDOOR_MEMORY=$INDOOR_MEMORY"
                  "INDOOR_TOKENS_FILE=$INDOOR_TOKENS_FILE" "INDOOR_RATE_PER_MINUTE=$INDOOR_RATE_PER_MINUTE"
                  "INDOOR_RATE_BURST=$INDOOR_RATE_BURST" "INDOOR_LOG_LEVEL=$INDOOR_LOG_LEVEL")
  local tool
  if ! command -v podman-compose >/dev/null 2>&1 && command -v distrobox-host-exec >/dev/null 2>&1; then
    tool=(distrobox-host-exec "${env_args[@]}" podman-compose)
  else
    tool=("${env_args[@]}" podman-compose)
  fi
  "${tool[@]}" -f "$INDOOR_DIR/compose.yaml" -p bettermaps-indoor "$@"
}

build_gateway() {
  log "building the gateway/import image"
  podman_cmd build -q -t "$GATEWAY_IMAGE" -f "$INDOOR_DIR/Containerfile" "$INDOOR_DIR" >&2
}

ensure_osmium() {
  podman_cmd image exists "$OSMIUM_IMAGE" ||
    podman_cmd build -q -t "$OSMIUM_IMAGE" -f "$INDOOR_DIR/osmium.Containerfile" "$INDOOR_DIR" >&2
}
