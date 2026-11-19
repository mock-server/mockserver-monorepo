#!/usr/bin/env bash
# Generate Javadoc and publish to S3.
#
# Dry-run: build Javadoc, skip S3 upload.

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

require_cmd docker
require_cmd git
require_release_inputs
skip_unless_release_type "javadoc" full,post-maven

log_step "Publish Javadoc $RELEASE_VERSION (dry-run=$DRY_RUN)"

log_info "Sync to origin/master + fetch tags"
sync_to_origin_master

if ! is_dry_run; then
  log_info "Check out release tag"
  git -C "$REPO_ROOT" checkout "mockserver-$RELEASE_VERSION"
  trap 'git -C "$REPO_ROOT" checkout master 2>/dev/null || true' EXIT
fi

log_info "Generate aggregate Javadoc"
# maven-javadoc-plugin's `aggregate` goal forks `compile` on each reactor
# module, but the forked compile resolves inter-module dependencies via the
# local Maven repository — not the reactor's in-flight build. On a fresh
# Buildkite agent the local repo is empty, so a downstream module (e.g.
# mockserver-netty, which depends on mockserver-core) fails its default-compile
# with "package org.mockserver... does not exist" and the whole aggregate
# aborts. Two-pass: install first to populate the local repo at $RELEASE_VERSION,
# then aggregate javadoc with all dependencies resolvable. (examples/java is not
# a reactor module, so it is not part of the aggregate javadoc.)
#
# IMPORTANT: the install pass MUST NOT use `-P release`. That profile activates
# maven-gpg-plugin which signs every artifact, and the Javadoc job's container
# never imports a GPG key (only maven-central.sh does that). With `-P release`
# the install fails with `maven-gpg-plugin:sign: Exit code: 2` before anything
# is installed. The release profile is still applied to the aggregate pass,
# where it is needed for the javadoc artifact configuration.
#
# The install pass also installs each module's -sources jar: under -P release the
# aggregate's forked lifecycle runs unpack-own-sources in the published
# *-no-dependencies modules, which resolve the MockServer modules' -sources jars from the local repo.
in_maven -w /build/mockserver \
  -- mvn org.apache.maven.plugins:maven-source-plugin:3.4.0:jar-no-fork install -DskipTests -DskipITs
in_maven -w /build/mockserver \
  -- mvn javadoc:aggregate -P release -DskipTests -DskipITs

# maven-javadoc-plugin writes the aggregate HTML to target/reports/apidocs.
APIDOCS="$REPO_ROOT/mockserver/target/reports/apidocs"
[[ -f "$APIDOCS/index.html" ]] \
  || { log_error "Aggregate Javadoc not found at $APIDOCS"; exit 1; }

if is_dry_run; then
  log_dry "skip: aws s3 sync of Javadoc"
  log_info "Built: $APIDOCS"
else
  [[ -n "${WEBSITE_BUCKET:-}" ]] || { log_error "WEBSITE_BUCKET not set"; exit 1; }
  assume_website_role
  log_info "Upload to S3"
  aws s3 sync "$APIDOCS" \
    "s3://$WEBSITE_BUCKET/versions/$RELEASE_VERSION/apidocs/" --delete
fi

log_info "Javadoc publish complete"
