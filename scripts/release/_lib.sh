#!/usr/bin/env bash
# Shared library for release scripts.
#
# DESIGN: see docs/operations/release-principles.md
#
# Release scripts are CI-agnostic. They read configuration from environment
# variables only. Any CI-specific glue (buildkite-agent meta-data lookups,
# CI annotations, etc.) lives in adapter scripts under .buildkite/scripts/
# or .github/workflows/.

set -euo pipefail

RELEASE_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$RELEASE_LIB_DIR/../.." && pwd)"
export REPO_ROOT

# -----------------------------------------------------------------------------
# Pinned tool images — single source of truth for both local and CI.
# Each is the digest-pinned FROM line of the named stage in images/Dockerfile,
# which Dependabot keeps current. Override via env var if needed for a run.
# -----------------------------------------------------------------------------

RELEASE_TOOL_IMAGES="$RELEASE_LIB_DIR/images/Dockerfile"
pin_tool_image() {
  local var="$1" stage="$2" image
  [[ -n "${!var:-}" ]] && return 0
  image="$(awk -v s="$stage" 'toupper($1) == "FROM" && toupper($3) == "AS" && $4 == s { print $2; exit }' "$RELEASE_TOOL_IMAGES")"
  if [[ "$image" != *@sha256:* ]]; then
    echo "--- :x: _lib.sh: no digest-pinned '$stage' stage in $RELEASE_TOOL_IMAGES (got '$image')" >&2
    exit 1
  fi
  printf -v "$var" '%s' "$image"
}
pin_tool_image MAVEN_IMAGE maven
pin_tool_image NODE_IMAGE node
pin_tool_image RUBY_IMAGE ruby
pin_tool_image HELM_IMAGE helm
pin_tool_image GH_IMAGE gh
pin_tool_image PYTHON_IMAGE python
pin_tool_image TERRAFORM_IMAGE terraform
pin_tool_image GO_IMAGE go
pin_tool_image RUST_IMAGE rust
pin_tool_image DOTNET_IMAGE dotnet
export MAVEN_IMAGE NODE_IMAGE RUBY_IMAGE HELM_IMAGE GH_IMAGE PYTHON_IMAGE TERRAFORM_IMAGE

# Test settings for release Maven builds that run the reactor's tests. They match
# the CI reactor build and netty IT shards (scripts/buildkite_quick_build.sh,
# scripts/buildkite_netty_it.sh), which are what proves the tests green; the pom
# defaults (log level ERROR, 1000 log entries) are a configuration CI never runs.
# testOutput/redirect keep INFO-level server logs out of the release step log.
# shellcheck disable=SC2034  # read by the components that source this file
RELEASE_MAVEN_TEST_FLAGS=(
  -Dmockserver.testLogLevel=INFO
  "-Dmockserver.testArgLine=-Dmockserver.maxLogEntries=10000 -Dmockserver.maxExpectations=5000"
  -Dmockserver.testOutput=quiet
  -DredirectTestOutputToFile=true
)

# After a failed test run: copies the failing classes' reports and redirected
# output into mockserver/target/failed-tests/ (release-pipeline.yml uploads it)
# and prints a pass/fail summary. Runs on the host, like CI; never fails.
collect_failed_tests() {
  "$REPO_ROOT/.buildkite/scripts/steps/java-collect-failures.sh" || true
}

REGION="${AWS_REGION:-eu-west-2}"

# -----------------------------------------------------------------------------
# Logging
# -----------------------------------------------------------------------------

log_info()  { echo "--- $*"; }
log_error() { echo "--- :x: $*" >&2; }
log_step()  { echo "--- :arrow_right: $*"; }
log_dry()   { echo "--- :test_tube: [DRY RUN] $*"; }

# -----------------------------------------------------------------------------
# Dry-run support
# -----------------------------------------------------------------------------

# Released scripts read this. Default: dry-run if not explicitly set, so a
# careless local invocation can't deploy. CI adapters explicitly set
# DRY_RUN=false to actually release.
DRY_RUN="${DRY_RUN:-true}"

is_dry_run() { [[ "$DRY_RUN" == "true" ]]; }

# -----------------------------------------------------------------------------
# Input validation
#
# Every component script calls this near the top. It checks that the env-var
# contract is honoured. No CI-specific lookups happen here — the caller is
# responsible for setting the env vars (whether it's a Buildkite adapter, a
# GitHub Actions workflow, or a local invocation).
# -----------------------------------------------------------------------------

