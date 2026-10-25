#!/usr/bin/env bash
#
# Runs one shard of mockserver-netty's integration tests (see scripts/buildkite_netty_it.sh
# and the netty-it-shard-* profiles in mockserver-netty/pom.xml) on the archives built by
# the ":maven: netty IT prebuild" step, then collects failing-test artefacts and, on any
# exit, reports SharedEchoServerGuard firings. The shard's own exit code is preserved, so
# a failing shard fails its step and the build.
#
# Usage: java-netty-it-shard.sh <proxy-http|mock|rest>

set -uo pipefail   # no -e: collect failures and re-raise the shard's exit code

SHARD="${1:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/../../.." || exit 1

echo "--- :buildkite: Downloading the prebuild archives"
if ! buildkite-agent artifact download --step "${NETTY_IT_PREBUILD_STEP:-netty-it-prebuild}" \
    "mockserver/target/netty-it/*.tar*" .; then
  echo "+++ :bangbang: could not download the netty IT prebuild archives" >&2
  exit 1
fi

trap '"$SCRIPT_DIR/java-netty-it-echo-guard-report.sh" "$SHARD" || true' EXIT

echo "--- :maven: mockserver-netty integration tests, shard ${SHARD}"
# -m 12g as the reactor build, so each test fork gets the same default heap there.
"$SCRIPT_DIR/../run-in-docker.sh" \
  -i mockserver/mockserver:maven \
  -m 12g \
  --cache maven \
  -- /build/scripts/buildkite_netty_it.sh shard "$SHARD"
rc=$?

"$SCRIPT_DIR/java-collect-failures.sh" || true

# The HTTP/3 suites skip, rather than fail, when the native QUIC transport cannot load;
# fail closed if they did (see the same check's history in java-build-and-collect.sh).
# The data-plane differential corpus must run too, or the data plane is unguarded.
# Both are in the rest shard, so a rebalance that moves them must move this check too.
if [ "$rc" -eq 0 ] && [ "$SHARD" = "rest" ]; then
  "$SCRIPT_DIR/assert-suite-ran.sh" \
    'mockserver/mockserver-netty/target/failsafe-reports/TEST-*Http3*IntegrationTest.xml' \
    'mockserver/mockserver-netty/target/failsafe-reports/TEST-*NettyDataPlaneDifferentialCorpusIntegrationTest.xml' \
    || rc=$?
fi

exit "$rc"
