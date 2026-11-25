#!/usr/bin/env bash
# Runs the osmium export of the import (config/osmium-export.json) on the hand-written fixture
# testdata/export-fixture.osm and compares the result with testdata/export-fixture.geojsonseq, which the Go tests
# read (internal/venues/fixture_test.go). After changing the config or the fixture (testdata/genfixture.py):
#   python3 testdata/genfixture.py >testdata/export-fixture.osm && scripts/check-export.sh --update
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
ensure_osmium
out="$(podman_cmd run --rm --network none --read-only --cap-drop ALL -v "$INDOOR_DIR:/w:ro,z" "$OSMIUM_IMAGE" \
  export -c /w/config/osmium-export.json -f geojsonseq --add-unique-id=type_id /w/testdata/export-fixture.osm)"
if [[ "${1:-}" == --update ]]; then
  printf '%s\n' "$out" >"$INDOOR_DIR/testdata/export-fixture.geojsonseq"
  log "updated testdata/export-fixture.geojsonseq"
elif diff <(printf '%s\n' "$out") "$INDOOR_DIR/testdata/export-fixture.geojsonseq" >&2; then
  log "export of the fixture unchanged"
else
  die "export differs from testdata/export-fixture.geojsonseq (--update to accept)"
fi
