#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=.buildkite/scripts/lib/last-successful-commit.sh
source "$SCRIPT_DIR/lib/last-successful-commit.sh"
# shellcheck source=.buildkite/scripts/lib/snapshot-image-inputs.sh
source "$SCRIPT_DIR/lib/snapshot-image-inputs.sh"

DEFAULT_BRANCH="${BUILDKITE_PULL_REQUEST_BASE_BRANCH:-}"
if [ -z "$DEFAULT_BRANCH" ]; then
  DEFAULT_BRANCH=$(git symbolic-ref refs/remotes/origin/HEAD 2>/dev/null | sed 's@^refs/remotes/origin/@@' || true)
fi
DEFAULT_BRANCH=${DEFAULT_BRANCH:-master}

trigger_all_pipelines() {
  echo "--- :warning: Cannot determine change base — triggering all pipelines"
  CHANGED_FILES=$(git ls-tree -r --name-only HEAD 2>/dev/null || echo "mockserver/")
}

if [ -n "${BUILDKITE_PULL_REQUEST_BASE_BRANCH:-}" ]; then
  MERGE_BASE=$(git merge-base HEAD "origin/${DEFAULT_BRANCH}" 2>/dev/null || echo "HEAD~1")
  CHANGED_FILES=$(git diff --name-only "$MERGE_BASE"..HEAD 2>/dev/null || git diff-tree --no-commit-id --name-only -r HEAD)
else
  LAST_COMMIT=""
  if [ -n "${BUILDKITE:-}" ]; then
    echo "--- :buildkite: Querying last successful build commit"
    LAST_COMMIT=$(last_successful_commit || true)
  fi

  if [ -n "$LAST_COMMIT" ]; then
    echo "    Diffing against last successful build: ${LAST_COMMIT:0:10}"
    CHANGED_FILES=$(git diff --name-only "$LAST_COMMIT"..HEAD 2>/dev/null)
    if [ -z "$CHANGED_FILES" ]; then
      CHANGED_FILES=$(git diff-tree --no-commit-id --name-only -r HEAD 2>/dev/null || true)
    fi
  elif [ -n "${BUILDKITE:-}" ]; then
    trigger_all_pipelines
  else
    CHANGED_FILES=$(git diff-tree --no-commit-id --name-only -r HEAD 2>/dev/null || git diff --name-only HEAD~1..HEAD)
  fi
fi

STEPS=""

# --- Native trigger vs command-step trigger (hybrid) -------------------------
# A native Buildkite `trigger` step runs inside the Buildkite backend and
# occupies NO agent. The alternative — a `command` step that runs
# trigger-pipeline.sh — holds an agent for the child build's entire duration
# (it polls in a loop), which saturates the small agent cap when several
# dispatcher builds run at once. So we prefer the native step.
#
# BUT the native step is only safe for NON-PR (push) builds. A native trigger
# step creates the child build as the *parent build's author*, inheriting that
# author's Buildkite permissions. Bot-authored PRs (e.g. Dependabot) have no
# Buildkite permissions, so a native trigger step fails SILENTLY for them —
# the child build is simply never created and nothing turns red. The
# command/script path avoids this because trigger-pipeline.sh authenticates
# with the pipeline's own API token and passes the PR metadata explicitly.
# This exact hybrid was adopted and reverted twice for this reason
# (23c51bab8, 553784bf3); DO NOT "simplify" it into an unconditional native
# trigger or you will silently break every bot PR again.
#
# The hazard above is about the build's AUTHOR, but this gate used to test only
# whether the build was a PR — and a merged Dependabot PR is neither. It lands on
# master as a PUSH build (BUILDKITE_PULL_REQUEST="false") still authored by
# dependabot[bot], so it took the native path and walked straight into the trap
# the comment describes. Observed on orchestrator builds 7357, 7358, 7359 and
# 7361: every one authored by dependabot[bot] with `creator: None`, and on each
# of them the SAME six trigger steps (Java, Go, .NET, Rust, PHP, Infra) failed in
# under half a second with `triggered_build: null` and no child build created,
# while the human-authored build 7356 created all thirteen. Those commits were
# reported red having never run a test.
#
# So gate on the thing that actually matters — is there an authenticated
# Buildkite user behind this build whose permissions the child build can inherit?
# BUILDKITE_BUILD_CREATOR is empty exactly when the REST API reports
# `creator: None`, which is the condition under which a native trigger is
# refused. The author check is a belt-and-braces second signal for a bot identity
# that somehow does carry a creator.
#
# BUILDKITE_PULL_REQUEST is the literal string "false" on push builds and the
# PR number otherwise. We take the native path ONLY on an explicit "false"; a
# PR number OR an unset/empty value both fall through to the safe command
# path (the conservative direction — worst case is an agent held, never a
# silently-skipped build). The same conservative direction applies to both new
# conditions: when in doubt, use the command path, which authenticates with the
# pipeline's own API token and therefore cannot be refused for want of a user.
USE_NATIVE_TRIGGER=false
if [ "${BUILDKITE_PULL_REQUEST:-}" = "false" ] \
    && [ -n "${BUILDKITE_BUILD_CREATOR:-}" ] \
    && case "${BUILDKITE_BUILD_AUTHOR:-}" in *'[bot]') false ;; *) true ;; esac; then
  USE_NATIVE_TRIGGER=true
