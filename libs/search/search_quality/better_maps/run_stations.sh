#!/usr/bin/env bash
# Runs the station evaluation set (stations.tsv) with search_quality_tool and writes the top 5 results as TSV.
#
# Usage: run_stations.sh <search_quality_tool> <mwm_dir> <out.tsv> [all] [extra tool flags]
#   <mwm_dir> must contain Germany_Berlin.mwm, "Germany_Free State of Bavaria_Middle Franconia.mwm", World.mwm and
#   WorldCoasts.mwm. With "all", all maps in <mwm_dir> are searched (e.g. all of Germany, like on a phone with
#   the whole country downloaded).
#
# Every query is searched from the viewport and user position named in front of it ("berlin: Nürnberg Hbf"),
# as typed in the app (German locale, no trailing space). The query column of the output keeps the prefix.
set -euo pipefail

TOOL="${1:?search_quality_tool path}"
MWM_DIR="${2:?mwm dir}"
OUT="${3:?output tsv}"
shift 3
ALL=0
if [[ "${1:-}" == "all" ]]; then
  ALL=1
  shift
fi

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$HERE" rev-parse --show-toplevel)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if [[ $ALL == 1 ]]; then
  find "$MWM_DIR" -maxdepth 1 -name '*.mwm' -printf '%f\n' | sed 's/\.mwm$//' | sort >"$TMP/mwms.txt"
else
  printf '%s\n' Germany_Berlin "Germany_Free State of Bavaria_Middle Franconia" World WorldCoasts >"$TMP/mwms.txt"
fi

declare -A POSITIONS=([berlin]=52.5200,13.4050 [nuremberg]=49.4521,11.0767 [bonn]=50.7374,7.0982)

printf 'query\tpos\tname\ttype\taddress\tdistance_m\topen\tranking\thighlight\n' >"$OUT"
for viewport in "${!POSITIONS[@]}"; do
  grep -v '^#' "$HERE/stations.tsv" | cut -f3 | { grep "^$viewport: " || true; } | sed "s/^$viewport: //" \
    >"$TMP/queries.txt"
  [[ -s "$TMP/queries.txt" ]] || continue

  TZ=Europe/Berlin LC_ALL=C "$TOOL" \
    --data_path="$REPO_ROOT/data" \
    --mwm_path="$MWM_DIR" \
    --mwm_list_path="$TMP/mwms.txt" \
    --queries_path="$TMP/queries.txt" \
    --locale=de \
    --viewport="$viewport" \
    --position="${POSITIONS[$viewport]}" \
    --opening_hours_time=1792607400 \
    --prefix_free=false \
    --top=5 \
    --tsv_out="$TMP/out.tsv" \
    "$@" >"$TMP/stdout.txt" 2>"$TMP/stderr.txt" || { cat "$TMP/stderr.txt" >&2; exit 1; }

  tail -n +2 "$TMP/out.tsv" | sed "s/^/$viewport: /" >>"$OUT"
  echo "$viewport: $(tail -2 "$TMP/stdout.txt" | tr '\n' ' ')"
done
