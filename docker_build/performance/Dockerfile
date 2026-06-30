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
FROM grafana/k6:2.1.0@sha256:65c920dc067d5e2e00befbf982af6ad6ad0117034e8b1c65817c7975c52d4669

LABEL maintainer="James Bloom <jamesdbloom@gmail.com>"
