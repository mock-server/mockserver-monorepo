#!/usr/bin/env bash
# Publish mockserver-client crate to crates.io.
#
# Dry-run: update Cargo.toml version + cargo check (in-container), skip publish.
# HARD: a failed cargo check/publish aborts the step. The crate is built and
# published inside a pinned rust container (no host `cargo` required) — this
# replaced a host `command -v cargo` probe that silently skipped the publish on
# the release-queue agent, which has only Java/Maven/Docker.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$SCRIPT_DIR/_lib.sh"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true; shift ;;
    --execute) DRY_RUN=false; shift ;;
    -h|--help) echo "Usage: $0 [--dry-run|--execute]"; exit 0 ;;
    *) log_error "Unknown arg: $1"; exit 2 ;;
  esac
done

require_release_inputs
skip_unless_release_type "client-rust" full,post-maven

log_step "Publish Rust client $RELEASE_VERSION (dry-run=$DRY_RUN)"

CRATE_DIR="$REPO_ROOT/mockserver-client-rust"
CARGO_TOML="$CRATE_DIR/Cargo.toml"

# Bump version in Cargo.toml (host file edit — Cargo.toml is on the bind-mounted
# repo, so the in-container cargo sees the bumped version).
if ! is_dry_run; then
  log_info "Updating version to $RELEASE_VERSION in Cargo.toml"
  sed -i.bak "s/^version = \".*\"/version = \"${RELEASE_VERSION}\"/" "$CARGO_TOML"
  rm -f "$CARGO_TOML.bak"
fi

# Validate the crate builds in a pinned rust container — no host 'cargo'
# required. HARD: a cargo check failure aborts the step (set -e propagates).
log_info "Validating crate (cargo check) in $RUST_IMAGE"
in_docker "$RUST_IMAGE" -w /build/mockserver-client-rust -- cargo check

if is_dry_run; then
  log_dry "skip: cargo publish to crates.io"
  exit 0
fi

# Idempotent: if this exact version is already on crates.io, skip the publish.
# crates.io API visibility is eventually-consistent so this check can lag a fresh
# publish — that's fine here, it only ever causes us to (re)attempt a publish,
# which the run_idempotent wrapper below absorbs as a duplicate.
# The `-A` User-Agent is REQUIRED: crates.io returns 403 to requests without a
# User-Agent, which would make this pre-check return non-200 and skip the
# fast-path (see the tc-rust build #53 failure for the same root cause).
CRATES_UA='mockserver-release (+https://github.com/mock-server/mockserver-monorepo)'
crates_http=$(curl -s -A "$CRATES_UA" -o /dev/null -w "%{http_code}" \
  "https://crates.io/api/v1/crates/mockserver-client/${RELEASE_VERSION}" 2>/dev/null || echo "000")
if [[ "$crates_http" == "200" ]]; then
  log_info "mockserver-client $RELEASE_VERSION already on crates.io — skipping"
  exit 0
fi

# The crates.io token EXISTS (mockserver-release/crates, key: token). If it is
# genuinely absent, that's a hard failure now — we no longer silently skip.
CARGO_TOKEN=$(load_secret "mockserver-release/crates" "token")
if [[ -z "$CARGO_TOKEN" || "$CARGO_TOKEN" == "null" ]]; then
  log_error "mockserver-release/crates secret missing/empty — cannot publish"
  exit 1
fi

# Publish to crates.io inside the container. HARD-fail on a real publish error,
# with retry to ride out transient registry/network blips. The token is passed
# via in_docker --secret-env.
# run_idempotent absorbs a duplicate publish: if a prior build already shipped
# this version, `cargo publish` exits 101 with "already exists on crates.io
# index" — the desired end state, so treat it as success while still HARD-failing
# on any other error. The token is never in the command, so never in captured output.
log_info "Publishing to crates.io from $RUST_IMAGE"
retry 3 5 -- run_idempotent 'already (exists|uploaded)' -- in_docker "$RUST_IMAGE" \
  --secret-env "CARGO_REGISTRY_TOKEN=$CARGO_TOKEN" \
  -w /build/mockserver-client-rust -- cargo publish --allow-dirty

# Confirm the crate appears in the crates.io API. Indexing is eventually-consistent
# and genuinely lags a fresh publish, so this is best-effort: retry, then tolerate
# with a warning (the publish — the real work — already succeeded). Never silently
# swallowed.
log_info "Verifying crates.io visibility for mockserver-client $RELEASE_VERSION"
if ! retry 3 5 -- bash -c '
  code=$(curl -s -A "'"$CRATES_UA"'" -o /dev/null -w "%{http_code}" \
    "https://crates.io/api/v1/crates/mockserver-client/'"${RELEASE_VERSION}"'" 2>/dev/null || echo "000")
  [[ "$code" == "200" ]]
'; then
  log_info ":warning: crates.io did not yet show mockserver-client $RELEASE_VERSION after retries — non-fatal (publish succeeded; indexing is eventually-consistent)"
fi

# Commit version bump.
git_commit_and_push "release: publish mockserver-client $RELEASE_VERSION to crates.io" \
  "$CRATE_DIR/Cargo.toml" "$CRATE_DIR/Cargo.lock" || \
  log_info ":warning: could not commit version bump (non-fatal — publish already succeeded)"

log_info "Rust client publish complete"
