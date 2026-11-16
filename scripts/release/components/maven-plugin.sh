#!/usr/bin/env bash
# RECOVERY ONLY: publish mockserver-maven-plugin on its own, for a release whose
# Maven Central deployment did not include it. A normal release publishes the plugin
# in the same deployment as every other module (maven-central.sh, -P with-maven-plugin),
# so this script is in neither the release pipeline nor release.sh's ALL_COMPONENTS.
#
# Preferred: publish the deployment already uploaded and VALIDATED (no build, any checkout):
#   RELEASE_VERSION=X.Y.Z RELEASE_TYPE=post-maven scripts/release/components/maven-plugin.sh --execute --deployment-id <uuid>
# Rebuild: run from a worktree at tag mockserver-X.Y.Z with this scripts/release overlaid;
# it refuses to build if mockserver/ differs from the tag. Both paths use the quota guard.
#
# Dry-run: build + verify (with tests) only; skip deploy.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source-path=SCRIPTDIR source=../_lib.sh
source "$SCRIPT_DIR/_lib.sh"

DEPLOYMENT_ID=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true; shift ;;
    --execute) DRY_RUN=false; shift ;;
    --deployment-id) DEPLOYMENT_ID="${2:-}"; shift 2 || { log_error "--deployment-id needs a value"; exit 2; } ;;
    -h|--help) echo "Usage: $0 [--dry-run|--execute] [--deployment-id <uuid>]"; exit 0 ;;
    *) log_error "Unknown arg: $1"; exit 2 ;;
  esac
done

require_cmd docker
require_cmd git
require_cmd curl
require_cmd jq
require_release_inputs
skip_unless_release_type "maven-plugin" full,post-maven

log_step "Release mockserver-maven-plugin $RELEASE_VERSION (dry-run=$DRY_RUN)"
# No sync_to_origin_master: by recovery time master has usually moved past the release, so a
# rebuild uses whatever is checked out, and mockserver/ must match the release tag exactly.
RELEASE_TAG="mockserver-$RELEASE_VERSION"
if ! is_dry_run && [[ -z "$DEPLOYMENT_ID" ]]; then
  if ! git -C "$REPO_ROOT" rev-parse -q --verify "refs/tags/$RELEASE_TAG" >/dev/null; then
    log_error "Tag $RELEASE_TAG not found locally; run 'git fetch --tags origin' first"
    exit 1
  fi
  if ! git -C "$REPO_ROOT" diff --quiet "$RELEASE_TAG" -- mockserver/; then
    log_error "mockserver/ differs from $RELEASE_TAG; rebuild from a worktree at that tag (see docs/operations/release-process.md)"
    exit 1
  fi
fi

# Idempotent: if this version is already on Maven Central the plugin release
# is done. A re-run must not redeploy an existing version - Central rejects it.
if ! is_dry_run && curl -fsI -o /dev/null \
    "https://repo1.maven.org/maven2/org/mock-server/mockserver-maven-plugin/$RELEASE_VERSION/mockserver-maven-plugin-$RELEASE_VERSION.pom"; then
  log_info "mockserver-maven-plugin $RELEASE_VERSION already on Maven Central - skipping"
  exit 0
fi

