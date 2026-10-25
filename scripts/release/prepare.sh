#!/usr/bin/env bash
# Prepare a release: validate inputs, bump pom.xml versions, commit, tag, push.
#
# This is the first script the orchestrator runs. After this completes, every
# subsequent component script syncs to origin/master to pick up the bump.
#
# Inputs (env vars): see docs/operations/release-principles.md §6.
# Args: --dry-run

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_lib.sh"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true; shift ;;
    --execute) DRY_RUN=false; shift ;;
    -h|--help) echo "Usage: $0 [--dry-run|--execute]"; exit 0 ;;
    *) log_error "Unknown arg: $1"; exit 2 ;;
  esac
done

require_cmd git
require_cmd python3
require_release_inputs

log_step "Prepare release $RELEASE_VERSION (dry-run=$DRY_RUN)"

# Partial reruns (docker-only, post-maven) bump no pom version and create no
# tag, so the version/tag/changelog validation below does not apply to them —
# exit before it runs.
case "$RELEASE_TYPE" in
  docker-only|post-maven)
    log_info "Skipping prepare for RELEASE_TYPE=$RELEASE_TYPE (no pom bump or tag)"
    exit 0 ;;
esac

# Validation
if [[ "$RELEASE_VERSION" == "$OLD_VERSION" ]]; then
  log_error "RELEASE_VERSION must differ from OLD_VERSION (latest tag: $OLD_VERSION)"
  exit 1
