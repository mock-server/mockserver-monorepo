#!/usr/bin/env bash
# Which client-language pipelines generate-pipeline.sh triggers: all seven on a wire-contract
# change (lib/client-wire-paths.sh) or FORCE_ALL_CLIENTS=true (none when an earlier passed sweep
# already covered HEAD), only the client's own pipeline on its own path, none on any other server
# change. Runs the real generator in a scratch git
# repo against a stub buildkite-agent.
# Run: .buildkite/scripts/test/generate-pipeline-clients-test.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
GEN="$REPO_ROOT/.buildkite/scripts/generate-pipeline.sh"
ALL_CLIENTS="mockserver-dotnet mockserver-go mockserver-node mockserver-php mockserver-python mockserver-ruby mockserver-rust"
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}
WORK="$(mktemp -d "${TMPDIR:-/tmp}/generate-pipeline-clients-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/bin" "$WORK/bin-api"
cat > "$WORK/bin/buildkite-agent" <<'STUB'
#!/usr/bin/env bash
[ "$1 $2" = "pipeline upload" ] && cat > "$UPLOAD_FILE"
exit 0
STUB
# The build lookups: no API by default; bin-api answers with $STUB_BUILDS (newest first), or
# else one bare build at $STUB_LAST_SHA, which carries no client-sweep fields.
printf '#!/usr/bin/env bash\nexit 1\n' > "$WORK/bin/aws"
printf '#!/usr/bin/env bash\necho fake-token\n' > "$WORK/bin-api/aws"
cat > "$WORK/bin-api/curl" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "${CURL_LOG:-/dev/null}"
[ -n "${STUB_FAIL_CURL:-}" ] && exit 22
if [ -n "${STUB_BUILDS:-}" ]; then printf '%s' "$STUB_BUILDS"; else echo "[{\"number\":1,\"commit\":\"$STUB_LAST_SHA\"}]"; fi
STUB
chmod +x "$WORK/bin/buildkite-agent" "$WORK/bin/aws" "$WORK/bin-api/aws" "$WORK/bin-api/curl"

REPO="$WORK/repo"
git init -q -b master "$REPO"
git -C "$REPO" config user.email test@example.com
git -C "$REPO" config user.name test
git -C "$REPO" config commit.gpgsign false
echo base > "$REPO/README.md"
git -C "$REPO" add README.md
git -C "$REPO" commit -qm base

change() { # path... -> a new HEAD commit touching exactly these paths
  local p
  for p in "$@"; do
    mkdir -p "$REPO/$(dirname "$p")"
    echo "$RANDOM" >> "$REPO/$p"
  done
  git -C "$REPO" add -A
  git -C "$REPO" commit -qm "change $1"
}

generate() { # case_name env... -> $WORK/<case_name>.yml; returns the generator's exit status
  local name="$1"; shift
  rm -f "$WORK/$name.yml"
  ( cd "$REPO" && env -i PATH="$WORK/bin:$PATH" HOME="${HOME:-/tmp}" UPLOAD_FILE="$WORK/$name.yml" \
      BUILDKITE_BRANCH=master "$@" bash "$GEN" ) >"$WORK/$name.log" 2>&1
}
run_case() { # case_name env...
  generate "$@" || bad "$1: generator exited non-zero ($(tail -2 "$WORK/$1.log" | tr '\n' ' '))"
}
triggered() { # case_name -> sorted, space-separated slugs of the triggered pipelines
  { grep -oE 'trigger-pipeline\.sh [a-z.-]+' "$WORK/$1.yml" 2>/dev/null || true; } | awk '{print $2}' | sort -u | tr '\n' ' ' | sed 's/ $//'
}
clients() { # case_name -> the triggered client pipelines only
  local t; t="$(triggered "$1")"
  { tr ' ' '\n' <<<"$t" | grep -E '^mockserver-(node|python|ruby|go|dotnet|rust|php)$' || true; } | tr '\n' ' ' | sed 's/ $//'
}
has() { grep -qw -- "$2" <<<"$(triggered "$1")" && echo yes || echo no; }

