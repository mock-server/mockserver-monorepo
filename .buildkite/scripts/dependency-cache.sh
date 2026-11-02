#!/usr/bin/env bash
# dependency-cache.sh restore|save <maven|gradle>
#
# S3 dependency cache, restored and saved INSIDE the job that uses it; called by
# run-in-docker.sh for each --cache TYPE. Other types are a silent no-op. Always
# exits 0: any failure leaves an empty directory and the build runs cold.
# The rules (fallback, cold-only master saves, OIDC writer role, exclusions) are
# in docs/infrastructure/ci-cd.md -> Dependency Caching.
# Overrides: DEPENDENCY_CACHE_DISABLED=true, DEPENDENCY_CACHE_BUCKET,
# DEPENDENCY_CACHE_MAX_MB, DEPENDENCY_CACHE_WRITER_ROLE_ARN.
set -uo pipefail

ACTION="${1:-}"
CACHE_TYPE="${2:-}"
BUCKET="${DEPENDENCY_CACHE_BUCKET:-mockserver-ci-dependency-cache}"
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-eu-west-2}}"
CHECKOUT="${BUILDKITE_BUILD_CHECKOUT_PATH:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
CACHE_BASE="${CHECKOUT}/.buildkite-cache"
LOCAL_DIR="${CACHE_BASE}/${CACHE_TYPE}"
STATE_FILE="${CACHE_BASE}/.state-${CACHE_TYPE}"
MAX_MB="${DEPENDENCY_CACHE_MAX_MB:-4096}"
WRITER_ROLE_NAME="buildkite-dependency-cache-writer"

log() { echo "[dependency-cache] ${ACTION} ${CACHE_TYPE}: $*" >&2; }
bail() { log "$*"; exit 0; }

case "$ACTION" in restore|save) ;; *) bail "unknown action '${ACTION}' -- skipping" ;; esac
case "$CACHE_TYPE" in maven|gradle) ;; *) exit 0 ;; esac
[[ "${DEPENDENCY_CACHE_DISABLED:-false}" == "true" ]] && bail "disabled by DEPENDENCY_CACHE_DISABLED"
# Release builds must resolve from Maven Central, never from a cache entry.
[[ "${BUILDKITE_AGENT_META_DATA_QUEUE:-}" == "release" || "${BUILDKITE_PIPELINE_SLUG:-}" == mockserver-release* ]] \
  && bail "release build -- cache not used"
command -v aws >/dev/null 2>&1 || bail "aws CLI not installed -- skipping"

# gradle is scoped by pipeline (the pipelines pin different Gradle versions) and
# persisted only for java: only java's nightly can save, so any other scope would
# restore misses forever. maven is one shared superset.
SCOPE=""
if [[ "$CACHE_TYPE" == "gradle" ]]; then
  SCOPE="${BUILDKITE_PIPELINE_SLUG:-}"
  SCOPE="${SCOPE#mockserver-}"
  [[ "$SCOPE" == "java" ]] || exit 0
fi
PREFIX="${CACHE_TYPE}/${SCOPE:+${SCOPE}/}"

hash_stream() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum; else shasum -a 256; fi | cut -d' ' -f1
}

