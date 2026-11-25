#!/usr/bin/env bash
# Stops and removes the containers. Downloads and imported data in $INDOOR_CACHE are kept.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
compose down >&2
log "server stopped; data kept in $INDOOR_CACHE"