require_release_inputs() {
  : "${RELEASE_VERSION:?RELEASE_VERSION must be set (X.Y.Z)}"
  if [[ ! "$RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    log_error "RELEASE_VERSION must be X.Y.Z, got: $RELEASE_VERSION"
    exit 1
  fi

  # Auto-derive NEXT_VERSION if not supplied.
  if [[ -z "${NEXT_VERSION:-}" ]]; then
    NEXT_VERSION="$(increment_patch_version "$RELEASE_VERSION")-SNAPSHOT"
  fi
  if [[ ! "$NEXT_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$ ]]; then
    log_error "NEXT_VERSION must be X.Y.Z-SNAPSHOT, got: $NEXT_VERSION"
    exit 1
  fi

  # Auto-derive OLD_VERSION as the latest released tag OTHER than the one we
  # are about to release. The exclusion matters because prepare.sh pushes the
  # `mockserver-$RELEASE_VERSION` tag before finalize.sh runs — without this
  # filter, `latest_release_version` returns the just-pushed tag and
  # OLD_VERSION would equal RELEASE_VERSION, making finalize.sh's
  # `s/$OLD_VERSION/$RELEASE_VERSION/g` find-and-replace a silent no-op
  # (e.g. Helm Chart.yaml, READMEs, docker-compose, example package.json
  # files all stayed pinned to the previous version through release 6.1.0).
  if [[ -z "${OLD_VERSION:-}" ]]; then
    git -C "$REPO_ROOT" fetch --tags --quiet 2>/dev/null || true
    OLD_VERSION="$(previous_release_version "$RELEASE_VERSION")"
  fi
  if [[ -z "$OLD_VERSION" ]]; then
    log_error "OLD_VERSION could not be derived — no mockserver-X.Y.Z git tag found"
    exit 1
  fi
  if [[ ! "$OLD_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    log_error "OLD_VERSION must be X.Y.Z, got: $OLD_VERSION"
    exit 1
  fi

  RELEASE_TYPE="${RELEASE_TYPE:-full}"
  case "$RELEASE_TYPE" in
    full|maven-only|docker-only|post-maven) ;;
    *) log_error "RELEASE_TYPE must be full|maven-only|docker-only|post-maven, got: $RELEASE_TYPE"; exit 1 ;;
  esac

  # Whether to stand up a new versioned-site subdomain (X-Y.mock-server.com) is
  # NOT an operator decision — it is fully determined by whether this is a
  # major/minor release, i.e. whether RELEASE_VERSION's major.minor differs from
  # OLD_VERSION's major.minor. Getting it wrong is silently destructive in BOTH
  # directions:
  #   - major/minor + no  -> `main` still resolves to the PREVIOUS version's
  #                          bucket (terraform latest_version not advanced), so
  #                          website.sh's `aws s3 sync --delete` OVERWRITES the
  #                          previous release's archived docs. This is what
  #                          happened on the 7.6.0 release: 7-5.mock-server.com
  #                          was destroyed, and nothing failed.
  #   - patch + yes       -> a spurious X-Y subdomain + CloudFront distribution
  #                          is created for a version that shares an existing
  #                          site.
  # So we DERIVE the correct value here (the single chokepoint every release
  # script funnels through, before prepare.sh tags/pushes anything) rather than
  # trust the dropdown. An explicit operator value is honoured only as a
  # confirmation: if it CONTRADICTS the derived value we fail closed. An empty
  # value or the literal `auto` (the pipeline default) means "use the derived
  # value".
  local expected_versioned_site="no"
  if [[ "${RELEASE_VERSION%.*}" != "${OLD_VERSION%.*}" ]]; then
    expected_versioned_site="yes"
  fi
  CREATE_VERSIONED_SITE="${CREATE_VERSIONED_SITE:-auto}"
  case "$CREATE_VERSIONED_SITE" in
    auto)
      CREATE_VERSIONED_SITE="$expected_versioned_site" ;;
    yes|no)
      if [[ "$CREATE_VERSIONED_SITE" != "$expected_versioned_site" ]]; then
        local kind
        [[ "$expected_versioned_site" == "yes" ]] && kind="major/minor" || kind="patch"
        log_error "CREATE_VERSIONED_SITE=$CREATE_VERSIONED_SITE contradicts the release: $OLD_VERSION -> $RELEASE_VERSION is a $kind release, which requires CREATE_VERSIONED_SITE=$expected_versioned_site."
        if [[ "$expected_versioned_site" == "yes" ]]; then
          log_error "  A major/minor release with =no leaves 'main' pointing at the previous version's bucket, so the docs publish OVERWRITES the previous release's archived site."
        else
          log_error "  A patch release with =yes creates a spurious versioned subdomain for a version that shares an existing site."
        fi
        log_error "  Leave the value as 'auto' (recommended) to derive it automatically, or set it to $expected_versioned_site."
        exit 1
      fi
      ;;
    *) log_error "CREATE_VERSIONED_SITE must be yes|no|auto, got: $CREATE_VERSIONED_SITE"; exit 1 ;;
  esac

  CURRENT_VERSION="$(current_project_version 2>/dev/null || echo "")"

  export RELEASE_VERSION NEXT_VERSION OLD_VERSION RELEASE_TYPE \
         CREATE_VERSIONED_SITE CURRENT_VERSION DRY_RUN
}

# Each component script declares which RELEASE_TYPEs it applies to. If the
# current RELEASE_TYPE isn't in the list, the script exits 0.
skip_unless_release_type() {
  local component="$1"; shift
  local types_csv="$*"
  local IFS=,
  local t
  for t in $types_csv; do
    if [[ "$RELEASE_TYPE" == "$t" ]]; then return 0; fi
  done
  log_info "Skipping $component for RELEASE_TYPE=$RELEASE_TYPE"
  exit 0
}

# -----------------------------------------------------------------------------
# Environment helpers
# -----------------------------------------------------------------------------

require_cmd() {
  local cmd="$1"
  command -v "$cmd" >/dev/null 2>&1 || { log_error "Missing required command: $cmd"; exit 1; }
}

# -----------------------------------------------------------------------------
# Version helpers
# -----------------------------------------------------------------------------

current_project_version() {
  grep -m1 -E '^[[:space:]]*<version>[^<]+</version>' "$REPO_ROOT/mockserver/pom.xml" \
    | sed -E 's/.*<version>([^<]+)<\/version>.*/\1/'
}

increment_patch_version() {
  local ver="$1"
  local major="${ver%%.*}"
  local minor_rest="${ver#*.}"
  local minor="${minor_rest%%.*}"
  local patch="${ver##*.}"
  echo "${major}.${minor}.$((patch + 1))"
}

# awk reads to the end here and below: an early `exit` lets sed die of SIGPIPE under pipefail once
# the tag list outgrows a single write.
latest_release_version() {
  git -C "$REPO_ROOT" tag --list "mockserver-[0-9]*" --sort=-v:refname \
    | sed 's/^mockserver-//' \
    | awk 'NR == 1 { print }'
}

# Return the highest mockserver-X.Y.Z tag that is NOT $1. Used by
# require_release_inputs to derive OLD_VERSION during finalize, where the new
# release's own tag has already been pushed and would otherwise dominate.
# Falls back to latest_release_version when no exclude is passed.
previous_release_version() {
  local exclude="${1:-}"
  if [[ -z "$exclude" ]]; then
    latest_release_version
    return
  fi
  git -C "$REPO_ROOT" tag --list "mockserver-[0-9]*" --sort=-v:refname \
    | sed 's/^mockserver-//' \
    | grep -v -x -F "$exclude" \
    | awk 'NR == 1 { print }'
}

version_to_subdomain() {
  local ver="$1"
  local major="${ver%%.*}"
  local minor_rest="${ver#*.}"
  local minor="${minor_rest%%.*}"
  echo "${major}-${minor}"
}

# Update <version>OLD</version> -> <version>NEW</version> in every pom.xml
# beneath a directory. Skips target/ directories.
update_pom_versions() {
  local search_dir="$1" old_v="$2" new_v="$3"
  require_cmd python3
  python3 - "$old_v" "$new_v" "$search_dir" << 'PYEOF'
import sys, pathlib
old_v, new_v, search = sys.argv[1], sys.argv[2], sys.argv[3]
old_tag = f"<version>{old_v}</version>"
new_tag = f"<version>{new_v}</version>"
updated = []
for path in pathlib.Path(search).rglob("pom.xml"):
    if "target" in path.parts: continue
    text = path.read_text()
    if old_tag in text:
        path.write_text(text.replace(old_tag, new_tag))
        updated.append(str(path.relative_to(search)))
if not updated:
    print(f"ERROR: no pom.xml under {search} contained {old_tag}", file=sys.stderr)
    sys.exit(1)
for p in updated:
    print(f"  updated: {p}")
PYEOF
}

# Read-only counterpart to update_pom_versions: return 0 if ANY pom.xml beneath a
# directory (excluding target/) contains <version>$2</version>, else 1. Used to
# detect an already-applied version bump so a re-run can no-op instead of failing.
poms_contain_version() {
  local search_dir="$1" v="$2"
  require_cmd python3
  python3 - "$v" "$search_dir" << 'PYEOF'
import sys, pathlib
v, search = sys.argv[1], sys.argv[2]
tag = f"<version>{v}</version>"
for path in pathlib.Path(search).rglob("pom.xml"):
    if "target" in path.parts: continue
    if tag in path.read_text():
        sys.exit(0)
sys.exit(1)
PYEOF
}

# -----------------------------------------------------------------------------
# Docker helpers
# -----------------------------------------------------------------------------

# Retry a command with exponential backoff, to ride out TRANSIENT failures
# (network blips, registry 5xx/429, brief auth propagation). Pair with the
# hard-fail publish policy: a real failure still aborts the release, but a flaky
# one is retried first so a transient hiccup never reddens a release whose
# artifacts are fine.
#
# Usage: retry [attempts] [base_delay_s] -- CMD ARGS...
#   attempts     total attempts (default 3)
#   base_delay_s first backoff in seconds, doubled each retry (default 5)
# Returns the command's exit code from the last attempt (0 on eventual success).
# Honours dry-run transparently — it just runs the (dry-run-aware) command.
retry() {
  local attempts=3 delay=5
  [[ "${1:-}" =~ ^[0-9]+$ ]] && { attempts="$1"; shift; }
  [[ "${1:-}" =~ ^[0-9]+$ ]] && { delay="$1"; shift; }
  [[ "${1:-}" == "--" ]] && shift
  if [[ $# -eq 0 ]]; then log_error "retry: no command given"; return 2; fi
  local attempt=1 rc=0
  while true; do
    # Capture the failure code in the else branch: an `if` whose condition fails
    # with no else returns 0, so `rc=$?` after `fi` would always read 0.
    if "$@"; then return 0; else rc=$?; fi
    if (( attempt >= attempts )); then
      log_info "retry: '$1' failed after ${attempt} attempt(s) (exit ${rc})"
      return "$rc"
    fi
    log_info "retry: '$1' failed (exit ${rc}); attempt ${attempt}/${attempts} — retrying in ${delay}s"
    sleep "$delay"
    delay=$(( delay * 2 ))
    attempt=$(( attempt + 1 ))
  done
}

# -----------------------------------------------------------------------------
# Pinned cosign: the one copy of the version and checksums that docker.sh,
# helm.sh, test-cosign-signing.sh and the credential probe all use.
# -----------------------------------------------------------------------------
COSIGN_VERSION="v2.4.3"

# Official sha256 of cosign-<os>-<arch> for COSIGN_VERSION (cosign_checksums.txt).
cosign_sha256() {
  case "$1" in
    linux-amd64)  echo "caaad125acef1cb81d58dcdc454a1e429d09a750d1e9e2b3ed1aed8964454708" ;;
    linux-arm64)  echo "bd0f9763bca54de88699c3656ade2f39c9a1c7a2916ff35601caf23a79be0629" ;;
    darwin-amd64) echo "98a3bfd691f42c6a5b721880116f89210d8fdff61cc0224cd3ef2f8e55a466fb" ;;
    darwin-arm64) echo "edfc761b27ced77f0f9ca288ff4fac7caa898e1e9db38f4dfdf72160cdf8e638" ;;
    *) return 1 ;;
  esac
}

