#!/usr/bin/env bash
set -euo pipefail

# The images ship the mockserver-netty-docker jar (the shaded jar with JNA unrelocated), not the
# mockserver-netty-no-dependencies library jar, whose relocated JNA cannot load (jarprep refuses it).
echo "--- :buildkite: Downloading the image JAR artifact"
buildkite-agent artifact download "mockserver/mockserver-netty-docker/target/mockserver-netty-docker-*.jar" .

shopt -s nullglob
SHADED_JAR=""
for f in mockserver/mockserver-netty-docker/target/mockserver-netty-docker-*.jar; do
  case "$(basename "$f")" in
    *-sources.jar|*-javadoc.jar|original-*) continue ;;
  esac
  SHADED_JAR="$f"
  break
done
shopt -u nullglob
if [ -z "$SHADED_JAR" ]; then
  echo "Error: image JAR (mockserver-netty-docker-*.jar) not found after artifact download"
  exit 1
fi

echo "--- :package: Found JAR: $SHADED_JAR"
cp "$SHADED_JAR" docker/local/mockserver-netty-jar-with-dependencies.jar
# docker/local's jarprep stage COPYs ca-bundle.pem and trusts it (when non-empty) before `apk add`;
# empty in CI is a no-op. Remove any stale bundle first so a fresh one is always written.
rm -f docker/local/ca-bundle.pem
docker/ensure-ca-bundle.sh docker/local >/dev/null

SMOKE_TAG="mockserver/mockserver:smoke-test-$$"
SMOKE_CONTAINER="mockserver-smoke-$$"

cleanup() {
  docker rm -f "$SMOKE_CONTAINER" 2>/dev/null || true
  docker rmi "$SMOKE_TAG" 2>/dev/null || true
}
trap cleanup EXIT

echo "--- :docker: Building local image for smoke test"
docker build --tag "$SMOKE_TAG" docker/local

echo "--- :test_tube: Running smoke test"
docker run -d --name "$SMOKE_CONTAINER" -p 0:1080 -e MOCKSERVER_METRICS_ENABLED=true "$SMOKE_TAG"

SMOKE_PORT=$(docker port "$SMOKE_CONTAINER" 1080 | head -1 | awk -F: '{print $NF}')
echo "MockServer container started on port $SMOKE_PORT"

DEADLINE=$((SECONDS + 30))
while [ $SECONDS -lt $DEADLINE ]; do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "http://localhost:${SMOKE_PORT}/mockserver/status" 2>/dev/null || true)
  if [ "$STATUS" = "200" ]; then
    echo "MockServer responded with 200 OK"
    break
  fi
  sleep 1
done

if [ "$STATUS" != "200" ]; then
  echo "Smoke test FAILED: MockServer did not return 200 within 30s (last status: $STATUS)"
  echo "Container logs:"
  docker logs "$SMOKE_CONTAINER" 2>&1 | tail -30
  exit 1
fi

EXPECTATION_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT \
  "http://localhost:${SMOKE_PORT}/mockserver/expectation" \
  -H "Content-Type: application/json" \
  -d '{
    "httpRequest": {"method": "GET", "path": "/smoke-test"},
    "httpResponse": {"statusCode": 200, "body": "ok"}
  }' 2>/dev/null || true)

if [ "$EXPECTATION_RESPONSE" != "201" ]; then
  echo "Smoke test FAILED: could not create expectation (status: $EXPECTATION_RESPONSE)"
  exit 1
fi

MOCK_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${SMOKE_PORT}/smoke-test" 2>/dev/null || true)
if [ "$MOCK_RESPONSE" != "200" ]; then
  echo "Smoke test FAILED: mock did not respond correctly (status: $MOCK_RESPONSE)"
  exit 1
fi

# jvm_memory_allocated_bytes needs the jdk.management module; a jlink module list without it
# drops the metric silently, so assert it on the image that is about to be published.
# Scrape once into a variable: `curl | grep -q` under pipefail fails when grep exits
# before curl finishes writing (curl exit 23), a false red (Rule 7 of check-false-green-guards.sh).
SMOKE_METRICS="$(curl -s "http://localhost:${SMOKE_PORT}/mockserver/metrics" 2>/dev/null || true)"
if ! grep -q '^jvm_memory_allocated_bytes ' <<<"$SMOKE_METRICS"; then
  echo "Smoke test FAILED: jvm_memory_allocated_bytes missing from /mockserver/metrics (is jdk.management in the jlink --add-modules list?)"
  exit 1
fi

