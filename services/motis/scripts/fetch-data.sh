#!/usr/bin/env bash
# Downloads the input files (OSM, GTFS) of a profile into $MOTIS_CACHE/input.
#
# Idempotent: uses ETag/Last-Modified (HTTP 304) where the server supports it, otherwise compares the
# SHA-256 of the new download with the existing file. Every download is checked before it replaces the
# old file (Geofabrik: published MD5; GTFS: ZIP integrity and required files). A SHA-256 per file is
# written to <file>.sha256.
#
# Usage: MOTIS_PROFILE=berlin scripts/fetch-data.sh
# Env:   MOTIS_MAX_FILESIZE (default 1G) refuses larger downloads.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_profile

MAX_FILESIZE="${MOTIS_MAX_FILESIZE:-1G}"
mkdir -p "$MOTIS_INPUT_DIR"

verify_download() { # <file> <name> <effective url>
  local file="$1" name="$2" url="$3"
  case "$name" in
    *.osm.pbf)
      # Geofabrik publishes <file>.md5 next to every extract; the effective URL is the dated file.
      local md5
      md5="$(curl -fsSL -A "$MOTIS_USER_AGENT" "$url.md5" | awk '{print $1}')" || die "$name: no MD5 at $url.md5"
      echo "$md5  $file" | md5sum -c --status - || die "$name: MD5 mismatch"
      log "$name: MD5 ok ($md5)"
      ;;
    *.zip)
      unzip -tqq "$file" >/dev/null || die "$name: corrupt ZIP"
      local listing f
      listing="$(unzip -Z1 "$file")"
      for f in agency.txt stops.txt routes.txt trips.txt stop_times.txt; do
        grep -qx "$f" <<<"$listing" || die "$name: $f missing, not a GTFS feed"
      done
      log "$name: ZIP and GTFS files ok"
      ;;
  esac
}

fetch() { # <name> <url>
  local name="$1" url="$2"
  local dst="$MOTIS_INPUT_DIR/$name" tmp="$MOTIS_INPUT_DIR/.$name.part" etag="$MOTIS_INPUT_DIR/$name.etag"
  local args=(-fsSL --retry 3 --retry-delay 5 --connect-timeout 20 -A "$MOTIS_USER_AGENT"
              --max-filesize "$MAX_FILESIZE" -R -o "$tmp" --etag-save "$tmp.etag"
              -w '%{http_code} %{url_effective} %{size_download}')
  if [[ -f "$dst" ]]; then
    [[ -s "$etag" ]] && args+=(--etag-compare "$etag")
    args+=(-z "$dst")   # If-Modified-Since from the file time (set with -R)
  fi

  log "$name: checking $url"
  rm -f "$tmp" "$tmp.etag"
  local out code eff size start=$SECONDS
  out="$(curl "${args[@]}" "$url")" || { rm -f "$tmp" "$tmp.etag"; die "$name: download failed ($url)"; }
  read -r code eff size <<<"$out"

  if [[ "$code" == 304 || ! -s "$tmp" ]]; then
    rm -f "$tmp" "$tmp.etag"
    log "$name: unchanged (HTTP $code)"
    return
  fi
  log "$name: downloaded $((size / 1024 / 1024)) MiB in $((SECONDS - start)) s"
  verify_download "$tmp" "$name" "$eff"

  local sha
  sha="$(sha256sum "$tmp" | awk '{print $1}')"
  if [[ -f "$dst.sha256" && "$(awk '{print $1}' "$dst.sha256")" == "$sha" ]]; then
    log "$name: same content as before (no ETag/Last-Modified support)"
    touch -r "$tmp" "$dst"
    rm -f "$tmp"
  else
    mv -f "$tmp" "$dst"
    echo "$sha  $name" >"$dst.sha256"
    echo "$url" >"$dst.url"
    log "$name: updated (sha256 $sha)"
  fi
  if [[ -s "$tmp.etag" ]]; then mv -f "$tmp.etag" "$etag"; else rm -f "$tmp.etag"; fi
}

while read -r name url; do
  fetch "$name" "$url"
done < <(profile_inputs)

log "input directory: $MOTIS_INPUT_DIR ($(du -sh "$MOTIS_INPUT_DIR" | cut -f1))"
