#!/usr/bin/env bash
# Downloads the OSM extracts ($INDOOR_REGIONS from Geofabrik) and their outlines (.poly) into $INDOOR_CACHE/input.
#
# Idempotent: uses ETag/Last-Modified (HTTP 304) while a downloaded file is there. Every download is checked against
# the MD5 that Geofabrik publishes before it replaces the old file. A SHA-256 is written to <file>.sha256 (kept
# after import.sh deleted the extract; update.sh compares it with the imported data).
#
# Usage: INDOOR_REGIONS="europe/germany/hessen europe/germany/nordrhein-westfalen" scripts/fetch-data.sh
# Env:   INDOOR_MAX_FILESIZE (default 4G) refuses larger downloads.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

mkdir -p "$INDOOR_INPUT_DIR"
chmod 700 "$INDOOR_CACHE"

fetch_one() { # <geofabrik path>
  local region="$1" name="${1##*/}"
  local url="https://download.geofabrik.de/$region-latest.osm.pbf"
  local dst="$INDOOR_INPUT_DIR/$name-latest.osm.pbf"
  local tmp="$INDOOR_INPUT_DIR/.$name-latest.osm.pbf.part" etag="$dst.etag"
  # The old URL redirects to <region>/<name>-latest.osm.pbf and from there to the dated file. Stalled transfers
  # (below 10 kB/s for 2 minutes) are aborted.
  local args=(-fsSL --retry 3 --retry-delay 5 --connect-timeout 20 --speed-limit 10240 --speed-time 120 -A "$INDOOR_USER_AGENT"
              --max-filesize "${INDOOR_MAX_FILESIZE:-4G}" -R -o "$tmp" --etag-save "$tmp.etag"
              -w '%{http_code} %{url_effective} %{size_download}')
  if [[ -f "$dst" ]]; then
    [[ -s "$etag" ]] && args+=(--etag-compare "$etag")
    args+=(-z "$dst")   # If-Modified-Since from the file time (set with -R)
  fi

  # Outline of the region (statistics only), small. Geofabrik moved the files into a directory per region
  # (<region>/<name>.poly); the old location is tried as well.
  local poly_args=(-fsSL --connect-timeout 20 --max-time 60 -A "$INDOOR_USER_AGENT" --max-filesize 20M
                   -o "$INDOOR_INPUT_DIR/.$name.poly.part")
  if curl "${poly_args[@]}" "https://download.geofabrik.de/$region/$name.poly" ||
     curl "${poly_args[@]}" "https://download.geofabrik.de/$region.poly"; then
    mv -f "$INDOOR_INPUT_DIR/.$name.poly.part" "$INDOOR_INPUT_DIR/$name.poly"
  else
    rm -f "$INDOOR_INPUT_DIR/.$name.poly.part"
    log "$name: no outline (.poly), venues per region will be missing from the statistics"
  fi

  log "$name: checking $url"
  rm -f "$tmp" "$tmp.etag"
  local start=$SECONDS out code eff size
  out="$(nice -n 10 curl "${args[@]}" "$url")" || { rm -f "$tmp" "$tmp.etag"; die "$name: download failed ($url)"; }
  read -r code eff size <<<"$out"

  if [[ "$code" == 304 || ! -s "$tmp" ]]; then
    rm -f "$tmp" "$tmp.etag"
    log "$name: unchanged (HTTP $code)"
    return 0
  fi
  log "$name: downloaded $((size / 1024 / 1024)) MiB in $((SECONDS - start)) s"

  # Geofabrik publishes <file>.md5 next to every extract; the effective URL is the mirror's file.
  local md5
  md5="$(curl -fsSL --max-time 30 -A "$INDOOR_USER_AGENT" "$eff.md5" 2>/dev/null ||
         curl -fsSL --max-time 30 -A "$INDOOR_USER_AGENT" "$url.md5")" ||
    { rm -f "$tmp" "$tmp.etag"; die "$name: no MD5 published"; }
  echo "${md5%% *}  $tmp" | md5sum -c --status - || { rm -f "$tmp" "$tmp.etag"; die "$name: MD5 mismatch"; }
  log "$name: MD5 ok"

  mv -f "$tmp" "$dst"
  sha256sum "$dst" | awk -v n="$(basename "$dst")" '{print $1 "  " n}' >"$dst.sha256"
  if [[ -s "$tmp.etag" ]]; then mv -f "$tmp.etag" "$etag"; else rm -f "$tmp.etag"; fi
  log "$name: updated ($(du -h "$dst" | cut -f1))"
}

for region in $INDOOR_REGIONS; do
  fetch_one "$region"
done
