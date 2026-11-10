#!/usr/bin/env bash
# Release the core Maven artifacts to Maven Central.
#
# Steps:
#   1. Pull origin/master so we see the version bump from prepare.sh.
#   2. Build + test the release version (mvn clean install).
#   3. Deploy signed artifacts to the Central Portal staging.
#   4. Poll the Portal until validation passes.
#   5. Publish (promote staging to release).
#   6. Wait for the artifacts to be searchable on Maven Central.
#
# Dry-run mode: runs the same build + tests, but skips the signed deploy,
# Sonatype upload, publish, and sync wait.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source-path=SCRIPTDIR source=../_lib.sh
source "$SCRIPT_DIR/_lib.sh"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true; shift ;;
    --execute) DRY_RUN=false; shift ;;
    -h|--help) echo "Usage: $0 [--dry-run|--execute]"; exit 0 ;;
    *) log_error "Unknown arg: $1"; exit 2 ;;
  esac
done

require_cmd docker
require_release_inputs
skip_unless_release_type "maven-central" full,maven-only

log_step "Release Maven Central artifacts $RELEASE_VERSION (dry-run=$DRY_RUN)"

# Surface the URLs the operator may want to open in a browser while the step
# runs. The Central Portal dashboard shows live deployment state (VALIDATING /
# VALIDATED / PUBLISHED / FAILED); repo1.maven.org goes 200 once the artifact
# has synced from Sonatype, which is the canonical "the release is live" signal.
log_info "Monitor & verify URLs:"
log_info "  - Sonatype Central Portal:  https://central.sonatype.com/publishing/deployments"
log_info "  - Central artifact view:    https://central.sonatype.com/artifact/org.mock-server/mockserver-netty/$RELEASE_VERSION"
log_info "  - Live at Maven Central:    https://repo1.maven.org/maven2/org/mock-server/mockserver-netty/$RELEASE_VERSION/"
log_info "  - Search Maven Central:     https://central.sonatype.com/search?namespace=org.mock-server&sort=published"

sync_to_origin_master

# ---- Build & test ----------------------------------------------------------
# Identical in dry-run and release, so a test that would fail the release fails
# the dry run first. Test output is redirected to files, so on failure copy the
# failing classes' reports and output into mockserver/target/failed-tests/.
log_info "Build + test $RELEASE_VERSION (Maven in Docker)"
in_maven -w /build/mockserver \
  -- mvn -T 1C clean install "${RELEASE_MAVEN_TEST_FLAGS[@]}" -Djava.security.egd=file:/dev/./urandom \
  || { build_rc=$?; collect_failed_tests; exit "$build_rc"; }

# ---- Deploy + sign ---------------------------------------------------------
# The passphrase is delivered to Maven via the `central-portal` server's
# <passphrase> element in the generated settings.xml, NOT via the
# -Dgpg.passphrase command-line property — that would expose the passphrase
# in the container's process list and in any tracing output.
DEPLOYMENT_ID=""
if is_dry_run; then
  # The release deploy attaches a CycloneDX SBOM to every module (-P release); run that goal
  # here so a broken SBOM build fails the dry-run instead of the release.
  log_info "Generate the CycloneDX SBOMs the release attaches"
  in_maven -w /build/mockserver \
    -- mvn -B -P release org.cyclonedx:cyclonedx-maven-plugin:makeBom
  # The dry-run tree keeps master's -SNAPSHOT version, so match any version; `clean install` above
  # removed every earlier SBOM.
  compgen -G "$REPO_ROOT/mockserver/mockserver-netty/target/mockserver-netty-*-cyclonedx.json" >/dev/null \
    || { log_error "makeBom wrote no CycloneDX SBOM for mockserver-netty"; exit 1; }
  log_dry "skip: deploy to Sonatype Central Portal"
  log_dry "skip: GPG-sign and upload artifacts"
