#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Fail closed when a NEW "false-green" test-shape is introduced — a test or
# CI step that reports success while verifying nothing.
#
# WHY THIS EXISTS
#
# The 2026-07-21 coverage audit classified its findings by the *shape* of the
# false green — the recognisable pattern of a test or gate that passes having
# proven nothing. Those categories lived only in plan documents, so nothing
# stopped them recurring; the repository then produced roughly a dozen fresh
# instances in a single day. This guard turns the highest-value,
# mechanically-checkable shapes into a standing control.
#
# It is deliberately NARROW. A noisy grep everyone learns to ignore is worse than
# nothing, so it enforces only patterns that can be pinned down precisely and that
# each caused a real, shipped false green. The heuristic categories from the audit
# (self-derived golden fixtures; a guard whose own precondition can silently fail)
# are NOT enforced here — they have no low-false-positive textual signature. See
# docs/operations/false-green-guards.md for the full rationale, the declined rules,
# and how to add an allow-list entry.
#
# RULES ENFORCED
#
#   Rule 1 — Docker-gated suite with no fail-closed CI assertion.
#     Every JUnit suite gated by
#       Assume.assumeTrue(DockerAvailability.isAvailable(...))
#     reports SKIPPED (Maven exits 0) when Docker is unusable, so a CI step that
#     only checks Maven's exit code goes green having tested nothing. AGENTS.md
#     already mandates pairing each such suite with assert-suite-ran.sh over its
#     surefire/failsafe reports. This asserts every Docker-gated suite's class is
#     named by some assert-suite-ran glob FOR THE SUITE'S OWN MAVEN MODULE — the
#     match is module-scoped, not class-name-only, so a gated suite in an unpaired
#     module cannot be reported covered merely because its class-name suffix is
#     shared by a glob belonging to a different module. It also asserts no glob is
#     left dangling (naming a suite that no longer exists).
#
#   Rule 2 — a step that mounts the Docker socket then deselects Docker tests.
#     A CI step that grants the Docker socket (run-in-docker.sh -s/--docker-socket)
#     but then runs the suite with the Docker-marked tests deselected (e.g.
#     `pytest -m "not docker"`) starts no container and passes green. This is the
#     exact defect that shipped for the python client. The contradiction — pay to
#     mount the socket, then skip everything that needs it — is the signal.
#
#   Rule 3 — a deferred-work skip that reads as green.
#     A container-integration skip helper (logTestSkip) invoked with deferral
#     language ("CI wiring is a follow-up", TODO, pending, ...) parks work that
#     was never done while the job stays green. The WAR case skipped this way for
#     months. This flags skips whose message defers work rather than declaring a
#     case genuinely N/A.
#
#   Rule 4 — a helm/k3d harness run that can silently revert to skipping.
#     The image-dependent k3d cases (helm_sidecar_injection, helm_clustered_
#     convergence, helm_jgroups_dns_ping) only fail closed on a missing image when
#     the step that runs the harness exports BOTH REQUIRE_CLUSTERED_IMAGE=true and
#     REQUIRE_WEBHOOK_IMAGE=true; delete those exports and CI silently records a
#     SKIP — green, testing nothing. This asserts every step that runs the harness
#     with helm tests active (invokes integration_tests.sh and does NOT set
#     SKIP_HELM_TESTS=true) exports both flags. Keyed on that BEHAVIOUR, not on the
#     filename helm-integration-test.sh, so a rename or a second helm-running step
#     is covered automatically and the docker-only harness step (which sets
#     SKIP_HELM_TESTS=true) is correctly exempt.
#
#   Rule 5 — a JVM-global logging side effect in mockserver-core's parallel phase.
#     LogManager.getLogManager().readConfiguration(...) performs a JVM-global
#     reset() that strips every handler from every logger; LogManager is not
#     classloader-isolated. A mockserver-core test that reaches that reset — by
#     forcing a fresh <clinit> of MockServerLogger/ConfigurationProperties via an
#     isolated classloader, or by calling the static logging setters — races the
#     log capture of any concurrently-running test, silently zeroing a capture (a
#     failing test) or falsely passing a silence assertion (a false green). Such a
#     class MUST be in the sequential-includes list in mockserver-core/pom.xml.
#     ParallelStaticStateGuardTest cannot catch this: it only checks classes
#     EXCLUDED from the parallel phase, and these are in neither list. This asserts
#     every core test source matching a (rare) global-logging signature is
#     sequential. See the signature list below for what it deliberately does NOT
#     catch (static-import call sites; other modules).
#
#   Rule 6 — the Dependabot auto-merge workflow's acceptance paths must stay
#     narrow. .github/workflows/dependabot-auto-merge.yml merges Dependabot PRs
#     with NO human in the loop; six load-bearing properties keep majors and
#     version bumps out, and each is easy to "tidy away" in a later edit. This
#     parses the acceptance regexes out of the workflow and asserts:
#     (6a) DIGEST_BRANCH_REGEX stays anchored (^…$), confined to dependabot/
#     docker/, keeps the literal /distroless/ segment, and ends in a hex-run
#     floor of >=7 — the /distroless/ scope is load-bearing, not cosmetic:
#     without it a date-tagged bump ("bump ubuntu from 202401151200 to 202402201200",
#     whose date tokens are all hex) satisfies the title regex and would merge as
#     a VERSION change, the scope being the only thing that rejects it; (6b)
#     DIGEST_TITLE_REGEX requires a >=7 hex run on BOTH the from and to sides;
#     (6c) path B still cross-checks the title as a conjunct of the branch shape,
#     failing closed on mismatch (the two-signal AND cannot collapse to the
#     branch alone); (6d) every group name in .github/dependabot.yml that path
#     A's GROUP_BRANCH_REGEX accepts declares update-types ⊆ {minor,patch}, so a
#     major can never ride a branch literally named *-minor-and-patch; (6e) path C
#     (Dependabot SECURITY updates) keeps its EXPLICIT semver major-exclusion —
#     VERSION_PAIR_REGEX still requires DOTTED-NUMERIC (non-hex) from/to versions,
#     and the path-C block still compares the major components and fails closed on
#     a mismatch (plus the 0.x-minor special case). Grouping cannot supply this
#     bound (GitHub ignores update-types for security groups), so if the major
#     conjunct is deleted or the regex drifts, a major security bump would silently
#     auto-merge; (6f) path B's GROUPED form (the distroless-digests group, whose
#     title carries no shas) keeps DIGEST_GROUP_BRANCH_REGEX anchored with its
#     literal /distroless-digests/distroless/ segment and >=7 hex tail,
#     DIGEST_GROUP_TITLE_REGEX anchored and distroless-only, the digest_only_diff
#     check as a fail-closed conjunct, and the group's patterns distroless/ only.
#     Fails closed if the workflow is missing, a regex cannot be
#     extracted, path B or path C cannot be located, or no group is accepted by
#     path A (an empty sweep is the specific shape this file exists to catch). NO
#     allow-list by design — none of these six has a legitimate exemption (a group
#     in a branch named minor-and-patch must never carry a major; a security PR must
#     never auto-merge across a major), so every violation is a real regression, not
#     a case to wave through.
#
#   Rule 7 — a pipe into an early-exit grep in a pipefail shell script.
#     Under pipefail, `writer | grep -q` fails when grep exits on its first match
#     while the writer still has output (SIGPIPE): a false RED in `if !`, a false
#     GREEN in `if`. Scope: tracked *.sh that set pipefail, all of .buildkite/ and
#     scripts/, and GitHub workflows/actions. The detector is self-tested against
#     check-false-green-guards.rule7-fixture on every run.
#
#   Rule 8 — a test assertion that accepts more than one HTTP status.
#     not(is(401)), anyOf(is(200), is(404)), isIn(200, 404), status < 300,
#     .not.toBe(404), `in (200, 404)` and the like pass when the endpoint answers an
#     unexpected status, including a 500 — the shape that hid a real dashboard 500.
#     Scope: Java/Kotlin/Groovy, JS/TS, Python and Go test sources. A genuinely
#     tolerant assertion carries an inline `status-tolerance-ok: <reason>` comment.
#     The detector is self-tested against check-false-green-guards.rule8-fixtures/.
#
# ALLOW-LISTS
#
# Some Docker-gated files are legitimately NOT assert-suite-ran-paired (the probe's
# own unit test). Each allow-list entry is verified to STILL be the thing it claims
# (mirroring check-certificate-expiry.sh's expired-fixture assertion): an entry that
# names something that no longer exists, or that is no longer exempt for the reason
# claimed, FAILS the build so the allow-list can never quietly mask a regression.
#
# Requires: git, grep, sed, awk. Runs directly on the agent (no Docker). Bash 3.2 safe.
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO_ROOT"

errors=0

# ══════════════════════════════════════════════════════════════════════
# Rule 1 — every Docker-gated JUnit suite must be assert-suite-ran-paired
# ══════════════════════════════════════════════════════════════════════

# Files that are Docker-gated but legitimately NOT paired with assert-suite-ran.
# Format: each entry is "<repo-relative .java path>". Justify every entry below.
# The rot check asserts each still exists, still references the probe, and is still
# NOT a real gated suite — so an entry can never silently mask a suite that needs
# coverage.
#
#   DockerAvailabilityTest — the probe's OWN unit test. It calls
#   DockerAvailability.isAvailable() with stubbed BooleanSuppliers to prove the
#   wrapper's try/catch behaviour; it never starts a container and carries no
#   Assume gate, so it runs in the ordinary build and needs no CI re-run assertion.
R1_ALLOWLIST=(
  "mockserver/mockserver-testing/src/test/java/org/mockserver/test/DockerAvailabilityTest.java"
)

r1_is_allowlisted() {
  local path="$1" entry
  for entry in "${R1_ALLOWLIST[@]}"; do
    [ "$path" = "$entry" ] && return 0
  done
  return 1
}

echo "--- :whale: Rule 1: Docker-gated suites must be assert-suite-ran-paired"

# Coverage patterns: the (module, class-name glob) pair from every assert-suite-ran
# report glob. assert-suite-ran arguments are single-quoted "<module-path>/target/
# <phase>-reports/TEST-<glob>.xml" tokens, each on its own line. Requiring the
# SINGLE-QUOTED "-reports/TEST-...xml" form (and dropping comment lines) is what
# distinguishes a real assertion from an example in a docstring or the failure-
# collector's own "TEST-*.xml" scan glob — extracting those would silently broaden
# coverage.
#
# The match MUST be module-scoped, not class-name-only. The <module-path> appears
# in two forms — "mockserver-blob-azure/target/..." (script runs from within
# mockserver/) and "mockserver/mockserver-netty/target/..." (from repo root) — so
# the correlation key is the Maven module DIRECTORY NAME: the last path segment
# before "/target/", which is identical in both forms. Each PATTERNS line is
# "<module>\t<glob>" (leading TEST- and trailing .xml removed from the glob) so a
# gated suite is only "covered" by a glob that belongs to the suite's own module.
PATTERNS=""
while IFS= read -r tok; do
  [ -n "$tok" ] || continue
  # Only accept the full "<module-path>/target/<phase>-reports/TEST-<glob>.xml" shape.
  case "$tok" in
    */target/*-reports/TEST-*.xml) ;;
    *) continue ;;
  esac
  modpath="${tok%%/target/*}"   # e.g. mockserver/mockserver-netty  OR  mockserver-blob-azure
  # Key on the module DIRECTORY BASENAME, not the full path: the same module is
  # written both ways above depending on whether the step runs from the repo root
  # or from within mockserver/, and the basename is the only part common to both.
  # ASSUMPTION: module directory basenames are globally unique in this repo (they
  # are today — every Java module is a flat, distinct mockserver/<module>). If a
  # nested module ever duplicates an existing basename, two distinct modules would
  # collapse to one key and a gated suite in one could be reported covered by the
  # other's glob — the same fail-open this module-scoping exists to close. Key on a
  # repo-root-relative module suffix if that ever becomes possible.
  module="${modpath##*/}"       # Maven module dir name, identical in both prefix forms
  glob="${tok##*/TEST-}"        # e.g. *BlobStoreContractTest.xml
  glob="${glob%.xml}"           # e.g. *BlobStoreContractTest
  [ -n "$module" ] || continue
  [ -n "$glob" ] || continue
  PATTERNS="${PATTERNS}${module}"$'\t'"${glob}"$'\n'
