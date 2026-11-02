#!/usr/bin/env bash
# Fixture tests for steps/java-snapshot-publish-guard.sh with `aws`, `curl` (Buildkite API and the
# image registry) and `buildkite-agent` stubbed on PATH. The guard and its libraries are copied into
# a scratch git history, so the skip/publish decision runs against real commits. Also pins the
# publish gating in pipeline-java.yml and the schedule gating in pipeline-infra.yml.
# Run: .buildkite/scripts/test/java-snapshot-publish-guard-test.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
T="$(mktemp -d "${TMPDIR:-/tmp}/snapshot-publish-guard-test.XXXXXX")"
trap 'rm -rf "$T"' EXIT
FAILS=0
ok()  { echo "  ok   $1"; }
bad() { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }

G="$T/repo"
mkdir -p "$G/.buildkite/scripts/steps" "$G/.buildkite/scripts/lib" "$T/bin"
cp "$REPO_ROOT/.buildkite/scripts/steps/java-snapshot-publish-guard.sh" "$REPO_ROOT/.buildkite/scripts/steps/java-snapshot-publish-record.sh" "$G/.buildkite/scripts/steps/"
cp "$REPO_ROOT/.buildkite/scripts/lib/last-successful-commit.sh" "$REPO_ROOT/.buildkite/scripts/lib/snapshot-image-inputs.sh" "$G/.buildkite/scripts/lib/"
git -C "$G" init -q
git -C "$G" -c user.email=t@t -c user.name=t add -A
git -C "$G" -c user.email=t@t -c user.name=t commit -qm base
commit_change() { # path
  mkdir -p "$G/$(dirname "$1")"; echo "$RANDOM" >> "$G/$1"
  git -C "$G" add -A; git -C "$G" -c user.email=t@t -c user.name=t commit -qm "change $1"
  git -C "$G" rev-parse HEAD
}
PUBLISHED="$(git -C "$G" rev-parse HEAD)"
DOCS_TIP="$(commit_change docs/readme.md)"
PLUGIN_TIP="$(commit_change mockserver/mockserver-maven-plugin/pom.xml)"
JAVA_TIP="$(commit_change mockserver/mockserver-core/Foo.java)"
# One commit per publish path the guard watches, each branching from PLUGIN_TIP.
eval "$(sed -n '/^PUBLISH_PATHS=(/,/)$/p' "$REPO_ROOT/.buildkite/scripts/steps/java-snapshot-publish-guard.sh")"
# Pinned here as well, so dropping a path from the guard turns this test red.
for required in docker/ .buildkite/pipeline-java.yml .buildkite/scripts/steps/java-docker-push-snapshot.sh \
  .buildkite/scripts/steps/java-deploy-snapshot.sh .buildkite/scripts/steps/java-publish-snapshot-bundles.sh \
  scripts/build-all-bundles.sh scripts/buildkite_quick_build.sh; do
  [[ " ${PUBLISH_PATHS[*]:-} " == *" $required "* ]] || bad "the guard's PUBLISH_PATHS no longer lists $required"
done
PATH_TIPS=()
for path in "${PUBLISH_PATHS[@]}"; do
  git -C "$G" checkout -q "$PLUGIN_TIP"
  case "$path" in */) path="${path}local/Dockerfile" ;; esac
  PATH_TIPS+=("$path=$(commit_change "$path")")
done

cat > "$T/bin/aws" <<'EOF'
#!/usr/bin/env bash
[ -n "${FAKE_NO_TOKEN:-}" ] && exit 255
echo "fake-token"
EOF
# The registry: tag -> index -> linux/amd64 manifest -> config blob carrying the revision label.
cat > "$T/bin/curl" <<'EOF'
#!/usr/bin/env bash
case "$*" in *api.buildkite.com*) printf '%s' "$FAKE_BUILDS"; exit 0 ;; esac
url="${*: -1}"
case "$url" in
  *public.ecr.aws/token/*) [ -n "${FAKE_REGISTRY_DOWN:-}" ] && exit 22; echo '{"token":"t"}' ;;
  */manifests/sha256:amd64-*) echo "{\"config\":{\"digest\":\"sha256:cfg-${url##*/manifests/sha256:amd64-}\"}}" ;;
  */manifests/*) tag="${url##*/manifests/}"
    echo "{\"manifests\":[{\"digest\":\"sha256:arm-$tag\",\"platform\":{\"os\":\"linux\",\"architecture\":\"arm64\"}},{\"digest\":\"sha256:amd64-$tag\",\"platform\":{\"os\":\"linux\",\"architecture\":\"amd64\"}}]}" ;;
  */blobs/sha256:cfg-*) tag="${url##*/blobs/sha256:cfg-}"; rev="$FAKE_IMAGE_REV"
    [ "$tag" = snapshot-graaljs ] && [ -n "${FAKE_GRAALJS_REV:-}" ] && rev="$FAKE_GRAALJS_REV"
    echo "{\"config\":{\"Labels\":{\"org.opencontainers.image.revision\":\"$rev\"}}}" ;;
  *) exit 22 ;;