# Hash relative path + content of every lockfile, in a fixed order, so the key is
# identical on every agent whatever order the filesystem returns entries in.
compute_key() {
  local files=() f
  case "$CACHE_TYPE" in
    maven)
      while IFS= read -r -d '' f; do files+=("$f"); done < <(
        cd "$CHECKOUT" && find mockserver -name pom.xml -not -path '*/target/*' \
          -not -path '*/node_modules/*' -print0 2>/dev/null | LC_ALL=C sort -z)
      ;;
    gradle)
      while IFS= read -r -d '' f; do files+=("$f"); done < <(
        cd "$CHECKOUT" && find . -name gradle-wrapper.properties -not -path '*/node_modules/*' \
          -not -path './.buildkite-cache/*' -not -path '*/build/*' -print0 2>/dev/null | LC_ALL=C sort -z)
      ;;
  esac
  [[ ${#files[@]} -eq 0 ]] && return 0
  for f in "${files[@]}"; do
    printf '%s\n' "$f"
    cat "${CHECKOUT}/${f}" 2>/dev/null
  done | hash_stream
}

state_get() { sed -n "s/^$1=//p" "$STATE_FILE" 2>/dev/null | head -1; }

restore() {
  [[ -f "$STATE_FILE" ]] && bail "already restored in this job"
  mkdir -p "$LOCAL_DIR" 2>/dev/null || bail "cannot create ${LOCAL_DIR} -- skipping"
  local key outcome="none" object="" tarball="${LOCAL_DIR}.tar.gz" start=$SECONDS
  key="$(compute_key)"
  [[ -n "$key" ]] || bail "no lockfiles found -- skipping"
  if [[ -n "$(ls -A "$LOCAL_DIR" 2>/dev/null)" ]]; then
    printf 'key=%s\nrestored=%s\n' "$key" "preexisting" >"$STATE_FILE"
    bail "${LOCAL_DIR} is not empty -- leaving it alone"
  fi

  if aws s3 cp "s3://${BUCKET}/${PREFIX}${key}.tar.gz" "$tarball" --region "$REGION" --only-show-errors >/dev/null 2>&1; then
    outcome="exact"; object="${PREFIX}${key}.tar.gz"
  elif [[ "$CACHE_TYPE" == "maven" ]]; then
    object="$(aws s3api list-objects-v2 --bucket "$BUCKET" --prefix "$PREFIX" --region "$REGION" \
      --query 'sort_by(Contents,&LastModified)[-1].Key' --output text 2>/dev/null)"
    if [[ "$object" == "${PREFIX}"*.tar.gz ]] \
       && aws s3 cp "s3://${BUCKET}/${object}" "$tarball" --region "$REGION" --only-show-errors >/dev/null 2>&1; then
      outcome="fallback"
    fi
  fi

  # Extract aside and rename into place, so a failure or a timeout kill mid-extract
  # never leaves a truncated jar where the build would trust it.
  local partial="${LOCAL_DIR}.partial"
  if [[ "$outcome" != "none" ]]; then
    rm -rf "$partial"
    if ! { mkdir -p "$partial" && tar -xzf "$tarball" -C "$partial" 2>/dev/null \
           && rmdir "$LOCAL_DIR" && mv "$partial" "$LOCAL_DIR"; }; then
      log "extracting ${object} failed -- discarding it"
      rm -rf "$partial"; mkdir -p "$LOCAL_DIR"
      outcome="none"
    fi
  fi
  local size=""
  [[ -f "$tarball" ]] && size="$(du -h "$tarball" 2>/dev/null | cut -f1)"
  rm -f "$tarball"
  printf 'key=%s\nrestored=%s\n' "$key" "$outcome" >"$STATE_FILE"
  case "$outcome" in
    none) log "miss for ${PREFIX}${key:0:16}... -- cold build" ;;
    *) log "${outcome} hit: s3://${BUCKET}/${object} (${size:-?}) in $((SECONDS - start))s" ;;
  esac
}

# The writer role's trust policy enforces the same (build_source session tag + sub);
# checking here only avoids building an archive that IAM would refuse.
trusted_master_build() {
  [[ "${BUILDKITE_SOURCE:-}" == "schedule" ]] || return 1
  [[ "${BUILDKITE_BRANCH:-}" == "master" ]] || return 1
  [[ "${BUILDKITE_PULL_REQUEST:-false}" == "false" ]] || return 1
  [[ -z "${BUILDKITE_REFSPEC:-}" ]] || return 1
  return 0
}

writer_role_arn() {
  local account
  if [[ -n "${DEPENDENCY_CACHE_WRITER_ROLE_ARN:-}" ]]; then echo "$DEPENDENCY_CACHE_WRITER_ROLE_ARN"; return 0; fi
  account="$(aws sts get-caller-identity --query Account --output text --region "$REGION")" || return 1
  [[ "$account" =~ ^[0-9]{12}$ ]] || { echo "unexpected account id" >&2; return 1; }
  echo "arn:aws:iam::${account}:role/${WRITER_ROLE_NAME}"
}

last_error() { tail -1 "$1" 2>/dev/null | cut -c1-300; }

