#!/usr/bin/env bash
# Creates a token for a device: appends its hash to the token file and prints the token once, to be entered in the
# app (Einstellungen -> Luftbild-Server). The server never stores the token itself. The gateway picks up the change
# within 30 s.
#
# Usage: scripts/new-token.sh <label>     label: name of the device, A-Z a-z 0-9 . _ -, e.g. pixel-7
# Revoke a token: delete its line from the token file.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
label="${1:?usage: scripts/new-token.sh <label>}"

if command -v go >/dev/null 2>&1; then
  out="$(cd "$IMAGERY_DIR" && go run ./cmd/imagery-gateway token "$label")"
else
  podman_cmd image exists localhost/bettermaps-imagery-gateway:latest ||
    podman_cmd build -q -t localhost/bettermaps-imagery-gateway:latest -f "$IMAGERY_DIR/Containerfile" "$IMAGERY_DIR" >&2
  out="$(podman_cmd run --rm --network none localhost/bettermaps-imagery-gateway:latest token "$label")"
fi
token="$(sed -n 's/^token=//p' <<<"$out")"
line="$(sed -n 's/^line=//p' <<<"$out")"
[[ -n "$token" && -n "$line" ]] || die "could not create a token"

mkdir -p "$(dirname "$IMAGERY_TOKENS_FILE")"
chmod 700 "$(dirname "$IMAGERY_TOKENS_FILE")"
touch "$IMAGERY_TOKENS_FILE"
chmod 600 "$IMAGERY_TOKENS_FILE"
if grep -q "^$label " "$IMAGERY_TOKENS_FILE"; then
  die "label $label exists already in $IMAGERY_TOKENS_FILE (delete its line to replace it)"
fi
echo "$line  # $(date -I)" >>"$IMAGERY_TOKENS_FILE"
log "added $label to $IMAGERY_TOKENS_FILE"
# Only to the terminal, not to a log.
echo "Token for $label (enter it in the app; it is shown only now):"
echo "$token"