esac
EOF
cat > "$T/bin/buildkite-agent" <<'EOF'
#!/usr/bin/env bash
echo "ARGS: $*" >> "$FAKE_AGENT_LOG"
case "$1 ${2:-}" in
  "pipeline upload") cat >> "$FAKE_AGENT_LOG"; [ -n "${FAKE_UPLOAD_FAILS:-}" ] && exit 1 ;;
  "annotate "*) cat >> "$FAKE_AGENT_LOG" ;;
  "meta-data get") printf '%s' "${FAKE_BUNDLES_DONE:-}" ;;
esac
exit 0
EOF
chmod +x "$T/bin/"*

builds_with() { # published commit for the second-newest build; the newest has no meta-data
  jq -cn --arg c "$1" '[{number: 12, meta_data: {}}, {number: 11, meta_data: {snapshot_published_commit: $c}}, {number: 10, meta_data: {snapshot_published_commit: "0000000000000000000000000000000000000000"}}]'
}

# expect <name> <skip|publish> <head> <builds json> [env...]; the images default to the recorded commit.
expect() {
  local name="$1" want="$2" head="$3" builds="$4"; shift 4
  local log="$T/agent.log" out rc=0
  : > "$log"
  git -C "$G" checkout -q "$head"
  out="$(env PATH="$T/bin:$PATH" FAKE_BUILDS="$builds" FAKE_IMAGE_REV="$PUBLISHED" FAKE_AGENT_LOG="$log" BUILDKITE_BRANCH=master "$@" \
    "$G/.buildkite/scripts/steps/java-snapshot-publish-guard.sh" 2>&1)" || rc=$?
  if [ "$rc" -ne 0 ]; then bad "$name: exited $rc: $out"; return; fi
  if [ "$want" = skip ]; then
    if grep -qF -- "ARGS: pipeline upload --replace" "$log" && grep -qF -- 'command: "true"' "$log"; then ok "$name"; else bad "$name: expected the rest of the build replaced; log: $(cat "$log"); out: $out"; fi
  else
    if grep -qF -- "pipeline upload" "$log"; then bad "$name: expected a publish, but the build was replaced"; else ok "$name"; fi
  fi
}

echo "--- skip: nothing that changes a publish changed"
expect "the published commit itself" skip "$PUBLISHED" "$(builds_with "$PUBLISHED")"
expect "docs-only commits since" skip "$DOCS_TIP" "$(builds_with "$PUBLISHED")"
expect "a maven-plugin-only change since" skip "$PLUGIN_TIP" "$(builds_with "$PUBLISHED")"

echo "--- publish: an image input or publish script changed"
expect "a mockserver/ change since" publish "$JAVA_TIP" "$(builds_with "$PUBLISHED")"
for entry in "${PATH_TIPS[@]}"; do
  expect "a change to ${entry%%=*} since" publish "${entry#*=}" "$(builds_with "$PUBLISHED")"
done
expect "the recorded commit is newer than HEAD" publish "$PUBLISHED" "$(builds_with "$JAVA_TIP")"

echo "--- publish: the pushed images are not what the record says"
expect "an older image overwrote a newer record (racing publishes)" publish "$JAVA_TIP" "$(builds_with "$JAVA_TIP")" FAKE_IMAGE_REV="$PUBLISHED"
expect ":snapshot-graaljs lags :snapshot and the record" publish "$JAVA_TIP" "$(builds_with "$JAVA_TIP")" FAKE_IMAGE_REV="$JAVA_TIP" FAKE_GRAALJS_REV="$PUBLISHED"
expect ":snapshot-graaljs is newer than HEAD" publish "$PUBLISHED" "$(builds_with "$PUBLISHED")" FAKE_GRAALJS_REV="$JAVA_TIP"
expect "an image with no usable revision label" publish "$PUBLISHED" "$(builds_with "$PUBLISHED")" FAKE_IMAGE_REV=HEAD
expect "the registry is unreachable" publish "$PUBLISHED" "$(builds_with "$PUBLISHED")" FAKE_REGISTRY_DOWN=1

