#!/usr/bin/env bash
# Stops and removes the containers. The tile cache in $IMAGERY_CACHE is kept (delete it to free the space).
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
compose down >&2
log "server stopped; tile cache kept in $IMAGERY_CACHE ($(du -sh "$IMAGERY_CACHE" 2>/dev/null | cut -f1))"
