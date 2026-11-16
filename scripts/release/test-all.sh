#!/usr/bin/env bash
# Local test runner: dry-run every release script and report pass/fail.
#
# Goal: catch as many issues as possible locally so that the next time we
# trigger Buildkite, the pipeline succeeds first attempt.
#
# This runs every component script with --dry-run + a fake RELEASE_VERSION.
# Each test exercises argument parsing, input validation, the build/lint/
# check phases (inside their pinned Docker container), and the skip paths
# for destructive operations. With Docker running locally, this hits roughly
# the same code paths the Buildkite agents will hit — minus the actual
# publish step.
#
# Behind a corporate TLS-inspecting proxy: set LOCAL_CA_BUNDLE (or
# NODE_EXTRA_CA_CERTS / AWS_CA_BUNDLE) to a PEM file. The in_docker helper
# mounts it into every container and configures pip/npm/node/aws/gem to
# trust it.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  cat <<EOF
Usage: $0 [--version X.Y.Z] [--only=A,B] [--skip=X,Y] [--quick]

Options:
  --version X.Y.Z     Fake version to use for the dry-run (default: 99.99.0)
  --only=A,B          Only test these scripts (comma-separated names)
  --skip=A,B          Skip these scripts
  --quick             Skip components that pull/build large Docker images
                      (maven-central, maven-plugin, javadoc)
  --keep-logs         Keep per-test logs in .tmp/test-all/
  -h, --help          This help
EOF
}

VERSION="99.99.0"
ONLY=""
SKIP=""
QUICK=false
KEEP_LOGS=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) VERSION="$2"; shift 2 ;;
    --only=*)  ONLY="${1#*=}"; shift ;;
    --skip=*)  SKIP="${1#*=}"; shift ;;
    --quick)   QUICK=true; shift ;;
    --keep-logs) KEEP_LOGS=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage >&2; exit 2 ;;
  esac
done

# All tests are defined as (script-path, label) pairs.
declare -a TESTS=(
  "$SCRIPT_DIR/prepare.sh:prepare"
  "$SCRIPT_DIR/finalize.sh:finalize"
  "$SCRIPT_DIR/components/maven-central.sh:maven-central"
  "$SCRIPT_DIR/components/maven-plugin.sh:maven-plugin"
  "$SCRIPT_DIR/components/docker.sh:docker"
  "$SCRIPT_DIR/components/npm.sh:npm"
  "$SCRIPT_DIR/components/pypi.sh:pypi"
  "$SCRIPT_DIR/components/rubygems.sh:rubygems"
  "$SCRIPT_DIR/components/helm.sh:helm"
  "$SCRIPT_DIR/components/javadoc.sh:javadoc"
  "$SCRIPT_DIR/components/website.sh:website"
  "$SCRIPT_DIR/components/schema.sh:schema"
  "$SCRIPT_DIR/components/swaggerhub.sh:swaggerhub"
  "$SCRIPT_DIR/components/github.sh:github"
  # Limit to one platform so the dry-run doesn't download every target JDK.
  "$SCRIPT_DIR/components/binary.sh:binary:BINARY_TARGETS=linux/x86_64"
  # Patch release: CREATE_VERSIONED_SITE derives to `no`, exercising the
  # read-only "seed downstream outputs" path (no new site created). RELEASE and
  # OLD are pinned to a self-consistent patch pair so this stays a patch
  # regardless of the repo's latest tag (the derivation compares major.minor).
  "$SCRIPT_DIR/components/versioned-site.sh:versioned-site:RELEASE_VERSION=50.0.1 OLD_VERSION=50.0.0"
  # Major/minor release: CREATE_VERSIONED_SITE=yes agrees with the derived
  # value, exercising the active new-site path. Pinned to a major/minor pair so
  # it stays major/minor vs OLD (and does not trip the fail-closed agreement
  # check) independent of repo tags. Reuse the same script under a different
  # name so logs separate.
  "$SCRIPT_DIR/components/versioned-site.sh:versioned-site-active:RELEASE_VERSION=50.1.0 OLD_VERSION=50.0.0 CREATE_VERSIONED_SITE=yes"
)

# Heavy tests touch Maven/JVM downloads which take minutes.
HEAVY=(maven-central maven-plugin javadoc binary)

