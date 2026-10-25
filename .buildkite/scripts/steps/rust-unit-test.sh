#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# rust:1 (latest stable 1.x) rather than a pinned minor: the client's transitive
# deps (idna/icu) track recent rustc and a pinned older image fails to compile
# them ("requires rustc 1.8x"). clippy isn't bundled in every rust image tag, so
# add it explicitly.
# --locked: fail when Cargo.lock no longer matches Cargo.toml instead of
# silently re-resolving it.
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i rust:1 \
  -w /build/mockserver-client-rust \
  -- bash -ec "rustup component add clippy >/dev/null 2>&1 || true
    cargo test --locked --all-targets
    cargo clippy --locked --all-targets -- -D warnings"
