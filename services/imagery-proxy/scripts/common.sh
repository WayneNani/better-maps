# Shared settings for the imagery-proxy scripts. Source this file, do not run it.
# All values can be overridden through the environment.

IMAGERY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Official MapProxy image (ghcr.io/mapproxy/mapproxy/mapproxy); mapproxy.Containerfile adds gunicorn.
export MAPPROXY_VERSION="${MAPPROXY_VERSION:-8.1.1}"
# Tile cache of the gateway (only tiles that devices asked for, no seeding). Outside the repo.
export IMAGERY_CACHE="${IMAGERY_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/bettermaps-imagery}"
export IMAGERY_CACHE_MAX_MB="${IMAGERY_CACHE_MAX_MB:-1024}"
export IMAGERY_CACHE_MAX_AGE="${IMAGERY_CACHE_MAX_AGE:-720h}"
# Gateway: published port, the only one. 127.0.0.1 is enough for a reverse proxy on the same machine and for the
# Android emulator (10.0.2.2 = host loopback).
export IMAGERY_PORT="${IMAGERY_PORT:-18200}"
export IMAGERY_BIND="${IMAGERY_BIND:-127.0.0.1}"
# Token hashes of the devices (scripts/new-token.sh adds lines). Outside the repo, readable only by you.
export IMAGERY_TOKENS_FILE="${IMAGERY_TOKENS_FILE:-${XDG_CONFIG_HOME:-$HOME/.config}/bettermaps-imagery/tokens}"
export IMAGERY_MAX_ZOOM="${IMAGERY_MAX_ZOOM:-19}"
export IMAGERY_RATE_PER_MINUTE="${IMAGERY_RATE_PER_MINUTE:-600}"
export IMAGERY_RATE_BURST="${IMAGERY_RATE_BURST:-300}"
# Concurrent tile renderings; MapProxy additionally limits the requests per state server (mapproxy.yaml).
export IMAGERY_MAX_CONCURRENT="${IMAGERY_MAX_CONCURRENT:-4}"
export IMAGERY_LOG_LEVEL="${IMAGERY_LOG_LEVEL:-info}"
export MAPPROXY_MEMORY="${MAPPROXY_MEMORY:-768m}"

log() { printf '[imagery %s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
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
  local env_args=(env "MAPPROXY_VERSION=$MAPPROXY_VERSION" "IMAGERY_CACHE=$IMAGERY_CACHE"
                  "IMAGERY_CACHE_MAX_MB=$IMAGERY_CACHE_MAX_MB" "IMAGERY_CACHE_MAX_AGE=$IMAGERY_CACHE_MAX_AGE"
                  "IMAGERY_PORT=$IMAGERY_PORT" "IMAGERY_BIND=$IMAGERY_BIND" "IMAGERY_TOKENS_FILE=$IMAGERY_TOKENS_FILE"
                  "IMAGERY_MAX_ZOOM=$IMAGERY_MAX_ZOOM" "IMAGERY_RATE_PER_MINUTE=$IMAGERY_RATE_PER_MINUTE"
                  "IMAGERY_RATE_BURST=$IMAGERY_RATE_BURST" "IMAGERY_MAX_CONCURRENT=$IMAGERY_MAX_CONCURRENT"
                  "IMAGERY_LOG_LEVEL=$IMAGERY_LOG_LEVEL" "MAPPROXY_MEMORY=$MAPPROXY_MEMORY")
  local tool
  if ! command -v podman-compose >/dev/null 2>&1 && command -v distrobox-host-exec >/dev/null 2>&1; then
    tool=(distrobox-host-exec "${env_args[@]}" podman-compose)
  else
    tool=("${env_args[@]}" podman-compose)
  fi
  "${tool[@]}" -f "$IMAGERY_DIR/compose.yaml" -p bettermaps-imagery "$@"
}

build_images() {
  log "building the gateway and MapProxy images"
  podman_cmd build -q -t localhost/bettermaps-imagery-gateway:latest -f "$IMAGERY_DIR/Containerfile" "$IMAGERY_DIR" >&2
  podman_cmd build -q --build-arg "MAPPROXY_VERSION=$MAPPROXY_VERSION" -t localhost/bettermaps-mapproxy:latest \
    -f "$IMAGERY_DIR/mapproxy.Containerfile" "$IMAGERY_DIR" >&2
}
