#!/usr/bin/env bash
# Stops and removes the container. Tokens, env file and secrets in the configuration directory are kept.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
compose down >&2
log "server stopped; configuration kept in $TRAFF_CONFIG_DIR"