done < <(grep -rhE "'[^']*-reports/TEST-[A-Za-z0-9*._-]*\.xml'" .buildkite/scripts 2>/dev/null \
           | grep -vE '^[[:space:]]*#' \
           | grep -oE "'[^']*-reports/TEST-[A-Za-z0-9*._-]*\.xml'" \
           | sed "s/^'//; s/'\$//" \
           | sort -u)

if [ -z "${PATTERNS//[$'\n\t']/}" ]; then
  echo "+++ :bangbang: Rule 1: found no assert-suite-ran report globs at all — the sweep matched nothing, failing closed" >&2
  errors=$(( errors + 1 ))
fi

# Every git-tracked test class basename (for the dangling-pattern rot check).
ALL_TEST_CLASSES="$(git ls-files '*.java' | grep -E '/src/test/' | sed -E 's#.*/##; s#\.java$##' | sort -u)"

pattern_matches_a_class() {
  local pat="$1" cls
  while IFS= read -r cls; do
    [ -n "$cls" ] || continue
    # shellcheck disable=SC2254  # $pat is a deliberate shell glob
    case "$cls" in $pat) return 0 ;; esac
  done <<EOF
$ALL_TEST_CLASSES
EOF
  return 1
}

# A gated suite is covered only by a glob for its OWN module: both the module name
# and the class-name glob must match.
class_is_covered() {
  local module="$1" cls="$2" pmod pglob
  while IFS=$'\t' read -r pmod pglob; do
    [ -n "$pglob" ] || continue
    [ "$pmod" = "$module" ] || continue
    # shellcheck disable=SC2254  # $pglob is a deliberate shell glob
    case "$cls" in $pglob) return 0 ;; esac
  done <<EOF
$PATTERNS
EOF
  return 1
}

# Docker-gated test suites: test sources referencing the canonical probe.
DOCKER_GATED_FILES="$(git grep -l 'DockerAvailability.isAvailable(' -- '*.java' | grep -E '/src/test/' || true)"

covered=0
while IFS= read -r file; do
  [ -n "$file" ] || continue
  cls="$(basename "$file" .java)"
  if r1_is_allowlisted "$file"; then
    continue
  fi
  # Maven module dir name = last path segment before /src/test/ — the same key
  # used for the assert-suite-ran globs, so coverage is correlated per module.
  modpath="${file%%/src/test/*}"
  module="${modpath##*/}"
  if class_is_covered "$module" "$cls"; then
    echo "    :white_check_mark: ${cls}: covered by an assert-suite-ran glob in module ${module}"
    covered=$(( covered + 1 ))
  else
    echo "+++ :bangbang: ${file}: Docker-gated (DockerAvailability.isAvailable) but NO assert-suite-ran glob for module '${module}' names ${cls} — a broken Docker socket would SKIP it and the build would stay green. Pair it via .buildkite/scripts/steps/assert-suite-ran.sh with a glob under '${module}/target/...' (see AGENTS.md), or allow-list it with a justification. (A same-suffix glob in another module does NOT count.)" >&2
    errors=$(( errors + 1 ))
  fi
done <<EOF
$DOCKER_GATED_FILES
EOF
echo "    ${covered} Docker-gated suite(s) confirmed paired"

# Allow-list rot: each entry must still exist, still reference the probe, and still
# NOT be a real gated suite (no Assume gate) — else the exemption is masking a suite.
for entry in "${R1_ALLOWLIST[@]}"; do
  if [ ! -f "$entry" ]; then
    echo "+++ :bangbang: Rule 1 allow-list entry '${entry}' does not exist — remove it or fix the path (the allow-list must not rot into a no-op)" >&2
    errors=$(( errors + 1 ))
    continue
  fi
  if ! grep -q 'DockerAvailability.isAvailable(' "$entry"; then
    echo "+++ :bangbang: Rule 1 allow-list entry '${entry}' no longer references DockerAvailability.isAvailable — it is not in the swept set, so exempting it is meaningless; remove it" >&2
    errors=$(( errors + 1 ))
    continue
  fi
  if grep -qE 'Assume\.assume(True|That)' "$entry"; then
    echo "+++ :bangbang: Rule 1 allow-list entry '${entry}' now carries an Assume gate — it has become a real Docker-gated suite and must be assert-suite-ran-paired, not exempted" >&2
    errors=$(( errors + 1 ))
    continue
  fi
  echo "    :white_check_mark: allow-list ok: ${entry} (probe unit test, no Assume gate)"
done

# Dangling-glob rot: an assert-suite-ran pattern that matches no test class is a
# renamed/typo'd suite whose assertion can never bite. (Class-name-only here by
# design: a glob whose class exists but has moved modules is caught as a coverage
# miss above, not a dangling glob.)
while IFS=$'\t' read -r pmod pglob; do
  [ -n "$pglob" ] || continue
  if ! pattern_matches_a_class "$pglob"; then
    echo "+++ :bangbang: assert-suite-ran glob '${pmod}/target/.../TEST-${pglob}.xml' matches no git-tracked test class — the suite was renamed or removed and the assertion is dangling; fix the glob" >&2
    errors=$(( errors + 1 ))
  fi
done <<EOF
$PATTERNS
EOF

# ══════════════════════════════════════════════════════════════════════
# Rule 2 — a step that mounts the Docker socket then deselects Docker tests
# ══════════════════════════════════════════════════════════════════════
echo "--- :electric_plug: Rule 2: no step mounts the Docker socket then deselects Docker tests"

# See R1_ALLOWLIST for the format/justification contract. No legitimate case is
# known; add one here (path + reason) only if a step genuinely must do both.
R2_ALLOWLIST=()

r2_is_allowlisted() {
  local path="$1" entry
  # ${arr[@]+…} keeps an EMPTY array from tripping `set -u` on bash 3.2.
  for entry in ${R2_ALLOWLIST[@]+"${R2_ALLOWLIST[@]}"}; do
    [ "$path" = "$entry" ] && return 0
  done
  return 1
}

# Strip shell comments so the historical defect quoted in a comment does not
# self-trigger the guard — including TRAILING comments (e.g. `run  # was: -m "not
# docker"`), which a full-line-only stripper would miss. The stripper is quote-
# aware: it tracks single/double quotes and cuts only at an UNQUOTED `#` that
# begins a word (column 1, or preceded by whitespace — the shell's own comment
# rule). This never truncates a `#` inside a quoted string, so it cannot HIDE a
# real `-m "not docker"` that follows a literal `#`; in the ambiguous `;#` case it
# errs toward keeping text (a possible false positive), never toward hiding.
strip_comments() {
  awk '
  {
    out = ""; sq = 0; dq = 0; n = length($0);
    for (i = 1; i <= n; i++) {
      c = substr($0, i, 1);
      if (c == "\047" && dq == 0) { sq = 1 - sq; out = out c; continue }  # single quote
      if (c == "\042" && sq == 0) { dq = 1 - dq; out = out c; continue }  # double quote
      if (c == "#" && sq == 0 && dq == 0) {
        if (i == 1) break;
        prev = substr($0, i - 1, 1);
        if (prev == " " || prev == "\t") break;
      }
      out = out c;
    }
    print out;
  }' "$1"
}

R2_SEEN=$'\n'
r2_scanned=0
while IFS= read -r script; do
  [ -n "$script" ] || continue
  r2_scanned=$(( r2_scanned + 1 ))
  body="$(strip_comments "$script")"
  # Docker socket granted: run-in-docker.sh -s/--docker-socket, or a raw mount.
  mounts_socket=0
  if grep -qE '(--docker-socket)|(/var/run/docker\.sock)|(^[[:space:]]*-s[[:space:]]*\\?[[:space:]]*$)' <<<"$body"; then
    mounts_socket=1
  fi
  # Docker-marked tests deselected (pytest -m "not docker" and equivalents).
  deselects_docker=0
  if grep -qE "[-]m[[:space:]]+['\"][^'\"]*not[[:space:]]+docker" <<<"$body"; then
    deselects_docker=1
  fi
  if [ "$mounts_socket" -eq 1 ] && [ "$deselects_docker" -eq 1 ]; then
    if r2_is_allowlisted "$script"; then
      R2_SEEN="${R2_SEEN}${script}"$'\n'
      echo "    :white_check_mark: allow-list ok: ${script} (mounts socket + deselects docker, justified)"
    else
      echo "+++ :bangbang: ${script}: mounts the Docker socket AND deselects Docker-marked tests (e.g. -m \"not docker\") — it pays to mount the socket then starts no container, passing green. Run the Docker suite, or drop the socket." >&2
      errors=$(( errors + 1 ))
    fi
  fi
done < <(git ls-files '.buildkite/scripts/steps/*.sh')

# Fail closed if the sweep matched no step scripts at all (path moved/renamed):
# an empty loop would "pass" Rule 2 having inspected nothing.
if [ "$r2_scanned" -eq 0 ]; then
  echo "+++ :bangbang: Rule 2: swept zero step scripts under .buildkite/scripts/steps/*.sh — the sweep matched nothing, failing closed" >&2
  errors=$(( errors + 1 ))
fi

for entry in ${R2_ALLOWLIST[@]+"${R2_ALLOWLIST[@]}"}; do
  if ! grep -Fxq -- "$entry" <<<"$R2_SEEN"; then
    echo "+++ :bangbang: Rule 2 allow-list entry '${entry}' no longer both mounts the socket and deselects Docker tests — remove it (the allow-list must not rot into a no-op)" >&2
    errors=$(( errors + 1 ))
  fi
done

# ══════════════════════════════════════════════════════════════════════
# Rule 3 — a container-integration skip that defers work while reading green
# ══════════════════════════════════════════════════════════════════════
echo "--- :fast_forward: Rule 3: no container-integration skip parks deferred work as green"

# See R1_ALLOWLIST for the format/justification contract. Entry form: "<file>:<lineno>".
R3_ALLOWLIST=()

r3_is_allowlisted() {
  local loc="$1" entry
  # ${arr[@]+…} keeps an EMPTY array from tripping `set -u` on bash 3.2.
  for entry in ${R3_ALLOWLIST[@]+"${R3_ALLOWLIST[@]}"}; do
    [ "$loc" = "$entry" ] && return 0
  done
  return 1
}

# Deferral language: a skip that means "work not done yet", not "case genuinely N/A".
DEFERRAL_RE='(follow[- ]?up|TODO|FIXME|not yet|pending|\bWIP\b|coming soon|(to|will) be (wired|added|implemented|done|enabled))'

# Fail closed if Rule 3's search corpus is empty. Unlike Rules 1/2 the VIOLATION
# grep below is legitimately empty in the good case, so the lower bound is on the
# corpus: logTestSkip must appear somewhere under container_integration_tests/. If
# the helper was renamed or the tree moved, the violation grep would silently pass
# having scanned nothing the rule can ever fire on.
if [ -z "$(git grep -l 'logTestSkip' -- 'container_integration_tests/**' 2>/dev/null)" ]; then
  echo "+++ :bangbang: Rule 3: found no logTestSkip usages under container_integration_tests/ — the rule's search corpus is empty (helper renamed or tree moved?), so it can never fire; failing closed" >&2
  errors=$(( errors + 1 ))
fi

