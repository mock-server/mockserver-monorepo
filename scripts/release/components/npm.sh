#!/usr/bin/env bash
# Publish mockserver-node and mockserver-client-node to npm.
#
# Dry-run mode: install + lint + grunt build + `npm publish --dry-run`.
# npm has a native --dry-run that goes through everything except the upload.

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
require_cmd curl
require_cmd jq
require_release_inputs
skip_unless_release_type "npm" full,post-maven

log_step "Publish npm packages $RELEASE_VERSION (dry-run=$DRY_RUN)"
sync_to_origin_master

# npm is eventually consistent — block until the just-published version is
# actually resolvable from the registry before returning. mockserver-client-node
# is published second and depends on mockserver-node, so its `npm install`
# must not run until mockserver-node's new version is downloadable.
#
# We MUST probe the same document `npm install` reads to resolve dependency
# versions: the abbreviated "install" packument at the package root, requested
# with the `application/vnd.npm.install-v1+json` Accept header. The per-version
# document (registry.npmjs.org/<pkg>/<version>) goes live almost immediately
# after publish, but the packument that `npm i` resolves against is served
# through a more heavily CDN-cached path and propagates with a longer lag — so
# probing the per-version URL passed while `npm i` of the dependent package
# still got ETARGET (No matching version found). Asserting the version key is
# present in the install packument is the honest precondition.
#
# The ceiling is long because npm processes a large package before listing it,
# and mockserver-node bundles the jar (~87 MB): that can take tens of minutes.
# The release-pipeline.yml npm step timeout must stay above twice this ceiling.
NPM_INSTALLABLE_TIMEOUT_S=2700
NPM_INSTALLABLE_POLL_S=30
wait_until_installable() {
  local pkg="$1" npm_name="$2" packument="" elapsed=0
  local start=$SECONDS
  log_info "[$pkg] waiting up to $((NPM_INSTALLABLE_TIMEOUT_S / 60))m for $npm_name@$RELEASE_VERSION to be installable from npm"
  # Match the version only as a `versions{}` JSON key ("<version>":) using a
  # fixed-string grep, so the `.` in the version is a literal (not a regex
  # wildcard) and we cannot false-match the version appearing as a dist-tag
  # value, a `version` field, or a tarball URL.
  # Capture the packument then grep a here-string: a direct `curl | grep -q` can
  # SIGPIPE curl under pipefail when grep short-circuits, misreading a successful
  # match as "not resolvable" (same class as the prepare.sh changelog bug).
  until packument=$(curl -fsS --connect-timeout 10 --max-time 15 \
      -H 'Accept: application/vnd.npm.install-v1+json' \
      "https://registry.npmjs.org/$npm_name" 2>/dev/null) \
      && grep -qF -- "\"$RELEASE_VERSION\":" <<<"$packument"; do
    elapsed=$((SECONDS - start))
    if [[ "$elapsed" -ge "$NPM_INSTALLABLE_TIMEOUT_S" ]]; then
      log_error "[$pkg] $npm_name@$RELEASE_VERSION still not resolvable from npm install metadata after $((elapsed / 60))m$((elapsed % 60))s; it is published, so re-running this step resumes the wait"
      return 1
    fi
    log_info "[$pkg] not yet resolvable from npm install metadata ($((elapsed / 60))m$((elapsed % 60))s of $((NPM_INSTALLABLE_TIMEOUT_S / 60))m elapsed), next check in ${NPM_INSTALLABLE_POLL_S}s"
    sleep "$NPM_INSTALLABLE_POLL_S"
  done
  elapsed=$((SECONDS - start))
  log_info "[$pkg] $npm_name@$RELEASE_VERSION is live on npm (after $((elapsed / 60))m$((elapsed % 60))s)"
}

