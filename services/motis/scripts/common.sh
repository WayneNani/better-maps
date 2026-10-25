# Shared settings for the MOTIS scripts. Source this file, do not run it.
# All values can be overridden through the environment.

MOTIS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Version of the official image (ghcr.io/motis-project/motis). Keep it in sync with the MOTIS version the
# app's API client was generated from (android/sdk/motis_openapi.yaml, MOTIS v2.11.x, API /api/v6).
export MOTIS_VERSION="${MOTIS_VERSION:-2.11.4}"
# Data profile = file name in config/ without .yml. Selects input files, config and data directory.
export MOTIS_PROFILE="${MOTIS_PROFILE:-berlin}"
# Downloads and import results live outside the repo.
export MOTIS_CACHE="${MOTIS_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/bettermaps-motis}"
export MOTIS_PORT="${MOTIS_PORT:-18090}"
# 127.0.0.1 is enough for the Android emulator (10.0.2.2 = host loopback). Use 0.0.0.0 for real devices.
export MOTIS_BIND="${MOTIS_BIND:-127.0.0.1}"
export MOTIS_MEMORY="${MOTIS_MEMORY:-6g}"
export MOTIS_CPUS="${MOTIS_CPUS:-3}"
export MOTIS_USER_AGENT="${MOTIS_USER_AGENT:-better-maps-motis-fetch/1.0 (self-hosted MOTIS test setup)}"

MOTIS_CONFIG="$MOTIS_DIR/config/$MOTIS_PROFILE.yml"
MOTIS_PROFILE_DIR="$MOTIS_CACHE/$MOTIS_PROFILE"
MOTIS_INPUT_DIR="$MOTIS_CACHE/input"

log() { printf '[motis %s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
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

# podman-compose (or `podman compose`) with the compose file of this directory. Environment variables are
# passed explicitly because distrobox-host-exec does not forward the caller's environment.
compose() {
  local env_args=(env "MOTIS_VERSION=$MOTIS_VERSION" "MOTIS_PROFILE=$MOTIS_PROFILE" "MOTIS_CACHE=$MOTIS_CACHE"
                  "MOTIS_PORT=$MOTIS_PORT" "MOTIS_BIND=$MOTIS_BIND" "MOTIS_MEMORY=$MOTIS_MEMORY"
                  "MOTIS_CPUS=$MOTIS_CPUS" "MOTIS_IMPORT_DIR=${MOTIS_IMPORT_DIR:-data}")
  local tool=(podman-compose)
  if ! command -v podman-compose >/dev/null 2>&1 && command -v distrobox-host-exec >/dev/null 2>&1; then
    tool=(distrobox-host-exec "${env_args[@]}" podman-compose)
  else
    tool=("${env_args[@]}" podman-compose)
  fi
  "${tool[@]}" -f "$MOTIS_DIR/compose.yaml" -p bettermaps-motis "$@"
}

require_profile() {
  [[ -f "$MOTIS_CONFIG" ]] || die "unknown profile '$MOTIS_PROFILE' (no $MOTIS_CONFIG)"
}

# Input files of a profile: lines "name url" from the comment block "# input: <name> <url>" in the config.
profile_inputs() {
  sed -n 's/^# input: \([^ ]*\) \(.*\)$/\1 \2/p' "$MOTIS_CONFIG"
}
