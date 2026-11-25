# MapProxy for the better-maps imagery proxy: the official image plus gunicorn as WSGI server (gunicorn writes no
# access log unless asked to, so tile coordinates are not logged).
#
# Build: podman build --build-arg MAPPROXY_VERSION=8.1.1 -t localhost/bettermaps-mapproxy -f mapproxy.Containerfile .
ARG MAPPROXY_VERSION=8.1.1
FROM ghcr.io/mapproxy/mapproxy/mapproxy:${MAPPROXY_VERSION}
RUN pip install --user --no-cache-dir --disable-pip-version-check gunicorn==23.0.0
WORKDIR /mapproxy
EXPOSE 8080
# 2 processes x 4 threads; renderings are limited by the gateway (4) and per state server (2) anyway.
CMD ["gunicorn", "--bind", "0.0.0.0:8080", "--workers", "2", "--threads", "4", "--worker-class", "gthread", \
     "--worker-tmp-dir", "/dev/shm", "--timeout", "60", "--graceful-timeout", "10", "--log-level", "warning", \
     "app:application"]
