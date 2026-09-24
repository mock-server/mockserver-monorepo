#
# MockServer Performance Dockerfile (k6 load-testing harness)
#
# https://github.com/mock-server/mockserver
# http://www.mock-server.com
#
# Built and published as mockserver/mockserver:performance. Runs the k6 scripts
# under mockserver-performance-test/k6/ (mount that directory at /k6). The k6
# scripts seed/reset MockServer themselves (in setup/teardown), so no extra
# tooling is needed in the image — it is just a pinned grafana/k6.
#   docker run --rm -v "$PWD/mockserver-performance-test/k6:/k6:ro" \
#     -e MOCKSERVER_HOST=host.docker.internal:1080 \
#     mockserver/mockserver:performance run /k6/load.js
#

# pinned to match the version the suite is validated against; entrypoint is k6.
# Digest is the multi-arch OCI index (linux/amd64 + linux/arm64) so CI and local
# builds resolve the same image — matches the repo's digest-pinning convention.
FROM grafana/k6:2.3.0@sha256:9c2dee7f8ed74d317e4027c06a10f169b625638189de8d4555d0b3486a5aeb34

LABEL maintainer="James Bloom <jamesdbloom@gmail.com>"