echo "--- publish: anything else the guard cannot establish"
expect "no token" publish "$DOCS_TIP" "$(builds_with "$PUBLISHED")" FAKE_NO_TOKEN=1
expect "no build records a publish" publish "$DOCS_TIP" '[{"number": 3, "meta_data": {}}]'
expect "a non-array API response" publish "$DOCS_TIP" '{"message": "Forbidden"}'
expect "a symbolic recorded commit" publish "$DOCS_TIP" "$(builds_with HEAD)"
expect "a recorded commit missing from the clone" publish "$DOCS_TIP" "$(builds_with 1111111111111111111111111111111111111111)"

echo "--- a failed replace still lets the build publish"
: > "$T/agent.log"
git -C "$G" checkout -q "$DOCS_TIP"
rc=0
out="$(env PATH="$T/bin:$PATH" FAKE_BUILDS="$(builds_with "$PUBLISHED")" FAKE_IMAGE_REV="$PUBLISHED" FAKE_AGENT_LOG="$T/agent.log" FAKE_UPLOAD_FAILS=1 BUILDKITE_BRANCH=master \
  "$G/.buildkite/scripts/steps/java-snapshot-publish-guard.sh" 2>&1)" || rc=$?
if [ "$rc" -eq 0 ] && grep -qF "could not replace the rest of the build — publishing ${DOCS_TIP:0:10}" <<<"$out" && ! grep -qF -- "annotate" "$T/agent.log"; then
  ok "the replace upload fails: exit 0, no skip annotation"
else
  bad "the replace upload fails: rc=$rc out=$out"
fi

echo "--- the record step records only a complete publish"
record() { # <BUILDKITE_COMMIT> [env...]; echoes the exit code
  local commit="$1"; shift
  : > "$T/agent.log"
  git -C "$G" checkout -q "$DOCS_TIP"
  local rc=0
  (cd "$G" && env PATH="$T/bin:$PATH" FAKE_AGENT_LOG="$T/agent.log" BUILDKITE_COMMIT="$commit" "$@" \
    "$G/.buildkite/scripts/steps/java-snapshot-publish-record.sh") >/dev/null 2>&1 || rc=$?
  echo "$rc"
}
rc="$(record "$DOCS_TIP" FAKE_BUNDLES_DONE=true)"
if [ "$rc" -eq 0 ] && grep -qxF "ARGS: meta-data set snapshot_published_commit $DOCS_TIP" "$T/agent.log"; then
  ok "bundles published: records the 40-hex commit"
else bad "bundles published: rc=$rc log=$(cat "$T/agent.log")"; fi
rc="$(record HEAD FAKE_BUNDLES_DONE=true)"
if [ "$rc" -eq 0 ] && grep -qxF "ARGS: meta-data set snapshot_published_commit $DOCS_TIP" "$T/agent.log"; then
  ok "a symbolic BUILDKITE_COMMIT=HEAD records the resolved SHA"
else bad "symbolic HEAD: rc=$rc log=$(cat "$T/agent.log")"; fi
rc="$(record "$DOCS_TIP")"
if [ "$rc" -eq 0 ] && ! grep -qF "meta-data set" "$T/agent.log" && grep -qF "ARGS: annotate" "$T/agent.log"; then
  ok "bundles not published: records nothing, warns, exits 0"
else bad "bundles not published: rc=$rc log=$(cat "$T/agent.log")"; fi
rc="$(record deadbeef FAKE_BUNDLES_DONE=true)"
if [ "$rc" -ne 0 ] && ! grep -qF "meta-data set" "$T/agent.log"; then
  ok "an unresolvable commit: fails and records nothing"
else bad "unresolvable commit: rc=$rc log=$(cat "$T/agent.log")"; fi
BUNDLES_SETS="$(grep -cE '^[[:space:]]*buildkite-agent meta-data set snapshot_bundles_done true$' \
  "$REPO_ROOT/.buildkite/scripts/steps/java-publish-snapshot-bundles.sh" || true)"