LOGS_DIR="$SCRIPT_DIR/../../.tmp/test-all"
mkdir -p "$LOGS_DIR"
rm -f "$LOGS_DIR"/*.log

results_pass=()
results_fail=()
results_skip=()

is_in_list() {
  local needle="$1" haystack="$2"
  [[ ",$haystack," == *",$needle,"* ]]
}

run_one() {
  local script="$1" name="$2" extra_env="${3:-}"
  local logfile="$LOGS_DIR/$name.log"
  if [[ -n "$ONLY" ]] && ! is_in_list "$name" "$ONLY"; then
    results_skip+=("$name (not in --only)")
    return
  fi
  if [[ -n "$SKIP" ]] && is_in_list "$name" "$SKIP"; then
    results_skip+=("$name (in --skip)")
    return
  fi
  if $QUICK; then
    for h in "${HEAVY[@]}"; do
      if [[ "$name" == "$h" ]]; then
        results_skip+=("$name (--quick)")
        return
      fi
    done
  fi

  echo ""
  echo "────────────────────────────────────────────────────────────"
  echo "▶  $name"
  echo "────────────────────────────────────────────────────────────"

  local start=$SECONDS
  if env RELEASE_VERSION="$VERSION" $extra_env "$script" --dry-run > "$logfile" 2>&1; then
    local elapsed=$((SECONDS - start))
    results_pass+=("$name (${elapsed}s)")
    echo "✓ PASS  ($(wc -l < "$logfile" | tr -d ' ') lines, ${elapsed}s)"
    tail -3 "$logfile" | sed 's/^/  │ /'
  else
    local rc=$? elapsed=$((SECONDS - start))
    results_fail+=("$name (exit $rc, ${elapsed}s)")
    echo "✗ FAIL  (exit $rc, ${elapsed}s) — last lines:"
    tail -15 "$logfile" | sed 's/^/  │ /'
  fi
}

echo "╔════════════════════════════════════════════════════════════╗"
echo "║ Release pipeline local test runner                         ║"
echo "║   Version:  $VERSION"
echo "║   Quick:    $QUICK"
echo "║   Only:     ${ONLY:-(all)}"
echo "║   Skip:     ${SKIP:-(none)}"
echo "║   Logs:     $LOGS_DIR/"
echo "╚════════════════════════════════════════════════════════════╝"

# Host check
echo ""
echo "Host preflight:"
for tool in bash git aws jq curl python3 docker; do
  if command -v "$tool" >/dev/null; then
    echo "  ✓ $tool"
  else
    echo "  ✗ $tool MISSING — required" >&2
    exit 1
  fi
done
if docker version >/dev/null 2>&1; then
  echo "  ✓ docker daemon reachable"
else
  echo "  ✗ docker daemon NOT reachable" >&2
  exit 1
fi
if [[ -n "${LOCAL_CA_BUNDLE:-${NODE_EXTRA_CA_CERTS:-${AWS_CA_BUNDLE:-}}}" ]]; then
  ca="${LOCAL_CA_BUNDLE:-${NODE_EXTRA_CA_CERTS:-${AWS_CA_BUNDLE:-}}}"
  echo "  ℹ corp CA bundle: $ca"
fi

# Executable-bit guard: Buildkite runs scripts directly (`command: ./foo.sh`)
# not via `bash foo.sh`, so any pipeline-invoked .sh that isn't 100755 in the
# git index will fail at runtime with `Permission denied` (exit 126). Catch
# that here so the only way to merge a non-executable script is to also break
# this preflight check locally.
echo ""
echo "Executable-bit check for pipeline-invoked scripts:"
REPO_ROOT_TEST="$(cd "$SCRIPT_DIR/../.." && pwd)"
bad_perm=0
while IFS= read -r path; do
  [[ -z "$path" ]] && continue
  [[ -f "$REPO_ROOT_TEST/$path" ]] || continue
  mode=$(git -C "$REPO_ROOT_TEST" ls-files -s "$path" 2>/dev/null | awk '{print $1}')
  if [[ "$mode" == "100755" ]]; then
    echo "  ✓ $path"
  else
    echo "  ✗ $path  (git mode=$mode, expected 100755)" >&2
    bad_perm=$((bad_perm + 1))
  fi
done < <(grep -rhoE '\.buildkite/scripts/[a-z0-9_-]+\.sh|scripts/release/[a-z0-9/_-]+\.sh' "$REPO_ROOT_TEST"/.buildkite/*.yml 2>/dev/null | sort -u)
if [[ "$bad_perm" -gt 0 ]]; then
  echo "" >&2
  echo "ABORT: $bad_perm pipeline script(s) missing executable bit in git index." >&2
  echo "Fix with: git update-index --chmod=+x <path>" >&2
  exit 1
fi

for test in "${TESTS[@]}"; do
  # Format: "path:name" or "path:name:EXTRA_ENV=value"
  IFS=":" read -r script name extra_env <<< "$test"
  run_one "$script" "$name" "${extra_env:-}"
done

# ── Fail-closed assertions ────────────────────────────────────────────
# run_one can only express "must exit 0", so the CONTRADICTION guard in
# require_release_inputs has no coverage there — and that guard is the whole
# defence against the 7.6.0 incident, where create-versioned-site=no on a minor
# release let the docs publish overwrite the previous version's archive in
# place (main_bucket_name follows latest_version, and website.sh syncs with
# --delete). A control with no test is how that shipped, so assert it directly:
# CREATE_VERSIONED_SITE must be REJECTED when it contradicts the derived value.
assert_inputs_rejected() {
  local label="$1" rv="$2" ov="$3" cvs="$4"
  if env -u CREATE_VERSIONED_SITE \
       RELEASE_VERSION="$rv" NEXT_VERSION="99.99.99-SNAPSHOT" OLD_VERSION="$ov" \
       RELEASE_TYPE=full CREATE_VERSIONED_SITE="$cvs" DRY_RUN=true \
       bash -c 'source "'"$SCRIPT_DIR"'/_lib.sh" >/dev/null 2>&1; require_release_inputs' \
       >/dev/null 2>&1; then
    results_fail+=("$label (accepted a contradictory CREATE_VERSIONED_SITE)")
    echo "✗ FAIL  $label — expected rejection, got exit 0"
  else
    results_pass+=("$label")
    echo "✓ PASS  $label (rejected, as required)"
  fi
}

# The Central Portal quota guard: a deployment the Portal reports as blocked must be
# dropped, never published. Stubbed curl, so it runs in seconds.
if [[ -z "$ONLY" || "$ONLY" == *"central-portal-guard"* ]]; then
  echo ""
  echo "────────────────────────────────────────────────────────────"
  echo "▶  central-portal-guard"
  echo "────────────────────────────────────────────────────────────"
  if "$SCRIPT_DIR/test-central-portal-guard.sh" > "$LOGS_DIR/central-portal-guard.log" 2>&1; then
    results_pass+=("central-portal-guard")
    echo "✓ PASS"
  else
    results_fail+=("central-portal-guard")
    echo "✗ FAIL — last lines:"
    tail -15 "$LOGS_DIR/central-portal-guard.log" | sed 's/^/  │ /'
  fi
fi

if [[ -z "$ONLY" || "$ONLY" == *"input-validation"* ]]; then
  echo ""
  echo "────────────────────────────────────────────────────────────"
  echo "▶  input-validation (fail-closed guards)"
  echo "────────────────────────────────────────────────────────────"
  assert_inputs_rejected "reject minor+no (the 7.6.0 incident)" "7.6.0" "7.5.0" "no"
  assert_inputs_rejected "reject major+no"                      "8.0.0" "7.9.0" "no"
  assert_inputs_rejected "reject patch+yes"                     "7.6.1" "7.6.0" "yes"
  assert_inputs_rejected "reject unknown value"                 "7.6.0" "7.5.0" "maybe"
fi

echo ""
echo "╔════════════════════════════════════════════════════════════╗"
echo "║ Summary                                                    ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo "PASS (${#results_pass[@]}):"
for r in "${results_pass[@]+"${results_pass[@]}"}"; do echo "  ✓ $r"; done
echo ""
echo "FAIL (${#results_fail[@]}):"
for r in "${results_fail[@]+"${results_fail[@]}"}"; do echo "  ✗ $r"; done
echo ""
echo "SKIP (${#results_skip[@]}):"
for r in "${results_skip[@]+"${results_skip[@]}"}"; do echo "  - $r"; done

if ! $KEEP_LOGS; then
  echo ""
  echo "Logs preserved at: $LOGS_DIR/"
fi

if [[ ${#results_fail[@]} -gt 0 ]]; then
  exit 1
fi
echo ""
echo "All tested scripts passed in dry-run mode."
