#!/usr/bin/env bash
set -euo pipefail

# Node is installed from the official nodejs.org binary tarball rather than the
# NodeSource apt repository. deb.nodesource.com started answering 403 to every
# request - the repository index and the GPG key alike - which broke this step
# (and so every build running it) with "gpg: no valid OpenPGP data found". The
# tarball needs no third-party apt repository and no signing key, so there is one
# fewer external service able to take the pipeline down.
#
# Pin a full version rather than a major so the toolchain is reproducible.
NODE_VERSION="${NODE_VERSION:-22.23.1}"

apt-get update -y
apt-get install -y --no-install-recommends ca-certificates curl xz-utils

case "$(dpkg --print-architecture)" in
  amd64) NODE_ARCH="x64" ;;
  arm64) NODE_ARCH="arm64" ;;
  *)
    echo "unsupported architecture: $(dpkg --print-architecture)" >&2
    exit 1
    ;;
esac

TARBALL="node-v${NODE_VERSION}-linux-${NODE_ARCH}.tar.xz"
BASE_URL="https://nodejs.org/dist/v${NODE_VERSION}"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

curl --max-time 300 --connect-timeout 10 --retry 3 --retry-delay 5 -fsSL \
  -o "${WORK_DIR}/${TARBALL}" "${BASE_URL}/${TARBALL}"
curl --max-time 60 --connect-timeout 10 --retry 3 --retry-delay 5 -fsSL \
  -o "${WORK_DIR}/SHASUMS256.txt" "${BASE_URL}/SHASUMS256.txt"

# Fail closed if the tarball is not listed - an empty filter would otherwise let
# "sha256sum -c" pass with nothing to check.
grep " ${TARBALL}\$" "${WORK_DIR}/SHASUMS256.txt" > "${WORK_DIR}/expected.sha256"
(cd "$WORK_DIR" && sha256sum -c expected.sha256)

# --strip-components=1 lands bin/node, bin/npm and lib/node_modules directly in
# /usr/local, so node and npm are on PATH with no extra symlinks.
tar -xJf "${WORK_DIR}/${TARBALL}" -C /usr/local --strip-components=1 --no-same-owner

node --version
npm --version