cosign_url() { echo "https://github.com/sigstore/cosign/releases/download/$COSIGN_VERSION/cosign-$1"; }

# This host's <os>-<arch> in cosign's asset names; non-zero when unsupported.
cosign_host_platform() {
  local os arch
  case "$(uname -s)" in Linux) os=linux ;; Darwin) os=darwin ;; *) return 1 ;; esac
  case "$(uname -m)" in x86_64|amd64) arch=amd64 ;; aarch64|arm64) arch=arm64 ;; *) return 1 ;; esac
  echo "$os-$arch"
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | awk '{print $1}'
}

# Usage: download_pinned_cosign TARGET
# Puts the pinned cosign for this host at TARGET, executable, keeping one that is
# already there only if its checksum matches. Non-zero on any download or
# checksum failure, with TARGET removed. Logs to stderr only.
download_pinned_cosign() {
  local target="$1" platform want
  platform="$(cosign_host_platform)" && want="$(cosign_sha256 "$platform")" \
    || { log_error "no pinned cosign for $(uname -s)/$(uname -m)"; return 1; }
  if [[ -f "$target" && "$(sha256_of "$target")" == "$want" ]]; then
    chmod +x "$target"; return 0
  fi
  rm -f "$target"
  if ! retry 3 5 -- curl -fsSL --max-time 120 -o "$target" "$(cosign_url "$platform")" >&2; then
    rm -f "$target"; log_error "failed to download cosign $COSIGN_VERSION ($platform)"; return 1
  fi
  if [[ "$(sha256_of "$target")" != "$want" ]]; then
    rm -f "$target"; log_error "cosign $COSIGN_VERSION ($platform) checksum mismatch: refusing to use it"; return 1
  fi
  chmod +x "$target"
}

# Usage: bin="$(resolve_cosign TARGET)"
# Prints a usable cosign: the one on PATH, else the pinned download at TARGET.
resolve_cosign() {
  if command -v cosign >/dev/null 2>&1; then echo cosign; return 0; fi
  download_pinned_cosign "$1" || return 1
  echo "$1"
}

# Usage: secret_files_ok DIR NAME... — non-zero unless each DIR/NAME holds a value. load_secret
# writes a bare newline for an empty field and "null" for a missing one, so a non-empty file is not
# enough. A NAME ending in "?" may be empty (a key password), but its file must still exist.
# xtrace is off while it reads the values, as in load_secret.
secret_files_ok() {
  local dir="$1" spec name v rc=0 xtrace_state
  shift
  xtrace_state=$(shopt -po xtrace 2>/dev/null || true)
  set +x
  for spec in "$@"; do
    name="${spec%\?}"
    if [[ ! -f "$dir/$name" ]]; then
      log_error "secret field '$name' is missing"
      rc=1; break
    fi
    v="$(cat "$dir/$name")" || v=""
    v="${v//[$'\n\r']/}"
    if [[ "$v" == "null" || ( -z "$v" && "$spec" != *\? ) ]]; then
      log_error "secret field '$name' is empty or missing"
      rc=1; break
    fi
  done
  v=""
  eval "$xtrace_state"
  return $rc
}