publish_one() {
  local pkg="$1"
  # The npm package name can differ from the directory name (mockserver-client-node
  # publishes as "mockserver-client"), so read it. We also read the version so
  # we can fail-fast before the registry idempotency check below — otherwise a
  # stale package.json (e.g. prepare.sh didn't bump it) would cause `npm publish`
  # to try to republish the old version and hit a 403, OR slip past unnoticed if
  # the old version was missing from npm. The version assert must run BEFORE
  # the "already on npm" check so we never mask a wrong-version publish.
  local npm_name pkg_version
  npm_name=$(jq -r '.name' "$REPO_ROOT/$pkg/package.json")
  pkg_version=$(jq -r '.version' "$REPO_ROOT/$pkg/package.json")
  # In dry-run, update-version-references (which bumps these manifests) is
  # skipped, and its bump would never reach this step's fresh checkout anyway.
  # Bump package.json in-place to RELEASE_VERSION so the dry-run validates the
  # real release version, and back up the lockfile (the lock-only install
  # rewrites its version). The EXIT trap restores both, on failure too, so the
  # working tree stays clean.
  local jar_version=""
  if is_dry_run; then
    mkdir -p "$REPO_ROOT/.tmp"
    cp "$REPO_ROOT/$pkg/package.json"      "$REPO_ROOT/.tmp/$pkg.package.json.bak"
    cp "$REPO_ROOT/$pkg/package-lock.json" "$REPO_ROOT/.tmp/$pkg.package-lock.json.bak"
    DRY_RUN_BUMPED_PKGS+=("$pkg")
    # download_jar fetches the jar named by package.json, which is a 404 until
    # this release reaches Central: fall back to the last released version.
    if [[ "$pkg" == "mockserver-node" ]] && ! curl -fsI --connect-timeout 10 --max-time 15 -o /dev/null \
        "https://repo1.maven.org/maven2/org/mock-server/mockserver-netty/$RELEASE_VERSION/mockserver-netty-$RELEASE_VERSION-jar-with-dependencies.jar"; then
      jar_version="$pkg_version"
      log_dry "[$pkg] mockserver-netty $RELEASE_VERSION is not on Maven Central; download_jar uses $jar_version"
    fi
    if [[ "$pkg_version" != "$RELEASE_VERSION" ]]; then
      _newpkg="$REPO_ROOT/.tmp/$pkg.package.json.new"
      # write in place, not mv: a replaced inode may not be visible yet to the
      # next Docker Desktop bind-mount container
      jq --arg v "$RELEASE_VERSION" '.version = $v' "$REPO_ROOT/$pkg/package.json" > "$_newpkg" \
        && cat "$_newpkg" > "$REPO_ROOT/$pkg/package.json" && rm -f "$_newpkg"
      pkg_version="$RELEASE_VERSION"
      log_info "[$pkg] dry-run: bumped package.json version to $RELEASE_VERSION in-place (not committed)"
    fi
  fi
  if [[ "$pkg_version" != "$RELEASE_VERSION" ]]; then
    log_error "[$pkg] $pkg/package.json version ($pkg_version) does not match RELEASE_VERSION ($RELEASE_VERSION) — refusing to publish wrong version"
    return 1
  fi
  # Idempotent: if this package version is already on npm, an earlier run
  # published it.
  if ! is_dry_run && curl -fsI --connect-timeout 10 --max-time 15 -o /dev/null \
      "https://registry.npmjs.org/$npm_name/$RELEASE_VERSION" 2>/dev/null; then
    log_info "[$pkg] $npm_name@$RELEASE_VERSION already on npm - skipping publish"
    # A re-run after a timed-out wait must keep waiting: the per-version
    # document above goes live long before the install packument does.
    wait_until_installable "$pkg" "$npm_name"
    return
  fi
  log_info "[$pkg] build"
  in_docker "$NODE_IMAGE" \
    -w "/build/$pkg" \
    -e "PKG_DIR=$pkg" \
    -e "MOCKSERVER_DOWNLOAD_JAR_VERSION=$jar_version" \
    -- bash -ec '
      # Install the committed lockfile, never a fresh resolution. The lock-only
      # install first records what this release changed in package.json (the
      # root version and, for mockserver-client-node, its exact mockserver-node
      # devDependency); every other entry keeps its locked version and hash.
      attempts=0
      until npm install --package-lock-only --ignore-scripts --no-audit --no-fund && npm ci; do
        attempts=$((attempts + 1))
        if [ "$attempts" -ge 5 ]; then
          echo "npm install failed after ${attempts} attempts"; exit 1
        fi
        echo "npm install failed, retrying in 15s"; sleep 15
      done
      # Release grunt invocation: package + lint only. The default grunt
      # task includes integration tests that start a real MockServer JVM,
      # which would require Java in this node container (we do not, and
      # do not need to, install Java here).
      if [ "$PKG_DIR" = "mockserver-node" ]; then
        npx grunt deleted_jars download_jar jshint
      else
        npx grunt jshint
      fi
    '

  if is_dry_run; then
    log_dry "skip: commit + push + tag + npm publish"
    log_info "[$pkg] npm publish --dry-run"
    in_docker "$NODE_IMAGE" \
      -w "/build/$pkg" \
      -- npm publish --dry-run --access=public
    return
  fi

  log_info "[$pkg] commit build artifacts"
  git_commit_and_push "release: publish $pkg $RELEASE_VERSION" "$pkg"
  git_tag_and_push "$pkg-$RELEASE_VERSION"

  log_info "[$pkg] npm publish"
  local npm_token
  npm_token=$(load_secret "mockserver-release/npm-token" "token")
  in_docker "$NODE_IMAGE" \
    -w "/build/$pkg" \
    --secret-env "NPM_TOKEN=$npm_token" \
    -- bash -ec '
      set +x
      cat > /tmp/.npmrc <<NPMRC
//registry.npmjs.org/:_authToken=${NPM_TOKEN}
registry=https://registry.npmjs.org/
always-auth=true
NPMRC
      export NPM_CONFIG_USERCONFIG=/tmp/.npmrc
      npm whoami >/dev/null || { echo "npm authentication failed"; exit 1; }
      npm publish --access=public
    '

  wait_until_installable "$pkg" "$npm_name"
}

# The in-container `npm ci` in publish_one writes a root-owned node_modules into
# each package's bind-mounted workspace dir that the next job's git checkout
# cannot clean (see clean_workspace_node_modules). Remove both on every exit path,
# and restore the manifests a dry-run bumped. A RETURN trap would not do: it does
# not fire when `set -e` exits the shell from inside publish_one.
DRY_RUN_BUMPED_PKGS=()
restore_dry_run_manifests() {
  local pkg
  for pkg in ${DRY_RUN_BUMPED_PKGS[@]+"${DRY_RUN_BUMPED_PKGS[@]}"}; do
    cp "$REPO_ROOT/.tmp/$pkg.package.json.bak" "$REPO_ROOT/$pkg/package.json" 2>/dev/null || true
    cp "$REPO_ROOT/.tmp/$pkg.package-lock.json.bak" "$REPO_ROOT/$pkg/package-lock.json" 2>/dev/null || true
  done
}
trap 'restore_dry_run_manifests; clean_workspace_node_modules mockserver-node mockserver-client-node' EXIT

publish_one mockserver-node
publish_one mockserver-client-node

log_info "npm publish complete"