# The image ships -XX:+UseZGC as its default collector (ENV JAVA_TOOL_OPTIONS in docker/local/Dockerfile). A
# refactor that dropped the flag would silently fall back to G1 and regress tail latency with no
# other symptom, so assert the running JVM actually reports a ZGC collector. jvm_runtime_info carries
# the joined GarbageCollectorMXBean names in its gc label; generational ZGC's beans all contain "ZGC"
# (e.g. "ZGC Major Cycles"), whereas G1's contain "G1", so this goes red on a regression to G1.
if ! grep -q '^jvm_runtime_info.*gc="[^"]*ZGC' <<<"$SMOKE_METRICS"; then
  echo "Smoke test FAILED: image is not running ZGC (jvm_runtime_info gc label has no ZGC collector — did the ENV JAVA_TOOL_OPTIONS=-XX:+UseZGC default get dropped?)"
  grep '^jvm_runtime_info' <<<"$SMOKE_METRICS" || true
  exit 1
fi

echo "Smoke test PASSED: MockServer starts, accepts expectations, serves mock responses, reports JVM allocation metrics and runs under ZGC"

docker rm -f "$SMOKE_CONTAINER" 2>/dev/null || true

echo "--- :test_tube: Running env var port override smoke test"
ENVVAR_CONTAINER="mockserver-envvar-$$"

cleanup_envvar() {
  docker rm -f "$ENVVAR_CONTAINER" 2>/dev/null || true
  docker rmi "$SMOKE_TAG" 2>/dev/null || true
}
trap cleanup_envvar EXIT

docker run -d --name "$ENVVAR_CONTAINER" -e MOCKSERVER_SERVER_PORT=1234 -p 0:1234 "$SMOKE_TAG"

ENVVAR_PORT=$(docker port "$ENVVAR_CONTAINER" 1234 | head -1 | awk -F: '{print $NF}')
echo "MockServer container started with MOCKSERVER_SERVER_PORT=1234 on host port $ENVVAR_PORT"

DEADLINE=$((SECONDS + 30))
STATUS=""
while [ $SECONDS -lt $DEADLINE ]; do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "http://localhost:${ENVVAR_PORT}/mockserver/status" 2>/dev/null || true)
  if [ "$STATUS" = "200" ]; then
    echo "MockServer responded with 200 OK on overridden port"
    break
  fi
  sleep 1
done

if [ "$STATUS" != "200" ]; then
  echo "Env var smoke test FAILED: MockServer did not start on port 1234 within 30s (last status: $STATUS)"
  echo "Container logs:"
  docker logs "$ENVVAR_CONTAINER" 2>&1 | tail -30
  exit 1
fi

EXPECTATION_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT \
  "http://localhost:${ENVVAR_PORT}/mockserver/expectation" \
  -H "Content-Type: application/json" \
  -d '{
    "httpRequest": {"method": "GET", "path": "/envvar-test"},
    "httpResponse": {"statusCode": 200, "body": "ok"}
  }' 2>/dev/null || true)

if [ "$EXPECTATION_RESPONSE" != "201" ]; then
  echo "Env var smoke test FAILED: could not create expectation (status: $EXPECTATION_RESPONSE)"
  exit 1
fi

MOCK_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${ENVVAR_PORT}/envvar-test" 2>/dev/null || true)
if [ "$MOCK_RESPONSE" != "200" ]; then
  echo "Env var smoke test FAILED: mock did not respond correctly on overridden port (status: $MOCK_RESPONSE)"
  exit 1
fi

echo "Env var smoke test PASSED: MOCKSERVER_SERVER_PORT correctly overrides default port"

docker rm -f "$ENVVAR_CONTAINER" 2>/dev/null || true
docker rmi "$SMOKE_TAG" 2>/dev/null || true
trap - EXIT

.buildkite/scripts/docker-login.sh
.buildkite/scripts/ecr-login.sh

ECR_REPO="public.ecr.aws/t2x9c0i6/mockserver"

# Source provenance stamped into the pushed images as OCI labels
# (org.opencontainers.image.revision / .created). SOURCE_COMMIT is the commit this
# snapshot was built from — the perf harness reads it back off the running SUT and
# refuses to attribute a perf result to a commit the measured binary did not come
# from (mutable snapshot tag + cached agent = otherwise-unprovable provenance).
SOURCE_COMMIT="${BUILDKITE_COMMIT:-$(git rev-parse HEAD 2>/dev/null || echo '')}"
BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "--- :label: stamping image provenance: revision=${SOURCE_COMMIT:0:12} created=${BUILD_DATE}"