else
  log_info "Deploy to Sonatype with GPG signing"

  # Write secrets to 0600 files under .tmp/ (mounted at /build in the
  # container) instead of passing via `docker run -e`. Env vars are readable
  # via /proc/1/environ and `docker inspect`; file-based secrets are not.
  # Pattern mirrors the cosign key handling in helm.sh.
  mkdir -p "$REPO_ROOT/.tmp"
  GPG_KEY_FILE="$REPO_ROOT/.tmp/gpg-key.$$"
  GPG_PASS_FILE="$REPO_ROOT/.tmp/gpg-passphrase.$$"
  SONATYPE_CREDS_FILE="$REPO_ROOT/.tmp/sonatype-creds.$$"
  _mc_cleanup_secrets() {
    rm -f "$GPG_KEY_FILE" "$GPG_PASS_FILE" "$SONATYPE_CREDS_FILE"
  }
  ( umask 077
    load_secret "mockserver-release/gpg-key" "key"        > "$GPG_KEY_FILE"
    load_secret "mockserver-release/gpg-key" "passphrase" > "$GPG_PASS_FILE"
    # Store sonatype user:pass as two lines (username\npassword) so the
    # in-container script can read them without needing jq.
    load_secret "mockserver-build/sonatype" "username"     > "$SONATYPE_CREDS_FILE"
    load_secret "mockserver-build/sonatype" "password"    >> "$SONATYPE_CREDS_FILE"
  )

  # tee the deploy output so we can extract the deploymentId the
  # central-publishing plugin prints after upload. We need it to poll the
  # Sonatype status API by ID — the namespace-listing endpoint is unreliable.
  # Location is .tmp/ (per AGENTS.md temp-file policy), and we rm the file
  # eagerly after extracting the id rather than relying on EXIT — the file
  # holds the full container stdout/stderr, which could contain credentials
  # if any subprocess accidentally enables `set -x`, and the polling phase
  # below runs for up to 30 minutes.
  DEPLOY_LOG=$(mktemp "$REPO_ROOT/.tmp/mockserver-deploy.XXXXXX")
  trap '_mc_cleanup_secrets; rm -f "$DEPLOY_LOG"' EXIT

  in_docker "$MAVEN_IMAGE" \
    -w /build/mockserver \
    -v mockserver-m2-cache:/root/.m2 \
    -- bash -ec "${maven_packaging_prelude}"'
      command -v gpg >/dev/null 2>&1 || { apt-get -o Acquire::Retries=3 update -qq >/dev/null \
        && apt-get -o Acquire::Retries=3 install -y -qq gnupg >/dev/null; }
      set +x
      base64 -d < /build/.tmp/gpg-key.'$$' | gpg --batch --import
      mkdir -p ~/.gnupg
      echo "allow-loopback-pinentry" >> ~/.gnupg/gpg-agent.conf
      gpgconf --reload gpg-agent 2>/dev/null || true
      GPG_PASSPHRASE=$(cat /build/.tmp/gpg-passphrase.'$$')
      SONATYPE_USERNAME=$(head -1 /build/.tmp/sonatype-creds.'$$')
      SONATYPE_PASSWORD=$(tail -1 /build/.tmp/sonatype-creds.'$$')
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
      mvn deploy -P release -DskipTests -DskipITs \
        -Dgpg.passphraseServerId=gpg.passphrase \
        -Dgpg.useagent=false \
        --settings /tmp/settings.xml
    ' 2>&1 | tee "$DEPLOY_LOG"
  # Under `set -o pipefail` a pipeline exits with the rightmost non-zero
  # status, which means `cmd | tee file` returns tee's exit code (almost
  # always 0). We need the docker exit code instead — without this check,
  # a failed mvn deploy would silently proceed to the deploymentId extract
  # against an error log.
  mvn_exit=${PIPESTATUS[0]}
  if [[ $mvn_exit -ne 0 ]]; then
    log_error "mvn deploy failed (in_docker exit $mvn_exit)"
    exit "$mvn_exit"
  fi

  # The central-publishing-maven-plugin emits one line per deployment of the
  # form `Uploaded bundle successfully, deployment name: ..., deploymentId:
  # <uuid>. ...`. The UUID is lowercase hex per java.util.UUID.toString().
  # Anchor the regex to a strict UUID shape (8-4-4-4-12 hex with hyphens),
  # then validate the captured value matches the same shape before using it
  # in API calls. A malformed or empty id is a hard failure.
  DEPLOYMENT_ID=$(grep -oE 'deploymentId: [a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\b' "$DEPLOY_LOG" \
    | head -1 | awk '{print $2}')
  # Drop the log file and secret files as soon as the id is captured — they
  # may contain secrets and live outside the EXIT trap window otherwise.
  # After this point and before the polling block creates
  # $status_response_file, no tmpfiles exist, so the EXIT trap can be safely
  # disarmed (a signal in this gap would leak nothing).
  rm -f "$DEPLOY_LOG"
  _mc_cleanup_secrets
  trap - EXIT
  if [[ ! "$DEPLOYMENT_ID" =~ ^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$ ]]; then
    log_error "Could not extract a well-formed deploymentId from mvn deploy output (got: '${DEPLOYMENT_ID:-<empty>}'; expected line: 'Uploaded bundle successfully, deployment name: ..., deploymentId: <uuid>')"
    exit 1
  fi
  log_info "Captured Central Portal deploymentId: $DEPLOYMENT_ID"