echo "--- 1. server changes outside the wire contract run no client pipeline"
change mockserver/mockserver-netty/src/main/java/org/mockserver/netty/proxy/RelayConnectHandler.java
run_case netty_internal
check "netty internal: no clients" "" "$(clients netty_internal)"
check "netty internal: still runs Java" "yes" "$(has netty_internal mockserver-java)"
change mockserver/mockserver-core/src/test/java/org/mockserver/model/HttpRequestTest.java
run_case server_test
check "server test source: no clients" "" "$(clients server_test)"
change mockserver/mockserver-core/src/main/java/org/mockserver/serialization/java/ExpectationToJavaSerializer.java
run_case java_codegen
check "Java code generator: no clients" "" "$(clients java_codegen)"
change mockserver/mockserver-core/src/main/java/org/mockserver/httpclient/NettyHttpClient.java \
  mockserver/mockserver-core/src/main/java/org/mockserver/configuration/Configuration.java
run_case core_internal
check "httpclient + configuration: no clients" "" "$(clients core_internal)"
change mockserver/mockserver-maven-plugin/src/main/java/org/mockserver/maven/MockServerRunAndWaitPlugin.java
run_case maven_plugin
check "maven plugin: no clients" "" "$(clients maven_plugin)"

echo "--- 2. wire-contract changes run all seven clients"
for p in \
    mockserver/mockserver-core/src/main/java/org/mockserver/model/HttpRequest.java \
    mockserver/mockserver-core/src/main/java/org/mockserver/mock/RequestMatchExplainer.java \
    test-fixtures/expectations/top_metadata.json \
    .buildkite/scripts/build-local-mockserver-image.sh; do
  change "$p"
  run_case wire
  check "wire (end to end): $p" "$ALL_CLIENTS" "$(clients wire)"
done

# One path per WIRE_CHANGES_REGEX alternative, so dropping any entry fails here.
C=mockserver/mockserver-core/src/main/java/org/mockserver
N=mockserver/mockserver-netty/src/main/java/org/mockserver
# shellcheck source=../lib/client-wire-paths.sh
. "$REPO_ROOT/.buildkite/scripts/lib/client-wire-paths.sh"
wire_ok() { [ -n "$(client_wire_changes <<<"$1")" ] && echo yes || echo no; }
for p in test-fixtures/expectations/a.json \
    mockserver/mockserver-core/src/main/resources/org/mockserver/authentication/jwk.json \
    $C/{model,mock,validator,mappers,matchers,verify,closurecallback,authentication,oidc,socket,grpc,graphql}/X.java \
    $C/{responsewriter,templates,llm,log,slo,metrics,load,openapi,imports,cors}/X.java \
    $C/mock/action/http/GrpcStreamResponseActionHandler.java $C/mock/dns/DnsIntentRegistry.java \
    $C/serialization/ExpectationSerializer.java $C/serialization/{model,serializers,deserializers,har}/X.java \
    $C/codec/{BodyDecoderEncoder,NettyHttpToMockServerHttpRequestDecoder,MockServerHttpToNettyHttpResponseEncoder}.java \
    $C/codec/{ExpandedParameterDecoder,PathParametersDecoder,MultipartFormDataDecoder,JsonSchemaBodyDecoder}.java \
    $N/netty/{HttpRequestHandler,OpenAPISpecHandler,unification/PortUnificationHandler}.java \
    $N/netty/{websocketregistry,dns,grpc,responsewriter}/X.java $N/{cli,lifecycle}/X.java \
    mockserver/mockserver-core/src/test/resources/grpc/greeting.dsc \
    mockserver/mockserver-netty/src/test/resources/org/mockserver/netty/integration/tls/ca.pem \
    mockserver/mockserver-netty-docker/pom.xml docker/local/Dockerfile docker/ensure-ca-bundle.sh \
    .buildkite/scripts/{build-local-mockserver-image,with-mockserver,run-in-docker}.sh; do
  check "wire: $p" "yes" "$(wire_ok "$p")"
done
for p in foo/test-fixtures/x xtest-fixtures/x mockserver/test-fixtures/x \
    $C/serialization/{java,code,curl}/X.java $C/serialization/javax/X.java $C/serialization/X.java.orig \
    $C/codec/MockServerHttpServerCodec.java $C/codec/BodyDecoderEncoder.javax $C/codec/sub/BodyX.java \
    $C/{httpclient,configuration,scheduler,state,telemetry}/X.java \
    $N/netty/{proxy,http3,mcp}/X.java $N/netty/unification/Http2MultiplexChildInitializer.java \
    $N/netty/HttpRequestHandlerX.java $N/dashboard/X.java \
    mockserver/mockserver-core/src/test/java/org/mockserver/model/XTest.java \
    docker/ensure-ca-bundle.shx docker/Dockerfile .buildkite/scripts/steps/with-mockserver.sh \
    .buildkite/scripts/run-in-dockerXsh .buildkite/scripts/run-in-docker.sh.bak \
    $N/netty/unification/PortUnificationHandler.java.orig mockserver/pom.xml; do
  check "not wire: $p" "no" "$(wire_ok "$p")"