fi

# A server change alters the wire format every client library encodes against,
# and test-fixtures/ is the shared parity corpus every client round-trips. Both
# must run the client conformance suites, or wire drift ships with zero
# non-Java verification (the client pipelines are otherwise gated only on their
# own directory). mockserver-maven-plugin/ is excluded — it has its own
# pipeline and does not define the wire format.
SERVER_OR_FIXTURES_CHANGED=false
# Here-strings and read-to-EOF substitutions, never `printf "$CHANGED_FILES" | grep -q`:
# under pipefail that pipeline can SIGPIPE the writer on a match and silently skip a
# trigger (see Rule 7 in check-false-green-guards.sh).
SERVER_CHANGES="$(grep -E -- "^mockserver/" <<<"$CHANGED_FILES" | grep -vE -- "^mockserver/mockserver-maven-plugin/" || true)"
if [ -n "$SERVER_CHANGES" ]; then
  SERVER_OR_FIXTURES_CHANGED=true
fi
if grep -qE -- "^test-fixtures/" <<<"$CHANGED_FILES"; then
  SERVER_OR_FIXTURES_CHANGED=true
fi

emit_trigger() {
  local pipeline_slug="$1"
  local label="$2"

  if [ "$USE_NATIVE_TRIGGER" = "true" ]; then
    # Native trigger step — occupies no agent.
    #
    # It MUST NOT carry `timeout_in_minutes` or `retry:`: both are rejected by
    # Buildkite config validation on a `trigger` step (HTTP 422 "... is not a
    # valid property on the 'trigger' step") and would fail the whole pipeline
    # UPLOAD, not silently no-op. Dropping `retry` is also correct on its own —
    # that block only guards the agent running the polling script against Spot
    # reclamation, and a native trigger has no agent to reclaim.
    #
    # `async: false` (the default, set explicitly for clarity) makes a failed
    # child fail this parent, and makes cancelling the parent cancel the child.
    # A superseded child reports as `skipped` (neutral), which async:false does
    # not treat as a failure.
    #
    # commit/branch/message are set explicitly to reproduce exactly what
    # trigger-pipeline.sh sends (the parent build's values). The native-step
    # defaults are NOT these — they are HEAD, the child pipeline's default
    # branch, and the trigger step's label respectively. The non-PR path sends
    # no env (trigger-pipeline.sh's ENV_VARS is {} for non-PR builds), so
    # build.env is omitted. `ignore_pipeline_branch_filters` has no native-step
    # equivalent and is unnecessary here: a push build's branch is master, which
    # passes every child pipeline's branch filter.
    #
    # Each value is JSON-encoded with `jq -Rs .` so an arbitrary, multi-line
    # commit message becomes a single valid YAML scalar (JSON strings are valid
    # YAML flow scalars). Every `$` is then doubled so buildkite-agent's
    # pre-upload variable interpolation renders it back to a single literal `$`
    # (`$$` -> `$`) instead of expanding `$FOO` from the agent environment.
    local commit branch message
    commit=$(printf '%s' "${BUILDKITE_COMMIT:-}" | jq -Rs . | sed 's/\$/$$/g')
    branch=$(printf '%s' "${BUILDKITE_BRANCH:-}" | jq -Rs . | sed 's/\$/$$/g')
    message=$(printf '%s' "${BUILDKITE_MESSAGE:-}" | jq -Rs . | sed 's/\$/$$/g')
    STEPS="${STEPS}  - label: \":pipeline: ${label}\"
    trigger: \"${pipeline_slug}\"
    async: false
    build:
      commit: ${commit}
      branch: ${branch}
      message: ${message}
"
  else
    # PR (or unknown) build — keep the polling command step byte-for-byte so the
    # PR auth and bot-PR handling in trigger-pipeline.sh is preserved.
    STEPS="${STEPS}  - label: \":pipeline: ${label}\"
    command: \".buildkite/scripts/trigger-pipeline.sh ${pipeline_slug} '${label}'\"
    timeout_in_minutes: 120
    agents:
      queue: trigger
    retry:
      automatic:
        - exit_status: -1
          limit: 2
"
  fi
}