echo "--- :docker: Building and pushing mockserver/mockserver:snapshot (multi-arch)"

DOCKER_CMD="docker buildx build --platform linux/amd64,linux/arm64 --push --build-arg SOURCE_COMMIT=$SOURCE_COMMIT --build-arg BUILD_DATE=$BUILD_DATE --tag mockserver/mockserver:snapshot --tag mockserver/mockserver:mockserver-snapshot --tag ${ECR_REPO}:snapshot --tag ${ECR_REPO}:mockserver-snapshot docker/local"

echo "┌──────────────────────────────────────────────────────────────────"
echo "│ Docker Command (copy to reproduce locally):"
echo "│"
echo "│   $DOCKER_CMD"
echo "│"
echo "└──────────────────────────────────────────────────────────────────"
echo ""

docker buildx create --use --name builder 2>/dev/null || docker buildx use builder
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --push \
  --build-arg SOURCE_COMMIT="$SOURCE_COMMIT" \
  --build-arg BUILD_DATE="$BUILD_DATE" \
  --tag mockserver/mockserver:snapshot \
  --tag mockserver/mockserver:mockserver-snapshot \
  --tag "${ECR_REPO}:snapshot" \
  --tag "${ECR_REPO}:mockserver-snapshot" \
  docker/local

echo "--- :docker: Building and pushing mockserver/mockserver:snapshot-graaljs (multi-arch)"
cp docker/local/mockserver-netty-jar-with-dependencies.jar docker/graaljs/mockserver-netty-jar-with-dependencies.jar
# Stage a CA bundle into the graaljs build context. The alpine stages COPY it
# in and (when non-empty) trust it before `apk add`, so builds behind a
# corporate TLS-inspecting proxy succeed. Empty file in CI is a no-op. Populated
# from MOCKSERVER_LOCAL_CA_BUNDLE (or the NODE_EXTRA_CA_CERTS / AWS_CA_BUNDLE
# fallbacks). Remove any stale bundle first so a fresh one is always written.
rm -f docker/graaljs/ca-bundle.pem
docker/ensure-ca-bundle.sh docker/graaljs >/dev/null
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --push \
  --build-arg source=copy \
  --build-arg SOURCE_COMMIT="$SOURCE_COMMIT" \
  --build-arg BUILD_DATE="$BUILD_DATE" \
  --tag mockserver/mockserver:snapshot-graaljs \
  --tag mockserver/mockserver:mockserver-snapshot-graaljs \
  --tag "${ECR_REPO}:snapshot-graaljs" \
  --tag "${ECR_REPO}:mockserver-snapshot-graaljs" \
  docker/graaljs

echo "--- :docker: Building and pushing mockserver/mockserver-webhook:snapshot (multi-arch)"
# Download the webhook fat jar artifact from the build step
buildkite-agent artifact download "mockserver/mockserver-k8s-webhook/target/mockserver-k8s-webhook-*-jar-with-dependencies.jar" . 2>/dev/null || true

WEBHOOK_JAR=""
shopt -s nullglob
for f in mockserver/mockserver-k8s-webhook/target/mockserver-k8s-webhook-*-jar-with-dependencies.jar; do
  WEBHOOK_JAR="$f"
  break
done
shopt -u nullglob

if [[ -n "$WEBHOOK_JAR" ]]; then
  cp "$WEBHOOK_JAR" docker/webhook/mockserver-webhook.jar
  # Error-isolated: a webhook push failure must never abort the pipeline —
  # the main + GraalJS images have already been published above.
  # Push Docker Hub first (primary registry), then ECR separately.
  if ! docker buildx build \
    --platform linux/amd64,linux/arm64 \
    --push \
    --tag mockserver/mockserver-webhook:snapshot \
    --tag mockserver/mockserver-webhook:mockserver-snapshot \
    docker/webhook; then
    echo "WARNING: webhook Docker Hub push failed — continuing (main images already published)"
  fi
  if ! docker buildx build \
    --platform linux/amd64,linux/arm64 \
    --push \
    --tag "${ECR_REPO}-webhook:snapshot" \
    --tag "${ECR_REPO}-webhook:mockserver-snapshot" \
    docker/webhook; then
    echo "WARNING: webhook ECR push failed — continuing (Docker Hub is the primary registry)"
  fi
else
  echo "WARNING: Webhook fat jar not found — skipping webhook snapshot image push"
fi

