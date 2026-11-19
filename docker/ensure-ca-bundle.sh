#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Ensure a ca-bundle.pem exists in a Docker build context.
#
# Several MockServer Dockerfiles (docker/, docker/root/, docker/snapshot/,
# docker/root-snapshot/, docker/clustered/, docker/graaljs/, docker/http3/, docker/local/,
# docker/aot/) `COPY ca-bundle.pem` into their alpine stages so that builds
# behind a corporate TLS-inspecting proxy can trust the corporate root CA before
# `apk add` and the wget jar and JDK downloads. The COPY instruction fails if the file is absent, so this
# helper guarantees one is present (docker/webhook COPYs it optionally, so needs it only behind a proxy):
#
#   - if MOCKSERVER_LOCAL_CA_BUNDLE (or the NODE_EXTRA_CA_CERTS / AWS_CA_BUNDLE
#     fallbacks) points at a readable PEM, copy it into the context;
#   - otherwise create an empty placeholder (the Dockerfiles' `[ -s ]` guards
#     skip all trust changes for an empty file, so CI + published images are
#     byte-identical to a no-CA build).
#
# Usage:
#   created=$(docker/ensure-ca-bundle.sh <context-dir>)
#   # ... docker build <context-dir> ...
#   [[ "$created" == "created" ]] && rm -f "<context-dir>/ca-bundle.pem"
#
# Echoes "created" if it created the placeholder/copy (so the caller can clean
# it up afterward) or "exists" if a ca-bundle.pem was already present (left
# untouched). All diagnostic output goes to stderr so stdout carries only the
# single status token.
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

if [[ $# -lt 1 || -z "${1:-}" ]]; then
  echo "usage: $0 <docker-build-context-dir>" >&2
  exit 2
fi

context_dir="$1"
if [[ ! -d "$context_dir" ]]; then
  echo "ensure-ca-bundle: context dir not found: $context_dir" >&2
  exit 2
fi

ca_bundle_path="${context_dir%/}/ca-bundle.pem"

# Leave an already-staged bundle untouched (a caller may have placed a real one).
if [[ -f "$ca_bundle_path" ]]; then
  echo "exists"
  exit 0
fi

# Primary env var is MOCKSERVER_LOCAL_CA_BUNDLE; fall back to the common
# NODE_EXTRA_CA_CERTS / AWS_CA_BUNDLE so an already-configured shell works too.
local_ca="${MOCKSERVER_LOCAL_CA_BUNDLE:-${LOCAL_CA_BUNDLE:-${NODE_EXTRA_CA_CERTS:-${AWS_CA_BUNDLE:-}}}}"
if [[ -n "$local_ca" && -r "$local_ca" ]]; then
  echo "ensure-ca-bundle: staging corporate CA into $ca_bundle_path ($local_ca)" >&2
  cp "$local_ca" "$ca_bundle_path"
else
  # Empty placeholder — the Dockerfiles' `[ -s ]` guards treat it as a no-op.
  : > "$ca_bundle_path"
fi
echo "created"
