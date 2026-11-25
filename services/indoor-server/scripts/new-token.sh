#!/usr/bin/env bash
# Creates a token for a device: appends its hash to the token file and prints the token once, to be entered in the
# app (Einstellungen -> Kartendarstellung -> Innenraumkarten-Server). The server never stores the token itself. The gateway picks up the change
# within 30 s.
#
# Usage: scripts/new-token.sh <label>     label: name of the device, A-Z a-z 0-9 . _ -, e.g. pixel-7
# Revoke a token: delete its line from the token file.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
label="${1:?usage: scripts/new-token.sh <label>}"

if command -v go >/dev/null 2>&1; then
  out="$(cd "$INDOOR_DIR" && go run ./cmd/indoor-gateway token "$label")"
else
  podman_cmd image exists "$GATEWAY_IMAGE" || build_gateway
  out="$(podman_cmd run --rm --network none "$GATEWAY_IMAGE" token "$label")"
fi
token="$(sed -n 's/^token=//p' <<<"$out")"
line="$(sed -n 's/^line=//p' <<<"$out")"
[[ -n "$token" && -n "$line" ]] || die "could not create a token"

mkdir -p "$(dirname "$INDOOR_TOKENS_FILE")"
chmod 700 "$(dirname "$INDOOR_TOKENS_FILE")"
touch "$INDOOR_TOKENS_FILE"
chmod 600 "$INDOOR_TOKENS_FILE"
if grep -q "^$label " "$INDOOR_TOKENS_FILE"; then
  die "label $label exists already in $INDOOR_TOKENS_FILE (delete its line to replace it)"
fi
echo "$line  # $(date -I)" >>"$INDOOR_TOKENS_FILE"
log "added $label to $INDOOR_TOKENS_FILE"
# Only to the terminal, not to a log.
echo "Token for $label (enter it in the app; it is shown only now):"
echo "$token"