# ---------------------------------------------------------------------------
# :snapshot-clustered — the Infinispan/JGroups image variant.
#
# WHY IT IS PUSHED HERE, from THIS job, rather than built where it is consumed:
# the daily perf run's clustered A/B (perf-test-run.sh) measures the
# clustered/in-memory state-backend ratio. The run is ATTRIBUTED to the SUT
# (graaljs) image's own org.opencontainers.image.revision, so the clustered image
# MUST come from the same commit or the ratio is filed under code it never ran —
# a silently meaningless comparison. Building it in the SAME job as the SUT image, from
# the SAME $SHADED_JAR and the SAME $SOURCE_COMMIT stamp, makes that pairing a
# property of the build rather than a coincidence of two pipelines; perf-test-run.sh
# then VERIFIES it by comparing the two images' revision labels and refuses to
# measure on a mismatch. The perf agent obtains it exactly as it obtains the SUT
# image — `docker pull` of a mutable snapshot tag — so nothing new is invented.
# (Before this, NOTHING pulled or built mockserver-snapshot-clustered and the
# block took its absent-image skip on every scale-to-zero perf agent.)
#
# The image composition mirrors the RELEASE clustered image exactly
# (scripts/release/components/docker.sh): the shaded jar as /mockserver-netty-
# jar-with-dependencies.jar plus the module jar + its runtime deps under /libs.
# Those libs are staged by the reactor build's `-P clustered-libs` profile
# (scripts/buildkite_quick_build.sh) and handed here as Buildkite artifacts, so
# this step does NO Maven work — same jars-not-images hand-off shape as the
# container-tests clustered image.
#
# HARD-FAIL, like the release's clustered push and unlike the error-isolated webhook
# push. That is exactly why this block is LAST in the step: everything that must not
# be starved by a clustered failure (main, graaljs, webhook) has already been pushed.
# A swallowed failure here reappears months later as a perf annotation counting
# consecutive skips; the main + graaljs images are already published by this point,
# so failing surfaces the real problem without costing the primary artefacts.
echo "--- :docker: Building and pushing mockserver/mockserver:snapshot-clustered (multi-arch)"
CLUSTERED_LIBS_DIR="mockserver/mockserver-state-infinispan/target/clustered-libs"
buildkite-agent artifact download "$CLUSTERED_LIBS_DIR/*.jar" .

CLUSTERED_LIB_COUNT=0
if [ -d "$CLUSTERED_LIBS_DIR" ]; then
  CLUSTERED_LIB_COUNT=$(find "$CLUSTERED_LIBS_DIR" -name '*.jar' -type f | wc -l | tr -d ' ')
fi
if [ "$CLUSTERED_LIB_COUNT" -eq 0 ]; then
  echo "ERROR: no clustered /libs jars found under $CLUSTERED_LIBS_DIR after artifact download." >&2
  echo "       The reactor build must run with -P clustered-libs and upload" >&2
  echo "       mockserver/mockserver-state-infinispan/target/clustered-libs/*.jar (pipeline-java.yml)." >&2
  echo "       Failing closed: publishing no clustered snapshot silently would disable the" >&2
  echo "       perf item-13 clustered A/B on every subsequent run." >&2
  exit 1
fi
# Sanity-check that the module jar itself made it in (copy-dependencies excludes
# org.mock-server, so buildkite_quick_build.sh copies it in separately). Without it
# /libs holds only third-party jars and MOCKSERVER_STATE_BACKEND=infinispan would
# fail to resolve the backend at RUNTIME — a working image that cannot cluster.
if [ -z "$(find "$CLUSTERED_LIBS_DIR" -name 'mockserver-state-infinispan-*.jar' -type f)" ]; then
  echo "ERROR: $CLUSTERED_LIBS_DIR has $CLUSTERED_LIB_COUNT jar(s) but NOT the mockserver-state-infinispan module jar." >&2
  echo "       The image would start but could not resolve the infinispan StateBackend. Failing closed." >&2
  exit 1