R3_SEEN=$'\n'
while IFS= read -r hit; do
  [ -n "$hit" ] || continue
  # `git grep -n` output: path:lineno:content
  file="${hit%%:*}"
  rest="${hit#*:}"
  lineno="${rest%%:*}"
  content="${rest#*:}"
  # Skip full-line comments and the helper's own definition.
  case "$content" in
    *[!\ ]*) ;;  # has non-space
    *) continue ;;
  esac
  trimmed="$(printf '%s' "$content" | sed -E 's/^[[:space:]]*//')"
  case "$trimmed" in
    \#*) continue ;;
    function\ logTestSkip*) continue ;;
  esac
  loc="${file}:${lineno}"
  if r3_is_allowlisted "$loc"; then
    R3_SEEN="${R3_SEEN}${loc}"$'\n'
    echo "    :white_check_mark: allow-list ok: ${loc} (justified deferral skip)"
  else
    echo "+++ :bangbang: ${loc}: logTestSkip with deferral language ('${trimmed}') — a skip that parks unfinished work reads as green forever. Wire the case up, or make the skip declare a genuine N/A reason (no follow-up/TODO/pending)." >&2
    errors=$(( errors + 1 ))
  fi
done < <(git grep -niE "logTestSkip.*${DEFERRAL_RE}" -- 'container_integration_tests/**' || true)

for entry in ${R3_ALLOWLIST[@]+"${R3_ALLOWLIST[@]}"}; do
  if ! grep -Fxq -- "$entry" <<<"$R3_SEEN"; then
    echo "+++ :bangbang: Rule 3 allow-list entry '${entry}' no longer matches a deferral skip — remove it (the allow-list must not rot into a no-op)" >&2
    errors=$(( errors + 1 ))
  fi
done

# ══════════════════════════════════════════════════════════════════════
# Rule 4 — a helm/k3d harness run that can silently revert to skipping
# ══════════════════════════════════════════════════════════════════════
echo "--- :helm: Rule 4: helm/k3d harness runs must require the built images"

# See R1_ALLOWLIST for the format/justification contract. Entry form: "<file>".
# A legitimate case would be a helm-running harness step that genuinely must NOT
# require the images (e.g. images pre-published out of band); none is known.
R4_ALLOWLIST=()

r4_is_allowlisted() {
  local path="$1" entry
  # ${arr[@]+…} keeps an EMPTY array from tripping `set -u` on bash 3.2.
  for entry in ${R4_ALLOWLIST[@]+"${R4_ALLOWLIST[@]}"}; do
    [ "$path" = "$entry" ] && return 0
  done
  return 1
}

# Detect, on the COMMENT-STRIPPED body (reusing Rule 2's quote-aware stripper), so
# a commented-out `export REQUIRE_*_IMAGE=true` can never satisfy the rule and a
# comment that merely mentions integration_tests.sh (e.g. docker-build-verify.sh)
# is not mistaken for a harness invocation.
r4_has() { grep -qE -- "$2" <<<"$1"; }

# Value must be literally true after expansion (the harness compares == "true");
# accept optional quoting, reject a longer identifier like `trueish`.
R4_TRUE='=['\''"]?true([^A-Za-z0-9_]|$)'

R4_SEEN=$'\n'
r4_helm_running=0
while IFS= read -r script; do
  [ -n "$script" ] || continue
  body="$(strip_comments "$script")"
  # Only steps that actually RUN the container-integration harness.
  r4_has "$body" 'integration_tests\.sh' || continue
  # …with the helm/k3d cases ACTIVE. A step that opts out via SKIP_HELM_TESTS=true
  # (the docker-compose-only run) does not build or need the images, so it is not
  # subject to this rule.
  if r4_has "$body" "SKIP_HELM_TESTS${R4_TRUE}"; then
    continue
  fi
  r4_helm_running=$(( r4_helm_running + 1 ))
  has_clustered=0; has_webhook=0
  r4_has "$body" "REQUIRE_CLUSTERED_IMAGE${R4_TRUE}" && has_clustered=1
  r4_has "$body" "REQUIRE_WEBHOOK_IMAGE${R4_TRUE}"   && has_webhook=1
  if [ "$has_clustered" -eq 1 ] && [ "$has_webhook" -eq 1 ]; then
    echo "    :white_check_mark: ${script}: runs the helm harness and exports both REQUIRE_*_IMAGE=true"
  elif r4_is_allowlisted "$script"; then
    R4_SEEN="${R4_SEEN}${script}"$'\n'
    echo "    :white_check_mark: allow-list ok: ${script} (helm harness without required images, justified)"
  else
    missing=""
    [ "$has_clustered" -eq 0 ] && missing="${missing} REQUIRE_CLUSTERED_IMAGE=true"
    [ "$has_webhook" -eq 0 ] && missing="${missing} REQUIRE_WEBHOOK_IMAGE=true"
    echo "+++ :bangbang: ${script}: runs the container-integration harness with helm/k3d tests active but does NOT export${missing} — a missing image would then record a SKIP instead of a FAILURE and the build would stay green having tested nothing. Export both flags (see helm-integration-test.sh), or set SKIP_HELM_TESTS=true if this step genuinely does not run the helm cases." >&2
    errors=$(( errors + 1 ))
  fi
done < <(git ls-files '.buildkite/scripts/steps/*.sh')

# Fail closed if NO step runs the helm harness at all: the rule would then be
# inspecting nothing (the step was renamed, moved, or the harness invocation
# changed) and could never fire — a silent false positive of its own.
if [ "$r4_helm_running" -eq 0 ]; then
  echo "+++ :bangbang: Rule 4: found no CI step that runs the container-integration harness with helm/k3d tests active — the sweep matched nothing (step renamed or harness invocation changed?), so the rule can never fire; failing closed" >&2
  errors=$(( errors + 1 ))
fi

for entry in ${R4_ALLOWLIST[@]+"${R4_ALLOWLIST[@]}"}; do
  if ! grep -Fxq -- "$entry" <<<"$R4_SEEN"; then
    echo "+++ :bangbang: Rule 4 allow-list entry '${entry}' no longer runs the helm harness while missing a REQUIRE_*_IMAGE=true export — remove it (the allow-list must not rot into a no-op)" >&2
    errors=$(( errors + 1 ))
  fi
done

# ══════════════════════════════════════════════════════════════════════
# Rule 5 — a JVM-global logging side effect in mockserver-core's parallel phase
# ══════════════════════════════════════════════════════════════════════
echo "--- :scroll: Rule 5: core tests with a global logging side effect must be sequential"

# See R1_ALLOWLIST for the format/justification contract. Entry form: "<repo-
# relative .java path>". A legitimate case would be a core test that matches a
# signature but provably cannot race the shared LogManager (e.g. it re-inits an
# org.mockserver class with no logging in its <clinit> chain); none is known.
R5_ALLOWLIST=()

r5_is_allowlisted() {
  local path="$1" entry
  # ${arr[@]+…} keeps an EMPTY array from tripping `set -u` on bash 3.2.
  for entry in ${R5_ALLOWLIST[@]+"${R5_ALLOWLIST[@]}"}; do
    [ "$path" = "$entry" ] && return 0
  done
  return 1
}

# Global-logging signatures, each rare in test sources and validated against the
# tree (today's core matches in parentheses). ALL four ultimately reach
# LogManager.getLogManager().readConfiguration(...) — a JVM-wide handler reset:
#
#   S1  LogManager.getLogManager().readConfiguration   — the reset itself (0)
#   S2  MockServerLogger.configureLogger(              — its only caller (0)
#   S3  ConfigurationProperties.{logLevel|disableSystemOut|disableLogging}(<arg>)
#       — the static setters, each of which calls configureLogger() (Configuration
#       Test, ExceptionHandlingTest)
#   S4  Class.forName(<expr>, true, <loader>)          — a fresh <clinit> forced via
#       an isolated classloader, which re-runs MockServerLogger's static reset
#       (ClassInitializationDeadlockTest, ConfigurationPropertiesInitializationTest)
#
# DELIBERATELY REJECTED as too noisy (would be muted by allow-list churn):
#   * bare `disableSystemOut(`/`disableLogging(`/`logLevel(` WITHOUT the
#     `ConfigurationProperties.` qualifier — these match the per-instance
#     `Configuration` builder (`configuration().logLevel(Level.INFO)
#     .disableSystemOut(false)`), which is NOT a global side effect; requiring the
#     static qualifier drops GrpcFailSafeLoggingTest, ConfigurationSerializerTest,
#     ConfigurationDTOTest and MockServerLoggerTest, all legitimately parallel.
#   * `Class.forName(` in any form — the 1-arg `Class.forName(className)` (already-
#     loaded classes) and `Class.forName('java.lang.Runtime')` inside a template
#     string are legitimately parallel; only the 3-arg initialize=true form forces
#     a fresh <clinit>.
#
# LIMITS (stated rather than implied — this rule does NOT catch):
#   * a call site reached via a static import (bare `logLevel("X")`) or reflection
#     — indistinguishable from the per-instance builder without semantic analysis,
#     so the qualified form is the low-false-positive choice;
#   * a global logging side effect in a module OTHER than mockserver-core — only
#     core has the parallel/sequential surefire split this rule keys on;
#   * S4 keys on the reflective SHAPE, not the loaded class name (one true call
#     site loads a variable), so a 3-arg initialize=true load of a non-logging
#     class would be flagged too — but forcing a fresh <clinit> in a chosen loader
#     is itself parallel-unsafe, and there are zero such cases today.
R5_SIGNATURES=(
  'LogManager\.getLogManager\(\)\.readConfiguration'
  'MockServerLogger\.configureLogger\('
  'ConfigurationProperties\.logLevel\([^)]'
  'ConfigurationProperties\.disableSystemOut\([^)]'
  'ConfigurationProperties\.disableLogging\([^)]'
  'Class\.forName\([^,)]+,[[:space:]]*true[[:space:]]*,'
)

