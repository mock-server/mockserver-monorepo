#!/usr/bin/env bash
# Create the GitHub Release for the mockserver-X.Y.Z tag.
#
# Dry-run: extract changelog notes + show them, skip `gh release create`.

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
skip_unless_release_type "github" full,post-maven

log_step "Create GitHub Release for $RELEASE_VERSION (dry-run=$DRY_RUN)"
sync_to_origin_master

log_info "Extract release notes from changelog.md"
CHANGELOG_EXTRACT=$(sed -n "/## \[$RELEASE_VERSION\]/,/## \[/p" "$REPO_ROOT/changelog.md" | sed '$d')
if [[ -z "$CHANGELOG_EXTRACT" ]]; then
  CHANGELOG_EXTRACT="Release $RELEASE_VERSION"
fi

NOTES_FILE="$REPO_ROOT/.tmp/changelog-extract.md"
mkdir -p "$REPO_ROOT/.tmp"
echo "$CHANGELOG_EXTRACT" > "$NOTES_FILE"

# GitHub rejects a release body over 125,000 characters with
# HTTP 422 "Validation Failed / body is too long", which fails this step outright.
# That is not hypothetical: 7.5.0 accumulated 245,801 characters of changelog over
# 25 days and build #69 died here AFTER Maven Central, Docker, npm, PyPI and
# RubyGems had already published - the worst possible place to stop, since the
# release is public by then but has no GitHub Release.
#
# Truncate rather than fail. A release body is a convenience copy; changelog.md is
# the source of truth, so pointing at it loses nothing. Leave headroom under the
# limit for the pointer appended below.
NOTES_LIMIT=120000
NOTES_SIZE=$(wc -c < "$NOTES_FILE")
if [[ "$NOTES_SIZE" -gt "$NOTES_LIMIT" ]]; then
  log_info "Release notes are $NOTES_SIZE characters, over GitHub's 125,000 limit — truncating"
  TRUNCATED_FILE="$REPO_ROOT/.tmp/changelog-extract-truncated.md"
  # Cut on a line boundary (drop the partial final line) so the markdown does not
  # end mid-construct - a body ending inside a table or code fence renders badly.
  head -c "$NOTES_LIMIT" "$NOTES_FILE" | sed '$d' > "$TRUNCATED_FILE"
  cat >> "$TRUNCATED_FILE" <<EOF

---

**These release notes are truncated.** This release's changelog entry exceeds GitHub's 125,000 character
limit for a release body. Read the complete entry in
[changelog.md](https://github.com/mock-server/mockserver-monorepo/blob/mockserver-$RELEASE_VERSION/changelog.md).
EOF
  mv "$TRUNCATED_FILE" "$NOTES_FILE"
  log_info "  truncated to $(wc -c < "$NOTES_FILE") characters"
fi

# Preview a head only. Echoing the whole body made the job log almost entirely
# release notes (2,653 lines for 7.5.0), which buried the actual failure.
log_info "Notes preview (first 40 lines of $(wc -l < "$NOTES_FILE")):"
head -40 "$NOTES_FILE" | sed 's/^/    /'

if is_dry_run; then
  log_dry "skip: gh release create mockserver-$RELEASE_VERSION"
else
  GITHUB_TOKEN=$(load_secret "mockserver-release/github-token" "token")
  # Idempotency by catching the API response rather than pre-checking with
  # `gh release view`. The maniator/gh container image is gh-only (no git),
  # so without an explicit --repo flag `gh release view` can fail to resolve
  # the repository even when the release exists — that's what bit us in
  # build #38, where the precheck silently returned non-zero and we hit
  # HTTP 422 "Release.tag_name already exists" on the subsequent create.
  # Capturing the create stderr and treating that exact error as success is
  # both simpler and more robust.
  log_info "Creating release mockserver-$RELEASE_VERSION"
  create_output=$(in_docker "$GH_IMAGE" \
    -w /build \
    -e "GITHUB_TOKEN=$GITHUB_TOKEN" \
    -- release create "mockserver-$RELEASE_VERSION" \
         --title "MockServer $RELEASE_VERSION" \
         --notes-file ".tmp/changelog-extract.md" \
         --latest 2>&1) && create_exit=0 || create_exit=$?
  if [[ $create_exit -ne 0 ]]; then
    # Match GitHub's specific "release already exists" error strings ONLY.
    # We deliberately do NOT match a bare "HTTP 422" — GitHub returns 422 for
    # other unrelated validation failures (malformed body, missing required
    # field, schema mismatch, etc.) and we don't want to silently mask a real
    # error as "idempotent success".
    if grep -qE "Release\.tag_name already exists|already_exists" <<<"$create_output"; then
      log_info "  release mockserver-$RELEASE_VERSION already exists — treating as idempotent success"
    else
      printf '%s\n' "$create_output"
      log_error "gh release create failed (exit $create_exit)"
      exit $create_exit
    fi
  fi
fi

rm -f "$NOTES_FILE"
log_info "GitHub Release complete"