fi
echo "    staging $CLUSTERED_LIB_COUNT jar(s) into docker/clustered/libs"
cp docker/local/mockserver-netty-jar-with-dependencies.jar docker/clustered/mockserver-netty-jar-with-dependencies.jar
rm -rf docker/clustered/libs && mkdir -p docker/clustered/libs
cp "$CLUSTERED_LIBS_DIR"/*.jar docker/clustered/libs/
# Same CA-bundle staging as graaljs: the alpine tcnative stage COPYs ca-bundle.pem
# and trusts it when non-empty. Empty file in CI is a no-op.
rm -f docker/clustered/ca-bundle.pem
docker/ensure-ca-bundle.sh docker/clustered >/dev/null
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --push \
  --build-arg SOURCE_COMMIT="$SOURCE_COMMIT" \
  --build-arg BUILD_DATE="$BUILD_DATE" \
  --tag mockserver/mockserver:snapshot-clustered \
  --tag mockserver/mockserver:mockserver-snapshot-clustered \
  --tag "${ECR_REPO}:snapshot-clustered" \
  --tag "${ECR_REPO}:mockserver-snapshot-clustered" \
  docker/clustered
# Leave no build-context residue for a later step / a re-used agent workspace.
rm -rf docker/clustered/libs
rm -f docker/clustered/mockserver-netty-jar-with-dependencies.jar docker/clustered/ca-bundle.pem

# ---------------------------------------------------------------------------
# :snapshot-http3 — deprecated alias of :snapshot, built FROM the :snapshot digest pushed above so it
# cannot drift. Smoke-tested with a real HTTP/3 request before it is pushed; HARD-FAIL and LAST, so a
# failure publishes no snapshot-http3 and reds the step after every other image is already pushed.
publish_snapshot_http3() {
  export HTTP3_SMOKE_ID="$$"
  local base_digest base_ref smoke_tag="mockserver/mockserver:smoke-http3-$HTTP3_SMOKE_ID"
  # On success, failure or the 20m timeout: drop the smoke image and any smoke containers.
  trap 'docker rm -f "mockserver-http3-smoke-$HTTP3_SMOKE_ID" "mockserver-http3-refuse-$HTTP3_SMOKE_ID" >/dev/null 2>&1 || true
        docker rmi -f "mockserver/mockserver:smoke-http3-$HTTP3_SMOKE_ID" >/dev/null 2>&1 || true' EXIT
  trap 'exit 143' TERM INT
  # Capture before parsing: piping imagetools into an early-exiting awk races, and when awk wins
  # imagetools dies of SIGPIPE (exit 255, no message), which pipefail turns into a silent failure.
  local inspect_out inspect_rc=0
  inspect_out="$(docker buildx imagetools inspect "${ECR_REPO}:snapshot" 2>&1)" || inspect_rc=$?
  if (( inspect_rc != 0 )); then
    echo "docker buildx imagetools inspect ${ECR_REPO}:snapshot failed (exit ${inspect_rc}): ${inspect_out}" >&2
    return 1
  fi
  base_digest="$(awk '/^Digest:/{print $2; exit}' <<<"$inspect_out")"
  if [[ ! "$base_digest" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    echo "docker buildx imagetools inspect ${ECR_REPO}:snapshot printed no sha256 Digest line: ${inspect_out}" >&2
    return 1
  fi
  base_ref="${ECR_REPO}@${base_digest}"
  echo "    base: $base_ref"
  rm -f docker/http3/ca-bundle.pem
  docker/ensure-ca-bundle.sh docker/http3 >/dev/null
  docker pull "$base_ref" >/dev/null || return 1
  docker buildx build --load --build-arg BASE_IMAGE="$base_ref" --tag "$smoke_tag" docker/http3 || return 1
  .buildkite/scripts/steps/docker-http3-smoke.sh "$smoke_tag" "$base_ref" || return 1
  docker buildx build \
    --platform linux/amd64,linux/arm64 \
    --push \
    --build-arg BASE_IMAGE="$base_ref" \
    --tag mockserver/mockserver:snapshot-http3 \
    --tag mockserver/mockserver:mockserver-snapshot-http3 \
    --tag "${ECR_REPO}:snapshot-http3" \
    --tag "${ECR_REPO}:mockserver-snapshot-http3" \
    docker/http3
}
echo "--- :docker: Building, smoke-testing and pushing mockserver/mockserver:snapshot-http3 (multi-arch)"
# Bounded, so a hung pull or smoke run fails the step in 20m rather than eating its whole timeout.
export ECR_REPO
export -f publish_snapshot_http3
http3_rc=0
timeout 20m bash -c 'set -euo pipefail; publish_snapshot_http3' || http3_rc=$?
rm -f docker/http3/ca-bundle.pem
if (( http3_rc != 0 )); then
  echo "ERROR: snapshot-http3 was NOT published: its build, HTTP/3 smoke test or push failed (exit ${http3_rc}, 124 = the 20m timeout) — see the log above" >&2
  # Exit 1, not the child's status: a 255 would match the step's agent-shutdown auto-retry.
  exit 1
fi
