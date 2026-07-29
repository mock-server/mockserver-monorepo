#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Clean node_modules inside the container (as container-root) on exit. Under the
# elastic-ci-stack's userns-remap, files the container writes into the bind-mounted
# workspace are owned by a remapped UID the buildkite-agent user CANNOT delete — so
# a leftover native module (mockserver-vscode/node_modules/keytar/build/Release/
# keytar.node, built by @vscode/vsce's deps) made the NEXT build's git checkout fail
# to clean the dir ("unlinkat ... permission denied" -> "cloning git repository:
# exit status 128"), reddening mockserver-editors. Removing node_modules here (the
# container owns those files) leaves the workspace cleanly removable. The npm cache
# (--cache npm, mounted separately) is untouched, so re-install stays fast. The trap
# runs whether the tests pass or fail.
# `vsce package` runs here, not just at release time. It enforces rules tsc does not
# — notably that @types/vscode is not newer than engines.vscode — so compiling and
# testing green says nothing about whether the extension can actually be packaged.
# Release build #69 found that out the hard way: a Dependabot bump to
# @types/vscode ^1.125.0 against engines.vscode ^1.80.0 passed every PR, then failed
# the VS Code publish mid-release, after Maven Central and the rest had shipped.
# Packaging to /tmp keeps the .vsix out of the bind-mounted workspace, which the
# agent cannot clean up under userns-remap (see the node_modules note above).
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i node:20 \
  -w /build/mockserver-vscode \
  --cache npm \
  -- bash -c 'trap "rm -rf node_modules" EXIT; npm ci && npm run compile && npm test && npx vsce package --out /tmp/mockserver-vscode.vsix'