trigger_if_changed() {
  local path_regex="$1"
  local pipeline_slug="$2"
  local label="$3"
  if grep -qE -- "$path_regex" <<<"$CHANGED_FILES"; then
    echo "--- :pipeline: Triggering ${label} (matched ${path_regex})"
    emit_trigger "$pipeline_slug" "$label"
  fi
}

# Client pipelines additionally trigger on any server or shared-fixture change,
# so wire-format drift is verified against every client library.
trigger_client_if_changed() {
  local path_regex="$1"
  local pipeline_slug="$2"
  local label="$3"
  if grep -qE -- "$path_regex" <<<"$CHANGED_FILES"; then
    echo "--- :pipeline: Triggering ${label} (matched ${path_regex})"
    emit_trigger "$pipeline_slug" "$label"
  elif [ "$SERVER_OR_FIXTURES_CHANGED" = "true" ]; then
    echo "--- :pipeline: Triggering ${label} (server / test-fixtures change — client conformance)"
    emit_trigger "$pipeline_slug" "$label"
  fi
}

# Match changes under mockserver/ excluding the maven-plugin submodule (which has its own pipeline).
# test-fixtures/ is included: the Java model is round-tripped against the same shared corpus.
# The paths live in lib/snapshot-image-inputs.sh, which the perf run reads to tell which commits share a :snapshot image.
# examples/java builds only in mockserver-java, and one of its tests checks the Java on extensions.html
# against it, so either alone runs that pipeline too. Neither is in the :snapshot image, so they stay
# out of JAVA_TRIGGER_REGEX, which also decides which commits share an image.
JAVA_EXAMPLES_TRIGGER_REGEX='^(examples/java/|jekyll-www\.mock-server\.com/mock_server/extensions\.html$)'
JAVA_CHANGES="$(java_trigger_changes <<<"$CHANGED_FILES")"
if [ -n "$JAVA_CHANGES" ]; then
  trigger_if_changed "$JAVA_TRIGGER_REGEX" "mockserver-java" "MockServer Java"
else
  trigger_if_changed "$JAVA_EXAMPLES_TRIGGER_REGEX" "mockserver-java" "MockServer Java"
fi
trigger_if_changed "^mockserver-ui/" "mockserver-ui" "MockServer UI"
# The jekyll copy of the OpenAPI spec is included deliberately: the Node suite asserts it stays
# byte-identical to the copy under mockserver-core (generated_types_drift_test.js). Without this,
# a commit touching ONLY the published spec runs the website pipeline alone, the assertion never
# fires, and the divergence surfaces later on an unrelated change — attributed to the wrong commit.
trigger_client_if_changed "^(mockserver-node/|mockserver-client-node/|mockserver-testcontainers/node/|jekyll-www\.mock-server\.com/mockserver-openapi\.yaml)" "mockserver-node" "MockServer Node"
trigger_client_if_changed "^(mockserver-client-python/|mockserver-testcontainers/python/)" "mockserver-python" "MockServer Python"
trigger_client_if_changed "^mockserver-client-ruby/" "mockserver-ruby" "MockServer Ruby"
trigger_client_if_changed "^(mockserver-client-go/|mockserver-testcontainers/go/)" "mockserver-go" "MockServer Go"
trigger_client_if_changed "^(mockserver-client-dotnet/|mockserver-testcontainers/dotnet/)" "mockserver-dotnet" "MockServer .NET"
trigger_client_if_changed "^(mockserver-client-rust/|mockserver-testcontainers/rust/)" "mockserver-rust" "MockServer Rust"
trigger_client_if_changed "^mockserver-client-php/" "mockserver-php" "MockServer PHP"
trigger_if_changed "^(mockserver-vscode/|mockserver-jetbrains/)" "mockserver-editors" "MockServer Editors"
# Its build step also runs the configuration call-site guard over the plugin, so a
# change to that guard or to the step itself must run it too.
trigger_if_changed "^(mockserver/mockserver-maven-plugin/|\.buildkite/pipeline-maven-plugin\.yml|\.buildkite/scripts/steps/maven-plugin-build\.sh|mockserver/mockserver-netty/src/test/java/org/mockserver/configuration/ConfigurationCallSiteGuardTest\.java)" "mockserver-maven-plugin" "MockServer Maven Plugin"
trigger_if_changed "^mockserver-performance-test/" "mockserver-performance-test" "MockServer Performance Test"
# docker/** routes here too (not only container_integration_tests/**): the
# container-tests Helm step builds docker/Dockerfile and docker/clustered, so a
# Dockerfile/build-context change is actually built (docker-build-verify.sh's
# runtime checks are not wired in yet; see pipeline-container-tests.yml).
# docker/** also triggers mockserver-infra for the static docker-validate-sync lint.
trigger_if_changed "^(container_integration_tests/|docker/)" "mockserver-container-tests" "MockServer Container Tests"
trigger_if_changed "^jekyll-www.mock-server.com/" "mockserver-website" "MockServer Website"
trigger_if_changed "^docker_build/maven/" "mockserver-build-image" "MockServer Build Image"