# Release signing is fail-closed: docker.sh, helm.sh and backfill-helm-oci.sh call this before
# publishing anything, so a missing or undecryptable mockserver-release/cosign-key fails the step
# with nothing pushed. Proves the key decrypts (cosign public-key); signs nothing. Runs in a
# subshell so the trap removes the staged key however the call ends; cosign's stderr is kept (it
# never prints the password). Non-zero on any failure.
cosign_key_preflight() (
  dir=""
  trap '[ -n "$dir" ] && rm -rf -- "$dir"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  set +x
  mkdir -p "$REPO_ROOT/.tmp"
  bin="$(resolve_cosign "$REPO_ROOT/.tmp/cosign")" || { log_error "cosign $COSIGN_VERSION is unavailable"; exit 1; }
  dir="$(mktemp -d "$REPO_ROOT/.tmp/cosign-preflight.XXXXXX")" || exit 1
  ( umask 077
    load_secret "mockserver-release/cosign-key" "key" > "$dir/key"
    load_secret "mockserver-release/cosign-key" "password" > "$dir/password" ) \
    || { log_error "could not read mockserver-release/cosign-key"; exit 1; }
  secret_files_ok "$dir" key 'password?' || { log_error "mockserver-release/cosign-key is incomplete"; exit 1; }
  COSIGN_PASSWORD="$(cat "$dir/password")" "$bin" public-key --key "$dir/key" >/dev/null \
    || { log_error "cosign public-key could not decrypt mockserver-release/cosign-key with its password"; exit 1; }
)

# Usage: require_cosign_key COMPONENT — exits the release step unless cosign_key_preflight passes.
require_cosign_key() {
  retry 3 5 -- cosign_key_preflight && return 0
  log_error "$1: refusing to publish unsigned release artifacts — the cosign key check failed (see the error above; check the key with scripts/release/check-release-credentials.sh)"
  exit 1
}

# Usage: cosign_sign_ghcr_charts VERSION... — cosign-signs ghcr.io/mock-server/charts/mockserver:<VERSION>
# for each VERSION, inside $HELM_IMAGE with the pinned, checksum-verified cosign. The key, password
# and GHCR credentials are 0600 files in a private directory under .tmp/ (mounted at /build), never
# `docker run -e`, and the subshell's trap removes them however the call ends.
cosign_sign_ghcr_charts() (
  dir=""
  trap '[ -n "$dir" ] && rm -rf -- "$dir"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  set +x
  mkdir -p "$REPO_ROOT/.tmp"
  dir="$(mktemp -d "$REPO_ROOT/.tmp/cosign-chart.XXXXXX")" || exit 1
  ( umask 077
    load_secret "mockserver-release/cosign-key" "key" > "$dir/key"
    load_secret "mockserver-release/cosign-key" "password" > "$dir/password"
    load_secret "mockserver-release/ghcr-token" "username" > "$dir/ghcr-username"
    load_secret "mockserver-release/ghcr-token" "token" > "$dir/ghcr-token" ) || exit 1
  secret_files_ok "$dir" key 'password?' ghcr-username ghcr-token || exit 1
  in_dir="/build/${dir#"$REPO_ROOT"/}"
  retry 3 5 -- in_docker "$HELM_IMAGE" --entrypoint sh -w /build \
    -e "COSIGN_DIR=$in_dir" -e "CHART_VERSIONS=$*" \
    -- -ec '
      set +x
      wget -qO /usr/local/bin/cosign "'"$(cosign_url linux-amd64)"'"
      echo "'"$(cosign_sha256 linux-amd64)"'  /usr/local/bin/cosign" | sha256sum -c -
      chmod +x /usr/local/bin/cosign
      COSIGN_PASSWORD=$(cat "$COSIGN_DIR/password"); export COSIGN_PASSWORD
      printf "%s" "$(cat "$COSIGN_DIR/ghcr-token")" \
        | cosign login ghcr.io --username "$(cat "$COSIGN_DIR/ghcr-username")" --password-stdin
      for v in $CHART_VERSIONS; do
        cosign sign --yes --key "$COSIGN_DIR/key" "ghcr.io/mock-server/charts/mockserver:$v"
      done
    '
)