publish_and_wait() {
  # Write Sonatype credentials to a 0600 netrc-format file for curl.
  mkdir -p "$REPO_ROOT/.tmp"
  CP_NETRC="$REPO_ROOT/.tmp/sonatype-netrc-plugin.$$"
  ( umask 077
    set +x
    _user=$(load_secret "mockserver-build/sonatype" "username")
    _pass=$(load_secret "mockserver-build/sonatype" "password")
    printf 'machine central.sonatype.com\nlogin %s\npassword %s\n' "$_user" "$_pass" > "$CP_NETRC"
  )
  trap 'rm -f "$CP_NETRC"' EXIT

  # Waits for VALIDATED; fails without dropping the deployment if the Portal says publishing is blocked.
  central_portal_publish "$CP_NETRC" "$DEPLOYMENT_ID" keep

  log_info "Waiting for the deployment to leave VALIDATED"
  state=""
  published=false
  for i in $(seq 1 20); do   # 20 × 15s = 5 min
    state=$(curl -fsS --connect-timeout 10 --max-time 30 -X POST --netrc-file "$CP_NETRC" \
      "$CENTRAL_PORTAL_API/status?id=$DEPLOYMENT_ID" \
      2>/dev/null | jq -r '.deploymentState' 2>/dev/null || true)
    log_info "  attempt $i: state=${state:-<empty>}"
    case "$state" in
      PUBLISHING|PUBLISHED) published=true; break ;;
      FAILED)               log_error "Central Portal deployment FAILED"; exit 1 ;;
    esac
    sleep 15
  done
  if ! $published; then
    log_error "Deployment $DEPLOYMENT_ID did not start publishing within 5 minutes"
    exit 1
  fi

  log_info "maven-plugin release complete (deployment $DEPLOYMENT_ID is $state)"
}

UUID_RE='^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
if [[ -n "$DEPLOYMENT_ID" ]]; then
  if [[ ! "$DEPLOYMENT_ID" =~ $UUID_RE ]]; then
    log_error "--deployment-id must be a UUID, got: $DEPLOYMENT_ID"
    exit 2
  fi
  if is_dry_run; then
    log_dry "skip: quota-check and publish existing deployment $DEPLOYMENT_ID"
    exit 0
  fi
  log_info "Publishing the existing Central Portal deployment $DEPLOYMENT_ID (no build, no deploy)"
  publish_and_wait
  exit 0
fi

log_info "Build core MockServer (in Docker)"
in_maven -w /build/mockserver \
  -- mvn clean install -DskipTests -DskipITs

# Same command in dry-run, so the dry run runs the plugin tests the release runs.
# Pom-default test settings, as CI's maven-plugin build (maven-plugin-build.sh) uses.
log_info "Verify maven-plugin (in Docker)"
in_maven -w /build/mockserver/mockserver-maven-plugin -- mvn clean verify \
  || { build_rc=$?; collect_failed_tests; exit "$build_rc"; }

if is_dry_run; then
  log_dry "skip: deploy"
  log_info "maven-plugin dry-run complete"
  exit 0
fi

log_info "Deploy maven-plugin to the Central Portal (GPG-sign in container)"

# Write secrets to 0600 files under .tmp/ (mounted at /build in the
# container) instead of passing via `docker run -e`. Env vars are readable
# via /proc/1/environ and `docker inspect`; file-based secrets are not.
# Pattern mirrors the cosign key handling in helm.sh.
mkdir -p "$REPO_ROOT/.tmp"
GPG_KEY_FILE="$REPO_ROOT/.tmp/gpg-key-plugin.$$"
GPG_PASS_FILE="$REPO_ROOT/.tmp/gpg-passphrase-plugin.$$"
SONATYPE_CREDS_FILE="$REPO_ROOT/.tmp/sonatype-creds-plugin.$$"
_mp_cleanup_secrets() {
  rm -f "$GPG_KEY_FILE" "$GPG_PASS_FILE" "$SONATYPE_CREDS_FILE"
}
( umask 077
  load_secret "mockserver-release/gpg-key" "key"        > "$GPG_KEY_FILE"
  load_secret "mockserver-release/gpg-key" "passphrase" > "$GPG_PASS_FILE"
  load_secret "mockserver-build/sonatype" "username"     > "$SONATYPE_CREDS_FILE"
  load_secret "mockserver-build/sonatype" "password"    >> "$SONATYPE_CREDS_FILE"
)

