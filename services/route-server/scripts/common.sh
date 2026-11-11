# Shared settings for the route-server scripts. Source this file, do not run it.
# All values can be overridden through the environment.

ROUTE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Official OSRM image (ghcr.io/project-osrm/osrm-backend).
export OSRM_VERSION="${OSRM_VERSION:-v6.0.0}"
# Geofabrik path of the extract without "-latest.osm.pbf", e.g. europe/germany or europe/germany/bremen.
export OSRM_EXTRACT="${OSRM_EXTRACT:-europe/germany}"
# Region name = last part of the path; selects the input file and the data directory.
export OSRM_REGION="${OSRM_REGION:-${OSRM_EXTRACT##*/}}"
# Downloads and import results live outside the repo.
export OSRM_CACHE="${OSRM_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/bettermaps-osrm}"
# Preprocessing limits: Germany needs about 9 GB peak (osrm-extract), see README "Ressourcenbedarf".
export OSRM_IMPORT_MEMORY="${OSRM_IMPORT_MEMORY:-12g}"
export OSRM_THREADS="${OSRM_THREADS:-6}"
# Routing engine limits (the MLD data of Germany is memory-mapped, ~4-5 GB resident).
export OSRM_MEMORY="${OSRM_MEMORY:-6g}"
export OSRM_CPUS="${OSRM_CPUS:-2}"
# Gateway: published port, the only one. 127.0.0.1 is enough for a reverse proxy on the same machine and for the
# Android emulator (10.0.2.2 = host loopback).
export ROUTE_PORT="${ROUTE_PORT:-18100}"
export ROUTE_BIND="${ROUTE_BIND:-127.0.0.1}"
# Token hashes of the devices (scripts/new-token.sh adds lines). Outside the repo, readable only by you.
export ROUTE_TOKENS_FILE="${ROUTE_TOKENS_FILE:-${XDG_CONFIG_HOME:-$HOME/.config}/bettermaps-route/tokens}"
export ROUTE_MAX_POINTS="${ROUTE_MAX_POINTS:-25}"
export ROUTE_RATE_PER_MINUTE="${ROUTE_RATE_PER_MINUTE:-30}"
export ROUTE_RATE_BURST="${ROUTE_RATE_BURST:-60}"
export ROUTE_LOG_LEVEL="${ROUTE_LOG_LEVEL:-info}"
export OSRM_USER_AGENT="${OSRM_USER_AGENT:-better-maps-osrm-fetch/1.0 (self-hosted OSRM setup)}"

OSRM_INPUT_DIR="$OSRM_CACHE/input"
OSRM_REGION_DIR="$OSRM_CACHE/$OSRM_REGION"
OSRM_PBF="$OSRM_INPUT_DIR/$OSRM_REGION-latest.osm.pbf"

log() { printf '[route %s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

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
  local env_args=(env "OSRM_VERSION=$OSRM_VERSION" "OSRM_REGION=$OSRM_REGION" "OSRM_CACHE=$OSRM_CACHE"
                  "OSRM_IMPORT_MEMORY=$OSRM_IMPORT_MEMORY" "OSRM_THREADS=$OSRM_THREADS" "OSRM_MEMORY=$OSRM_MEMORY"
                  "OSRM_CPUS=$OSRM_CPUS" "OSRM_IMPORT_DIR=${OSRM_IMPORT_DIR:-data.next}"
                  "ROUTE_PORT=$ROUTE_PORT" "ROUTE_BIND=$ROUTE_BIND" "ROUTE_TOKENS_FILE=$ROUTE_TOKENS_FILE"
                  "ROUTE_MAX_POINTS=$ROUTE_MAX_POINTS" "ROUTE_RATE_PER_MINUTE=$ROUTE_RATE_PER_MINUTE"
                  "ROUTE_RATE_BURST=$ROUTE_RATE_BURST" "ROUTE_LOG_LEVEL=$ROUTE_LOG_LEVEL")
  local tool
  if ! command -v podman-compose >/dev/null 2>&1 && command -v distrobox-host-exec >/dev/null 2>&1; then
    tool=(distrobox-host-exec "${env_args[@]}" podman-compose)
  else
    tool=("${env_args[@]}" podman-compose)
  fi
  "${tool[@]}" -f "$ROUTE_DIR/compose.yaml" -p bettermaps-route "$@"
}