fi

# ---- Helpers for the Central Portal API -----------------------------------
# Write Sonatype credentials to a 0600 netrc-format file in .tmp/ rather than
# holding decoded user:pass in a shell variable across the 30-min polling loop.
# Curl's --netrc-file reads the credential on each request and the file is
# trap-removed on exit.
CP_NETRC=""
setup_central_portal_netrc() {
  mkdir -p "$REPO_ROOT/.tmp"
  CP_NETRC="$REPO_ROOT/.tmp/sonatype-netrc.$$"
  local user pass
  set +x
  user=$(load_secret "mockserver-build/sonatype" "username")
  pass=$(load_secret "mockserver-build/sonatype" "password")
  ( umask 077; printf 'machine central.sonatype.com\nlogin %s\npassword %s\n' \
      "$user" "$pass" > "$CP_NETRC" )
}
cleanup_central_portal_netrc() {
  rm -f "$CP_NETRC"
}

if ! is_dry_run; then
  setup_central_portal_netrc
fi

# ---- Poll for validation ---------------------------------------------------
# We poll the per-deployment status endpoint (POST /publisher/status?id=...)
# rather than the namespace-listing endpoint (/publisher/deployment/list),
# because the listing endpoint has returned HTTP 500 from Sonatype since the
# Central Portal API refactor — silently swallowed by the previous polling
# loop, which then timed out after 30 min of <empty> states.
if is_dry_run; then
  log_dry "skip: poll Central Portal validation"
else
  log_info "Polling Central Portal for validation result (deployment $DEPLOYMENT_ID)"
  TIMEOUT_ITERATIONS=60   # 60 × 30s = 30 minutes
  MAX_CONSECUTIVE_ERRORS=5
  status_response_file=$(mktemp "$REPO_ROOT/.tmp/mockserver-status.XXXXXX")
  trap 'rm -f "$status_response_file"; cleanup_central_portal_netrc' EXIT
  consecutive_errors=0
  validation_status=""
  for i in $(seq 1 "$TIMEOUT_ITERATIONS"); do
    http_code=$(curl -sS --max-time 30 -o "$status_response_file" -w '%{http_code}' \
      -X POST --netrc-file "$CP_NETRC" \
      "https://central.sonatype.com/api/v1/publisher/status?id=$DEPLOYMENT_ID" 2>/dev/null || echo "000")
    if [[ "$http_code" != "200" ]]; then
      consecutive_errors=$((consecutive_errors + 1))
      log_info "  attempt $i: HTTP $http_code (consecutive errors: $consecutive_errors/$MAX_CONSECUTIVE_ERRORS)"
      if [[ "$consecutive_errors" -ge "$MAX_CONSECUTIVE_ERRORS" ]]; then
        log_error "$MAX_CONSECUTIVE_ERRORS consecutive Central Portal API errors polling deployment $DEPLOYMENT_ID; last HTTP $http_code"
        cat "$status_response_file" || true
        exit 1
      fi
      sleep 30
      continue
    fi
    consecutive_errors=0
    validation_status=$(jq -r '.deploymentState // empty' "$status_response_file" 2>/dev/null || true)
    log_info "  attempt $i: state=${validation_status:-<empty>}"
    case "$validation_status" in
      VALIDATED|PUBLISHING|PUBLISHED)
        log_info "Validation passed: $validation_status"
        break ;;
      FAILED)
        log_error "Validation FAILED"; jq . "$status_response_file" 2>/dev/null || cat "$status_response_file"; exit 1 ;;
    esac
    sleep 30
  done
  # Loop exhaustion — explicit timeout failure rather than silent fall-through.
  case "$validation_status" in
    VALIDATED|PUBLISHING|PUBLISHED) ;;
    *)
      log_error "Central Portal validation timed out after $((TIMEOUT_ITERATIONS * 30))s (last state=${validation_status:-<empty>})"
      exit 1 ;;
  esac