# Run a publish command that may legitimately fail because this exact VERSION is
# already published (re-running a release after a partial failure, or a prior
# build already shipped this component). An "already published" outcome is a
# SUCCESS for an idempotent release — the artifact the user wants is live — so
# this swallows ONLY that specific case and HARD-fails on every other error.
#
# Usage (pair with retry for transient blips):
#   retry 3 5 -- run_idempotent 'already exists|already published' -- CMD ARGS...
#
#   $1 = extended-regex matched case-insensitively against the command's combined
#        stdout+stderr; a match means "already published".
#
# Because an already-published match returns 0 on the FIRST attempt, `retry` does
# not waste attempts re-publishing something that will never change. Output is
# captured (so it can be inspected) and re-echoed, so the log still shows it. Do
# NOT pass secrets in the command BODY — they would be captured here; pass them
# via in_docker --secret-env, exactly as the publish components already do.
run_idempotent() {
  local marker="$1"; shift
  [[ "${1:-}" == "--" ]] && shift
  if [[ $# -eq 0 ]]; then log_error "run_idempotent: no command given"; return 2; fi
  local out rc=0
  out=$("$@" 2>&1) || rc=$?
  printf '%s\n' "$out"
  if [[ $rc -eq 0 ]]; then return 0; fi
  if grep -qiE "$marker" <<<"$out"; then
    log_info ":information_source: '$1' reports this version already published (matched /$marker/) — treating as success (idempotent)"
    return 0
  fi
  return "$rc"
}

# Run a command inside a Docker container with the repo mounted at /build.
# Usage:
#   in_docker IMAGE [-w WORKDIR] [-v VOL:DST] [-e KEY=VAL] [--secret-env NAME[=VALUE]] -- CMD ARGS...
#
# --secret-env passes a credential WITHOUT `docker run -e` (release-principles §7):
# VALUE (or, with no =VALUE, the environment variable NAME) goes to a 0600 file
# under .tmp/secret-env.*, removed on return or interrupt, and a sh loader running
# as PID 1 exports it into CMD's own environment, so it is not in `docker inspect`,
# /proc/1/environ or the docker argv. The loader replaces the image entrypoint, so
# for an image whose entrypoint is the tool (gh) pass --entrypoint.
#
# Wraps the existing run-in-docker.sh which logs the docker command for
# local reproduction. The wrapper script redacts secrets in its log banner.
#
# When run behind a corporate TLS-inspecting proxy, set LOCAL_CA_BUNDLE
# (or rely on NODE_EXTRA_CA_CERTS / AWS_CA_BUNDLE which the lib reads
# automatically) to a PEM file on the host. The CA is:
#   - mounted into the container at /etc/ssl/local-ca.pem
#   - exposed via env vars that each toolchain respects (pip/npm/node/aws/gem/curl/git)
#   - installed into the OS CA bundle (so `curl`, `wget` etc. trust it)
#   - imported into the JDK cacerts truststore (so Maven and JVM tools trust it)
#
# The CA-setup prelude only runs when LOCAL_CA_BUNDLE is provided — in CI
# there's no proxy so the wrapper is a no-op and commands run directly.
in_docker() {
  local -a ca_args=()
  local ca="${LOCAL_CA_BUNDLE:-${NODE_EXTRA_CA_CERTS:-${AWS_CA_BUNDLE:-}}}"
  if [[ -n "$ca" && -f "$ca" ]]; then
    ca_args=(
      -v "$ca:/etc/ssl/local-ca.pem:ro"
      -e "NODE_EXTRA_CA_CERTS=/etc/ssl/local-ca.pem"
      -e "AWS_CA_BUNDLE=/etc/ssl/local-ca.pem"
      -e "SSL_CERT_FILE=/etc/ssl/local-ca.pem"
      -e "REQUESTS_CA_BUNDLE=/etc/ssl/local-ca.pem"
      -e "PIP_CERT=/etc/ssl/local-ca.pem"
      -e "GIT_SSL_CAINFO=/etc/ssl/local-ca.pem"
      -e "CURL_CA_BUNDLE=/etc/ssl/local-ca.pem"
    )
  fi
  local image="$1"; shift
  local -a orig=("$@") opts=() secrets=() entry=()
  while [[ $# -gt 0 && "$1" != "--" ]]; do
    case "$1" in
      --secret-env|--entrypoint)
        [[ $# -ge 2 ]] || { echo "--- :x: in_docker: $1 needs a value" >&2; return 2; }
        if [[ "$1" == --secret-env ]]; then secrets+=("$2"); else entry=("$2"); fi
        shift 2 ;;
      *) opts+=("$1"); shift ;;
    esac
  done
  if [[ ${#secrets[@]} -eq 0 ]]; then
    "$REPO_ROOT/.buildkite/scripts/run-in-docker.sh" -i "$image" "${ca_args[@]+"${ca_args[@]}"}" "${orig[@]+"${orig[@]}"}"
    return
  fi
  if [[ "${1:-}" != "--" ]]; then
    echo "--- :x: in_docker: --secret-env needs an explicit -- before the command" >&2
    return 2
  fi
  shift
  : "${secret_env_loader:?in_docker: secret_env_loader is not defined (export it with in_docker)}"
  # A subshell, so the trap removes the staged secrets however the call ends.
  (
    dir=""
    trap '[ -n "$dir" ] && rm -rf -- "$dir"' EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    mkdir -p "$REPO_ROOT/.tmp"
    dir="$(mktemp -d "$REPO_ROOT/.tmp/secret-env.XXXXXX")" || exit 2
    for spec in "${secrets[@]}"; do
      name="${spec%%=*}"
      if [[ "$spec" == *=* ]]; then value="${spec#*=}"; else value="${!name:-}"; fi
      if [[ ! "$name" =~ ^[A-Za-z_][A-Za-z0-9_]*$ || -z "$value" ]]; then
        echo "--- :x: in_docker: --secret-env '$name' needs a valid name and a non-empty value" >&2
        exit 2
      fi
      ( umask 077; printf '%s' "$value" > "$dir/$name" ) || exit 2
    done
    local secret_names=() spec
    for spec in "${secrets[@]}"; do secret_names+=("${spec%%=*}"); done
    banner="<in_docker --secret-env loader, secrets: ${secret_names[*]}>${entry[0]+ ${entry[0]}} $*"
    "$REPO_ROOT/.buildkite/scripts/run-in-docker.sh" -i "$image" "${ca_args[@]+"${ca_args[@]}"}" "${opts[@]+"${opts[@]}"}" \
      --entrypoint sh --banner-command "$banner" \
      -- -c "$secret_env_loader" sh "/build/.tmp/${dir##*/}" "${entry[@]+"${entry[@]}"}" "$@"
  )
}

# Runs as the container's PID 1 (sh): exports each secret file as NAME=value, then
# runs the command as a CHILD, never via exec, so PID 1's environment (what
# /proc/1/environ and `docker inspect` show) never holds a secret. PID 1 has no
# default signal handling, so it forwards TERM/INT to the child and exits with the
# child's exact status (128+N when the child was killed by signal N).
# shellcheck disable=SC2089  # shell source run by sh -c, not an argument list
secret_env_loader='
d="$1"; shift
for f in "$d"/*; do
  [ -f "$f" ] || { echo "in_docker: no secret files in $d" >&2; exit 2; }
  v="$(cat "$f")" || exit 2
  export "${f##*/}=$v"
done
unset d f v
exec 3<&0
"$@" <&3 3<&- &
c=$!
trap '"'"'kill -TERM "$c" 2>/dev/null'"'"' TERM INT
wait "$c"; rc=$?
while kill -0 "$c" 2>/dev/null; do wait "$c"; rc=$?; done
exit "$rc"
'

# Removes secret directories an interrupted in_docker could not clean up. Only
# for entry points that run nothing else concurrently in this checkout.
sweep_stale_secret_env() {
  find "$REPO_ROOT/.tmp" -maxdepth 1 -name 'secret-env.*' -exec rm -rf {} + 2>/dev/null || true
}

# Remove bind-mounted node_modules dir(s) from INSIDE a container, as the
# container user that created them. Under the elastic-ci-stack's userns-remap,
# files an in-container `npm ci`/`npm i` writes into the bind-mounted workspace
# are owned by a remapped UID the host buildkite-agent user CANNOT delete, so a
# node_modules left behind breaks the NEXT job's git checkout/clean on the same
# agent with "unlinkat .../node_modules/...: permission denied" ->
# "cloning git repository: exit status 128". The container owns the files, so it
# can remove them. Best-effort by design: output is suppressed and the call can
# never fail the caller (so it is safe in an EXIT trap and on agents without
# Docker — a missing `docker` or `node_modules` is a no-op, not a red step). A
# genuine cleanup failure surfaces later as the next job's checkout error, which
# is the same signal we had before this helper. Register on EXIT after the
# workspace npm install, e.g.:  trap 'clean_workspace_node_modules mockserver-vscode' EXIT
clean_workspace_node_modules() {
  local d
  for d in "$@"; do
    in_docker "$NODE_IMAGE" -w "/build/$d" -- sh -c 'rm -rf node_modules' >/dev/null 2>&1 || true
  done
}

# Maven-specific Docker invocation that ALSO installs the host's corp CA
# into the JDK truststore so plugins that download from HTTPS (e.g. the
# frontend-maven-plugin downloading Node.js) work behind a TLS proxy.
#
# In CI, no CA is mounted, the prelude is a no-op, and behaviour matches
# vanilla `in_docker`.
#
# Usage: in_maven [docker-options...] -- <mvn-args...>
in_maven() {
  local -a docker_opts=() mvn_args=()
  local found_sep=false
  for arg in "$@"; do
    if $found_sep; then
      mvn_args+=("$arg")
    elif [[ "$arg" == "--" ]]; then
      found_sep=true
    else
      docker_opts+=("$arg")
    fi
  done
  if [[ ${#mvn_args[@]} -eq 0 ]]; then
    log_error "in_maven: no command after --"
    exit 2
  fi

  # Quote each mvn arg for safe embedding in the bash -ec body.
  local quoted=""
  for a in "${mvn_args[@]}"; do
    quoted+=" $(printf '%q' "$a")"
  done

  in_docker "$MAVEN_IMAGE" \
    "${docker_opts[@]+"${docker_opts[@]}"}" \
    -v mockserver-m2-cache:/root/.m2 \
    -- bash -ec "${ca_install_prelude}${maven_packaging_prelude}exec${quoted}"
}

# Installs `unzip`, which the src/packaging/assert-*.sh assertions bound to
# package/verify shell out to, and which the maven:*-eclipse-temurin image does
# not ship. Every $MAVEN_IMAGE run that invokes mvn must start with it (enforced
# by .buildkite/scripts/steps/check-release-maven-prelude.sh). Fails the
# container closed rather than letting a build reach an assertion that cannot run.
maven_packaging_prelude='
if ! command -v unzip >/dev/null 2>&1; then
  { apt-get -o Acquire::Retries=3 update -qq >/dev/null \
      && apt-get -o Acquire::Retries=3 install -y -qq --no-install-recommends unzip >/dev/null; } \
    || { echo "cannot install unzip (the src/packaging/assert-*.sh packaging assertions need it)" >&2; exit 1; }
fi
'

# Emit a shell snippet that installs the host's corp CA bundle into the
# container's OS trust store AND the JDK truststore. Designed to be the
# first line of a `bash -ec '...'` heredoc passed to in_docker via
# Maven/Java containers.
#
# Idempotent and silent in CI (where /etc/ssl/local-ca.pem isn't mounted)
# so the same heredoc body works in both environments.
ca_install_prelude='
if [ -f /etc/ssl/local-ca.pem ]; then
  if command -v update-ca-certificates >/dev/null 2>&1; then
    cp /etc/ssl/local-ca.pem /usr/local/share/ca-certificates/local-ca.crt 2>/dev/null || true
    update-ca-certificates --fresh >/dev/null 2>&1 || true
  fi
  if command -v keytool >/dev/null 2>&1 && [ -n "${JAVA_HOME:-}" ] && [ -f "${JAVA_HOME}/lib/security/cacerts" ]; then
    keytool -delete -alias local-ca -keystore "${JAVA_HOME}/lib/security/cacerts" -storepass changeit >/dev/null 2>&1 || true
    keytool -importcert -noprompt -trustcacerts -alias local-ca -file /etc/ssl/local-ca.pem -keystore "${JAVA_HOME}/lib/security/cacerts" -storepass changeit >/dev/null 2>&1 || true
  fi
fi
'

# -----------------------------------------------------------------------------
# Cross-step state
#
# Some components produce values that downstream components need (e.g.
# versioned-site computes WEBSITE_BUCKET which helm/javadoc/website/schema
# read). On a single host these can be exported as env vars, but in CI each
# step runs on a different agent so we need persistent storage.
#
# The release scripts are CI-agnostic: they write outputs to a known file
# (.tmp/release-outputs.env) using set_release_output. The CI adapter is
# responsible for syncing that file to the CI's own cross-step state (e.g.
# Buildkite meta-data) and seeding env vars for the next step.
# -----------------------------------------------------------------------------

RELEASE_OUTPUTS_FILE="$REPO_ROOT/.tmp/release-outputs.env"

set_release_output() {
  local key="$1" value="$2"
  mkdir -p "$REPO_ROOT/.tmp"
  # Remove any previous value for this key, then append the new one.
  if [[ -f "$RELEASE_OUTPUTS_FILE" ]]; then
    grep -v "^${key}=" "$RELEASE_OUTPUTS_FILE" > "$RELEASE_OUTPUTS_FILE.tmp" || true
    mv "$RELEASE_OUTPUTS_FILE.tmp" "$RELEASE_OUTPUTS_FILE"
  fi
  printf '%s=%s\n' "$key" "$value" >> "$RELEASE_OUTPUTS_FILE"
  export "$key=$value"
}

# -----------------------------------------------------------------------------
# AWS helpers
# -----------------------------------------------------------------------------

# Load a JSON secret from AWS Secrets Manager. Returns the value of the
# specified key. In --dry-run mode, returns a placeholder so scripts can
# exercise their build/check/lint logic without needing real credentials.
load_secret() {
  local secret_id="$1" key="$2"
  if is_dry_run && [[ -z "${LOAD_REAL_SECRETS_IN_DRY_RUN:-}" ]]; then
    echo "DRY_RUN_PLACEHOLDER_${key^^}"
    return
  fi
  local xtrace_state
  xtrace_state=$(shopt -po xtrace 2>/dev/null || true)
  set +x
  local -a aws_args=(--region "$REGION" --secret-id "$secret_id" --query SecretString --output text)
  [[ -n "${AWS_PROFILE:-}" ]] && aws_args+=(--profile "$AWS_PROFILE")
  local json
  json=$(aws secretsmanager get-secret-value "${aws_args[@]}")
  echo "$json" | jq -r ".$key"
  eval "$xtrace_state"
}

assume_website_role() {
  # Note: NOT skipped in dry-run — terraform plan against the website account
  # needs these creds. Callers who only want write-side actions (S3 sync,
  # CloudFront invalidation) wrap their own dry-run guards around them.
  # Suppress xtrace BEFORE loading any secret so neither the role ARN nor the
  # external id can leak to stderr if a caller had `set -x` active.
  local xtrace_state
  xtrace_state=$(shopt -po xtrace 2>/dev/null || true)
  set +x
  local role_arn
  role_arn=$(load_secret "mockserver-release/website-role" "role_arn")
  local external_id
  external_id=$(load_secret "mockserver-release/website-role" "external_id" 2>/dev/null || true)
  local -a assume_args=(
    --role-arn "$role_arn"
    --role-session-name "mockserver-release-${RELEASE_VERSION}"
    --duration-seconds 3600
    --output json
  )
  if [[ -n "${external_id:-}" && "$external_id" != "null" ]]; then
    assume_args+=(--external-id "$external_id")
  fi
  local creds
  creds=$(aws sts assume-role "${assume_args[@]}")
  AWS_ACCESS_KEY_ID=$(echo "$creds" | jq -r '.Credentials.AccessKeyId')
  AWS_SECRET_ACCESS_KEY=$(echo "$creds" | jq -r '.Credentials.SecretAccessKey')
  AWS_SESSION_TOKEN=$(echo "$creds" | jq -r '.Credentials.SessionToken')
  export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
  eval "$xtrace_state"
}

# -----------------------------------------------------------------------------
# Git helpers
# -----------------------------------------------------------------------------

sync_to_origin_master() {
  if is_dry_run; then
    log_dry "skip: git fetch + reset --hard origin/master"
    return
  fi
  git -C "$REPO_ROOT" fetch --quiet --tags origin master
  # Reset to FETCH_HEAD (the ref just fetched) rather than the origin/master
  # remote-tracking ref. In a normal clone `fetch origin master` updates
  # refs/remotes/origin/master too, but in a shallow/CI clone whose configured
  # refspec doesn't cover the branch it may only move FETCH_HEAD — and then
  # `reset --hard origin/master` would silently land on a stale commit.
  git -C "$REPO_ROOT" reset --quiet --hard FETCH_HEAD
}

# Configure git identity (no-op if already configured) and install a push
# credential via http.extraheader. Idempotent. Skipped entirely in dry-run.
#
# Audit finding F-BK-03: the http.extraheader contains the GitHub token in
# base64. On agents where the workspace persists between builds (Buildkite
# default), this header survives into the next job. Cleanup is performed
# synchronously by `clear_git_push_credentials` in the push helpers below —
# we don't rely on EXIT traps because individual release scripts overwrite
# the EXIT trap for their own cleanup (e.g. maven-plugin.sh removes its
# tee'd deploy log).
configure_git_for_push() {
  if is_dry_run; then return; fi

  if [[ -z "$(git -C "$REPO_ROOT" config user.email 2>/dev/null || true)" ]]; then
    git -C "$REPO_ROOT" config user.email "release@mock-server.com"
  fi
  if [[ -z "$(git -C "$REPO_ROOT" config user.name 2>/dev/null || true)" ]]; then
    git -C "$REPO_ROOT" config user.name "MockServer Release"
  fi

  local token
  token=$(load_secret "mockserver-release/github-token" "token" 2>/dev/null || echo "")
  if [[ -n "$token" && "$token" != "null" ]]; then
    # `base64 | tr -d '\n'` is portable across macOS base64 and GNU base64
    # (which wraps at 76 chars by default — that breaks header parsing).
    git -C "$REPO_ROOT" config "http.https://github.com/.extraheader" \
      "AUTHORIZATION: basic $(printf 'x-access-token:%s' "$token" | base64 | tr -d '\n')"
  fi
}

# Synchronous companion to configure_git_for_push.
# Idempotent: silent no-op if the header was never set.
# Always safe to call multiple times.
clear_git_push_credentials() {
  git -C "$REPO_ROOT" config --unset "http.https://github.com/.extraheader" 2>/dev/null || true
}

# -----------------------------------------------------------------------------
# Dependabot release-in-flight gate
#
# .github/scripts/dependabot-release-gate.sh is the ONLY writer of the
# RELEASE_IN_PROGRESS repository variable that the Dependabot auto-merge
# workflow reads to stand down while a release is cutting. We invoke it here.
# It is best-effort and NEVER fails the release: it exits 0 and shouts loudly
# (a stdout+stderr INERT block, plus a red Buildkite annotation) if it cannot
# write, so an inert gate is impossible to miss but never aborts a release.
# No-op in dry-run: no secret is loaded and nothing is written.
# -----------------------------------------------------------------------------
DEPENDABOT_RELEASE_GATE="$REPO_ROOT/.github/scripts/dependabot-release-gate.sh"

# release_gate <set|clear>
release_gate() {
  local action="$1"
  if is_dry_run; then
    log_dry "would: dependabot release gate '$action' (RELEASE_IN_PROGRESS)"
    return 0
  fi
  if [[ ! -x "$DEPENDABOT_RELEASE_GATE" ]]; then
    log_error "dependabot release gate script missing or not executable ($DEPENDABOT_RELEASE_GATE) — skipping '$action'"
    return 0
  fi
  # Load the release PAT with xtrace suppressed so the token cannot leak to a
  # log (mirrors load_secret / assume_website_role).
  local xtrace_state token
  xtrace_state=$(shopt -po xtrace 2>/dev/null || true)
  set +x
  token=$(load_secret "mockserver-release/github-token" "token" 2>/dev/null || echo "")
  eval "$xtrace_state"
  if [[ -z "$token" || "$token" == "null" ]]; then
    log_error "could not load mockserver-release/github-token — skipping release gate '$action' (gate not $action)"
    return 0
  fi
  log_info "Dependabot release gate: $action RELEASE_IN_PROGRESS (containerised gh via $GH_IMAGE)"
  # Run `gh` inside the pinned GH_IMAGE via the in_docker convention (as
  # components/github.sh does) — the release-queue AMI installs no host `gh`, so
  # the gate would otherwise be permanently inert. in_docker is a shell
  # function, so export it for the child gate script to resolve GATE_GH_CMD.
  #
  # Token handling: the gate script decides and annotates on the HOST; only the
  # gh call is delegated into Docker. The token is exported as GH_TOKEN to the
  # gate script, and in_docker --secret-env GH_TOKEN stages it as a file, so it
  # is on no argv and not in `docker inspect`. The child script needs in_docker's
  # helpers too, hence the exports. The gate script never returns non-zero, but
  # tolerate it defensively so it can never abort the release.
  export -f in_docker
  # shellcheck disable=SC2090  # exported as sh -c source text for the child script
  export secret_env_loader
  GH_TOKEN="$token" \
  GATE_GH_CMD="in_docker $GH_IMAGE --entrypoint gh --secret-env GH_TOKEN --" \
    "$DEPENDABOT_RELEASE_GATE" "$action" || true
  unset token
}

git_commit_and_push() {
  local message="$1"; shift
  local -a paths=("$@")
  if is_dry_run; then
    log_dry "would: git add ${paths[*]}"
    log_dry "would: git commit -m \"$message\""
    log_dry "would: git push origin HEAD:master"
    return
  fi
  configure_git_for_push
  # F-BK-03: clear extraheader on every exit path of this function — including
  # early exit from `set -e`, signals, and rebase-retry failure — so the token
  # does not persist on the agent workspace. The RETURN trap fires when the
  # function returns (normally or via `return`) and does not clobber the
  # caller's EXIT trap.
  trap 'clear_git_push_credentials' RETURN
  local rc=0
  {
    git -C "$REPO_ROOT" add "${paths[@]}"
    # Idempotent: a re-run may find the change already committed by an earlier
    # run. `git commit` errors on an empty commit, so skip when nothing staged.
    if git -C "$REPO_ROOT" diff --cached --quiet; then
      log_info "Nothing to commit ($message) - already up to date"
    else
      git -C "$REPO_ROOT" commit -m "$message"
      # Retry on non-fast-forward: if someone pushed to master while we were
      # building, rebase the release commit on top of the new tip and retry.
      # Bounded retries to fail loud if there's a systemic conflict.
      local attempts=0
      while ! git -C "$REPO_ROOT" push origin HEAD:master 2>/tmp/push_err.$$; do
        # "[remote rejected]" (permissions, hooks) is not a race; retrying cannot fix it.
        if ! grep -qE "\[rejected\]|non-fast-forward|fetch first" /tmp/push_err.$$; then
          cat /tmp/push_err.$$ >&2
          rm -f /tmp/push_err.$$
          rc=1
          break
        fi
        attempts=$((attempts + 1))
        if [[ "$attempts" -gt 5 ]]; then
          log_error "Push to master kept losing the race after $attempts retries"
          cat /tmp/push_err.$$ >&2
          rm -f /tmp/push_err.$$
          rc=1
          break
        fi
        log_info "Push rejected (non-fast-forward) — rebasing on origin/master and retrying ($attempts)"
        git -C "$REPO_ROOT" fetch --quiet origin master
        # Release components can leave unstaged working-tree changes (e.g. the
        # Helm packaging step), which make `git rebase` refuse to run and turned
        # this retry loop into a guaranteed failure. Stash around the rebase so
        # the committed release change replays cleanly, then restore.
        local stashed=0
        if ! git -C "$REPO_ROOT" diff --quiet || ! git -C "$REPO_ROOT" diff --cached --quiet; then
          git -C "$REPO_ROOT" stash push --include-untracked --quiet && stashed=1
        fi
        if ! git -C "$REPO_ROOT" rebase origin/master; then
          git -C "$REPO_ROOT" rebase --abort || true
          if [[ "$stashed" == "1" ]]; then git -C "$REPO_ROOT" stash pop --quiet || log_info "stash pop after aborted rebase failed — residue left in stash"; fi
          log_error "Rebase onto origin/master failed while retrying the push"
          rc=1
          break
        fi
        [[ "$stashed" == "1" ]] && { git -C "$REPO_ROOT" stash pop --quiet || log_info "stash pop after rebase failed — build-artifact residue left in stash (release commit unaffected)"; }
      done
      rm -f /tmp/push_err.$$
    fi
  } || rc=$?
  return $rc
}

# Go's semantic import versioning: a vN.x.y tag (N >= 2) is only installable when
# the module path ends in /vN. prepare.sh rewrites the suffix on a major release;
# this refuses to tag while any reference to either Go module is on another major.
# A dry run only reports, because prepare's rewrite has not happened yet.
require_go_module_major() {
  require_cmd python3
  local major="${RELEASE_VERSION%%.*}"
  if python3 "$RELEASE_LIB_DIR/go_module_major.py" --check "$major"; then
    log_info "Go module paths end in /v$major"
  elif is_dry_run; then
    log_dry "Go module paths are not on /v$major yet; prepare.sh --execute rewrites them"
  else
    log_error "Go module paths do not end in /v$major: the proxy would reject the v$RELEASE_VERSION tag"
    return 1
  fi
}

git_tag_and_push() {
  local tag="$1"
  if is_dry_run; then
    log_dry "would: git tag $tag"
    log_dry "would: git push origin $tag"
    return
  fi
  configure_git_for_push
  # F-BK-03: clear extraheader on every exit path — RETURN trap fires even on
  # early exit from `set -e` within the function, without clobbering the
  # caller's EXIT trap.
  trap 'clear_git_push_credentials' RETURN
  local rc=0
  {
    # Idempotent: a re-run may find the tag already created by an earlier run.
    if git -C "$REPO_ROOT" rev-parse -q --verify "refs/tags/$tag" >/dev/null 2>&1 \
      || git -C "$REPO_ROOT" ls-remote --exit-code origin "refs/tags/$tag" >/dev/null 2>&1; then
      log_info "Tag $tag already exists - skipping"
    else
      git -C "$REPO_ROOT" tag "$tag"
      git -C "$REPO_ROOT" push origin "$tag"
    fi
  } || rc=$?
  return $rc
}

# Returns 0 if a public GitHub repo (owner/name) exists and is reachable, so a
# component that publishes by pushing to a companion repo skips gracefully until
# that repo has been created. dry-run always returns 0 (no network dependency
# locally).
pm_repo_available() {
  local repo="$1"
  is_dry_run && return 0
  curl -fsSL -o /dev/null --max-time 30 "https://api.github.com/repos/$repo" 2>/dev/null
}