# The sequential-includes list lives in the `sequential-tests` execution of
# mockserver-core/pom.xml. Extract ONLY that execution's <include> basenames — a
# second, unrelated <includes> block (the state/contract failsafe run) must not be
# read as "sequential". Track the execution by its <id> and close on </execution>.
CORE_POM="mockserver/mockserver-core/pom.xml"
SEQUENTIAL_INCLUDES="$(awk '
  /<id>sequential-tests<\/id>/ { inseq = 1 }
  inseq && /<include>/ {
    line = $0
    sub(/.*<include>\*\*\//, "", line)
    sub(/<\/include>.*/, "", line)
    sub(/\.java$/, "", line)
    print line
  }
  inseq && /<\/execution>/ { inseq = 0 }
' "$CORE_POM")"

r5_is_sequential() {
  local cls="$1" seq
  while IFS= read -r seq; do
    [ "$cls" = "$seq" ] && return 0
  done <<EOF
$SEQUENTIAL_INCLUDES
EOF
  return 1
}

# Collect offending files: every mockserver-core test source with a signature
# match on a NON-COMMENT line (a Java comment mentioning a setter or forName must
# not count). git grep -n gives path:lineno:content.
R5_OFFENDERS=""
r5_corpus=0
for sig in "${R5_SIGNATURES[@]}"; do
  while IFS= read -r hit; do
    [ -n "$hit" ] || continue
    file="${hit%%:*}"
    rest="${hit#*:}"
    content="${rest#*:}"
    trimmed="$(printf '%s' "$content" | sed -E 's/^[[:space:]]*//')"
    # Drop Java comment lines (javadoc `*`, `//`, block `/*`/`*/`).
    case "$trimmed" in
      \**|//*|/\**) continue ;;
    esac
    r5_corpus=$(( r5_corpus + 1 ))
    R5_OFFENDERS="${R5_OFFENDERS}${file}"$'\n'
  done < <(git grep -nE "$sig" -- '*.java' \
             | grep '/mockserver-core/' \
             | grep '/src/test/' || true)
done

# Fail closed on an empty corpus: if NO signature matches any core test source,
# the signatures have rotted (methods renamed / tree moved) and the rule can never
# fire — a silent false positive. (Today S4 alone guarantees ≥2 matches.)
if [ "$r5_corpus" -eq 0 ]; then
  echo "+++ :bangbang: Rule 5: no core test source matches any JVM-global-logging signature — the signatures have rotted (methods renamed or tree moved?), so the rule can never fire; failing closed" >&2
  errors=$(( errors + 1 ))
fi

R5_SEEN=$'\n'
while IFS= read -r file; do
  [ -n "$file" ] || continue
  cls="$(basename "$file" .java)"
  if r5_is_sequential "$cls"; then
    echo "    :white_check_mark: ${cls}: performs a global logging side effect and is sequential"
  elif r5_is_allowlisted "$file"; then
    R5_SEEN="${R5_SEEN}${file}"$'\n'
    echo "    :white_check_mark: allow-list ok: ${file} (global logging side effect, justified as non-racing)"
  else
    echo "+++ :bangbang: ${file}: performs a JVM-global logging side effect (LogManager reset via a static logging setter or a forced fresh <clinit>) but is NOT in the sequential-includes list of ${CORE_POM} — in the parallel phase it races other tests' log capture, silently zeroing a capture or falsely passing a silence assertion. Add <include>**/${cls}.java</include> to the sequential-tests execution (and the matching parallel <exclude>), or allow-list it with a justification." >&2
    errors=$(( errors + 1 ))
  fi
done < <(printf '%s' "$R5_OFFENDERS" | sort -u)

for entry in ${R5_ALLOWLIST[@]+"${R5_ALLOWLIST[@]}"}; do
  if [ ! -f "$entry" ]; then
    echo "+++ :bangbang: Rule 5 allow-list entry '${entry}' does not exist — remove it or fix the path (the allow-list must not rot into a no-op)" >&2
    errors=$(( errors + 1 ))
    continue
  fi
  if ! grep -Fxq -- "$entry" <<<"$R5_SEEN"; then
    echo "+++ :bangbang: Rule 5 allow-list entry '${entry}' either no longer matches a global-logging signature or has since been added to the sequential list — the exemption is now meaningless; remove it" >&2
    errors=$(( errors + 1 ))
  fi
done

# ══════════════════════════════════════════════════════════════════════
# Rule 6 — the Dependabot auto-merge acceptance paths must stay narrow
# ══════════════════════════════════════════════════════════════════════
echo "--- :robot_face: Rule 6: Dependabot auto-merge acceptance paths stay narrow"

# There is deliberately NO allow-list for Rule 6 (unlike Rules 1-5): it does not
# sweep a set of files where an individual entry could be legitimately exempt — it
# parses fixed properties out of one workflow, none of which has a valid exemption
# (a group in a branch named minor-and-patch must never carry a major, a digest
# path must always keep its /distroless/ scope, etc.). Any violation is a real
# regression, so there is nothing to wave through.
WF=".github/workflows/dependabot-auto-merge.yml"
DEPENDABOT_YML=".github/dependabot.yml"

if [ ! -f "$WF" ]; then
  echo "+++ :bangbang: Rule 6: ${WF} is missing — the auto-merge guard cannot verify the acceptance paths; failing closed" >&2
  errors=$(( errors + 1 ))
else
  # Extract the single-quoted RHS of `VAR='...'` (one assignment line per var; the
  # comments that mention these names never begin with `VAR='` so are not matched).
  r6_extract() {
    sed -n -E "s/^[[:space:]]*$1='([^']*)'.*/\1/p" "$WF"
  }
  # numeric-safe ">= floor" test — never let a non-integer trip `set -e` under `[`.
  r6_ge() { case "$1" in ''|*[!0-9]*) return 1 ;; esac; [ "$1" -ge "$2" ]; }

  GROUP_RE="$(r6_extract GROUP_BRANCH_REGEX || true)"
  DIGEST_BR_RE="$(r6_extract DIGEST_BRANCH_REGEX || true)"
  DIGEST_TI_RE="$(r6_extract DIGEST_TITLE_REGEX || true)"

  # ── extraction fail-closed: a missing/renamed assignment must FAIL, not pass ──
  if [ -z "$GROUP_RE" ]; then
    echo "+++ :bangbang: Rule 6: could not extract GROUP_BRANCH_REGEX from ${WF} — the assignment moved or was renamed; failing closed" >&2
    errors=$(( errors + 1 ))
  fi
  if [ -z "$DIGEST_BR_RE" ]; then
    echo "+++ :bangbang: Rule 6: could not extract DIGEST_BRANCH_REGEX from ${WF} — the assignment moved or was renamed; failing closed" >&2
    errors=$(( errors + 1 ))
  fi
  if [ -z "$DIGEST_TI_RE" ]; then
    echo "+++ :bangbang: Rule 6: could not extract DIGEST_TITLE_REGEX from ${WF} — the assignment moved or was renamed; failing closed" >&2
    errors=$(( errors + 1 ))
  fi

  # ── 6a: DIGEST_BRANCH_REGEX shape ────────────────────────────────────────────
  if [ -n "$DIGEST_BR_RE" ]; then
    r6a_ok=1
    case "$DIGEST_BR_RE" in
      '^'*) ;;
      *) echo "+++ :bangbang: Rule 6a: DIGEST_BRANCH_REGEX ('${DIGEST_BR_RE}') is not start-anchored (^) — an unanchored match accepts far more than a digest branch" >&2; errors=$(( errors + 1 )); r6a_ok=0 ;;
    esac
    case "$DIGEST_BR_RE" in
      *'$') ;;
      *) echo "+++ :bangbang: Rule 6a: DIGEST_BRANCH_REGEX ('${DIGEST_BR_RE}') is not end-anchored (\$) — trailing text after the hex run would still match" >&2; errors=$(( errors + 1 )); r6a_ok=0 ;;
    esac
    case "$DIGEST_BR_RE" in
      '^dependabot/docker/'*) ;;
      *) echo "+++ :bangbang: Rule 6a: DIGEST_BRANCH_REGEX ('${DIGEST_BR_RE}') is not confined to '^dependabot/docker/' — it could match non-Docker ecosystems" >&2; errors=$(( errors + 1 )); r6a_ok=0 ;;
    esac
    case "$DIGEST_BR_RE" in
      *'/distroless/'*) ;;
      *) echo "+++ :bangbang: Rule 6a: DIGEST_BRANCH_REGEX ('${DIGEST_BR_RE}') dropped the load-bearing '/distroless/' scope — a date-tagged version bump (e.g. ubuntu 20240115->20240220, whose date tokens are all hex) would then satisfy DIGEST_TITLE_REGEX and auto-merge as a digest bump. This scope is the ONLY thing that rejects it." >&2; errors=$(( errors + 1 )); r6a_ok=0 ;;
    esac
    if grep -Eq '\[0-9a-f\]\{[0-9]+,\}\$$' <<<"$DIGEST_BR_RE"; then
      r6a_floor="$(printf '%s' "$DIGEST_BR_RE" | sed -E 's/.*\[0-9a-f\]\{([0-9]+),\}\$$/\1/')"
      if ! r6_ge "$r6a_floor" 7; then
        echo "+++ :bangbang: Rule 6a: DIGEST_BRANCH_REGEX trailing hex-run floor is {${r6a_floor},} — it must be at least {7,}; a shorter run lets short tag tokens through" >&2; errors=$(( errors + 1 )); r6a_ok=0
      fi
    else
      echo "+++ :bangbang: Rule 6a: DIGEST_BRANCH_REGEX ('${DIGEST_BR_RE}') does not end in a hex-run quantifier ([0-9a-f]{N,}\$) — it can no longer pin the trailing digest sha" >&2; errors=$(( errors + 1 )); r6a_ok=0
    fi
    [ "$r6a_ok" -eq 1 ] && echo "    :white_check_mark: DIGEST_BRANCH_REGEX: anchored, docker/distroless-scoped, trailing hex-run floor >=7"
  fi

  # ── 6b: DIGEST_TITLE_REGEX needs a >=7 hex run on BOTH the from and to sides ──
  r6_title_side() {  # $1=label $2=regex-fragment ; return 1 (with a message) if no >=7 hex run
    local lbl="$1" part="$2" f
    if ! grep -Eq '\[0-9a-f\]\{[0-9]+,\}' <<<"$part"; then
      echo "+++ :bangbang: Rule 6b: DIGEST_TITLE_REGEX '${lbl}' side has no [0-9a-f]{N,} hex run — collapsing the two-sided sha check lets a version-bump title match" >&2
      return 1
    fi
    f="$(printf '%s' "$part" | sed -E 's/.*\[0-9a-f\]\{([0-9]+),\}.*/\1/')"
    if ! r6_ge "$f" 7; then
      echo "+++ :bangbang: Rule 6b: DIGEST_TITLE_REGEX '${lbl}' side hex-run floor is {${f},} — it must be at least {7,} so a bare version token cannot match" >&2
      return 1
    fi
    return 0
  }
  if [ -n "$DIGEST_TI_RE" ]; then
    r6b_ok=1
    r6b_from="${DIGEST_TI_RE%% to *}"
    r6b_to="${DIGEST_TI_RE#* to }"
    if [ "$r6b_from" = "$DIGEST_TI_RE" ]; then
      echo "+++ :bangbang: Rule 6b: DIGEST_TITLE_REGEX ('${DIGEST_TI_RE}') has no ' to ' separator — it cannot express a from/to digest-bump shape" >&2; errors=$(( errors + 1 )); r6b_ok=0
    else
      case "$r6b_from" in
        *from*) ;;
        *) echo "+++ :bangbang: Rule 6b: DIGEST_TITLE_REGEX ('${DIGEST_TI_RE}') is missing the 'from' keyword before the first sha" >&2; errors=$(( errors + 1 )); r6b_ok=0 ;;
      esac
      if ! r6_title_side from "$r6b_from"; then errors=$(( errors + 1 )); r6b_ok=0; fi
      if ! r6_title_side to   "$r6b_to";   then errors=$(( errors + 1 )); r6b_ok=0; fi
    fi
    [ "$r6b_ok" -eq 1 ] && echo "    :white_check_mark: DIGEST_TITLE_REGEX: requires a >=7 hex run on both the from and to sides"
  fi

  # ── 6c: path B keeps the branch+title AND (the title cross-check is a conjunct) ──
  # Isolate path B — the `elif … DIGEST_BRANCH_REGEX … then` branch, up to the next
  # `else`/`elif` at that level — and require it to (1) reference DIGEST_TITLE_REGEX
  # in a negated guard and (2) fail closed (return 1). Drop either and a branch that
  # merely matches the shape would auto-merge without a digest-bump title.
  r6c_block="$(awk '
    /elif .*DIGEST_BRANCH_REGEX.*then/ { inblk = 1; next }
    inblk && /^[[:space:]]*else[[:space:]]*$/ { inblk = 0 }
    inblk && /^[[:space:]]*elif[[:space:]]/ { inblk = 0 }
    inblk { print }
  ' "$WF")"
  if [ -z "$r6c_block" ]; then
    echo "+++ :bangbang: Rule 6c: could not locate path B (the 'elif … DIGEST_BRANCH_REGEX … then' acceptance branch) in ${WF} — the acceptance structure changed; failing closed" >&2
    errors=$(( errors + 1 ))
  elif ! grep -Eq 'if ! .*DIGEST_TITLE_REGEX' <<<"$r6c_block"; then
    echo "+++ :bangbang: Rule 6c: path B (distroless digest branch) no longer cross-checks DIGEST_TITLE_REGEX — the two-signal AND has collapsed to the branch shape alone, so a branch matching the shape would auto-merge without a digest-bump title. Restore the title conjunct." >&2
    errors=$(( errors + 1 ))
  elif ! grep -q 'return 1' <<<"$r6c_block"; then
    echo "+++ :bangbang: Rule 6c: path B references DIGEST_TITLE_REGEX but no longer fails closed (no 'return 1') when the title does not match — the cross-check is non-blocking. Restore the refuse/return." >&2
    errors=$(( errors + 1 ))
  else
    echo "    :white_check_mark: path B cross-checks the digest-bump title and fails closed on mismatch"
  fi

  # ── 6d: every group name path A accepts must exclude majors ───────────────────
  if [ -n "$GROUP_RE" ]; then
    if [ ! -f "$DEPENDABOT_YML" ]; then
      echo "+++ :bangbang: Rule 6d: ${DEPENDABOT_YML} is missing — cannot verify that path-A-accepted groups exclude majors; failing closed" >&2
      errors=$(( errors + 1 ))
    else
      # One record per group: "<group-name>\t<space-separated update-types>".
      # Indentation-driven (match($0,/^ */) → RLENGTH) so it needs no interval
      # support: groups: at ind 4, group name at 6, update-types: at 8, items at 10;
      # any non-blank line dedented to <=4 ends the block.
      r6_groups="$(awk '
        { match($0, /^ */); ind = RLENGTH }
        ind == 4 && /^    groups:[[:space:]]*$/ { ingroups = 1; next }
        ingroups && ind <= 4 && $0 !~ /^[[:space:]]*$/ { ingroups = 0 }
        ingroups && ind == 6 && /:[[:space:]]*$/ {
          if (name != "") print name "\t" types
          key = $0; sub(/^ +/, "", key); sub(/:[[:space:]]*$/, "", key)
          name = key; types = ""; intypes = 0; next
        }
        # A group written inline as an empty mapping -- `name: {}` -- must be RECORDED
        # with no types, not ignored. `{}` is valid Dependabot config and defaults to
        # ALL update-types, majors included; skipping the line entirely meant such a
        # group was never checked, while the other block-style groups kept the
        # empty-sweep guard from firing. Recorded with types="" so 6d fails it.
        ingroups && ind == 6 && /:[[:space:]]*\{[[:space:]]*\}[[:space:]]*$/ {
          if (name != "") print name "\t" types
          key = $0; sub(/^ +/, "", key); sub(/:[[:space:]]*\{.*$/, "", key)
          name = key; types = ""; intypes = 0; next
        }
        ingroups && ind == 8 && /^ +update-types:[[:space:]]*$/ { intypes = 1; next }
        ingroups && intypes && ind == 10 && /^ +- / {
          v = $0; sub(/^ +- +/, "", v); gsub(/"/, "", v); sub(/[[:space:]]+$/, "", v)
          if (v != "") types = (types == "" ? v : types " " v)
          next
        }
        ingroups && ind == 8 && $0 !~ /^ +update-types:/ { intypes = 0 }
        END { if (name != "") print name "\t" types }
      ' "$DEPENDABOT_YML")"

      r6d_matched=0
      while IFS=$'\t' read -r gname gtypes; do
        [ -n "$gname" ] || continue
        # Would path A accept a Dependabot branch carrying this group name?
        synthetic="dependabot/testeco/testdir/${gname}-a1b2c3d"
        if grep -Eq -- "$GROUP_RE" <<<"$synthetic"; then
          r6d_matched=$(( r6d_matched + 1 ))
          bad=""
          if [ -z "$gtypes" ]; then
            bad="declares NO update-types (defaults to ALL types, major included)"
          else
            offending=""
            for t in $gtypes; do
              case "$t" in
                minor|patch) ;;
                *) offending="${offending}${offending:+ }${t}" ;;
              esac
            done
            [ -n "$offending" ] && bad="declares out-of-policy update-type(s): ${offending}"
          fi
          if [ -n "$bad" ]; then
            echo "+++ :bangbang: Rule 6d: Dependabot group '${gname}' produces branches accepted by path A (GROUP_BRANCH_REGEX '${GROUP_RE}') but ${bad} — path A would then auto-merge that update on a branch literally named '*-minor-and-patch'. Restrict this group's update-types to minor+patch, or rename it so path A no longer accepts it." >&2
            errors=$(( errors + 1 ))
          else
            echo "    :white_check_mark: group '${gname}': accepted by path A and correctly limited to minor+patch"
          fi
        fi
      done <<EOF