CLEANUP=()
save() {
  # Bash also runs the EXIT trap when `timeout` TERMs it (the fixture test proves it),
  # so a kill never leaves the token file or the archive behind.
  trap 'rm -f "${CLEANUP[@]+"${CLEANUP[@]}"}"' EXIT
  trusted_master_build || bail "not a scheduled master build -- read-only"
  [[ -f "$STATE_FILE" ]] || bail "no restore ran in this job -- nothing to save"
  local key restored
  key="$(state_get key)"; restored="$(state_get restored)"
  [[ "$key" =~ ^[0-9a-f]{64}$ ]] || bail "no valid key recorded -- skipping"
  [[ "$restored" == "none" ]] || bail "this job restored a cache (${restored}); only a cold build's tree is saved"
  # A file, not just a directory: run-in-docker creates empty gradle subdirectories.
  [[ -n "$(find "$LOCAL_DIR" -type f -print -quit 2>/dev/null)" ]] || bail "${LOCAL_DIR} holds no files -- nothing to save"

  local object="${PREFIX}${key}.tar.gz"
  if aws s3api head-object --bucket "$BUCKET" --key "$object" --region "$REGION" >/dev/null 2>&1; then
    bail "s3://${BUCKET}/${object} already exists -- skipping"
  fi

  # maven: never cache our own org.mock-server artefacts -- a later partial (-pl)
  # build would resolve a sibling from them instead of from the code under test.
  # gradle: lock files are per-process, transforms are re-derived from cached files.
  local excludes=() tarball="${LOCAL_DIR}.tar.gz" start=$SECONDS size_mb
  CLEANUP+=("$tarball")
  case "$CACHE_TYPE" in
    maven) excludes=(--exclude=./org/mock-server --exclude='*.lastUpdated' --exclude=resolver-status.properties) ;;
    gradle) excludes=(--exclude='*.lock' --exclude=gc.properties --exclude='./caches/*/transforms' --exclude='./caches/transforms-*') ;;
  esac
  if ! tar -czf "$tarball" "${excludes[@]}" -C "$LOCAL_DIR" . 2>/dev/null; then
    bail "tar failed -- not saved"
  fi
  # Bound what is actually uploaded, i.e. after the exclusions.
  size_mb="$(du -m "$tarball" 2>/dev/null | cut -f1)"
  if ! [[ "$size_mb" =~ ^[0-9]+$ ]] || (( size_mb > MAX_MB )); then
    bail "archive is ${size_mb:-?} MB, over the ${MAX_MB} MB bound -- not saved"
  fi

  # The OIDC token goes to a 0600 file, not argv; the CLI assumes the role from it.
  # build_source rides in the token as an AWS session tag; the trust policy requires schedule.
  local err="${LOCAL_DIR}.err" token_file role_arn
  CLEANUP+=("$err")
  token_file="$(umask 077 && mktemp "${TMPDIR:-/tmp}/dependency-cache-oidc.XXXXXX")" || bail "cannot create a token file -- not saved"
  CLEANUP+=("$token_file")
  if ! command -v buildkite-agent >/dev/null 2>&1 \
     || ! buildkite-agent oidc request-token --audience sts.amazonaws.com --lifetime 300 \
            --aws-session-tag build_source >"$token_file" 2>"$err" \
     || [[ ! -s "$token_file" ]] || ! role_arn="$(writer_role_arn 2>>"$err")"; then
    log "no Buildkite OIDC token or writer role ARN: $(last_error "$err")"
    bail "not saved"
  fi
  if env -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN -u AWS_PROFILE \
       AWS_ROLE_ARN="$role_arn" AWS_WEB_IDENTITY_TOKEN_FILE="$token_file" \
       AWS_ROLE_SESSION_NAME="dependency-cache-${BUILDKITE_BUILD_NUMBER:-0}" \
       aws s3 cp "$tarball" "s3://${BUCKET}/${object}" --region "$REGION" --only-show-errors >/dev/null 2>"$err"; then
    log "saved s3://${BUCKET}/${object} (${size_mb} MB) in $((SECONDS - start))s"
  else
    log "upload as ${WRITER_ROLE_NAME} failed -- not saved: $(last_error "$err")"
  fi
}

"$ACTION"
exit 0