# examples/ (generated Postman/Bruno collections) and the OpenAPI spec route to
# infra too, so the collections-validate gate catches spec/collection drift.
#
# .claude/ and CLAUDE.md route here as well: .claude/agents/** and .claude/commands/**
# are enumerated AI-component control paths (commit-workflow.md, AGENTS.md), and both
# the opencode config lint and the AI eval gate validate them — without this, a commit
# that only weakens .claude/agents/review-final.md would trigger no pipeline at all.
if grep -qE -- "^(\.buildkite/|\.github/|terraform/|docker/|scripts/|helm/|docs/|examples/|jekyll-www\.mock-server\.com/mockserver-openapi\.yaml|AGENTS\.md|CLAUDE\.md|opencode\.jsonc|\.opencode/|\.claude/)" <<<"$CHANGED_FILES"; then
  echo "--- :pipeline: Triggering MockServer Infra (infra changes)"
  # Same hybrid as every other trigger (native on push, command on PR).
  emit_trigger "mockserver-infra" "MockServer Infra"
fi

# Runs on EVERY build, deliberately outside the path-filtered triggers above. The client version
# pins it guards live in per-client directories (mockserver-client-rust/Cargo.toml,
# mockserver-client-python/mockserver/launcher.py, ...), so a commit that drifts one of them routes
# only to that client's own pipeline. Attaching this to any path filter means the guard never sees
# the change it exists to catch. It is a few greps and costs ~1s.
#
# The false-green guard runs always-on for the same reason: a new false-green shape can be introduced
# from a Java test source (routes to mockserver-java), a .buildkite step script (mockserver-infra), a
# client test step (that client's pipeline) or a container_integration_tests script — no single
# path-filtered pipeline sees them all, so only an always-on step catches every case. Also greps-only,
# ~1s.
#
# The shared-RNG hot-path guard is always-on for the same reason: a new Math.random(), unseeded
# new Random(), UUID.randomUUID() or new SecureRandom() can be introduced from any server-runtime Java
# source (mockserver-core / -netty / -async), and each draws from a shared, contended static generator
# that serialises the worker event loops under load. Three earlier sweeps each fixed one such shape by
# enumerating it once; only an always-on control stops the next one reappearing. Greps-only, ~1s.
#
# The version-reference guard is always-on because a mention of the current release can be added
# to any doc, README or manifest in the repository, which no single path filter covers. ~1s.
ALWAYS_STEPS='  - label: ":package: validate client version pins"
    command: ".buildkite/scripts/steps/clients-version-consistency.sh"
    timeout_in_minutes: 5
    agents:
      queue: trigger
    retry:
      automatic:
        - exit_status: -1   # agent lost (e.g. Spot reclamation)
          limit: 2
        - exit_status: 255  # agent forced shutdown
          limit: 2
  - label: ":detective: guard against false-green test shapes"
    command: ".buildkite/scripts/steps/check-false-green-guards.sh"
    timeout_in_minutes: 5
    agents:
      queue: trigger
    retry:
      automatic:
        - exit_status: -1   # agent lost (e.g. Spot reclamation)
          limit: 2
        - exit_status: 255  # agent forced shutdown
          limit: 2
  - label: ":game_die: guard against new shared-RNG hot-path calls"
    command: ".buildkite/scripts/steps/check-shared-rng-hotpath.sh"
    timeout_in_minutes: 5
    agents:
      queue: trigger
    retry:
      automatic:
        - exit_status: -1   # agent lost (e.g. Spot reclamation)
          limit: 2
        - exit_status: 255  # agent forced shutdown
          limit: 2
  - label: ":hourglass: guard every child step declares a timeout"
    command: ".buildkite/scripts/steps/check-pipeline-step-timeouts.sh"
    timeout_in_minutes: 5
    agents:
      queue: trigger
    retry:
      automatic:
        - exit_status: -1   # agent lost (e.g. Spot reclamation)
          limit: 2
        - exit_status: 255  # agent forced shutdown
          limit: 2
  - label: ":label: guard version references survive the release rewrite"
    command: "scripts/release/test-version-refs.sh"
    timeout_in_minutes: 5
    agents:
      queue: trigger
    retry:
      automatic:
        - exit_status: -1   # agent lost (e.g. Spot reclamation)
          limit: 2
        - exit_status: 255  # agent forced shutdown
          limit: 2
'

if [ -z "$STEPS" ]; then
  echo "--- :pipeline: No project-specific changes detected (running always-on gates)"
  printf "steps:\n%s" "$ALWAYS_STEPS" | buildkite-agent pipeline upload
else
  printf "steps:\n%s%s" "$ALWAYS_STEPS" "$STEPS" | buildkite-agent pipeline upload
fi
