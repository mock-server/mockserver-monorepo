#!/usr/bin/env bash
# Build, test and lint the Rust client on exactly its declared MSRV (`rust-version`
# in mockserver-client-rust/Cargo.toml), which the stable step cannot check. Fails
# closed when the image's rustc is not that version, so the two cannot drift apart.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

RUST_MSRV_IMAGE="rust:1.88.0@sha256:af306cfa71d987911a781c37b59d7d67d934f49684058f96cf72079c3626bfe0"

msrv="$(sed -n 's/^rust-version = "\([0-9.]*\)"$/\1/p' "$REPO_ROOT/mockserver-client-rust/Cargo.toml")"
if [[ -z "$msrv" ]]; then
  echo "+++ :bangbang: no rust-version found in mockserver-client-rust/Cargo.toml" >&2
  exit 1
fi

# --locked: the committed Cargo.lock is what must build on the MSRV; without it
# cargo could re-resolve to dependency releases that need a newer rustc.
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i "$RUST_MSRV_IMAGE" \
  -w /build/mockserver-client-rust \
  -e "RUST_MSRV=$msrv" \
  -- bash -ec 'rustup component add clippy >/dev/null 2>&1 || true
    actual="$(rustc --version | cut -d" " -f2)"
    echo "rustc $actual (declared MSRV $RUST_MSRV)"
    case "$actual" in
      "$RUST_MSRV"|"$RUST_MSRV".*) ;;
      *) echo "+++ :bangbang: container rustc $actual is not the declared MSRV $RUST_MSRV" >&2; exit 1 ;;
    esac
    cargo test --locked --all-targets
    cargo clippy --locked --all-targets -- -D warnings'
