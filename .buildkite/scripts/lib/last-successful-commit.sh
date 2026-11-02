#!/usr/bin/env bash
# Shared helper: resolve the commit SHA of the last PASSED build of a Buildkite
# pipeline, so a build can diff against it for change detection.
#
# Sourced by:
#   - generate-pipeline.sh   (path-based pipeline dispatch)
#   - steps/perf-test-guard.sh (skip the daily perf run when master hasn't moved)
#   - steps/java-snapshot-publish-guard.sh (skip the nightly publish when nothing changed)
#
# Reads the Buildkite API token from AWS Secrets Manager. Echoes the SHA on
# success; returns non-zero (with a `reason:` on stderr) when it cannot
# determine one, so callers can fall back conservatively.
#
# Honours these env vars (all optional, with sensible BUILDKITE_* defaults):
#   BUILDKITE_API_TOKEN_SECRET_ID, AWS_REGION, BUILDKITE_ORGANIZATION_SLUG,
#   BUILDKITE_PIPELINE_SLUG, BUILDKITE_BRANCH, BUILDKITE_BUILD_NUMBER

last_successful_commit() {
  local secret_id="${BUILDKITE_API_TOKEN_SECRET_ID:-mockserver-build/buildkite-api-token-readonly}"
  local region="${AWS_REGION:-eu-west-2}"
  local org="${BUILDKITE_ORGANIZATION_SLUG:-mockserver}"
  local pipeline="${BUILDKITE_PIPELINE_SLUG:-mockserver}"
  local branch="${BUILDKITE_BRANCH:-master}"
  local current_build="${BUILDKITE_BUILD_NUMBER:-}"

  local token
  { set +x; } 2>/dev/null  # F-BK-04: suppress xtrace before secret fetch
  token=$(aws secretsmanager get-secret-value \
    --secret-id "$secret_id" --region "$region" \
    --query SecretString --output text 2>/dev/null) || { echo "    reason: secrets manager unavailable" >&2; return 1; }
  [ -n "$token" ] || { echo "    reason: empty API token" >&2; return 1; }

  local api_base="https://api.buildkite.com/v2/organizations/${org}/pipelines/${pipeline}/builds"

  local response
  response=$(curl -sS --max-time 10 --connect-timeout 5 \
    --get "$api_base" \
    --data-urlencode "branch=${branch}" \
    --data-urlencode "state=passed" \
    --data-urlencode "per_page=10" \
    -H "Authorization: Bearer ${token}" 2>/dev/null) || { echo "    reason: Buildkite API request failed" >&2; return 1; }

  if ! printf '%s' "$response" | jq -e 'type == "array"' >/dev/null 2>&1; then
    echo "    reason: Buildkite API returned non-array response" >&2
    return 1
  fi

  local commit
  if [ -n "$current_build" ] && [[ "$current_build" =~ ^[0-9]+$ ]]; then
    commit=$(printf '%s' "$response" | jq -r \
      --argjson current "$current_build" \
      '[.[] | select(.number < $current)][0].commit // empty' 2>/dev/null)
  else
    commit=$(printf '%s' "$response" | jq -r '.[0].commit // empty' 2>/dev/null)
  fi

  if [ -z "$commit" ]; then
    echo "    reason: no previous successful build found" >&2
    return 1
  fi

  if ! git cat-file -t "$commit" >/dev/null 2>&1; then
    echo "    reason: commit ${commit:0:10} not in local history (shallow clone?)" >&2
    return 1
  fi

  echo "$commit"
}

