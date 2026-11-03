#!/usr/bin/env bash
# Which client-language pipelines generate-pipeline.sh triggers: all seven on a wire-contract
# change (lib/client-wire-paths.sh) or FORCE_ALL_CLIENTS=true, only the client's own pipeline
# on its own path, none on any other server change. Runs the real generator in a scratch git
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
# The last-successful-build lookup: no API by default; bin-api answers with $STUB_LAST_SHA.
printf '#!/usr/bin/env bash\nexit 1\n' > "$WORK/bin/aws"
printf '#!/usr/bin/env bash\necho fake-token\n' > "$WORK/bin-api/aws"
printf '#!/usr/bin/env bash\necho "[{\\"number\\":1,\\"commit\\":\\"$STUB_LAST_SHA\\"}]"\n' > "$WORK/bin-api/curl"
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

if [ "$FAILS" -gt 0 ]; then echo "FAILED: $FAILS check(s)" >&2; exit 1; fi
echo "all generate-pipeline client-trigger checks passed"