done

echo "--- 2b. a file moved out of a wire package still counts (no rename detection)"
change mockserver/mockserver-core/src/main/java/org/mockserver/model/Moved.java
git -C "$REPO" mv mockserver/mockserver-core/src/main/java/org/mockserver/model/Moved.java docs-moved.java
git -C "$REPO" commit -qm "move out of model"
run_case renamed PATH="$WORK/bin-api:$WORK/bin:$PATH" BUILDKITE=true BUILDKITE_BUILD_NUMBER=2 \
  STUB_LAST_SHA="$(git -C "$REPO" rev-parse HEAD~1)"
check "rename out of model/: all seven" "$ALL_CLIENTS" "$(clients renamed)"
git -C "$REPO" update-ref refs/remotes/origin/master HEAD~1
run_case renamed_pr BUILDKITE_PULL_REQUEST_BASE_BRANCH=master BUILDKITE_PULL_REQUEST=1
check "PR build, rename out of model/: all seven" "$ALL_CLIENTS" "$(clients renamed_pr)"
git -C "$REPO" update-ref -d refs/remotes/origin/master

echo "--- 3. a client's own path runs only that client"
change mockserver-client-go/client.go
run_case go_only
check "go client: only go" "mockserver-go" "$(clients go_only)"
change mockserver-testcontainers/python/pyproject.toml
run_case py_tc
check "python testcontainers: only python" "mockserver-python" "$(clients py_tc)"
change jekyll-www.mock-server.com/mockserver-openapi.yaml
run_case jekyll_spec
check "published OpenAPI copy: only node" "mockserver-node" "$(clients jekyll_spec)"

echo "--- 4. FORCE_ALL_CLIENTS"
change mockserver/mockserver-netty/src/main/java/org/mockserver/netty/http3/Http3Server.java
run_case force FORCE_ALL_CLIENTS=true
check "true: all seven on a non-wire change" "$ALL_CLIENTS" "$(clients force)"
check "true: the change's own pipeline still runs" "yes" "$(has force mockserver-java)"
run_case force_false FORCE_ALL_CLIENTS=false
check "false: no clients" "" "$(clients force_false)"
run_case force_empty FORCE_ALL_CLIENTS=
check "empty: no clients" "" "$(clients force_empty)"
for v in TRUE 1 yes " true"; do
  if generate force_bad "FORCE_ALL_CLIENTS=$v"; then
    bad "'$v': generator should fail"
  else
    ok "'$v': generator fails"
  fi
  check "'$v': nothing uploaded" "no" "$([ -s "$WORK/force_bad.yml" ] && echo yes || echo no)"
  check "'$v': says why" "1" "$(grep -c 'FORCE_ALL_CLIENTS must be' "$WORK/force_bad.log" || true)"
done

echo "--- 5. the scheduled sweep on an already-built HEAD (last successful build == HEAD)"
HEAD_SHA="$(git -C "$REPO" rev-parse HEAD)"
run_case sched_force PATH="$WORK/bin-api:$WORK/bin:$PATH" BUILDKITE=true BUILDKITE_BUILD_NUMBER=2 \
  STUB_LAST_SHA="$HEAD_SHA" FORCE_ALL_CLIENTS=true
check "forced: all seven clients" "$ALL_CLIENTS" "$(clients sched_force)"
check "forced: HEAD's own pipeline is not re-run" "no" "$(has sched_force mockserver-java)"
run_case sched_plain PATH="$WORK/bin-api:$WORK/bin:$PATH" BUILDKITE=true BUILDKITE_BUILD_NUMBER=2 \
  STUB_LAST_SHA="$HEAD_SHA"
check "unforced: falls back to HEAD's files as before" "yes" "$(has sched_plain mockserver-java)"
check "unforced: no clients" "" "$(clients sched_plain)"