fi
if [[ ! "$CURRENT_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$ ]]; then
  log_error "Current pom version must be X.Y.Z-SNAPSHOT, got: $CURRENT_VERSION"
  exit 1
fi
# CREATE_VERSIONED_SITE is derived and validated (in BOTH directions) by
# require_release_inputs above — a major/minor release with =no, and a patch
# release with =yes, both fail closed there, before this script tags or pushes.
# No additional guard is needed here.
if git -C "$REPO_ROOT" rev-parse "mockserver-$RELEASE_VERSION" >/dev/null 2>&1; then
  log_error "Tag mockserver-$RELEASE_VERSION already exists"
  exit 1
fi
if grep -Eq "^## \[$RELEASE_VERSION\]" "$REPO_ROOT/changelog.md"; then
  log_error "changelog.md already contains a section for $RELEASE_VERSION"
  exit 1
fi
UNRELEASED_SECTION=$(sed -n '/^## \[Unreleased\]/,/^## \[/p' "$REPO_ROOT/changelog.md" | sed '1d;$d')
if ! grep -Eq '^- ' <<<"$UNRELEASED_SECTION"; then
  log_error "changelog.md has no bullets under Unreleased"
  exit 1
fi

log_info "Inputs OK:"
log_info "  Release:  $RELEASE_VERSION"
log_info "  Next:     $NEXT_VERSION"
log_info "  Previous: $OLD_VERSION"
log_info "  Current:  $CURRENT_VERSION"
log_info "  Type:     $RELEASE_TYPE"
log_info "  Versioned site: $CREATE_VERSIONED_SITE"

# Hold Dependabot auto-merge for the duration of this release cut. Best-effort:
# never fails the release, and shouts loudly if the flag cannot be written.
# Placed after validation (which is read-only) so a trivial input error does not
# strand a flag, and before any master mutation so the hold covers the whole cut.
release_gate set

log_info "Updating pom.xml versions from $CURRENT_VERSION to $RELEASE_VERSION"
if is_dry_run; then
  log_dry "would: update Maven pom.xml files"
else
  # Scan the whole repo, not just mockserver/, because sibling builds live
  # outside mockserver/ and must move in lock-step with it: examples/java (a
  # standalone build whose parent is ../../mockserver/pom.xml, no longer a
  # reactor module) and the mockserver-maven-plugin. update_pom_versions only
  # rewrites poms containing the exact <version>$CURRENT_VERSION</version>
  # literal, so integration-test fixture poms pinned to released versions are
  # left alone. examples/java/pom.xml is also listed explicitly in the commit
  # below.
  update_pom_versions "$REPO_ROOT" "$CURRENT_VERSION" "$RELEASE_VERSION"

  # Guard: fail before we commit, tag, and push if any reactor pom was missed.
  # A leftover <version>$CURRENT_VERSION</version> means a module lives outside
  # the scanned tree (the original examples/java bug) and the Maven Central
  # build would fail at "Scanning for projects" — after the tag already exists,
  # forcing a manual rollback. Catching it here keeps prepare atomic.
  leftover=$(grep -rl --include=pom.xml --exclude-dir=target "<version>$CURRENT_VERSION</version>" "$REPO_ROOT" || true)
  if [[ -n "$leftover" ]]; then
    log_error "pom.xml files still reference $CURRENT_VERSION after the version bump:"
    printf '  %s\n' $leftover >&2
    exit 1
  fi
fi

# Non-Maven version manifests. The npm/pypi/rubygems component scripts read
# the published version from per-package source files (package.json,
# pyproject.toml, version.rb) rather than $RELEASE_VERSION. If we don't bump
# them here, those publishers either republish-and-fail-403 (npm) or silently
# skip the publish because they think the version is already live (pypi,
# rubygems). The Helm chart's Chart.yaml is bumped by helm.sh itself.
NON_MAVEN_VERSION_PATHS=(
  mockserver-node/package.json
  mockserver-client-node/package.json
  mockserver-client-python/pyproject.toml
  mockserver-client-python/mockserver/launcher.py
  mockserver-client-php/src/BinaryLauncher.php
  mockserver-client-rust/Cargo.toml
  mockserver-client-rust/Cargo.lock
  mockserver-client-ruby/lib/mockserver/version.rb
  mockserver-client-go/VERSION
  mockserver-client-dotnet/src/MockServer.Client/MockServer.Client.csproj
  mockserver-client-dotnet/src/MockServer.Client/MockServerBinaryLauncher.cs
  mockserver/mockserver-core/src/main/resources/org/mockserver/openapi/mock-server-openapi-embedded-model.yaml
  jekyll-www.mock-server.com/mockserver-openapi.yaml
)
log_info "Updating non-Maven version manifests to $RELEASE_VERSION"
if is_dry_run; then
  log_dry "would: bump ${NON_MAVEN_VERSION_PATHS[*]} to $RELEASE_VERSION"
else
  python3 - "$RELEASE_VERSION" "$REPO_ROOT" << 'PYEOF'
import re, sys, pathlib
new_v, repo_root = sys.argv[1], pathlib.Path(sys.argv[2])

def sub_once(path_rel, pattern, replacement, label):
    p = repo_root / path_rel
    text = p.read_text()
    new_text, n = re.subn(pattern, replacement, text, count=1)
    if n != 1:
        raise SystemExit(f"ERROR: could not find {label} in {path_rel}")
    p.write_text(new_text)
    print(f"  updated: {path_rel}")

# package.json — match the first top-level "version": "..." entry. Nested
# dependency entries are keyed by package name, not literal "version", so
# they won't false-match.
sub_once("mockserver-node/package.json",
         r'("version"\s*:\s*)"[^"]+"', f'\\g<1>"{new_v}"',
         '"version": "..."')
sub_once("mockserver-client-node/package.json",
         r'("version"\s*:\s*)"[^"]+"', f'\\g<1>"{new_v}"',
         '"version": "..."')

# pyproject.toml — line-anchored to avoid matching e.g. `requires-python`.
sub_once("mockserver-client-python/pyproject.toml",
         r'(?m)^version\s*=\s*"[^"]+"', f'version = "{new_v}"',
         '^version = "..."')

# Python binary launcher — _CLIENT_VERSION is the version ensure_binary() downloads
# by default. It is NOT derived from pyproject.toml, so without this bump it froze
# (it sat at 7.1.0 for three minors while every other client tracked the release).
sub_once("mockserver-client-python/mockserver/launcher.py",
         r'_CLIENT_VERSION = "[^"]+"', f'_CLIENT_VERSION = "{new_v}"',
         '_CLIENT_VERSION = "..."')

# PHP binary launcher — same situation. composer.json carries no version field at
# all, so this constant is the only place PHP records which binary it fetches.
sub_once("mockserver-client-php/src/BinaryLauncher.php",
         r"private const DEFAULT_VERSION = '[^']+'",
         f"private const DEFAULT_VERSION = '{new_v}'",
         "DEFAULT_VERSION = '...'")

# Rust — launcher.rs reads env!("CARGO_PKG_VERSION"), so the Cargo.toml package
# version IS the launcher pin. components/client-rust.sh also bumps this at publish
# time, but that commit is explicitly non-fatal on failure and had already silently
# drifted a minor behind; bumping here (where a miss is a hard error) is the guard.
sub_once("mockserver-client-rust/Cargo.toml",
         r'(?m)^version = "[^"]+"', f'version = "{new_v}"',
         '^version = "..."')
# Cargo.lock records the crate's own version; left stale, `cargo --locked` fails.
sub_once("mockserver-client-rust/Cargo.lock",
         r'(?m)^(name = "mockserver-client"\nversion = )"[^"]+"', f'\\g<1>"{new_v}"',
         'mockserver-client version stanza')

# version.rb — Ruby uses single quotes here; mockserver-client.gemspec reads
# MockServer::VERSION from this file, so the gemspec needs no separate bump.
sub_once("mockserver-client-ruby/lib/mockserver/version.rb",
         r"VERSION\s*=\s*'[^']+'", f"VERSION = '{new_v}'",
         "VERSION = '...'")

# Go client — VERSION file is embedded at compile time via //go:embed.
# The file contains only the version string (plus a trailing newline).
go_version_file = repo_root / "mockserver-client-go" / "VERSION"
go_version_file.write_text(new_v + "\n")
print(f"  updated: mockserver-client-go/VERSION")

# .NET client — the <Version> in the csproj is the version that actually SHIPS: the launcher reads
# AssemblyInformationalVersionAttribute (derived from this property) FIRST and only falls back to the
# constant below. components/client-dotnet.sh also sets it at publish time, but that is late and this
# is the value the consistency check asserts on every build, so bump it here too. Re-bumping to the
# same value later is a harmless no-op.
sub_once("mockserver-client-dotnet/src/MockServer.Client/MockServer.Client.csproj",
         r'<Version>[^<]+</Version>', f'<Version>{new_v}</Version>',
         '<Version>...</Version>')

# .NET client — FallbackVersion constant in MockServerBinaryLauncher.cs.
# The primary DefaultVersion is derived from the assembly version at runtime
# (set by <Version> in the csproj, bumped by client-dotnet.sh), but the
# FallbackVersion safety net must also track.
sub_once("mockserver-client-dotnet/src/MockServer.Client/MockServerBinaryLauncher.cs",
         r'private const string FallbackVersion = "[^"]+"',
         f'private const string FallbackVersion = "{new_v}"',
         'FallbackVersion = "..."')

# OpenAPI spec — the info.version field in the embedded YAML. Must be bumped
# in prepare (before the parallel publish group runs), not in finalize, so
# that swaggerhub.sh uploads a spec whose body matches its registry version
# label. Pattern is anchored to the indented `  version:` form to avoid
# false-matching other YAML keys.
sub_once("mockserver/mockserver-core/src/main/resources/org/mockserver/openapi/mock-server-openapi-embedded-model.yaml",
         r'(?m)^(\s+)version:\s*[0-9][^\s]*', f'\\g<1>version: {new_v}',
         "  version: X.Y.Z under info:")

# Full public OpenAPI spec on the website — the single source of truth the Postman /
# Bruno collections are generated from (scripts/collections/generate_collections.py).
# postman-collection.sh guards that this info.version == RELEASE_VERSION before it
# publishes. sub_once replaces only the FIRST indented `version:` (info.version);
# later `version:` lines inside example bodies are left untouched.
sub_once("jekyll-www.mock-server.com/mockserver-openapi.yaml",
         r'(?m)^(\s+)version:\s*[0-9][^\s]*', f'\\g<1>version: {new_v}',
         "  version: X.Y.Z under info:")
PYEOF
fi

# Go modules carry the release major in their path (/vN); on a major release
# rewrite every reference to it, so the Go tags pushed later are installable.
GO_MAJOR="${RELEASE_VERSION%%.*}"
GO_MODULE_PATHS=()
if is_dry_run; then
  log_dry "would: rewrite the Go module paths to /v$GO_MAJOR"
  python3 "$SCRIPT_DIR/go_module_major.py" --check "$GO_MAJOR" \
    || log_dry "the rewrite above would change the references listed"
else
  log_info "Setting the Go module paths to /v$GO_MAJOR"
  go_changed="$(python3 "$SCRIPT_DIR/go_module_major.py" --set "$GO_MAJOR")"
  while IFS= read -r path; do
    if [[ -n "$path" ]]; then
      GO_MODULE_PATHS+=("$path")
      log_info "  updated: $path"
    fi
  done <<< "$go_changed"
fi

git_commit_and_push "release: set version $RELEASE_VERSION" \
  mockserver/ \
  examples/java/pom.xml \
  "${NON_MAVEN_VERSION_PATHS[@]}" \
  "${GO_MODULE_PATHS[@]+"${GO_MODULE_PATHS[@]}"}"
git_tag_and_push "mockserver-$RELEASE_VERSION"

log_info "Prepare complete"