# Echo the HARNESS commit the perf REGRESSION RUN last executed against, read from
# the perf-test builds' meta-data (`perf_regression_ran_commit`, set by
# perf-test-run.sh). This is deliberately the harness/build-checkout SHA, NOT the
# result's attributed image-revision commit: the guard compares it to the current git
# HEAD (also a harness-checkout SHA) to decide "has master moved since the last run".
# The image revision lags master, so keying on it would make the guard fire every day.
# This is deliberately NOT the last *successful build*: the
# perf-test pipeline passes on the lint step alone on every push, so the last
# successful build is almost always HEAD and would make the daily guard skip
# forever. The meta-data is written only when the heavy run actually executes,
# so it is the true "when did we last run" signal. Returns non-zero (reason on
# stderr) when none can be determined — callers then run conservatively.
last_perf_run_commit() {
  local secret_id="${BUILDKITE_API_TOKEN_SECRET_ID:-mockserver-build/buildkite-api-token-readonly}"
  local region="${AWS_REGION:-eu-west-2}"
  local org="${BUILDKITE_ORGANIZATION_SLUG:-mockserver}"
  local pipeline="${BUILDKITE_PIPELINE_SLUG:-mockserver-performance-test}"
  local branch="${BUILDKITE_BRANCH:-master}"

  local token
  { set +x; } 2>/dev/null # suppress xtrace before secret fetch
  token=$(aws secretsmanager get-secret-value \
    --secret-id "$secret_id" --region "$region" \
    --query SecretString --output text 2>/dev/null) || { echo "    reason: secrets manager unavailable" >&2; return 1; }
  [ -n "$token" ] || { echo "    reason: empty API token" >&2; return 1; }

  local response
  response=$(curl -sS --max-time 10 --connect-timeout 5 \
    --get "https://api.buildkite.com/v2/organizations/${org}/pipelines/${pipeline}/builds" \
    --data-urlencode "branch=${branch}" \
    --data-urlencode "per_page=50" \
    -H "Authorization: Bearer ${token}" 2>/dev/null) || { echo "    reason: Buildkite API request failed" >&2; return 1; }

  printf '%s' "$response" | jq -e 'type == "array"' >/dev/null 2>&1 || { echo "    reason: non-array API response" >&2; return 1; }

  # Builds come newest-first; take the most recent that recorded a run commit.
  local commit
  commit=$(printf '%s' "$response" | jq -r '[.[] | .meta_data.perf_regression_ran_commit // empty][0] // empty' 2>/dev/null)
  [ -n "$commit" ] || { echo "    reason: no prior perf run recorded" >&2; return 1; }
  echo "$commit"
}

# Echo the commit the last snapshot publish pushed: the newest mockserver-java build on the
# branch carrying `snapshot_published_commit` meta-data, which pipeline-java.yml sets only after
# the Sonatype deploy and the image push both pass. Returns non-zero (reason on stderr) when
# none is found, so the caller publishes.
last_snapshot_publish_commit() {
  local secret_id="${BUILDKITE_API_TOKEN_SECRET_ID:-mockserver-build/buildkite-api-token-readonly}"
  local region="${AWS_REGION:-eu-west-2}"
  local org="${BUILDKITE_ORGANIZATION_SLUG:-mockserver}"
  local pipeline="${BUILDKITE_PIPELINE_SLUG:-mockserver-java}"
  local branch="${BUILDKITE_BRANCH:-master}"

  local token
  { set +x; } 2>/dev/null # suppress xtrace before secret fetch
  token=$(aws secretsmanager get-secret-value \
    --secret-id "$secret_id" --region "$region" \
    --query SecretString --output text 2>/dev/null) || { echo "    reason: secrets manager unavailable" >&2; return 1; }
  [ -n "$token" ] || { echo "    reason: empty API token" >&2; return 1; }

  local response
  response=$(curl -sS --max-time 10 --connect-timeout 5 \
    --get "https://api.buildkite.com/v2/organizations/${org}/pipelines/${pipeline}/builds" \
    --data-urlencode "branch=${branch}" \
    --data-urlencode "per_page=100" \
    -H "Authorization: Bearer ${token}" 2>/dev/null) || { echo "    reason: Buildkite API request failed" >&2; return 1; }

  printf '%s' "$response" | jq -e 'type == "array"' >/dev/null 2>&1 || { echo "    reason: non-array API response" >&2; return 1; }

  local commit
  commit=$(printf '%s' "$response" | jq -r '[.[] | .meta_data.snapshot_published_commit // empty][0] // empty' 2>/dev/null)
  [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || { echo "    reason: no snapshot publish recorded in the last 100 builds" >&2; return 1; }
  echo "$commit"
}
