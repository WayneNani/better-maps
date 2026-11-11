# osmium-tool from Debian, to filter the OSM extract to what the OSRM car profile reads before osrm-extract
# (Germany: 4.6 GB -> ~1 GB; osrm-extract then fits into the memory limit).
#
# Build: podman build -t bettermaps-osmium -f osmium.Containerfile .
FROM docker.io/library/debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends osmium-tool && rm -rf /var/lib/apt/lists/*
ENTRYPOINT ["osmium"]
