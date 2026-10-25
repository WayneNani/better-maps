#!/usr/bin/env bash
# Runs the Berlin evaluation set (queries.tsv) with search_quality_tool and writes the top 5 results as TSV.
#
# Usage: run_eval.sh <search_quality_tool> <mwm_dir> <out.tsv> [extra tool flags]
#   <mwm_dir> must contain Germany_Berlin.mwm, World.mwm and WorldCoasts.mwm
#   (e.g. pulled from the emulator: /sdcard/Android/data/app.comaps.fdroid.debug/files/<version>/).
#
# The search runs like the app's "everywhere" search: German locale, position and viewport in Berlin-Mitte,
# queries without a trailing space. Opening hours are evaluated at a fixed moment
# (Wed 2026-10-21 20:30 Europe/Berlin), so the results are reproducible.
set -euo pipefail

TOOL="${1:?search_quality_tool path}"
MWM_DIR="${2:?mwm dir}"
OUT="${3:?output tsv}"
shift 3

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$HERE" rev-parse --show-toplevel)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

grep -v '^#' "$HERE/queries.tsv" | cut -f3 >"$TMP/queries.txt"
printf '%s\n' Germany_Berlin World WorldCoasts >"$TMP/mwms.txt"

TZ=Europe/Berlin LC_ALL=C "$TOOL" \
  --data_path="$REPO_ROOT/data" \
  --mwm_path="$MWM_DIR" \
  --mwm_list_path="$TMP/mwms.txt" \
  --queries_path="$TMP/queries.txt" \
  --locale=de \
  --viewport=berlin \
  --position=52.5200,13.4050 \
  --opening_hours_time=1792607400 \
  --prefix_free=false \
  --top=5 \
  --tsv_out="$OUT" \
  "$@" >"$TMP/stdout.txt" 2>"$TMP/stderr.txt" || { cat "$TMP/stderr.txt" >&2; exit 1; }

tail -3 "$TMP/stdout.txt"