if [ "$BUNDLES_SETS" -ge 2 ]; then ok "the bundles step marks success on both of its success paths"
else bad "java-publish-snapshot-bundles.sh sets snapshot_bundles_done $BUNDLES_SETS time(s), expected 2 (the release-version skip and the end)"; fi

# Stdlib only (no PyYAML on the agents). Steps split on `  - label:`; comment lines are dropped, and
# each step's own keys are read at its 4-space indent, so text in a comment can never satisfy a check.
echo "--- the pipelines keep their gating"
gating="$(python3 - "$REPO_ROOT/.buildkite/pipeline-java.yml" "$REPO_ROOT/.buildkite/pipeline-infra.yml" <<'PY'
import re, sys
def steps(path):
    text = "\n".join(l for l in open(path, encoding="utf-8").read().split("\n") if not l.lstrip().startswith("#"))
    out = []
    for block in re.split(r"(?m)^  - (?=label:)", text)[1:]:
        lines = ("    " + block).split("\n")
        keys, lists, current = {}, {}, None
        for line in lines:
            m = re.match(r"^    ([a-z_]+):\s*(.*)$", line)
            if m:
                current = m.group(1)
                keys[current] = m.group(2).strip()
                continue
            m = re.match(r'^      - "?([^"]*)"?\s*$', line)
            if m and current:
                lists.setdefault(current, []).append(m.group(1))
        out.append((keys, lists))
    return out
errors = []
publish_if = "\"build.branch == 'master' && build.env('PUBLISH_SNAPSHOT') == 'true'\""
group = '"mockserver-java/snapshot-publish"'
publish_scripts = ("java-deploy-snapshot.sh", "java-docker-push-snapshot.sh",
                   "java-publish-snapshot-bundles.sh", "java-snapshot-publish-record.sh")
java = steps(sys.argv[1])
for script in publish_scripts:
    found = [(k, l) for k, l in java if script in k.get("command", "")]
    if len(found) != 1:
        errors.append("pipeline-java.yml: expected one step running %s, found %d" % (script, len(found)))
        continue
    k, l = found[0]
    if k.get("if") != publish_if:
        errors.append("pipeline-java.yml: the %s step has if: %s" % (script, k.get("if")))
    if k.get("concurrency_group") != group or k.get("concurrency") != "1":
        errors.append("pipeline-java.yml: the %s step is not in concurrency group %s with concurrency 1" % (script, group))
    if script == "java-snapshot-publish-record.sh" and sorted(l.get("depends_on", [])) != ["snapshot-bundles", "snapshot-deploy", "snapshot-docker"]:
        errors.append("pipeline-java.yml: the record step must depend on snapshot-deploy, snapshot-docker and snapshot-bundles, has %s" % l.get("depends_on"))
guard = [k for k, l in java if "java-snapshot-publish-guard.sh" in k.get("command", "")]
if len(guard) != 1:
    errors.append("pipeline-java.yml: expected one guard step, found %d" % len(guard))
else:
    if guard[0].get("if") != "\"build.source == 'schedule'\"":
        errors.append("pipeline-java.yml: the guard step must run only on scheduled builds, has if: %s" % guard[0].get("if"))
    if guard[0].get("soft_fail") != "true":
        errors.append("pipeline-java.yml: the guard step must be soft_fail: true")
infra = steps(sys.argv[2])
fresh = [k for k, l in infra if "perf-baseline-freshness" in k.get("command", "")]
if len(fresh) != 2:
    errors.append("pipeline-infra.yml: expected 2 freshness steps, found %d" % len(fresh))
for k, l in infra:
    if "perf-baseline-freshness" in k.get("command", ""):
        if "if" in k:
            errors.append("pipeline-infra.yml: freshness step %s carries an if:" % k.get("label"))
    elif k.get("if") != "\"build.source != 'schedule'\"":
        errors.append("pipeline-infra.yml: step %s runs on the schedule (if: %s)" % (k.get("label"), k.get("if")))
print("\n".join(errors))
PY
)" || gating="the gating check itself failed"
if [ -z "$gating" ]; then ok "publish steps, guard and record keep their gating; only the freshness steps run on the infra schedule"; else bad "$gating"; fi

echo
if [ "$FAILS" -gt 0 ]; then echo ":x: $FAILS check(s) failed" >&2; exit 1; fi
echo ":white_check_mark: all snapshot publish guard checks passed"
