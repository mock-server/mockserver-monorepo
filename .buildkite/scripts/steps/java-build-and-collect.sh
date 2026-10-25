#!/usr/bin/env bash
#
# Runs the :maven: build, then collects failing-test artefacts and prints an
# end-of-log pass/fail summary. The collector NEVER fails the build, and the
# build's own exit code is preserved and re-raised so a red build stays red.
#
# Wiring lives in a script (not inline YAML) so the shell — not Buildkite's
# pipeline-upload interpolation — owns the `$?` / `$rc` expansion.

set -uo pipefail   # NOTE: deliberately no `set -e` — we want to run the
                   # collector and re-raise the build's exit code even when the
                   # build fails.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

"$SCRIPT_DIR/java-build.sh"
rc=$?

# Always collect failing-test artefacts + print the summary, even on a failed
# build. The collector exits 0 on its own, but `|| true` is belt-and-braces so
# a collector crash can never turn a green build red (or mask the real rc).
"$SCRIPT_DIR/java-collect-failures.sh" || true

# ──────────────────────────────────────────────────────────────────────
# Fail closed if the HTTP/3 (QUIC) integration suite silently skipped.
#
# The Http3*IntegrationTest suites (mockserver-netty) are Failsafe ITs, so the
# main build's `clean install` already runs them in the verify phase — they need
# NO Docker socket, only the native QUIC (BoringSSL) transport, which is bundled
# in the mockserver/mockserver:maven image (Quic.isAvailable() is true on its
# linux-x86_64 JVM). That is WHY this is asserted here over the reports the main
# build already produced, rather than in a dedicated re-run step like the
# socket-gated async/cloud/testcontainers suites: those DON'T run in the main
# build (no socket) so they need their own step; http3 DOES, so re-running it
# would only duplicate the largest module's build and risk a new flaky red.
#
# But each suite is guarded by `Assume.assumeTrue(Quic.isAvailable())` and skips
# gracefully when the native cannot load — so if a future change (native stripped
# from the image/uber-jar, an unsupported agent arch, a netty classifier break)
# made QUIC unavailable, every http3 test would SKIP and the build would stay
# green having tested no HTTP/3 at all. assert-suite-ran.sh turns that silent skip
# into a loud failure (it also fails if the reports are absent, i.e. the suite did
# not run at all). Only checked on a green build (rc==0): a failed build is
# already red, and piling a report-absence error on top would only obscure the
# real cause. When the netty integration tests run in the shard steps instead
# (MOCKSERVER_NETTY_ITS_IN_SHARDS), the rest shard makes this check.
if [ "$rc" -eq 0 ] && [ "${MOCKSERVER_NETTY_ITS_IN_SHARDS:-false}" != "true" ]; then
  # the HTTP/3 differential corpus is named as well, so renaming it out of the first glob cannot drop it
  "$SCRIPT_DIR/assert-suite-ran.sh" \
    'mockserver/mockserver-netty/target/failsafe-reports/TEST-*Http3*IntegrationTest.xml' \
    'mockserver/mockserver-netty/target/failsafe-reports/TEST-*Http3DataPlaneDifferentialCorpusIntegrationTest.xml' \
    || rc=$?
fi

exit "$rc"