# Write the mvn output to a tmpfile under .tmp/ (per AGENTS.md policy) so we
# can both stream it to the agent's stdout AND extract the deploymentId
# afterwards. Capturing in `$(...)` would obscure mvn's exit code: under
# `set -o pipefail` the pipeline's exit status is the rightmost non-zero,
# so a `$(cmd | tee /dev/stderr)` returns tee's exit code (always 0) — a
# failed `mvn deploy` would be silently swallowed (same class of bug as
# F-MC-01 in maven-central.sh). Explicit PIPESTATUS[0] check after the
# pipe makes the failure surface immediately. tmpfile is rm'd as soon as
# DEPLOYMENT_ID is captured because it can contain GPG/Sonatype output if
# any subprocess accidentally enables `set -x`.
DEPLOY_LOG=$(mktemp "$REPO_ROOT/.tmp/mockserver-maven-plugin-deploy.XXXXXX")
trap '_mp_cleanup_secrets; rm -f "$DEPLOY_LOG"' EXIT

in_docker "$MAVEN_IMAGE" \
  -w /build/mockserver/mockserver-maven-plugin \
  -v mockserver-m2-cache:/root/.m2 \
  -- bash -ec "${maven_packaging_prelude}"'
    command -v gpg >/dev/null 2>&1 || { apt-get -o Acquire::Retries=3 update -qq >/dev/null \
      && apt-get -o Acquire::Retries=3 install -y -qq gnupg >/dev/null; }
    set +x
    base64 -d < /build/.tmp/gpg-key-plugin.'$$' | gpg --batch --import
    mkdir -p ~/.gnupg
    echo "allow-loopback-pinentry" >> ~/.gnupg/gpg-agent.conf
    gpgconf --reload gpg-agent 2>/dev/null || true
    GPG_PASSPHRASE=$(cat /build/.tmp/gpg-passphrase-plugin.'$$')
    SONATYPE_USERNAME=$(head -1 /build/.tmp/sonatype-creds-plugin.'$$')
    SONATYPE_PASSWORD=$(tail -1 /build/.tmp/sonatype-creds-plugin.'$$')
    cat > /tmp/settings.xml <<SETTINGS
<?xml version="1.0" encoding="UTF-8"?>
<settings>
  <servers>
    <server>
      <id>central-portal</id>
      <username>${SONATYPE_USERNAME}</username>
      <password>${SONATYPE_PASSWORD}</password>
    </server>
    <server>
      <id>gpg.passphrase</id>
      <passphrase>${GPG_PASSPHRASE}</passphrase>
    </server>
  </servers>
</settings>
SETTINGS
    # central-publishing-maven-plugin is configured in the parent pom with
    # autoPublish=false / waitUntil=validated. Those are explicit <configuration>
    # values, which Maven gives precedence over -D user properties, so they
    # cannot be flipped from the command line. `mvn deploy` therefore only
    # uploads and validates the deployment and prints its deploymentId; the
    # promote-to-published step is done via the Central Portal API below.
    mvn deploy -P release -DskipTests -DskipITs \
      -Dgpg.passphraseServerId=gpg.passphrase \
      -Dgpg.useagent=false \
      --settings /tmp/settings.xml
  ' 2>&1 | tee "$DEPLOY_LOG"
mvn_exit=${PIPESTATUS[0]}
if [[ $mvn_exit -ne 0 ]]; then
  log_error "mvn deploy failed (in_docker exit $mvn_exit)"
  exit "$mvn_exit"
fi

# central-publishing-maven-plugin logs "deploymentId: <uuid>" on upload; extract it
# and validate its shape before any API call.
DEPLOYMENT_ID=$(grep -oE 'deploymentId: [0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b' "$DEPLOY_LOG" \
  | head -1 | awk '{print $2}')
rm -f "$DEPLOY_LOG"
_mp_cleanup_secrets
trap - EXIT
if [[ ! "$DEPLOYMENT_ID" =~ $UUID_RE ]]; then
  log_error "Could not extract a well-formed deploymentId from mvn deploy output (got: '${DEPLOYMENT_ID:-<empty>}')"
  exit 1
fi
log_info "Central Portal deployment: $DEPLOYMENT_ID"

publish_and_wait
