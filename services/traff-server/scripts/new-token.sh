#!/usr/bin/env bash
# Creates a token for a device: appends its hash to the token file and prints the token once, to be entered in the
# app (Einstellungen -> Verkehr -> Zugangs-Token). The server never stores the token itself. The server picks up the
# change within 30 s.
#
# Usage: scripts/new-token.sh <label>     label: name of the device, A-Z a-z 0-9 . _ -, e.g. pixel-7
# Revoke a token: delete its line from the token file.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
label="${1:?usage: scripts/new-token.sh <label>}"

if command -v go >/dev/null 2>&1; then
  out="$(cd "$TRAFF_DIR" && go run ./cmd/traff-server token "$label")"
else
  podman_cmd image exists "$TRAFF_IMAGE" || build_image
  out="$(podman_cmd run --rm --network none "$TRAFF_IMAGE" token "$label")"
fi
token="$(sed -n 's/^token=//p' <<<"$out")"
line="$(sed -n 's/^line=//p' <<<"$out")"
[[ -n "$token" && -n "$line" ]] || die "could not create a token"

mkdir -p "$(dirname "$TRAFF_TOKENS_FILE")"
chmod 700 "$(dirname "$TRAFF_TOKENS_FILE")"
touch "$TRAFF_TOKENS_FILE"
chmod 600 "$TRAFF_TOKENS_FILE"
if grep -q "^$label " "$TRAFF_TOKENS_FILE"; then
  die "label $label exists already in $TRAFF_TOKENS_FILE (delete its line to replace it)"
fi
echo "$line  # $(date -I)" >>"$TRAFF_TOKENS_FILE"
log "added $label to $TRAFF_TOKENS_FILE"
# Only to the terminal, not to a log.
echo "Token for $label (enter it in the app; it is shown only now):"
echo "$token"