echo "--- 6. the nightly schedule (pipelines.tf) sets exactly FORCE_ALL_CLIENTS=true on the umbrella"
TF="$REPO_ROOT/terraform/buildkite-pipelines/pipelines.tf"
SCHED="$(awk '/^resource "buildkite_pipeline_schedule" "clients_conformance_nightly"/ {p=1} p {print} p && /^}/ {exit}' "$TF")"
check "schedule targets the umbrella pipeline" "1" "$(grep -c 'buildkite_pipeline.pipeline\["pipeline"\].id' <<<"$SCHED" || true)"
check "schedule is on master" "1" "$(grep -cE '^  branch *= "master"$' <<<"$SCHED" || true)"
check "schedule runs daily at 03:00 UTC" "0 3 * * *" "$(sed -n 's/^  cronline *= "\(.*\)"$/\1/p' <<<"$SCHED")"
ENABLED_LINE="$(grep -E '^ *enabled *=' <<<"$SCHED" || true)"
check "schedule is not disabled (no enabled line, or enabled = true)" "ok" \
  "$(if [ -z "$ENABLED_LINE" ] || grep -qxE ' *enabled *= *true' <<<"$ENABLED_LINE"; then echo ok; else echo "$ENABLED_LINE"; fi)"
check "schedule value" "true" "$(sed -n 's/^    FORCE_ALL_CLIENTS *= "\(.*\)"$/\1/p' <<<"$SCHED")"

echo "--- 7. the nightly sweep skips the clients when an earlier passed sweep covered HEAD"
change mockserver/mockserver-netty/src/main/java/org/mockserver/netty/http3/Http3Settings.java
HEAD_SHA="$(git -C "$REPO" rev-parse HEAD)"
PREV_SHA="$(git -C "$REPO" rev-parse HEAD~1)"
push_build() { echo "{\"number\":$1,\"commit\":\"$2\",\"state\":\"passed\",\"source\":\"webhook\",\"env\":{}}"; }
sweep_build() { echo "{\"number\":$1,\"commit\":\"$2\",\"state\":\"passed\",\"source\":\"schedule\",\"env\":{\"FORCE_ALL_CLIENTS\":\"true\"}}"; }
sweep_case() { # case_name builds-json [env...]
  local name="$1" builds="$2"; shift 2
  run_case "$name" PATH="$WORK/bin-api:$WORK/bin:$PATH" BUILDKITE=true BUILDKITE_BUILD_NUMBER=10 \
    BUILDKITE_SOURCE=schedule FORCE_ALL_CLIENTS=true STUB_BUILDS="$builds" "$@"
}
sweep_case sweep_covered "[$(push_build 9 "$HEAD_SHA"),$(sweep_build 8 "$HEAD_SHA"),$(sweep_build 7 "$PREV_SHA")]"
check "covered: says it skipped" "1" "$(grep -c ':zzz: master unchanged since the last passed sweep' "$WORK/sweep_covered.log" || true)"
check "covered: nothing triggered" "" "$(triggered sweep_covered)"
check "covered: always-on gates still run" "yes" \
  "$(grep -q 'check-false-green-guards.sh' "$WORK/sweep_covered.yml" 2>/dev/null && echo yes || echo no)"
COVERED="[$(push_build 9 "$HEAD_SHA"),$(sweep_build 8 "$HEAD_SHA")]"
sweep_case sweep_by_hand "$COVERED" BUILDKITE_SOURCE=api
check "forced by hand on a covered HEAD: all seven" "$ALL_CLIENTS" "$(clients sweep_by_hand)"
sweep_case sweep_pr "$COVERED" BUILDKITE_PULL_REQUEST_BASE_BRANCH=master BUILDKITE_PULL_REQUEST=1
check "PR build on a covered HEAD: all seven" "$ALL_CLIENTS" "$(clients sweep_pr)"
sweep_case sweep_moved "[$(push_build 9 "$HEAD_SHA"),$(sweep_build 8 "$PREV_SHA")]"
check "master moved: all seven" "$ALL_CLIENTS" "$(clients sweep_moved)"
check "master moved: no skip" "0" "$(grep -c ':zzz:' "$WORK/sweep_moved.log" || true)"
sweep_case sweep_none "[$(push_build 9 "$HEAD_SHA")]"
check "no earlier sweep: all seven" "$ALL_CLIENTS" "$(clients sweep_none)"
sweep_case sweep_api_down "[]" STUB_FAIL_CURL=1
check "API down: all seven" "$ALL_CLIENTS" "$(clients sweep_api_down)"
run_case sweep_unforced PATH="$WORK/bin-api:$WORK/bin:$PATH" BUILDKITE=true BUILDKITE_BUILD_NUMBER=10 \
  STUB_BUILDS="[$(push_build 9 "$HEAD_SHA"),$(sweep_build 7 "$HEAD_SHA")]"
