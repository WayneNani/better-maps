# osmium-tool from Debian, to filter the OSM extracts to the indoor-relevant objects and export them as GeoJSONSeq.
# Identical to services/route-server/osmium.Containerfile and built under the same name, so both services share
# one image (scripts/common.sh: ensure_osmium builds it only if it does not exist).
#
# Build: podman build -t bettermaps-osmium -f osmium.Containerfile .
FROM docker.io/library/debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends osmium-tool && rm -rf /var/lib/apt/lists/*
ENTRYPOINT ["osmium"]
