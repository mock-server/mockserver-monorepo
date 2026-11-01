#!/usr/bin/env bash
# Dashboard end-to-end tests: drive the SERVED dashboard in a real browser
# (headless Chromium via Playwright) against a REAL MockServer over real REST and
# a real WebSocket — the browser-level backstop the jsdom/vitest suite cannot
# provide. Covers every dashboard area (Mock, Observe, Verify, Resilience,
# Library / Inspect, the shell) against the live control plane and the live
# WebSocket log stream.
#
# Runs two Playwright suites: the served-dashboard suite described below, and
# the Panel scroll-anchoring regression, which drives the real Panel +
# ProgressiveList over its own Vite harness and needs no server.
#
# Hard CI gate — a failure in EITHER blocks master (fail-closed: Playwright exits
# non-zero on any failure AND when zero tests are found, and in CI the
# no-silent-skip reporter fails the run if any test skips other than a
# test.fixme for a known open defect).
#
# Topology (same-origin, mirroring how a user runs the dashboard; identical to a
# local `npm run test:e2e`):
#   1. Build the CURRENT runnable JAR (the build-ui Maven profile bundles the
#      current UI source into it) using the Maven CI image.
#   2. Stage a JRE from the Temurin image into the workspace, because the
#      Playwright image has none.
#   3. Run the Playwright image (Chromium bundled). Its config boots the JAR
#      itself inside that container (e2e/start-mockserver.mjs): the main server
#      under test and a secondary server (proxied upstream, metrics on), and the
#      observe spec boots a short-lived third one. Everything is on 127.0.0.1, so
#      a server can forward to an upstream the test runner itself listens on.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
cd "$REPO_ROOT"

# --- 1. Build the current runnable JAR (bundles the current UI) -------------
echo "--- :maven: Building the runnable MockServer JAR (bundles current dashboard)"
# -m 12g as in maven-plugin-build.sh: the 6g Maven heap plus the vite and shaded-jar probe JVMs it forks.
"$SCRIPT_DIR/../run-in-docker.sh" \
  -i mockserver/mockserver:maven \
  -m 12g \
  --cache maven \
  -w /build/mockserver \
  -- ./mvnw -q clean install -DskipTests -DskipITs -pl mockserver-netty-no-dependencies -am

# newest runnable jar (exclude -sources/-javadoc/original- variants); find+sort avoids `ls | grep` (SC2010)
JAR=$(find "$REPO_ROOT/mockserver/mockserver-netty-no-dependencies/target" -maxdepth 1 -type f \
  -name 'mockserver-netty-no-dependencies-*.jar' \
  ! -name '*-sources*' ! -name '*-javadoc*' ! -name 'original-*' \
  -printf '%T@\t%p\n' 2>/dev/null | sort -rn | head -1 | cut -f2-)
if [ -z "$JAR" ]; then
  echo "ERROR: runnable JAR not found after build"
  exit 1
fi
JAR_REL="${JAR#"$REPO_ROOT"/}"
echo "--- Built $JAR_REL"

# --- 2. Stage a JRE for the Playwright container -----------------------------
# The -noble Temurin build matches the Playwright -noble image's Ubuntu, so the
# JRE's glibc and native libraries load there unchanged.
JRE_REL=".tmp/ui-e2e-jre"
rm -rf "${REPO_ROOT:?}/$JRE_REL"
mkdir -p "$REPO_ROOT/.tmp"
echo "--- :java: Staging a JRE for the Playwright container"
"$SCRIPT_DIR/../run-in-docker.sh" \
  -i eclipse-temurin:17-jre-noble \
  -- cp -a /opt/java/openjdk "/build/$JRE_REL"

# --- 3. Run the Playwright browser tests ------------------------------------
# Derive the Playwright image tag from the @playwright/test version in the
# lockfile so the bundled Chromium always matches the installed Playwright (a
# hardcoded tag drifts the moment Dependabot bumps @playwright/test).
PW_VERSION=$(python3 -c "import json;print(json.load(open('mockserver-ui/package-lock.json'))['packages']['node_modules/@playwright/test']['version'])")
echo "--- :playwright: Using Playwright image v${PW_VERSION}-noble (from mockserver-ui/package-lock.json)"

"$SCRIPT_DIR/../run-in-docker.sh" \
  -i "mcr.microsoft.com/playwright:v${PW_VERSION}-noble" \
  -w /build/mockserver-ui \
  --cache npm \
  -e "E2E_JAVA=/build/$JRE_REL/bin/java" \
  -e "E2E_MS_JAR=/build/$JAR_REL" \
  -e "CI=true" \
  -- bash -c '
    set -uo pipefail
    npm ci || exit 1
    # Two browser suites, both hard gates, run independently so one failing
    # still yields the other'"'"'s result instead of masking it.
    #   test:e2e        — the served dashboard against the JAR built above.
    #   test:e2e:anchor — the Panel scroll-anchoring regression (e2e/anchor-harness).
    #                     It needs no server: the defect is a pure client-side
    #                     virtualization/scroll interaction, and jsdom structurally
    #                     cannot reproduce it (no layout engine, so scrollTop /
    #                     scrollHeight / offsetHeight are always 0 and the list
    #                     never windows). Real browser or no coverage at all.
    npm run test:e2e; RC_MAIN=$?
    npm run test:e2e:anchor; RC_ANCHOR=$?
    echo "--- e2e exit codes: dashboard=$RC_MAIN anchor=$RC_ANCHOR"
    [ "$RC_MAIN" -eq 0 ] && [ "$RC_ANCHOR" -eq 0 ]
  '

# --- 4. Fail closed unless every expected spec actually ran -------------------
# Playwright still passes when a spec drops out of testMatch, is renamed, or
# loses most of its tests to test.fixme. Assert from the JUnit XML that each
# spec below ran tests and that the total that ran is not below the floor.
# Lower the floor or edit the list only when tests are removed on purpose.
E2E_MIN_TESTS=236
E2E_SPECS=(dashboard.spec.ts dashboard-follow.spec.ts dashboard-live-scroll.spec.ts
  library.spec.ts mock.spec.ts observe.spec.ts resilience.spec.ts shell.spec.ts verify.spec.ts)
echo "--- :white_check_mark: Checking every e2e spec ran (floor ${E2E_MIN_TESTS} tests)"
python3 - "$REPO_ROOT/mockserver-ui/test-reports/e2e-results.xml" "$E2E_MIN_TESTS" "${E2E_SPECS[@]}" <<'PY'
import sys
import xml.etree.ElementTree as ET

report, floor, specs = sys.argv[1], int(sys.argv[2]), sys.argv[3:]
try:
    suites = {s.get('name'): s for s in ET.parse(report).getroot().iter('testsuite')}
except (OSError, ET.ParseError) as e:
    sys.exit(f"+++ :bangbang: cannot read {report}: {e} - the e2e suite did not run")
problems = []
total_ran = 0
for name, suite in suites.items():
    total_ran += int(suite.get('tests', 0)) - int(suite.get('skipped', 0))
for spec in specs:
    suite = suites.get(spec)
    ran = 0 if suite is None else int(suite.get('tests', 0)) - int(suite.get('skipped', 0))
    print(f"{spec}: {ran} ran")
    if ran <= 0:
        problems.append(f"{spec} ran no tests")
if total_ran < floor:
    problems.append(f"{total_ran} tests ran, below the floor of {floor}")
if problems:
    sys.exit("+++ :bangbang: " + "; ".join(problems))
print(f"{total_ran} tests ran across {len(suites)} spec files")
PY