check "unforced: unchanged (HEAD's own pipeline)" "yes" "$(has sweep_unforced mockserver-java)"
check "unforced: no sweep lookup message" "0" "$(grep -c 'client sweep' "$WORK/sweep_unforced.log" || true)"

echo "--- 8. last_client_sweep_commit picks the newest earlier passed FORCE_ALL_CLIENTS schedule build"
A=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
B=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
lookup() { # builds-json [env...] -> the echoed commit, or "fail:<reason>"
  local builds="$1"; shift
  env -i PATH="$WORK/bin-api:$WORK/bin:$PATH" HOME="${HOME:-/tmp}" STUB_BUILDS="$builds" CURL_LOG="$WORK/curl.log" \
    BUILDKITE_BUILD_NUMBER=10 "$@" bash -c '
      . "$1"; out="$(last_client_sweep_commit 2>"$2")" && echo "$out" || echo "fail:$(sed "s/^ *reason: //" "$2")"' \
    _ "$REPO_ROOT/.buildkite/scripts/lib/last-successful-commit.sh" "$WORK/lookup.err"
}
sweep() { # number commit [state] [source] [force]
  echo "{\"number\":$1,\"commit\":\"$2\",\"state\":\"${3:-passed}\",\"source\":\"${4:-schedule}\",\"env\":{\"FORCE_ALL_CLIENTS\":\"${5:-true}\"}}"
}
rm -f "$WORK/curl.log"
check "newest matching wins" "$A" "$(lookup "[$(sweep 9 "$A"),$(sweep 8 "$B")]")"
check "asks for passed builds on the branch, 100 per page" "1" \
  "$(grep -c 'branch=master.*state=passed.*per_page=100' "$WORK/curl.log" || true)"
check "skips non-schedule builds" "$B" "$(lookup "[$(sweep 9 "$A" passed webhook),$(sweep 8 "$B")]")"
check "skips schedules without FORCE_ALL_CLIENTS=true" "$B" "$(lookup "[$(sweep 9 "$A" passed schedule false),$(sweep 8 "$B")]")"
NO_ENV="{\"number\":9,\"commit\":\"$A\",\"state\":\"passed\",\"source\":\"schedule\",\"env\":null}"
check "skips builds with no env" "$B" "$(lookup "[$NO_ENV,$(sweep 8 "$B")]")"
check "skips builds that did not pass" "$B" "$(lookup "[$(sweep 9 "$A" failed),$(sweep 8 "$B")]")"
check "skips the current and later builds" "$B" "$(lookup "[$(sweep 11 "$A"),$(sweep 10 "$A"),$(sweep 8 "$B")]")"
check "no build number: newest matching" "$A" "$(lookup "[$(sweep 11 "$A"),$(sweep 8 "$B")]" BUILDKITE_BUILD_NUMBER=)"
check "none found fails" "fail:no passed client sweep in the last 100 passed builds" "$(lookup "[$(sweep 9 "$A" passed webhook)]")"
check "malformed commit fails" "fail:no passed client sweep in the last 100 passed builds" "$(lookup "[$(sweep 9 "not-a-sha")]")"
check "non-array response fails" "fail:non-array API response" "$(lookup '{"message":"Unauthorized"}')"
check "API request failure fails" "fail:Buildkite API request failed" "$(lookup "[$(sweep 9 "$A")]" STUB_FAIL_CURL=1)"
check "no token fails" "fail:secrets manager unavailable" \
  "$(lookup "[$(sweep 9 "$A")]" PATH="$WORK/bin:$PATH")"

if [ "$FAILS" -gt 0 ]; then echo "FAILED: $FAILS check(s)" >&2; exit 1; fi
echo "all generate-pipeline client-trigger checks passed"
