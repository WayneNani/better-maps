#!/usr/bin/env bash
# Downloads the OSM extract ($OSRM_EXTRACT from Geofabrik) into $OSRM_CACHE/input.
#
# Idempotent: uses ETag/Last-Modified (HTTP 304). Every download is checked against the MD5 that Geofabrik publishes
# before it replaces the old file. A SHA-256 is written to <file>.sha256.
#
# Usage: OSRM_EXTRACT=europe/germany scripts/fetch-data.sh
# Env:   OSRM_MAX_FILESIZE (default 8G) refuses larger downloads.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

url="https://download.geofabrik.de/$OSRM_EXTRACT-latest.osm.pbf"
name="$(basename "$OSRM_PBF")"
dst="$OSRM_PBF" tmp="$OSRM_INPUT_DIR/.$name.part" etag="$OSRM_PBF.etag"
mkdir -p "$OSRM_INPUT_DIR"

args=(-fsSL --retry 3 --retry-delay 5 --connect-timeout 20 -A "$OSRM_USER_AGENT" --max-filesize "${OSRM_MAX_FILESIZE:-8G}"
      -R -o "$tmp" --etag-save "$tmp.etag" -w '%{http_code} %{url_effective} %{size_download}')
if [[ -f "$dst" ]]; then
  [[ -s "$etag" ]] && args+=(--etag-compare "$etag")
  args+=(-z "$dst")   # If-Modified-Since from the file time (set with -R)
fi

log "$name: checking $url"
rm -f "$tmp" "$tmp.etag"
start=$SECONDS
out="$(nice -n 10 curl "${args[@]}" "$url")" || { rm -f "$tmp" "$tmp.etag"; die "$name: download failed ($url)"; }
read -r code eff size <<<"$out"

if [[ "$code" == 304 || ! -s "$tmp" ]]; then
  rm -f "$tmp" "$tmp.etag"
  log "$name: unchanged (HTTP $code)"
  exit 0
fi
log "$name: downloaded $((size / 1024 / 1024)) MiB in $((SECONDS - start)) s"

# Geofabrik publishes <file>.md5 next to every extract; the effective URL is the mirror's file.
md5="$(curl -fsSL -A "$OSRM_USER_AGENT" "$eff.md5" 2>/dev/null || curl -fsSL -A "$OSRM_USER_AGENT" "$url.md5")" ||
  { rm -f "$tmp" "$tmp.etag"; die "$name: no MD5 published"; }
echo "${md5%% *}  $tmp" | md5sum -c --status - || { rm -f "$tmp" "$tmp.etag"; die "$name: MD5 mismatch"; }
log "$name: MD5 ok"

mv -f "$tmp" "$dst"
sha256sum "$dst" | awk -v n="$name" '{print $1 "  " n}' >"$dst.sha256"
if [[ -s "$tmp.etag" ]]; then mv -f "$tmp.etag" "$etag"; else rm -f "$tmp.etag"; fi
log "$name: updated ($(du -h "$dst" | cut -f1))"