$r6_groups
EOF
      # Fail closed if path A accepts NO group at all — the sweep matched nothing
      # (groups renamed, regex changed, or the parser broke), so the rule could
      # never fire. This empty-set case is the specific shape this file exists to catch.
      if [ "$r6d_matched" -eq 0 ]; then
        echo "+++ :bangbang: Rule 6d: no Dependabot group in ${DEPENDABOT_YML} is accepted by path A (GROUP_BRANCH_REGEX '${GROUP_RE}') — the sweep matched nothing (groups renamed, regex changed, or parser broken); failing closed" >&2
        errors=$(( errors + 1 ))
      fi
    fi
  fi

  # ── 6e: path C (SECURITY updates) keeps its EXPLICIT semver major-exclusion ───
  # Path C has no group to lean on — GitHub ignores update-types for security
  # groups — so its ONLY defence against a major security bump is the explicit
  # major comparison of the title versions. Assert (1) VERSION_PAIR_REGEX and
  # SECURITY_BODY_MARKER still exist; (2) VERSION_PAIR_REGEX still requires a
  # DOTTED-NUMERIC (not hex, not wildcard) version on BOTH the from and to sides,
  # so it can never drift into matching a digest sha or arbitrary text; and (3)
  # EACH comparison — the major `!=` and the 0.x-minor `!=` — is present AND carries
  # a live `return 1` inside THAT comparison's own `if … fi` (bound to that specific
  # `if`, not merely somewhere in path C: C1's npairs `return 1` must not be able to
  # cover a gutted major/minor refuse). Drop any of these and a major (or a breaking
  # 0.x-minor) security bump would silently auto-merge.
  #
  # LIMITS. Like every rule here this is a TEXTUAL tripwire: it checks that each
  # refuse is PRESENT and BOUND to its own comparison, not that it is REACHABLE or
  # that the branch polarity is right. An `if false; then … return 1; fi` wrapper, or
  # a swap of the refuse into an `else` arm, still reads as green. Both are
  # conspicuous control-flow rewrites rather than the quiet deletion / "tidy-away"
  # shape this rule exists to catch, and are left to diff review.
  VER_PAIR_RE="$(r6_extract VERSION_PAIR_REGEX || true)"
  SEC_MARKER="$(r6_extract SECURITY_BODY_MARKER || true)"
  if [ -z "$VER_PAIR_RE" ]; then
    echo "+++ :bangbang: Rule 6e: could not extract VERSION_PAIR_REGEX from ${WF} — path C's title parser moved or was renamed; failing closed" >&2
    errors=$(( errors + 1 ))
  fi
  if [ -z "$SEC_MARKER" ]; then
    echo "+++ :bangbang: Rule 6e: could not extract SECURITY_BODY_MARKER from ${WF} — path C's security signal moved or was renamed; failing closed" >&2
    errors=$(( errors + 1 ))
  fi

  if [ -n "$VER_PAIR_RE" ]; then
    r6e_ok=1
    case "$VER_PAIR_RE" in
      'bump '*) ;;
      *) echo "+++ :bangbang: Rule 6e: VERSION_PAIR_REGEX ('${VER_PAIR_RE}') no longer starts with 'bump ' — it is no longer pinned to a Dependabot bump title" >&2; errors=$(( errors + 1 )); r6e_ok=0 ;;
    esac
    # Both sides of ' to ' must require a dotted-numeric version ('[0-9]+\.'); this
    # is what keeps a version bump distinct from a digest sha (path B).
    vp_from="${VER_PAIR_RE%% to *}"
    vp_to="${VER_PAIR_RE#* to }"
    if [ "$vp_from" = "$VER_PAIR_RE" ]; then
      echo "+++ :bangbang: Rule 6e: VERSION_PAIR_REGEX ('${VER_PAIR_RE}') has no ' to ' separator — it cannot express a from/to version pair" >&2; errors=$(( errors + 1 )); r6e_ok=0
    else
      if ! grep -qF '[0-9]+\.' <<<"$vp_from"; then
        echo "+++ :bangbang: Rule 6e: VERSION_PAIR_REGEX 'from' side has no dotted-numeric '[0-9]+\\.' version token — it could match a non-version (or a hex sha), letting path C parse a bogus major" >&2; errors=$(( errors + 1 )); r6e_ok=0
      fi
      if ! grep -qF '[0-9]+\.' <<<"$vp_to"; then
        echo "+++ :bangbang: Rule 6e: VERSION_PAIR_REGEX 'to' side has no dotted-numeric '[0-9]+\\.' version token — it could match a non-version (or a hex sha), letting path C parse a bogus major" >&2; errors=$(( errors + 1 )); r6e_ok=0
      fi
    fi
    # The version tokens must NOT admit hex letters — a hex-class token would let a
    # digest sha satisfy the pair regex and collapse the C/B distinction.
    if grep -qF '0-9a-f' <<<"$VER_PAIR_RE"; then
      echo "+++ :bangbang: Rule 6e: VERSION_PAIR_REGEX ('${VER_PAIR_RE}') admits hex characters ([0-9a-f]) — a digest sha could then satisfy it and be treated as a version bump" >&2; errors=$(( errors + 1 )); r6e_ok=0
    fi
    [ "$r6e_ok" -eq 1 ] && echo "    :white_check_mark: VERSION_PAIR_REGEX: 'bump' title with dotted-numeric (non-hex) from/to versions"
  fi

  # Isolate path C — the `elif … SECURITY_BODY_MARKER … then` branch, up to the
  # next `else`/`elif` at that level — as a coarse existence check for the path.
  r6e_block="$(awk '
    /elif .*SECURITY_BODY_MARKER.*then/ { inblk = 1; next }
    inblk && /^[[:space:]]*else[[:space:]]*$/ { inblk = 0 }
    inblk && /^[[:space:]]*elif[[:space:]]/ { inblk = 0 }
    inblk { print }
  ' "$WF")"

  # Extract ONE comparison sub-block: the live `if [ … ]; then … fi` whose opening
  # line starts with `if [` (never a comment) and contains ALL the given tokens,
  # captured up to its first standalone `fi`. This BINDS the fail-closed assertion
  # to the specific comparison, instead of asking "does a `return 1` exist anywhere
  # in path C" — which C1's npairs `return 1` satisfies even when C2's/C3's refuse
  # bodies have been gutted. Token-matching (index(), not a dynamic regex) sidesteps
  # awk backslash-escape pitfalls with `$`/`[`/`!=`.
  r6e_subblock() {  # $1 = space-separated tokens ALL required on the opening if-line
    awk -v tokens="$1" '
      function has_all(line,   n, i, arr) {
        n = split(tokens, arr, " ")
        for (i = 1; i <= n; i++) if (index(line, arr[i]) == 0) return 0
        return 1
      }
      !inblk && $0 ~ /^[[:space:]]*if \[/ && has_all($0) { inblk = 1; print; next }
      inblk { print; if ($0 ~ /^[[:space:]]*fi[[:space:]]*$/) exit }
    ' "$WF"
  }
  # True if $1 has a LIVE (non-comment) line containing the fixed string $2 — a
  # comment mentioning it must not count. The comment filter feeds a command
  # substitution (read to EOF), never a pipe into grep -q (see Rule 7).
  r6e_live_has() {
    local live
    live="$(grep -v '^[[:space:]]*#' <<<"$1" || true)"
    grep -qF -- "$2" <<<"$live"
  }
  r6e_fails_closed() { r6e_live_has "$1" 'return 1'; }

  if [ -z "$r6e_block" ]; then
    echo "+++ :bangbang: Rule 6e: could not locate path C (the 'elif … SECURITY_BODY_MARKER … then' acceptance branch) in ${WF} — the acceptance structure changed; failing closed" >&2
    errors=$(( errors + 1 ))
  else
    r6e_blk_ok=1
    # (1) path C must still parse the title into a version pair (live line, not prose).
    if ! r6e_live_has "$r6e_block" 'VERSION_PAIR_REGEX'; then
      echo "+++ :bangbang: Rule 6e: path C no longer references VERSION_PAIR_REGEX on a live line — it is no longer parsing the title into a version pair before accepting a security PR. Restore the title parse." >&2
      errors=$(( errors + 1 )); r6e_blk_ok=0
    fi
    # (2) the MAJOR comparison must exist AND carry its own live `return 1`.
    r6e_major_blk="$(r6e_subblock 'from_major != to_major')"
    if [ -z "$r6e_major_blk" ]; then
      echo "+++ :bangbang: Rule 6e: could not locate path C's major-comparison sub-block ('if [ \$from_major != \$to_major ]; then … fi') in ${WF} — the EXPLICIT major-exclusion conjunct is gone or was rewritten, so a MAJOR security bump could auto-merge. Restore the major comparison; failing closed." >&2
      errors=$(( errors + 1 )); r6e_blk_ok=0
    elif ! r6e_fails_closed "$r6e_major_blk"; then
      echo "+++ :bangbang: Rule 6e: path C's major-comparison sub-block ('if [ \$from_major != \$to_major ]') no longer fails closed — it has no live 'return 1' inside its own if...fi, so a cross-MAJOR security bump would NOT be refused and would auto-merge to master unattended. Restore the refuse/return inside that if." >&2
      errors=$(( errors + 1 )); r6e_blk_ok=0
    fi
    # (3) the 0.x-minor comparison must exist AND carry its own live `return 1`.
    r6e_minor_blk="$(r6e_subblock 'from_minor != to_minor')"
    if [ -z "$r6e_minor_blk" ]; then
      echo "+++ :bangbang: Rule 6e: could not locate path C's 0.x-minor sub-block ('if [ \$from_minor != \$to_minor ]; then … fi') in ${WF} — the 0.x special case is gone or was rewritten, so a breaking 0.x MINOR security bump (e.g. 0.4.x -> 0.5.0) could auto-merge. Restore the 0-major minor check; failing closed." >&2
      errors=$(( errors + 1 )); r6e_blk_ok=0
    elif ! r6e_fails_closed "$r6e_minor_blk"; then
      echo "+++ :bangbang: Rule 6e: path C's 0.x-minor sub-block ('if [ \$from_minor != \$to_minor ]') no longer fails closed — it has no live 'return 1' inside its own if...fi, so a breaking 0.x MINOR security bump (e.g. 0.4.x -> 0.5.0) would NOT be refused. Restore the refuse/return inside that if." >&2
      errors=$(( errors + 1 )); r6e_blk_ok=0
    fi
    [ "$r6e_blk_ok" -eq 1 ] && echo "    :white_check_mark: path C: major and 0.x-minor comparisons each present and each fails closed (return 1 bound to its own check)"
  fi

  # ── 6f: path B's GROUPED form stays distroless-scoped and diff-checked ────────
  # A grouped distroless PR has no shas in its title, so the digest_only_diff call
  # is what stands in for 6b's title check. Its refuse must stay bound to that call.
  r6f_ok=1
  r6f_fail() { echo "+++ :bangbang: Rule 6f: $1" >&2; errors=$(( errors + 1 )); r6f_ok=0; }
  DG_BR_RE="$(r6_extract DIGEST_GROUP_BRANCH_REGEX || true)"
  DG_TI_RE="$(r6_extract DIGEST_GROUP_TITLE_REGEX || true)"
  if [ -z "$DG_BR_RE" ]; then
    r6f_fail "could not extract DIGEST_GROUP_BRANCH_REGEX from ${WF} — the assignment moved or was renamed; failing closed"
  else
    case "$DG_BR_RE" in
      '^dependabot/docker/'*'$') ;;
      *) r6f_fail "DIGEST_GROUP_BRANCH_REGEX ('${DG_BR_RE}') is not anchored as ^dependabot/docker/…\$ — it could accept other ecosystems or trailing text" ;;
    esac
    case "$DG_BR_RE" in
      */distroless-digests/distroless/*) ;;
      *) r6f_fail "DIGEST_GROUP_BRANCH_REGEX ('${DG_BR_RE}') dropped the literal '/distroless-digests/distroless/' segment — a PR from another group or image would reach the grouped digest path" ;;
    esac
    if grep -Eq '\[0-9a-f\]\{[0-9]+,\}\$$' <<<"$DG_BR_RE"; then
      r6f_floor="$(printf '%s' "$DG_BR_RE" | sed -E 's/.*\[0-9a-f\]\{([0-9]+),\}\$$/\1/')"
      r6_ge "$r6f_floor" 7 || r6f_fail "DIGEST_GROUP_BRANCH_REGEX trailing hex-run floor is {${r6f_floor},} — it must be at least {7,}"
    else
      r6f_fail "DIGEST_GROUP_BRANCH_REGEX ('${DG_BR_RE}') does not end in a hex-run quantifier ([0-9a-f]{N,}\$)"
    fi
  fi
  if [ -z "$DG_TI_RE" ]; then
    r6f_fail "could not extract DIGEST_GROUP_TITLE_REGEX from ${WF} — the assignment moved or was renamed; failing closed"
  else
    case "$DG_TI_RE" in
      '^'*'$') ;;
      *) r6f_fail "DIGEST_GROUP_TITLE_REGEX ('${DG_TI_RE}') is not anchored at both ends (^…\$)" ;;
    esac
    case "$DG_TI_RE" in
      *distroless/*) ;;
      *) r6f_fail "DIGEST_GROUP_TITLE_REGEX ('${DG_TI_RE}') no longer requires 'distroless/' — a grouped bump of any image could pass the title check" ;;
    esac
    case "$DG_TI_RE" in
      *'.*'*|*'.+'*) r6f_fail "DIGEST_GROUP_TITLE_REGEX ('${DG_TI_RE}') contains a .* or .+ wildcard — it no longer pins Dependabot's grouped title shape" ;;
    esac
  fi
  # digest_only_diff must still exist and keep each conjunct below; weakening one
  # means deleting or editing its pinned text.
  r6f_fn="$(awk '/^[[:space:]]*digest_only_diff\(\) \{/ { inblk = 1 } inblk { print } inblk && /^[[:space:]]*\}[[:space:]]*$/ { exit }' "$WF")"
  if [ -z "$r6f_fn" ]; then
    r6f_fail "could not locate the digest_only_diff() function in ${WF} — the grouped digest path has lost its diff check; failing closed"
  else
    while IFS='~' read -r r6f_pin r6f_why; do
      r6e_live_has "$r6f_fn" "$r6f_pin" || r6f_fail "digest_only_diff() lost '${r6f_pin}' — ${r6f_why}"
    done <<'PINS'
@sha256:[0-9a-f]{64}(?![0-9a-f])~it no longer isolates exactly one 64-hex digest per line
contains("distroless/")~changed lines no longer have to name distroless/
(.patch // "") == ""~a file with no patch (binary or too large) would go unchecked
($f | length) != ($changed_files | tonumber)~a truncated file listing would go unnoticed
($rm | length) != ($ad | length)~unequal removed/added counts per file would no longer be refused
range(0; $rm | length) as $i | $rm[$i] as $r | $ad[$i] as $a~lines are no longer paired in order within each file
($r | dg | length) != 1 or ($a | dg | length) != 1~a dropped, added or merged digest pin would pass
($r | gsub(sha; "")) != ($a | gsub(sha; ""))~a tag or image change would pass
($r | dg) == ($a | dg)~a pair whose digest did not move would pass
PINS
  fi
  # In path B: the grouped regexes are a negated, fail-closed guard, and the
  # digest_only_diff call is its own `if ! …; then … return 1 … fi`.
  r6f_subblock() {  # $1 = path-B text, $2 = token required on the `if !` line
    awk -v tok="$2" '
      !inblk && $0 ~ /^[[:space:]]*if ! / && index($0, tok) { inblk = 1; print; next }
      inblk { print; if ($0 ~ /^[[:space:]]*fi[[:space:]]*$/) exit }
    ' <<<"$1"
  }
  if [ -n "$r6c_block" ]; then
    r6f_grp="$(r6f_subblock "$r6c_block" 'DIGEST_GROUP_BRANCH_REGEX')"
    if [ -z "$r6f_grp" ] || ! r6e_live_has "$r6f_grp" 'DIGEST_GROUP_TITLE_REGEX' || ! r6e_fails_closed "$r6f_grp"; then
      r6f_fail "path B no longer refuses (return 1) a PR unless BOTH DIGEST_GROUP_BRANCH_REGEX and DIGEST_GROUP_TITLE_REGEX match, in one 'if ! …' guard"
    fi
    r6f_diff="$(r6f_subblock "$r6c_block" 'digest_only_diff')"
    if [ -z "$r6f_diff" ] || ! r6e_fails_closed "$r6f_diff"; then
      r6f_fail "path B no longer calls digest_only_diff in an 'if ! …' guard that fails closed (return 1) — a grouped distroless PR would auto-merge on its branch and title alone, with no proof it only moves digests"
    fi
  fi
  # dependabot.yml: the distroless-digests group must exist and match distroless images only.
  if [ -f "$DEPENDABOT_YML" ]; then
    r6f_patterns="$(awk '
      { match($0, /^ */); ind = RLENGTH }
      ind == 6 && /^      distroless-digests:[[:space:]]*$/ { ingrp = 1; next }
      ingrp && ind <= 6 && $0 !~ /^[[:space:]]*(#.*)?$/ { ingrp = 0 }
      ingrp && ind == 8 { inpat = ($0 ~ /^ +patterns:[[:space:]]*$/) }
      ingrp && inpat && ind == 10 && /^ +- / { v = $0; sub(/^ +- +/, "", v); gsub(/"/, "", v); print v }
    ' "$DEPENDABOT_YML")"
    if [ -z "$r6f_patterns" ]; then
      r6f_fail "no 'distroless-digests' group with patterns found in ${DEPENDABOT_YML} — the grouped digest path in ${WF} no longer matches the config; failing closed"
    else
      while IFS= read -r p; do
        case "$p" in
          distroless/?*) ;;
          *) r6f_fail "Dependabot group 'distroless-digests' has pattern '${p}' — only 'distroless/…' patterns may feed the grouped digest path" ;;
        esac
      done <<<"$r6f_patterns"
    fi
  fi
  [ "$r6f_ok" -eq 1 ] && echo "    :white_check_mark: path B grouped form: distroless-digests scoped branch/title, digest_only_diff fails closed, group patterns distroless-only"
fi

# ══════════════════════════════════════════════════════════════════════
# Rule 7 — no pipe into an early-exit grep in a pipefail script
# ══════════════════════════════════════════════════════════════════════
echo "--- :droplet: Rule 7: no pipe into an early-exit grep in a pipefail shell script"

# See R1_ALLOWLIST for the format/justification contract. Entry form: "<file>:<lineno>"
# (the line the pipeline starts on). No legitimate case is known: a bash here-string,
# or a POSIX heredoc in sh contexts, is always available. Multi-line `sh -c '…'`
# payloads are deliberately still scanned (not skipped): telling them apart needs
# cross-line quote parsing that would fail open, and the heredoc fix is always valid.
R7_ALLOWLIST=()

r7_is_allowlisted() {
  local loc="$1" entry
  # ${arr[@]+…} keeps an EMPTY array from tripping `set -u` on bash 3.2.
  for entry in ${R7_ALLOWLIST[@]+"${R7_ALLOWLIST[@]}"}; do
    [ "$loc" = "$entry" ] && return 0
  done
  return 1
}

# Print "<lineno>\t<logical line>" for each pipe (`|`, `|&`) into grep -q/-m/--quiet/
# --silent/--max-count, also wrapped in ( ) or { }, behind command/env/timeout/VAR=,
# or spelt \grep, /bin/grep, egrep. Lines joined on `\`, `|`, `&&` (blank or comment
# lines under a pending `|`/`&&` are skipped). Quoted text is blanked so a mention is
# not flagged, but a line is matched raw if a quote stays open or it runs code:
# "$(…)", a backtick inside quotes, or eval.
r7_hits() {
  strip_comments "$1" | awk '
    BEGIN {
      arg = "[[:space:]]+(-[^[:space:]]+|[A-Za-z_][A-Za-z0-9_]*=[^[:space:]]*)"
      prefix = "((command([[:space:]]+-[pvV])*|env(" arg ")*|timeout([[:space:]]+(-[^[:space:]]+|[A-Z0-9.]+[smhd]?))+|[A-Za-z_][A-Za-z0-9_]*=[^[:space:]]*)[[:space:]]+)*"
      re = "(^|[^|])[|]&?[[:space:]]*([({][[:space:]]*)?" prefix "(\\\\)?((/usr)?/bin/)?(e|f)?grep([[:space:]]+[^|;&]*)?[[:space:]](-[A-Za-z]*[qm][A-Za-z0-9]*|--quiet|--silent|--max-count[=0-9]*)([[:space:]]|$)"
    }
    function unquote(s,   out, i, n, c, q) {
      if (s ~ /(^|[^A-Za-z0-9_])eval[[:space:]]/) return s
      out = ""; q = ""; n = length(s)
      for (i = 1; i <= n; i++) {
        c = substr(s, i, 1)
        if (q == "") {
          if (c == "\\") { out = out c substr(s, i + 1, 1); i++; continue }
          if (c == "\047" || c == "\"") q = c
          out = out c
        } else if (q == "\"" && (c == "`" || (c == "$" && substr(s, i + 1, 2) ~ /^\([^(]/))) {
          return s
        } else if (q == "\"" && c == "\\") { i++ }
        else if (c == q) { out = out c; q = "" }
      }
      return (q == "") ? out : s
    }
    function flush() { if (buf != "" && buf ~ re) print start "\t" raw; buf = ""; raw = ""; pend = 0 }
    {
      if (buf != "" && pend && $0 ~ /^[[:space:]]*$/) next
      if (buf == "") start = NR
      line = $0; cont = 0; pend = 0
      if (line ~ /\\[[:space:]]*$/) { sub(/\\[[:space:]]*$/, " ", line); cont = 1 }
      else if (line ~ /[|&][[:space:]]*$/) { cont = 1; pend = 1 }
      raw = raw line
      buf = buf unquote(line)
      if (!cont) flush()
    }
    END { flush() }
  '
}

# Self-test: the detector must report exactly the fixture lines marked #@flag and
# none marked #@pass, so a regression in the quote handling fails here, not silently.
R7_FIXTURE=".buildkite/scripts/steps/check-false-green-guards.rule7-fixture"
if [ ! -f "$R7_FIXTURE" ]; then
  echo "+++ :bangbang: Rule 7: detector fixture ${R7_FIXTURE} is missing — the detector cannot be self-tested; failing closed" >&2
  errors=$(( errors + 1 ))
else
  r7_want="$(awk '/^#@flag$/ { print NR + 1 }' "$R7_FIXTURE")"
  r7_npass="$(awk '/^#@pass$/ { n++ } END { print n + 0 }' "$R7_FIXTURE")"
  r7_got="$(r7_hits "$R7_FIXTURE" | cut -f1)"
  if [ -z "$r7_want" ] || [ "$r7_npass" -eq 0 ]; then
    echo "+++ :bangbang: Rule 7: detector fixture has no #@flag or no #@pass cases — the self-test would prove nothing; failing closed" >&2
    errors=$(( errors + 1 ))
  elif [ "$r7_got" != "$r7_want" ]; then
    echo "+++ :bangbang: Rule 7: detector self-test FAILED on ${R7_FIXTURE} — want lines [$(tr '\n' ' ' <<<"$r7_want")] got [$(tr '\n' ' ' <<<"$r7_got")]. The detector has regressed; fix r7_hits before trusting this rule." >&2
    errors=$(( errors + 1 ))
  else
    echo "    :white_check_mark: detector self-test: $(wc -l <<<"$r7_want" | tr -d ' ') must-flag and ${r7_npass} must-pass fixture cases correct"
  fi
fi

# Swept corpus: every tracked *.sh that sets pipefail, plus every *.sh under
# .buildkite/ and scripts/ (a sourced library inherits its caller's pipefail without
# naming it), plus every GitHub workflow / composite action: `shell: bash` runs with
# -o pipefail, and a run: block may `set -o pipefail` itself. Whole YAML files are
# scanned; outside run: blocks the pattern does not occur.
R7_SEEN=$'\n'
r7_scanned=0
while IFS= read -r script; do
  [ -n "$script" ] || continue
  case "$script" in
    .buildkite/*|scripts/*|.github/workflows/*|.github/actions/*) ;;
    *) grep -q 'pipefail' "$script" || continue ;;
  esac
  r7_scanned=$(( r7_scanned + 1 ))
  while IFS=$'\t' read -r lineno text; do
    [ -n "$lineno" ] || continue
    loc="${script}:${lineno}"
    if r7_is_allowlisted "$loc"; then
      R7_SEEN="${R7_SEEN}${loc}"$'\n'
      echo "    :white_check_mark: allow-list ok: ${loc} (early-exit grep pipeline, justified)"
    else
      trimmed="$(sed -E 's/^[[:space:]]*//' <<<"$text")"
      echo "+++ :bangbang: ${loc}: pipes into an early-exit grep ('${trimmed}') — under pipefail, grep -q exiting on its first match SIGPIPEs a writer that still has output, so the pipeline fails (141, or curl 23): a false RED in an 'if !' check and a false GREEN in an 'if' check. In bash use a here-string (grep -q PAT <<<\"\$x\"); in a POSIX sh context (an 'sh -c' payload, a #!/bin/sh script) use a heredoc (grep -q PAT <<EOF / \$x / EOF), which has no writer process either; or capture the writer's output first." >&2
      errors=$(( errors + 1 ))
    fi
  done < <(r7_hits "$script")
done < <(git ls-files '*.sh' '.github/workflows/*.yml' '.github/workflows/*.yaml' \
                       '.github/actions/*.yml' '.github/actions/*.yaml')

# Fail closed on an empty sweep: the corpus always includes this script itself.
if [ "$r7_scanned" -eq 0 ]; then
  echo "+++ :bangbang: Rule 7: swept zero shell scripts/workflows — the sweep matched nothing (paths moved?), so the rule can never fire; failing closed" >&2
  errors=$(( errors + 1 ))
else
  echo "    ${r7_scanned} shell script(s) and workflow(s) swept"
fi

for entry in ${R7_ALLOWLIST[@]+"${R7_ALLOWLIST[@]}"}; do
  if ! grep -Fxq -- "$entry" <<<"$R7_SEEN"; then
    echo "+++ :bangbang: Rule 7 allow-list entry '${entry}' no longer starts an early-exit grep pipeline — remove it (the allow-list must not rot into a no-op)" >&2
    errors=$(( errors + 1 ))
  fi
done

# ══════════════════════════════════════════════════════════════════════
# Rule 8 — no status-tolerant HTTP status assertion in a test
# ══════════════════════════════════════════════════════════════════════
echo "--- :dart: Rule 8: test assertions must pin the exact HTTP status"

# Exemption is an inline comment on the assertion (or the line above it), never an
# allow-list: "status-tolerance-ok: <reason of 10+ characters>". A marker that excuses
# no loose assertion, or that gives no reason, fails the build like a violation.
#
# R8_AWK prints "<file>\t<line>\t<loose|allowed|noreason|orphan>\t<statement>" for each
# assertion (joined across lines, comments stripped, strings blanked unless a bare status
# such as "404") whose matcher admits several status literals and whose subject or message
# names a status; a negated or anyOf matcher on status literals needs no subject. Matching
# is textual: a status held in a constant or a helper is not seen. Avoid a repeated group
# inside a repeated group: mawk 1.3.4 20200120 (Debian 12, Ubuntu 22.04) mismatches it.
# shellcheck disable=SC2016  # an awk program, not a shell expansion
R8_AWK='
function langof(f) {
  if (forcelang != "") return forcelang
  if (f ~ /\.(java|kt|kts|groovy)$/) return "java"
  if (f ~ /\.(ts|tsx|js|jsx|mjs|cjs)$/) return "js"
  if (f ~ /\.py$/) return "py"
  if (f ~ /\.go$/) return "go"
  return ""
}
# Strip comments and blank string contents, keeping a literal that is exactly an
# HTTP status ("404") so a string-typed status still reaches the signatures.
function code(s,   out, i, n, c, q, lit, j, rest) {
  out = ""; q = ""; lit = ""; n = length(s); i = 1
  while (i <= n) {
    rest = substr(s, i)
    if (inblock) {
      j = index(rest, "*/"); if (j == 0) return out
      inblock = 0; i += j + 1; continue
    }
    if (mq != "") {
      j = index(rest, mq); if (j == 0) return out
      i += j + length(mq) - 1; mq = ""; out = out "\"\""; continue
    }
    if (q != "") {
      c = substr(s, i, 1)
      if (c == "\\") { i += 2; continue }
      if (c == q) { out = out q ((lit ~ /^[1-5][0-9][0-9]$/) ? lit : "") q; q = ""; lit = "" }
      else lit = lit c
      i++; continue
    }
    if (!match(rest, specials)) return out rest
    out = out substr(rest, 1, RSTART - 1); i += RSTART - 1
    c = substr(s, i, 1)
    if (substr(s, i, 3) == "\"\"\"" || (lang == "py" && substr(s, i, 3) == "\047\047\047")) { mq = substr(s, i, 3); i += 3; continue }
    if (c == "`") { mq = c; i++; continue }
    if (c == "\"" || c == "\047") { q = c; i++; continue }
    if (c == "#") { if (lang == "py") return out; out = out c; i++; continue }
    if (lang != "py" && substr(s, i + 1, 1) == "/") return out
    if (lang != "py" && substr(s, i + 1, 1) == "*") { inblock = 1; i += 2; continue }
    out = out c; i++
  }
  if (q != "") out = out q q
  return out
}
function nparen(s,   t, o, cl) { t = s; o = gsub(/\(/, "", t); t = s; cl = gsub(/\)/, "", t); return o - cl }
function loose(b) {
  if (b ~ sig_not || b ~ sig_any || b ~ sig_in || b ~ sig_range || b ~ sig_jsnot || b ~ sig_list || b ~ sig_ne || b ~ sig_div) return 1
  if (lang == "py") return b ~ sig_rel || b ~ sig_rel2 || b ~ sig_pyin || b ~ sig_pyor || b ~ sig_pyassert
  if (lang == "go") return b ~ sig_gorel || b ~ sig_rel2 || b ~ sig_goand || b ~ sig_gotest
  return b ~ sig_false || (b ~ boolctx && (b ~ sig_rel || b ~ sig_rel2 || b ~ sig_or))
}
function flush(   k, allowed) {
  if (buf != "" && (((buf ~ subj || raw ~ subj) && loose(buf)) || buf ~ sig_not || buf ~ sig_jsnot || buf ~ sig_any2)) {
    allowed = 0
    if ((start - 1) in markonly) { allowed = mark[start - 1]; used[start - 1] = 1 }
    for (k = start; k <= stop; k++) if (k in mark) { allowed = mark[k]; used[k] = 1 }
    gsub(/[[:space:]]+/, " ", raw)
    print curfile "\t" start "\t" (allowed == 1 ? "allowed" : (allowed == 2 ? "noreason" : "loose")) "\t" raw
  }
  buf = ""; raw = ""; depth = 0; pend = 0; nl = 0
}
function endfile(   k) {
  flush()
  for (k in mark) if (!(k in used)) print curfile "\t" k "\torphan\t" mtext[k]
  for (k in mark) delete mark[k]
  for (k in used) delete used[k]
  for (k in mtext) delete mtext[k]
  for (k in markonly) delete markonly[k]
  inblock = 0; mq = ""
}
BEGIN {
  OFS = "\t"
  S = "\"?[1-5][0-9][0-9]\"?([^0-9A-Za-z_.\"]|$)"
  W = "[[:space:]]*"
  subj = "[Ss]tatus|\\.code\\(\\)|[Rr]esponse[Cc]ode"
  Q = "([A-Za-z]*Matchers\\.|Is\\.|IsNot\\.|IsEqual\\.|AnyOf\\.)?"
  L = "\"?[1-5][0-9][0-9]\"?"
  sig_not = "(^|[^A-Za-z0-9_.]|Matchers\\.|IsNot\\.)not" W "\\(" W "(" Q "(is|equalTo)" W "\\(" W ")?" S
  sig_any = "(anyOf|either|not" W "\\(" W Q "anyOf)" W "\\(" W Q "(is|equalTo)" W "\\(" W S
  sig_any2 = "(anyOf|either)" W "\\(" W Q "(is|equalTo)" W "\\(" W L W "\\)" W "\\)?" W "[,.]" W "(or" W "\\(" W ")?" Q "(is|equalTo)" W "\\(" W S
  sig_div = "/" W "100([^0-9.]|$)"
  sig_in = "(^|[^A-Za-z0-9_])(oneOf|isOneOf|isIn|isNotIn|in)" W "\\(" W "((Arrays\\.)?asList|List\\.of|Set\\.of)?" W "\\(?" W S
  sig_range = "(^|[^A-Za-z0-9_])(greaterThan|greaterThanOrEqualTo|lessThan|lessThanOrEqualTo|isGreaterThan|isGreaterThanOrEqualTo|isLessThan|isLessThanOrEqualTo|isBetween|isStrictlyBetween|isNotEqualTo|toBeGreaterThan|toBeGreaterThanOrEqual|toBeLessThan|toBeLessThanOrEqual|Less|LessOrEqual|Greater|GreaterOrEqual|NotEqual|NotContains)" W "\\(" W "([^()]*," W ")?" S
  sig_jsnot = "\\." W "not" W "\\." W "(toBe|toEqual|toStrictEqual)" W "\\(" W S
  sig_list = "[[(]" W "\"?[1-5][0-9][0-9]\"?" W "," "[^])]*[])]" W "\\)?" W "\\.(toContain|includes|contains|indexOf)" W "\\("
  sig_ne = "(^|[^A-Za-z0-9_])(assertNotEquals|notStrictEqual|notEqual|assertNotEqual|assert_ne)" W "\\(" "([^()]*,)?" W S
  sig_rel = "(!==?|(^|[^=<>!-])[<>]=?)" W S
  sig_rel2 = "\"?[1-5][0-9][0-9]\"?" W "(<|>|<=|>=)[^=>]"
  sig_or = "===?" W S "[^;]*\\|\\|[^;]*===?" W S
  sig_false = "assertFalse" W "\\(.*==" W S
  sig_gorel = "(^|[^=<>!-])[<>]=?" W S
  sig_pyor = "==" W "[1-5][0-9][0-9]" W "[[:space:]]or[[:space:]].*==" W S
  boolctx = "(^|[^A-Za-z0-9_])(assertTrue|assertFalse|assert" W "\\(|assert\\.ok)|is" W "\\(" W "(true|false)" W "\\)|toBe" W "\\(" W "(true|false)" W "\\)|toBeTruthy|toBeFalsy"
  sig_pyin = "(^|[^A-Za-z0-9_])in" W "[[({]" W "\"?[1-5][0-9][0-9]\"?" W ","
  sig_pyassert = "assert(In|NotIn|NotEqual|Less|LessEqual|Greater|GreaterEqual)" W "\\(.*" S
  sig_goand = "!=" W S "[^{]*&&[^{]*!=" W S
  sig_gotest = "(assert|require)\\.(Contains|Subset|ElementsMatch)" W "\\(.*\\[\\][a-z0-9]*int[{]" W S
  mk = "(//|#)[[:space:]]*status-tolerance-ok:"
  specials = "[\"\047`/#]"
  prefilter = "assert|expect|require|if[[:space:]]|/\\*|\"\"\"|\047\047\047|`"
}
FNR == 1 { if (NR > 1) endfile(); curfile = FILENAME; lang = langof(FILENAME) }
{
  line = $0
  if (match(line, mk)) {
    reason = substr(line, RSTART + RLENGTH)
    gsub(/[[:space:]]/, "", reason)
    mark[FNR] = (length(reason) >= 10) ? 1 : 2
    mtext[FNR] = line
    if (line ~ /^[[:space:]]*(\/\/|#)/) markonly[FNR] = 1
  }
  if (buf == "" && !inblock && mq == "" && line !~ prefilter) next
  c = code(line)
  if (buf != "" && pend) {
    if (c ~ /^[[:space:]]*\./ && lang != "py" && lang != "go") { pend = 0 }
    else flush()
  }
  if (buf == "") {
    if (lang == "java" || lang == "js") startre = "(^|[^A-Za-z0-9_])(assert[A-Za-z]*|expect)" W "(\\.[A-Za-z]+)?" W "\\("
    else if (lang == "py") startre = "(^|[^A-Za-z0-9_])(assert([[:space:]]|\\()|self\\.assert[A-Za-z]+" W "\\()"
    else if (lang == "go") startre = "(^|[^A-Za-z0-9_])(if[[:space:]]|(assert|require)\\.[A-Za-z]+" W "\\()"
    else next
    if (c !~ startre) next
    start = FNR
  }
  buf = buf " " c; raw = raw " " line; stop = FNR; nl++
  depth += nparen(c)
  if (nl >= 15) { flush(); next }
  if (depth <= 0) {
    if (lang == "java" || lang == "js") { if (c ~ /;[[:space:]]*$/) flush(); else pend = 1 }
    else if (lang != "go" || c !~ /(&&|\|\|)[[:space:]]*$/) flush()
  }
}
END { if (NR > 0) endfile() }
  '
r8_hits() {  # $1 = forced language (java|js|py|go) or "" to infer from each extension
  awk -v forcelang="$1" "$R8_AWK" "${@:2}"
}

R8_FIXTURES=".buildkite/scripts/steps/check-false-green-guards.rule8-fixtures"
for r8_lang in java js py go; do
  r8_fixture="${R8_FIXTURES}/${r8_lang}.txt"
  if [ ! -f "$r8_fixture" ]; then
    echo "+++ :bangbang: Rule 8: detector fixture ${r8_fixture} is missing — the ${r8_lang} detector cannot be self-tested; failing closed" >&2
    errors=$(( errors + 1 ))
    continue
  fi
  # A directive on line N names the outcome the detector must report for line N+1.
  r8_want="$(awk '
    match($0, /(\/\/|#)@(flag|allow|noreason|orphan|pass)[[:space:]]*$/) {
      k = substr($0, RSTART); sub(/^(\/\/|#)@/, "", k); sub(/[[:space:]]*$/, "", k)
      if (k == "pass") next
      if (k == "flag") k = "loose"
      if (k == "allow") k = "allowed"
      print NR + 1 "\t" k
    }' "$r8_fixture")"
  r8_npass="$(awk '/(\/\/|#)@pass[[:space:]]*$/ { n++ } END { print n + 0 }' "$r8_fixture")"
  r8_got="$(r8_hits "$r8_lang" "$r8_fixture" | cut -f2,3)"
  if [ -z "$r8_want" ] || [ "$r8_npass" -eq 0 ]; then
    echo "+++ :bangbang: Rule 8: ${r8_fixture} has no must-report or no #@pass cases — the self-test would prove nothing; failing closed" >&2
    errors=$(( errors + 1 ))
  elif [ "$r8_got" != "$r8_want" ]; then
    echo "+++ :bangbang: Rule 8: ${r8_lang} detector self-test FAILED on ${r8_fixture} — want [$(tr '\n\t' ' :' <<<"$r8_want")] got [$(tr '\n\t' ' :' <<<"$r8_got")]. The detector has regressed; fix r8_hits before trusting this rule." >&2
    errors=$(( errors + 1 ))
  else
    echo "    :white_check_mark: ${r8_lang} detector self-test: $(wc -l <<<"$r8_want" | tr -d ' ') must-report and ${r8_npass} must-pass fixture cases correct"
  fi
done

# Swept corpus: test sources in every module and client, plus the shared integration
# suites that live in mockserver-integration-testing/src/main. Untracked files are
# included so a new test is checked before it is first committed.
r8_files="$(git ls-files --cached --others --exclude-standard -- \
  '*/src/test/*.java' '*/src/test/*.kt' '*/src/test/*.groovy' \
  'mockserver/mockserver-integration-testing/src/main/*.java' \
  '*_test.go' \
  '*.test.ts' '*.test.tsx' '*.test.js' '*.test.mjs' '*.test.cjs' \
  '*.spec.ts' '*.spec.tsx' '*.spec.js' '*.spec.mjs' \
  '*/__tests__/*.ts' '*/__tests__/*.tsx' '*/__tests__/*.js' \
  '*/e2e/*.ts' '*/e2e/*.js' '*/test/*.ts' '*/test/*.js' '*/tests/*.ts' '*/tests/*.js' \
  'test_*.py' '*/test_*.py' '*_test.py' '*/tests/*.py' '*/test/*.py' | sort -u)"
r8_scanned=0
r8_list=()
while IFS= read -r f; do
  [ -n "$f" ] && [ -f "$f" ] || continue
  r8_list+=("$f")
  r8_scanned=$(( r8_scanned + 1 ))
done <<<"$r8_files"

if [ "$r8_scanned" -eq 0 ]; then
  echo "+++ :bangbang: Rule 8: swept zero test sources — the sweep matched nothing (paths moved?), so the rule can never fire; failing closed" >&2
  errors=$(( errors + 1 ))
else
  # xargs batches the file list so it can never exceed ARG_MAX; awk state resets per file.
  if ! r8_out="$(printf '%s\0' "${r8_list[@]}" | xargs -0 awk -v forcelang= "$R8_AWK")"; then
    echo "+++ :bangbang: Rule 8: the detector failed while sweeping the test sources — failing closed" >&2
    errors=$(( errors + 1 ))
    r8_out=""
  fi
  while IFS=$'\t' read -r file lineno kind text; do
    [ -n "$file" ] || continue
    loc="${file}:${lineno}"
    case "$kind" in
      allowed)
        echo "    :white_check_mark: allowed: ${loc} (status-tolerance-ok marker with a reason)" ;;
      noreason)
        echo "+++ :bangbang: ${loc}: status-tolerance-ok marker gives no reason (10+ characters) — say why several statuses are genuinely correct here, or pin the exact status" >&2
        errors=$(( errors + 1 )) ;;
      orphan)
        echo "+++ :bangbang: ${loc}: status-tolerance-ok marker excuses no status-tolerant assertion — remove it (a marker must not rot into a no-op)" >&2
        errors=$(( errors + 1 )) ;;
      *)
        echo "+++ :bangbang: ${loc}: status assertion accepts more than one status ('${text# }') — a tolerance such as not(401), anyOf(200, 404) or < 300 passes when the endpoint answers an unexpected status, including a 500. Assert the exact status; if several are genuinely correct (a race between two valid outcomes), add a comment 'status-tolerance-ok: <reason>' on the assertion." >&2
        errors=$(( errors + 1 )) ;;
    esac
  done <<<"$r8_out"
  echo "    ${r8_scanned} test source(s) swept"
fi

# ══════════════════════════════════════════════════════════════════════
echo "--- :bar_chart: false-green guard summary: ${errors} error(s)"
if [ "$errors" -gt 0 ]; then
  echo "+++ :bangbang: false-green guard FAILED with ${errors} error(s) — a new false-green shape was introduced (see docs/operations/false-green-guards.md)" >&2
  exit 1
fi
echo "--- :white_check_mark: false-green guard PASSED"
