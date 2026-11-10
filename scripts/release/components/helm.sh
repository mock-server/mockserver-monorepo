#!/usr/bin/env bash
# Publish Helm chart for MockServer.
#
# Dry-run: package + lint chart, skip S3 upload + git push.

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
require_release_inputs
skip_unless_release_type "helm" full,post-maven

log_step "Publish Helm chart $RELEASE_VERSION (dry-run=$DRY_RUN)"
sync_to_origin_master

CHART_FILE="$REPO_ROOT/helm/mockserver/Chart.yaml"

log_info "Update Chart.yaml to $RELEASE_VERSION"
if is_dry_run; then
  log_dry "would: set version + appVersion in $CHART_FILE"
else
  sed -i.bak "s/^version: .*/version: \"$RELEASE_VERSION\"/" "$CHART_FILE"
  sed -i.bak "s/^appVersion: .*/appVersion: \"$RELEASE_VERSION\"/" "$CHART_FILE"
  rm -f "$CHART_FILE.bak"
fi

log_info "Lint chart"
in_docker "$HELM_IMAGE" -w /build -- lint ./helm/mockserver/

log_info "Package chart"
# In dry-run write to a scratch dir so we don't touch the tracked
# helm/charts/ (which holds the already-published release artifacts). We
# also pass --version/--app-version because Chart.yaml isn't bumped in
# dry-run, so otherwise the tgz would be named for the *previous* release
# and would clobber it.
if is_dry_run; then
  mkdir -p "$REPO_ROOT/.tmp/helm-charts"
  # --destination is relative to the container's /build workdir (the repo is
  # mounted at /build by in_docker), so .tmp/helm-charts/ here = $REPO_ROOT/.tmp/helm-charts/.
  in_docker "$HELM_IMAGE" -w /build -- package ./helm/mockserver/ \
    --version "$RELEASE_VERSION" --app-version "$RELEASE_VERSION" \
    --destination .tmp/helm-charts/
else
  mkdir -p "$REPO_ROOT/helm/charts"
  in_docker "$HELM_IMAGE" -w /build -- package ./helm/mockserver/ --destination helm/charts/
fi

if is_dry_run; then
  log_dry "skip: helm registry login + helm push to oci://ghcr.io/mock-server/charts"
  log_dry "skip: cosign key check and cosign sign of the OCI chart"
  log_dry "skip: aws s3 download index, repack, upload, commit/push"
  log_info "Built chart: .tmp/helm-charts/mockserver-$RELEASE_VERSION.tgz"
else
  # Signing is fail-closed: a missing or broken key stops the release before the chart is pushed.
  require_cosign_key "Helm chart"

  # Push to GHCR (OCI) before touching S3 so a transient GHCR failure aborts
  # the step before any S3 mutation. helm push is idempotent against the same
  # version tag, so a step retry after a mid-publish failure is safe.
  log_info "Push chart to GHCR (oci://ghcr.io/mock-server/charts)"
  # GHCR credentials are 0600 files in a private directory under .tmp/ (mounted at /build), never
  # `docker run -e` (readable via /proc/1/environ and `docker inspect`). A subshell, so the trap
  # removes them however the call ends.
  ghcr_push_chart() (
    dir=""
    trap '[ -n "$dir" ] && rm -rf -- "$dir"' EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    set +x
    mkdir -p "$REPO_ROOT/.tmp"
    dir="$(mktemp -d "$REPO_ROOT/.tmp/ghcr-push.XXXXXX")" || exit 1
    ( umask 077
      load_secret "mockserver-release/ghcr-token" "username" > "$dir/ghcr-username"
      load_secret "mockserver-release/ghcr-token" "token" > "$dir/ghcr-token" ) || exit 1
    secret_files_ok "$dir" ghcr-username ghcr-token || exit 1
    in_docker "$HELM_IMAGE" --entrypoint sh -w /build -e "CREDS_DIR=/build/${dir#"$REPO_ROOT"/}" \
      -- -ec '
        set +x
        printf "%s" "$(cat "$CREDS_DIR/ghcr-token")" | helm registry login ghcr.io \
          --username "$(cat "$CREDS_DIR/ghcr-username")" --password-stdin
        helm push "helm/charts/mockserver-'"$RELEASE_VERSION"'.tgz" \
          oci://ghcr.io/mock-server/charts
      '
  )
  ghcr_push_chart

  # Cosign-sign the pushed OCI chart (Artifact Hub shows the "Signed" badge) with
  # cosign_sign_ghcr_charts (_lib.sh). HARD-fail: require_cosign_key above proved the key decrypts,
  # so a signing failure aborts the release. Validate with scripts/release/test-cosign-signing.sh.
  log_info "Cosign-signing the OCI chart"
  if cosign_sign_ghcr_charts "$RELEASE_VERSION"; then
    log_info "Chart signed with cosign"
  else
    log_error "cosign signing failed — chart could not be signed"
    exit 1
  fi

  log_info "Sync existing charts from S3"
  if [[ -z "${WEBSITE_BUCKET:-}" ]]; then
    log_error "WEBSITE_BUCKET not set"; exit 1
  fi
  # Subshell: assume_website_role exports website-account credentials. They
  # must not leak into git_commit_and_push below, which reads the GitHub token
  # from a build-account secret and so needs the agent's own credentials.
  (
    assume_website_role
    rm -f "$REPO_ROOT"/helm/charts/index.yaml
    aws s3 sync "s3://$WEBSITE_BUCKET/" "$REPO_ROOT/helm/charts/" \
      --exclude "*" --include "mockserver-*.tgz" --include "index.yaml"

    log_info "Rebuild Helm repo index"
    in_docker "$HELM_IMAGE" -w /build -- repo index helm/charts/ --url "https://www.mock-server.com"

    log_info "Upload to S3"
    # Upload every chart in helm/charts/, not just the new release. The
    # bucket is meant to hold all historical charts referenced by index.yaml,
    # but a new major/minor release lands in a freshly created bucket and
    # the versioned-site mirror has previously failed to carry the older
    # .tgz files across (see issue #2282). Syncing every chart on each run
    # makes the bucket self-heal: helm/charts/ is the canonical set, so any
    # gap created by a missed mirror is closed here.
    aws s3 sync "$REPO_ROOT/helm/charts/" "s3://$WEBSITE_BUCKET/" \
      --exclude "*" --include "mockserver-*.tgz"
    aws s3 cp "$REPO_ROOT/helm/charts/index.yaml" "s3://$WEBSITE_BUCKET/"
  )

  git_commit_and_push "release: Helm chart $RELEASE_VERSION" \
    helm/mockserver/Chart.yaml \
    "helm/charts/mockserver-$RELEASE_VERSION.tgz" \
    helm/charts/index.yaml
fi

log_info "Helm publish complete"