fi

# ---- Publish (promote) -----------------------------------------------------
if is_dry_run; then
  log_dry "skip: publish (promote staging to release)"
else
  # The validation poll may have observed the state already at PUBLISHING /
  # PUBLISHED if the Portal auto-published. Only call POST publish if we're
  # still at VALIDATED.
  if [[ "$validation_status" == "VALIDATED" ]]; then
    log_info "Publishing deployment $DEPLOYMENT_ID to Maven Central"
    publish_response_file=$(mktemp "$REPO_ROOT/.tmp/mockserver-publish.XXXXXX")
    publish_http=$(curl -sS --max-time 30 -o "$publish_response_file" -w '%{http_code}' \
      -X POST --netrc-file "$CP_NETRC" \
      "https://central.sonatype.com/api/v1/publisher/deployment/$DEPLOYMENT_ID" 2>/dev/null || echo "000")
    if [[ "$publish_http" != "204" && "$publish_http" != "200" ]]; then
      log_error "Failed to publish deployment $DEPLOYMENT_ID (HTTP $publish_http)"
      cat "$publish_response_file" || true
      rm -f "$publish_response_file"
      exit 1
    fi
    rm -f "$publish_response_file"
  else
    log_info "Skipping publish — Portal state already $validation_status"
  fi
fi

# ---- Wait for Maven Central sync ------------------------------------------
# This is the automated gate the downstream publishes depend on (there is no
# manual "approve downstream publish" step in the pipeline). Confirm the main
# MockServer artifacts have synced to repo1 before this step completes. Every
# module publishes in the SAME Central deployment and syncs together, so this
# representative set stands in for the whole release — no need to enumerate all
# ~20 modules (mockserver-maven-plugin is released by a separate pipeline and
# is intentionally excluded). The timeout is deliberately generous: far better
# to wait too long than to fail a real release because a sync lagged.
if is_dry_run; then
  log_dry "skip: wait for Maven Central sync"
else
  SYNC_ARTIFACTS=(mockserver-netty mockserver-client-java mockserver-core mockserver-junit-jupiter)
  log_info "Waiting for Maven Central sync of $RELEASE_VERSION (artifacts: ${SYNC_ARTIFACTS[*]})"
  SYNC_TIMEOUT_ITERATIONS=120  # 120 × 60s = 2 hours
  synced=false
  missing=()
  for i in $(seq 1 "$SYNC_TIMEOUT_ITERATIONS"); do
    missing=()
    for artifact in "${SYNC_ARTIFACTS[@]}"; do
      url="https://repo1.maven.org/maven2/org/mock-server/$artifact/$RELEASE_VERSION/$artifact-$RELEASE_VERSION.pom"
      curl -sf --max-time 30 -o /dev/null -I "$url" || missing+=("$artifact")
    done
    if [[ ${#missing[@]} -eq 0 ]]; then
      log_info "Synced — all ${#SYNC_ARTIFACTS[@]} main artifacts for $RELEASE_VERSION are live on Maven Central"
      synced=true
      break
    fi
    log_info "  attempt $i/$SYNC_TIMEOUT_ITERATIONS: ${#missing[@]} not yet visible: ${missing[*]}"
    sleep 60
  done
  if ! $synced; then
    log_error "Maven Central sync timed out after $((SYNC_TIMEOUT_ITERATIONS * 60))s; still missing: ${missing[*]}"
    exit 1
  fi
fi

log_info "Maven Central release complete"
