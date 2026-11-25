# Shared settings for the traff-server scripts (run.sh, stop.sh, new-token.sh). Source this file, do not run it.
# All values can be overridden through the environment.

TRAFF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TRAFF_CONFIG_DIR="${TRAFF_CONFIG_DIR:-${XDG_CONFIG_HOME:-$HOME/.config}/bettermaps-traff}"

# Published port, the only one. 127.0.0.1 is enough for a reverse proxy on the same machine and for the Android
# emulator (10.0.2.2 = host loopback).
export TRAFF_PORT="${TRAFF_PORT:-18400}"
export TRAFF_BIND="${TRAFF_BIND:-127.0.0.1}"
export TRAFF_MEMORY="${TRAFF_MEMORY:-256m}"
# Token hashes of the devices (scripts/new-token.sh adds lines). Outside the repo, readable only by you.
export TRAFF_TOKENS_FILE="${TRAFF_TOKENS_FILE:-$TRAFF_CONFIG_DIR/tokens}"
# Sources and their credentials as KEY=value lines (TRAFF_DATEX2_FEEDS, TRAFF_DATEX2_<NAME>_CLIENT_CERT, ...), read by
# the container as env_file. Paths in it refer to the container: the secrets directory is mounted at /secrets.
export TRAFF_ENV_FILE="${TRAFF_ENV_FILE:-$TRAFF_CONFIG_DIR/traff.env}"
# Client certificates/keys (Mobilithek), password files. Mounted read-only at /secrets.
export TRAFF_SECRETS_DIR="${TRAFF_SECRETS_DIR:-$TRAFF_CONFIG_DIR/secrets}"
export TRAFF_RATE_PER_MINUTE="${TRAFF_RATE_PER_MINUTE:-60}"
export TRAFF_RATE_BURST="${TRAFF_RATE_BURST:-30}"
export TRAFF_LOG_LEVEL="${TRAFF_LOG_LEVEL:-info}"

TRAFF_IMAGE=localhost/bettermaps-traff-server:latest

log() { printf '[traff %s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
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
  local env_args=(env "TRAFF_PORT=$TRAFF_PORT" "TRAFF_BIND=$TRAFF_BIND" "TRAFF_MEMORY=$TRAFF_MEMORY"
                  "TRAFF_TOKENS_FILE=$TRAFF_TOKENS_FILE" "TRAFF_ENV_FILE=$TRAFF_ENV_FILE"
                  "TRAFF_SECRETS_DIR=$TRAFF_SECRETS_DIR" "TRAFF_RATE_PER_MINUTE=$TRAFF_RATE_PER_MINUTE"
                  "TRAFF_RATE_BURST=$TRAFF_RATE_BURST" "TRAFF_LOG_LEVEL=$TRAFF_LOG_LEVEL")
  local tool
  if ! command -v podman-compose >/dev/null 2>&1 && command -v distrobox-host-exec >/dev/null 2>&1; then
    tool=(distrobox-host-exec "${env_args[@]}" podman-compose)
  else
    tool=("${env_args[@]}" podman-compose)
  fi
  "${tool[@]}" -f "$TRAFF_DIR/compose.yaml" -p bettermaps-traff "$@"
}

build_image() {
  log "building the traff-server image"
  podman_cmd build -q -t "$TRAFF_IMAGE" -f "$TRAFF_DIR/Containerfile" "$TRAFF_DIR" >&2
}

# Creates the configuration directory, an empty env file and the secrets directory (all private) if missing.
ensure_config() {
  mkdir -p "$TRAFF_CONFIG_DIR" "$TRAFF_SECRETS_DIR"
  chmod 700 "$TRAFF_CONFIG_DIR" "$TRAFF_SECRETS_DIR"
  if [[ ! -e "$TRAFF_ENV_FILE" ]]; then
    install -m 600 /dev/null "$TRAFF_ENV_FILE"
    log "created empty $TRAFF_ENV_FILE (sources: Autobahn API only)"
  fi
}
