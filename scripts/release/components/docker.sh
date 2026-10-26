#!/usr/bin/env bash
# Build and push the MockServer Docker images (linux/amd64 + linux/arm64) to
# Docker Hub and AWS ECR Public.
#
# Dry-run: docker buildx build (local, no --push), skip ECR login.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$SCRIPT_DIR/_lib.sh"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true; shift ;;
    --execute) DRY_RUN=false; shift ;;
    -h|--help) echo "Usage: $0 [--dry-run|--execute]"; exit 0 ;;
    *) log_error "Unknown arg: $1"; exit 2 ;;
  esac
done

require_cmd docker
require_cmd curl
require_release_inputs
skip_unless_release_type "docker" full,post-maven,docker-only

log_step "Publish Docker images $RELEASE_VERSION (dry-run=$DRY_RUN)"
sync_to_origin_master

# ---- Derive the image JAR -------------------------------------------------
# The images ship the mockserver-netty-docker jar: the RELEASED mockserver-netty-no-dependencies jar
# with JNA moved back to com.sun.jna (libjnidispatch cannot load relocated JNA). It is never
# published, so derive it here from the library jar on Maven Central, pinned to RELEASE_VERSION by
# -Dmockserver.docker.baseVersion (the tree is at master's in-dev version, see the install below).
cd "$REPO_ROOT"
CENTRAL_URL="https://repo1.maven.org/maven2/org/mock-server/mockserver-netty-no-dependencies/${RELEASE_VERSION}/mockserver-netty-no-dependencies-${RELEASE_VERSION}.jar"
DOCKER_JAR_DIR="mockserver/mockserver-netty-docker/target"
rm -f "$DOCKER_JAR_DIR"/mockserver-netty-docker-*.jar
if curl -sf -I --max-time 30 --retry 3 --retry-delay 5 "$CENTRAL_URL" >/dev/null 2>&1; then
  log_info "Deriving the image JAR from the released mockserver-netty-no-dependencies $RELEASE_VERSION"
  in_maven -w /build/mockserver \
    -- mvn -B -DskipTests -pl mockserver-netty-docker package \
      "-Dmockserver.docker.baseVersion=$RELEASE_VERSION"
  SHADED_JAR="$DOCKER_JAR_DIR/mockserver-netty-docker-${RELEASE_VERSION}.jar"
  # The derive resolved the library jar through the persistent m2 cache volume; a stale or corrupt
  # copy there must not become the image, so it has to match Central's published checksum.
  CENTRAL_SHA1="$(curl -fsSL --max-time 30 --retry 3 --retry-delay 5 "${CENTRAL_URL}.sha1" | awk '{ print $1 }')"
  [[ "$CENTRAL_SHA1" =~ ^[0-9a-f]{40}$ ]] || { log_error "No valid SHA-1 at ${CENTRAL_URL}.sha1"; exit 1; }
  M2_JAR="/root/.m2/repository/org/mock-server/mockserver-netty-no-dependencies/${RELEASE_VERSION}/mockserver-netty-no-dependencies-${RELEASE_VERSION}.jar"
  CACHED_SHA1="$(in_docker "$MAVEN_IMAGE" -v mockserver-m2-cache:/root/.m2 -- sha1sum "$M2_JAR" \
    | grep -E "^[0-9a-f]{40}  $M2_JAR\$" | awk '{ print $1 }' || true)"
  if [[ "$CACHED_SHA1" != "$CENTRAL_SHA1" ]]; then
    log_error "The mockserver-netty-no-dependencies $RELEASE_VERSION jar the image JAR was derived from has SHA-1 '${CACHED_SHA1:-unreadable}', but Maven Central publishes $CENTRAL_SHA1"
    exit 1
  fi
  log_info "Derived from the released library jar (SHA-1 $CENTRAL_SHA1, matches Maven Central)"
elif is_dry_run; then
  # `package` (not `install`): the jar is consumed by file path, never resolved by Maven again.
  log_dry "skip: $RELEASE_VERSION is not on Maven Central yet - deriving the image JAR from this checkout"
  in_maven -w /build/mockserver \
    -- mvn -DskipTests -pl mockserver-netty-docker -am package
  SHADED_JAR=$(find "$DOCKER_JAR_DIR" -name 'mockserver-netty-docker-*.jar' ! -name 'original-*' -print -quit 2>/dev/null || true)
else
  log_error "mockserver-netty-no-dependencies $RELEASE_VERSION is not on Maven Central ($CENTRAL_URL)"
  exit 1
fi
[[ -n "$SHADED_JAR" && -f "$SHADED_JAR" ]] || { log_error "No image JAR (mockserver-netty-docker) available"; exit 1; }
log_info "Using JAR: $SHADED_JAR"
cp "$SHADED_JAR" docker/local/mockserver-netty-jar-with-dependencies.jar
cp "$SHADED_JAR" docker/graaljs/mockserver-netty-jar-with-dependencies.jar
cp "$SHADED_JAR" docker/clustered/mockserver-netty-jar-with-dependencies.jar
# The experimental -aot variant consumes the image jar from its build context via
# `--build-arg source=copy` (its `copy` stage COPYs mockserver-netty-jar-with-dependencies.jar),
# so it needs the same staged copy as graaljs/clustered.
cp "$SHADED_JAR" docker/aot/mockserver-netty-jar-with-dependencies.jar

# Stage a CA bundle into the build contexts whose alpine stages COPY it in and
# (when non-empty) trust it before `apk add`, so builds behind a corporate
# TLS-inspecting proxy succeed. Empty file in CI is a no-op. Populated from
# MOCKSERVER_LOCAL_CA_BUNDLE (or the NODE_EXTRA_CA_CERTS / AWS_CA_BUNDLE
# fallbacks). docker/webhook is single-stage and does NOT COPY a bundle, so it
# is excluded; docker/local and docker/aot COPY it into their jarprep stage.
# Remove any stale bundle first so the helper always writes a fresh one.
rm -f docker/local/ca-bundle.pem docker/graaljs/ca-bundle.pem docker/clustered/ca-bundle.pem docker/aot/ca-bundle.pem
"$REPO_ROOT/docker/ensure-ca-bundle.sh" docker/local >/dev/null
"$REPO_ROOT/docker/ensure-ca-bundle.sh" docker/graaljs >/dev/null
"$REPO_ROOT/docker/ensure-ca-bundle.sh" docker/clustered >/dev/null
"$REPO_ROOT/docker/ensure-ca-bundle.sh" docker/aot >/dev/null
# docker/http3 only needs it for its Maven Central fallback, which the shaded release base never takes.
rm -f docker/http3/ca-bundle.pem
"$REPO_ROOT/docker/ensure-ca-bundle.sh" docker/http3 >/dev/null

# ---- Resolve Infinispan clustered-state libs for the -clustered image ------
# Use Maven to resolve the transitive runtime dependencies of the
# mockserver-state-infinispan module. The module JAR itself is downloaded
# separately from Maven Central (it's excluded by -DexcludeGroupIds).
find_local_infinispan_jar() {
  find mockserver/mockserver-state-infinispan/target \
    -name 'mockserver-state-infinispan-*.jar' \
    ! -name '*-sources.jar' \
    ! -name '*-javadoc.jar' \
    ! -name '*-tests.jar' \
    -print -quit 2>/dev/null || true
}

mkdir -p docker/clustered/libs

# Populate .m2 with the reactor's mock-server artifacts (mockserver-core etc.)
# at the CURRENT working-tree version BEFORE the dependency:copy-dependencies
# call below. This is mandatory in ALL modes, because sync_to_origin_master has
# left the tree at master's in-dev version (e.g. 7.1.1-SNAPSHOT):
#
#   dependency:copy-dependencies must first RESOLVE mockserver-state-infinispan's
#   full transitive tree — which includes mockserver-core at the master SNAPSHOT
#   version — before -DexcludeGroupIds=org.mock-server filters the mock-server
#   artifacts out of what gets copied. The exclusion controls what is COPIED, not
#   what is RESOLVED. In a FULL release the Maven Central step has already
#   `mvn install`ed the whole reactor into .m2, so resolution succeeds. In a
#   POST-MAVEN release that install is skipped and the SNAPSHOT core is neither
#   in .m2 nor on Maven Central, so resolution previously failed with
#   "Could not find artifact org.mock-server:mockserver-core:jar:<SNAPSHOT>".
#
# `mvn -am install` is idempotent and populates .m2 with core (and the
# infinispan module itself) at the current version, satisfying resolution. It
# ALSO builds the infinispan module JAR into target/, so we can locate it below.
#
# -DskipTests AND -Djacoco.skip=true are BOTH required: mockserver-core binds
# jacoco:check to the `verify` phase (part of `install`) with a BUNDLE LINE
# coverage floor of 0.65. With tests skipped no coverage data is produced, so
# without -Djacoco.skip=true the install aborts with "Coverage checks have not
# been met" before any artifact reaches .m2. We only need the artifacts
# installed for dependency resolution, not the coverage gate. -DskipITs too:
# -DskipTests skips surefire only, and failsafe would still run core's ITs.
log_info "Installing reactor artifacts to .m2 so infinispan deps resolve (post-maven safe)"
in_maven -w /build/mockserver \
  -- mvn -DskipTests -DskipITs -Djacoco.skip=true -pl mockserver-state-infinispan -am install

# Choose the module JAR to copy INTO the image. Prefer the RELEASE_VERSION
# artifact from Maven Central over the locally-installed SNAPSHOT the step above
# just built: the image must ship the released module JAR, not master's in-dev
# SNAPSHOT (the install above exists only to make dependency:copy-dependencies
# resolve mockserver-core; it is not the artifact we publish). The transitive
# lib versions are pom-pinned and identical between the release and master, so
# resolving them from the master reactor is correct. Fall back to the local
# build only when the release JAR is not (yet) on Central (e.g. dry-run).
INFINISPAN_JAR="mockserver/mockserver-state-infinispan/target/mockserver-state-infinispan-${RELEASE_VERSION}.jar"
INFINISPAN_CENTRAL_URL="https://repo1.maven.org/maven2/org/mock-server/mockserver-state-infinispan/${RELEASE_VERSION}/mockserver-state-infinispan-${RELEASE_VERSION}.jar"
if curl -sf -I "$INFINISPAN_CENTRAL_URL" >/dev/null 2>&1; then
  log_info "Downloading released infinispan $RELEASE_VERSION JAR from Maven Central"
  mkdir -p mockserver/mockserver-state-infinispan/target
  curl -fsSL --max-time 300 --connect-timeout 30 --retry 3 --retry-delay 5 \
    -o "$INFINISPAN_JAR" \
    "$INFINISPAN_CENTRAL_URL"
else
  log_info "Released infinispan $RELEASE_VERSION JAR not on Maven Central — using locally-built module JAR"
  INFINISPAN_JAR=$(find_local_infinispan_jar)
fi

BUILD_CLUSTERED=false
if [[ -n "$INFINISPAN_JAR" && -f "$INFINISPAN_JAR" ]]; then
  log_info "Using infinispan JAR: $INFINISPAN_JAR"
  cp "$INFINISPAN_JAR" docker/clustered/libs/

  # Resolve transitive runtime dependencies (Infinispan, JGroups, etc.). The
  # mock-server reactor artifacts these depend on were installed to .m2 above,
  # so resolution succeeds; -DexcludeGroupIds=org.mock-server then keeps them
  # out of the copied set (the module JAR is copied separately, just above).
  log_info "Resolving infinispan transitive dependencies"
  in_maven -w /build/mockserver \
    -- mvn -pl mockserver-state-infinispan dependency:copy-dependencies \
      -DincludeScope=runtime -DexcludeGroupIds=org.mock-server \
      -DoutputDirectory=/build/docker/clustered/libs
  BUILD_CLUSTERED=true
else
  log_info "WARNING: Infinispan JAR not available — skipping clustered image build"
fi

# ---- Auth (skipped in dry-run) --------------------------------------------
# MIRROR_GHCR gates the GHCR image mirror (ghcr.io/mock-server/mockserver). It
# is enabled only when a GHCR login succeeds, so a missing/expired token or a
# GHCR outage degrades to "Docker Hub + ECR only" rather than aborting here;
# verify.sh then fails the release on the missing GHCR images, after every
# other channel has published.
MIRROR_GHCR=false
if ! is_dry_run; then
  log_info "Login to Docker Hub + ECR Public"
  # Release images use the release-scoped Docker Hub token (release queue only).
  DOCKERHUB_SECRET_ID="mockserver-release/dockerhub" \
    "$REPO_ROOT/.buildkite/scripts/docker-login.sh"
  "$REPO_ROOT/.buildkite/scripts/ecr-login.sh"

  # GHCR mirror login. Reuses the same mockserver-release/ghcr-token secret the
  # Helm chart already pushes with (oci://ghcr.io/mock-server/charts), so the
  # org packages + write scope already exist. Non-fatal here so the remaining
  # channels still publish; verify.sh's GHCR check is the gate.
  if aws secretsmanager describe-secret --region "$REGION" \
       --secret-id mockserver-release/ghcr-token >/dev/null 2>&1; then
    log_info "Login to GHCR (ghcr.io/mock-server) for the image mirror"
    if GHCR_USER=$(load_secret "mockserver-release/ghcr-token" "username") \
       && GHCR_TOKEN=$(load_secret "mockserver-release/ghcr-token" "token") \
       && printf '%s' "$GHCR_TOKEN" | docker login ghcr.io \
            --username "$GHCR_USER" --password-stdin >/dev/null 2>&1; then
      MIRROR_GHCR=true
      log_info "  GHCR login OK — images will be mirrored to ghcr.io/mock-server/mockserver"
    else
      log_info "  WARNING: GHCR login failed — skipping GHCR mirror (non-fatal)"
    fi
    unset GHCR_TOKEN GHCR_USER
  else
    log_info "GHCR token not configured (mockserver-release/ghcr-token) — skipping GHCR mirror"
  fi
fi

# GHCR image repository (mirror target). The manifests are copied from ECR
# Public with `docker buildx imagetools create` after the primary push, so the
# GHCR images share the exact same digest (and cosign signature) as Docker Hub.
GHCR_REPO="ghcr.io/mock-server/mockserver"

FULL_TAG="mockserver-$RELEASE_VERSION"
SHORT_TAG="$RELEASE_VERSION"
# ECR Public repository URI. The registry alias is AWS-assigned (it is not the
# repository name), so resolve it at run time rather than hard-coding. A real
# run must fail loudly if the lookup fails; a local dry-run without AWS
# credentials falls back to a placeholder — the ECR tags are built but never
# pushed in dry-run.
if is_dry_run; then
  ECR_REPO=$(aws ecr-public describe-repositories --region us-east-1 \
    --repository-names mockserver --query 'repositories[0].repositoryUri' \
    --output text 2>/dev/null || echo "public.ecr.aws/dry-run/mockserver")
else
  ECR_REPO=$(aws ecr-public describe-repositories --region us-east-1 \
    --repository-names mockserver --query 'repositories[0].repositoryUri' --output text)
fi

log_info "Build images"

# ---- Locate or build webhook fat JAR ----------------------------------------
find_local_webhook_jar() {
  find mockserver/mockserver-k8s-webhook/target \
    -name 'mockserver-k8s-webhook-*-jar-with-dependencies.jar' \
    -print -quit 2>/dev/null || true
}
WEBHOOK_JAR=$(find_local_webhook_jar)
if [[ -z "$WEBHOOK_JAR" ]]; then
  log_info "Webhook fat JAR not found locally — downloading from Maven Central"
  mkdir -p mockserver/mockserver-k8s-webhook/target
  WEBHOOK_JAR="mockserver/mockserver-k8s-webhook/target/mockserver-k8s-webhook-${RELEASE_VERSION}-jar-with-dependencies.jar"
  WEBHOOK_CENTRAL_URL="https://repo1.maven.org/maven2/org/mock-server/mockserver-k8s-webhook/${RELEASE_VERSION}/mockserver-k8s-webhook-${RELEASE_VERSION}-jar-with-dependencies.jar"
  if is_dry_run && ! curl -sf -I "$WEBHOOK_CENTRAL_URL" >/dev/null 2>&1; then
    log_dry "skip: download webhook $RELEASE_VERSION JAR (not yet on Maven Central)"
    WEBHOOK_JAR=$(find_local_webhook_jar)
    if [[ -z "$WEBHOOK_JAR" ]]; then
      log_dry "no local webhook JAR available — running 'mvn package' to produce one"
      in_maven -w /build/mockserver \
        -- mvn -DskipTests -pl mockserver-k8s-webhook -am package
      WEBHOOK_JAR=$(find_local_webhook_jar)
    fi
  else
    curl -fsSL --max-time 300 --connect-timeout 30 --retry 3 --retry-delay 5 \
      -o "$WEBHOOK_JAR" \
      "$WEBHOOK_CENTRAL_URL"
  fi
fi
if [[ -n "$WEBHOOK_JAR" && -f "$WEBHOOK_JAR" ]]; then
  log_info "Using webhook JAR: $WEBHOOK_JAR"
  cp "$WEBHOOK_JAR" docker/webhook/mockserver-webhook.jar
  BUILD_WEBHOOK=true
else
  log_info "WARNING: Webhook JAR not available — skipping webhook image build"
  BUILD_WEBHOOK=false
fi

# --- Dashboard usage analytics (PostHog Cloud EU) ----------------------------
# The official released dashboard images (local / graaljs / clustered — NOT webhook, which has
# no dashboard) activate cookieless usage analytics by baking the project's PostHog Cloud EU
# endpoint + write-only public ingest key into the image as MOCKSERVER_DASHBOARD_ANALYTICS_*
# (read by the dashboard's activation gate). The values live ONLY in Secrets Manager
# (mockserver-release/dashboard-analytics, keys: endpoint + key) — never in the source tree — so
# a fork building these Dockerfiles directly ships analytics INERT. In dry-run we leave them
# empty so locally-built test images also stay inert; a missing secret degrades to inert, never
# a release failure.
DASHBOARD_ANALYTICS_ENDPOINT=""
DASHBOARD_ANALYTICS_KEY=""
if ! is_dry_run; then
  DASHBOARD_ANALYTICS_ENDPOINT="$(load_secret "mockserver-release/dashboard-analytics" "endpoint" 2>/dev/null || echo "")"
  DASHBOARD_ANALYTICS_KEY="$(load_secret "mockserver-release/dashboard-analytics" "key" 2>/dev/null || echo "")"
  # A secret present but missing the field yields the jq literal "null"; normalise to empty so a
  # misconfigured secret ships INERT rather than baking endpoint/key="null".
  [[ "$DASHBOARD_ANALYTICS_ENDPOINT" == "null" ]] && DASHBOARD_ANALYTICS_ENDPOINT=""
  [[ "$DASHBOARD_ANALYTICS_KEY" == "null" ]] && DASHBOARD_ANALYTICS_KEY=""
  if [[ -z "$DASHBOARD_ANALYTICS_KEY" || -z "$DASHBOARD_ANALYTICS_ENDPOINT" ]]; then
    log_info "WARNING: mockserver-release/dashboard-analytics not fully available — dashboard analytics will ship INERT in released images"
  fi
fi

if is_dry_run; then
  # Local single-arch via the default daemon. Plain `docker build` reuses
  # Docker Desktop's CA trust (whereas a fresh buildx builder does not).
  docker build \
    --tag "mockserver/mockserver:$FULL_TAG" \
    --tag "mockserver/mockserver:$SHORT_TAG" \
    --tag "mockserver/mockserver:latest" \
    --tag "${ECR_REPO}:$FULL_TAG" \
    --tag "${ECR_REPO}:$SHORT_TAG" \
    --tag "${ECR_REPO}:latest" \
    docker/local

  docker build \
    --build-arg source=copy \
    --tag "mockserver/mockserver:$FULL_TAG-graaljs" \
    --tag "mockserver/mockserver:$SHORT_TAG-graaljs" \
    --tag "mockserver/mockserver:latest-graaljs" \
    --tag "${ECR_REPO}:$FULL_TAG-graaljs" \
    --tag "${ECR_REPO}:$SHORT_TAG-graaljs" \
    --tag "${ECR_REPO}:latest-graaljs" \
    docker/graaljs

  # Experimental JDK 25 AOT-cache variant (opt-in -aot tags). source=copy consumes the staged
  # shaded jar; no dashboard-analytics build args (the aot Dockerfile does not declare them).
  # Hard-fail here is intentional (asymmetric with the soft-fail release path): build defects
  # should surface in the dry-run rather than being silently skipped at release time.
  docker build \
    --build-arg source=copy \
    --tag "mockserver/mockserver:$FULL_TAG-aot" \
    --tag "mockserver/mockserver:$SHORT_TAG-aot" \
    --tag "mockserver/mockserver:latest-aot" \
    --tag "${ECR_REPO}:$FULL_TAG-aot" \
    --tag "${ECR_REPO}:$SHORT_TAG-aot" \
    --tag "${ECR_REPO}:latest-aot" \
    docker/aot

  if [[ "$BUILD_CLUSTERED" == "true" ]]; then
    docker build \
      --tag "mockserver/mockserver:clustered-$FULL_TAG" \
      --tag "mockserver/mockserver:clustered-$SHORT_TAG" \
      --tag "mockserver/mockserver:clustered-latest" \
      --tag "${ECR_REPO}:clustered-$FULL_TAG" \
      --tag "${ECR_REPO}:clustered-$SHORT_TAG" \
      --tag "${ECR_REPO}:clustered-latest" \
      docker/clustered
  fi

  if [[ "$BUILD_WEBHOOK" == "true" ]]; then
    docker build \
      --tag "mockserver/mockserver-webhook:$FULL_TAG" \
      --tag "mockserver/mockserver-webhook:$SHORT_TAG" \
      --tag "mockserver/mockserver-webhook:latest" \
      --tag "${ECR_REPO}-webhook:$FULL_TAG" \
      --tag "${ECR_REPO}-webhook:$SHORT_TAG" \
      --tag "${ECR_REPO}-webhook:latest" \
      docker/webhook
  fi

  # -http3 LAST, as in the real release: a deprecated alias of the image above, and both are
  # smoke-tested with a real HTTP/3 request. Hard-fail: build defects surface here, not at release time.
  docker build \
    --build-arg BASE_IMAGE="mockserver/mockserver:$FULL_TAG" \
    --tag "mockserver/mockserver:$FULL_TAG-http3" \
    --tag "mockserver/mockserver:$SHORT_TAG-http3" \
    --tag "mockserver/mockserver:latest-http3" \
    --tag "${ECR_REPO}:$FULL_TAG-http3" \
    --tag "${ECR_REPO}:$SHORT_TAG-http3" \
    --tag "${ECR_REPO}:latest-http3" \
    docker/http3
  "$REPO_ROOT/.buildkite/scripts/steps/docker-http3-smoke.sh" "mockserver/mockserver:$FULL_TAG-http3" "mockserver/mockserver:$FULL_TAG"

  log_dry "skip: push to Docker Hub + ECR (built locally, not pushed)"
  log_dry "skip: mirror to ghcr.io/mock-server/mockserver (only on --execute)"
else
  # CI: multi-arch + push via buildx.
  docker buildx create --use --name multiarch 2>/dev/null || docker buildx use multiarch

  docker buildx build \
    --platform "linux/amd64,linux/arm64" \
    --push \
    --build-arg DASHBOARD_ANALYTICS_ENDPOINT="$DASHBOARD_ANALYTICS_ENDPOINT" \
    --build-arg DASHBOARD_ANALYTICS_KEY="$DASHBOARD_ANALYTICS_KEY" \
    --tag "mockserver/mockserver:$FULL_TAG" \
    --tag "mockserver/mockserver:$SHORT_TAG" \
    --tag "mockserver/mockserver:latest" \
    --tag "${ECR_REPO}:$FULL_TAG" \
    --tag "${ECR_REPO}:$SHORT_TAG" \
    --tag "${ECR_REPO}:latest" \
    docker/local

  docker buildx build \
    --platform "linux/amd64,linux/arm64" \
    --push \
    --build-arg source=copy \
    --build-arg DASHBOARD_ANALYTICS_ENDPOINT="$DASHBOARD_ANALYTICS_ENDPOINT" \
    --build-arg DASHBOARD_ANALYTICS_KEY="$DASHBOARD_ANALYTICS_KEY" \
    --tag "mockserver/mockserver:$FULL_TAG-graaljs" \
    --tag "mockserver/mockserver:$SHORT_TAG-graaljs" \
    --tag "mockserver/mockserver:latest-graaljs" \
    --tag "${ECR_REPO}:$FULL_TAG-graaljs" \
    --tag "${ECR_REPO}:$SHORT_TAG-graaljs" \
    --tag "${ECR_REPO}:latest-graaljs" \
    docker/graaljs

  # ---- Experimental JDK 25 AOT-cache variant (opt-in -aot tags) --------------
  # SOFT-fail (non-fatal): unlike the clustered image, an -aot build/push failure must NOT abort
  # the release — the main + graaljs images are already published by this point, and -aot is an
  # experimental opt-in surface. The arm64 training run executes under QEMU emulation (the AOT
  # cache is arch-specific, so each platform trains its own during the build) and is the most
  # likely failure point; wrapping the whole build in `if ! ...` swallows any non-zero exit so a
  # failure degrades to "no -aot tags this release" rather than a failed release. No
  # dashboard-analytics build args: the aot Dockerfile does not declare them. source=copy consumes
  # the staged shaded jar (its download stage — which would COPY ca-bundle.pem — is not built).
  echo "--- :docker: Building and pushing experimental -aot image variant (non-fatal)"
  AOT_PUBLISHED=false
  if docker buildx build \
    --platform "linux/amd64,linux/arm64" \
    --push \
    --build-arg source=copy \
    --tag "mockserver/mockserver:$FULL_TAG-aot" \
    --tag "mockserver/mockserver:$SHORT_TAG-aot" \
    --tag "mockserver/mockserver:latest-aot" \
    --tag "${ECR_REPO}:$FULL_TAG-aot" \
    --tag "${ECR_REPO}:$SHORT_TAG-aot" \
    --tag "${ECR_REPO}:latest-aot" \
    docker/aot; then
    AOT_PUBLISHED=true
  else
    log_info "WARNING: -aot image build failed — skipping (non-fatal; likely the arm64 QEMU training run)"
  fi

  if [[ "$BUILD_CLUSTERED" == "true" ]]; then
    # HARD-fail with retry: a transient registry blip is retried, but a real
    # clustered image push failure aborts the release rather than being silently
    # swallowed. No release error should be ignored.
    echo "--- :docker: Building and pushing clustered image variant"
    retry 3 5 -- docker buildx build \
      --platform "linux/amd64,linux/arm64" \
      --push \
      --build-arg DASHBOARD_ANALYTICS_ENDPOINT="$DASHBOARD_ANALYTICS_ENDPOINT" \
      --build-arg DASHBOARD_ANALYTICS_KEY="$DASHBOARD_ANALYTICS_KEY" \
      --tag "mockserver/mockserver:clustered-$FULL_TAG" \
      --tag "mockserver/mockserver:clustered-$SHORT_TAG" \
      --tag "mockserver/mockserver:clustered-latest" \
      --tag "${ECR_REPO}:clustered-$FULL_TAG" \
      --tag "${ECR_REPO}:clustered-$SHORT_TAG" \
      --tag "${ECR_REPO}:clustered-latest" \
      docker/clustered
  fi

  if [[ "$BUILD_WEBHOOK" == "true" ]]; then
    # Push webhook to Docker Hub first (primary registry used by Helm chart).
    # HARD-fail with retry: the webhook repo + push scope are provisioned, so a
    # push failure is a real error and must abort the release, not be swallowed.
    retry 3 5 -- docker buildx build \
      --platform "linux/amd64,linux/arm64" \
      --push \
      --tag "mockserver/mockserver-webhook:$FULL_TAG" \
      --tag "mockserver/mockserver-webhook:$SHORT_TAG" \
      --tag "mockserver/mockserver-webhook:latest" \
      docker/webhook
    # Push webhook to ECR separately. HARD-fail with retry — same policy as the
    # Docker Hub push above; a real failure aborts rather than being swallowed.
    retry 3 5 -- docker buildx build \
      --platform "linux/amd64,linux/arm64" \
      --push \
      --tag "${ECR_REPO}-webhook:$FULL_TAG" \
      --tag "${ECR_REPO}-webhook:$SHORT_TAG" \
      --tag "${ECR_REPO}-webhook:latest" \
      docker/webhook
  fi

  # ---- Mirror images to GHCR -----------------------------------------------
  # Copy the already-pushed multi-arch manifests to GHCR with `docker buildx
  # imagetools create` (a registry-to-registry manifest copy — no rebuild,
  # identical digest). The MIRROR_GHCR gate (set only when GHCR login succeeded)
  # decides WHETHER to mirror; once we're inside it, the GHCR mirror is a
  # committed surface, so a per-tag mirror failure is HARD with retry — a
  # transient blip is retried, a real failure aborts the release.
  #
  # Source the copy from ECR Public, NOT Docker Hub. The single buildx build
  # above pushed the SAME manifest (identical digest) to both registries, so
  # either is a valid source — but Docker Hub rate-limits authenticated pulls
  # (`429 toomanyrequests` for mockserverprincipal), and a manifest copy re-pulls
  # every layer. Build #52's mirror exhausted the Docker Hub pull budget and
  # aborted the release; ECR Public has no such pull limit, so mirroring from it
  # sidesteps the rate limit entirely while producing the identical GHCR digest.
  if [[ "$MIRROR_GHCR" == "true" ]]; then
    echo "--- :docker: Mirroring images to ghcr.io/mock-server/mockserver (source: ECR Public)"
    mirror_to_ghcr() {
      # $1 = source ref (ECR Public — not Docker Hub, to dodge its pull limit),
      # $2 = destination ref (GHCR)
      docker buildx imagetools create --tag "$2" "$1"
    }
    for t in "$FULL_TAG" "$SHORT_TAG" "latest" \
             "$FULL_TAG-graaljs" "$SHORT_TAG-graaljs" "latest-graaljs"; do
      retry 3 5 -- mirror_to_ghcr "${ECR_REPO}:$t" "${GHCR_REPO}:$t"
    done
    if [[ "$BUILD_CLUSTERED" == "true" ]]; then
      for t in "clustered-$FULL_TAG" "clustered-$SHORT_TAG" "clustered-latest"; do
        retry 3 5 -- mirror_to_ghcr "${ECR_REPO}:$t" "${GHCR_REPO}:$t"
      done
    fi
    if [[ "$BUILD_WEBHOOK" == "true" ]]; then
      for t in "$FULL_TAG" "$SHORT_TAG" "latest"; do
        retry 3 5 -- mirror_to_ghcr "${ECR_REPO}-webhook:$t" "${GHCR_REPO}-webhook:$t"
      done
    fi
  fi

  # Prints the index digest of image ref $1: the first `Digest:` line of `imagetools inspect`'s plain
  # output (`--format` template fields differ across buildx versions; on the release agent
  # `.Manifest.Digest` printed nothing). The output is captured before parsing because piping it into
  # an early-exiting awk races: imagetools can die of SIGPIPE (exit 255, no message). On failure it
  # prints the command, exit status and output on stderr and returns 1.
  image_index_digest() {
    local out rc=0 digest
    out="$(docker buildx imagetools inspect "$1" 2>&1)" || rc=$?
    if (( rc != 0 )); then
      echo "docker buildx imagetools inspect $1 failed (exit $rc): $out" >&2
      return 1
    fi
    digest="$(awk '/^Digest:/{print $2; exit}' <<<"$out")"
    if [[ ! "$digest" =~ ^sha256:[0-9a-f]{64}$ ]]; then
      echo "docker buildx imagetools inspect $1 printed no sha256 Digest line: $out" >&2
      return 1
    fi
    printf '%s\n' "$digest"
  }

  # ---- Cosign-sign pushed Docker images ------------------------------------
  # Sign by digest so the signature binds to the exact manifest, not a mutable
  # tag. Uses the SAME cosign key infrastructure as helm.sh. NO-OP until a
  # signing key is stored at mockserver-release/cosign-key (keys: key, password)
  # — the describe-secret guard skips this entirely otherwise.
  # Signing is additive and STRICTLY non-fatal: a failure here never aborts
  # the release — the images are already pushed.
  # Resolve a usable cosign binary into $_cosign_bin: the one on PATH, else the
  # pinned release downloaded into .tmp/ (resolve_cosign in _lib.sh; no sudo
  # needed — docker.sh signs on the HOST). Non-zero if none can be had.
  ensure_cosign() {
    _cosign_bin="$(resolve_cosign "$REPO_ROOT/.tmp/cosign")" || {
      log_error "cosign unavailable — cannot sign images"
      return 1
    }
  }

  cosign_sign_docker_image() {
    local image_ref="$1"
    # Resolve the tag to a digest so we sign by content, not by mutable tag.
    local digest
    if ! digest="$(image_index_digest "$image_ref")"; then
      log_info "WARNING: could not resolve digest for $image_ref — skipping cosign sign"
      return 1
    fi
    local repo="${image_ref%%:*}"
    local ref_by_digest="${repo}@${digest}"
    log_info "  cosign sign $ref_by_digest"
    "$_cosign_bin" sign --yes --key "$_cosign_key_file" "$ref_by_digest" || return 1
  }

  # With arguments, signs exactly those refs (the -http3 block); without, the core release images.
  cosign_sign_docker_images() {
    local rc=0
    ensure_cosign || return 1
    mkdir -p "$REPO_ROOT/.tmp"
    _cosign_key_file="$REPO_ROOT/.tmp/cosign-key-docker.$$"
    local _cosign_pw_file="$REPO_ROOT/.tmp/cosign-pw-docker.$$"
    ( umask 077; load_secret "mockserver-release/cosign-key" "key" > "$_cosign_key_file" ) \
      || { rm -f "$_cosign_key_file"; return 1; }
    ( umask 077; load_secret "mockserver-release/cosign-key" "password" > "$_cosign_pw_file" ) \
      || { rm -f "$_cosign_key_file" "$_cosign_pw_file"; return 1; }
    export COSIGN_PASSWORD
    COSIGN_PASSWORD=$(cat "$_cosign_pw_file")
    rm -f "$_cosign_pw_file"

    # Sign primary images (Docker Hub + ECR) by their :latest tag (resolves to
    # the multi-arch manifest digest).
    local -a images_to_sign=(
      "mockserver/mockserver:$FULL_TAG"
      "${ECR_REPO}:$FULL_TAG"
      "mockserver/mockserver:$FULL_TAG-graaljs"
      "${ECR_REPO}:$FULL_TAG-graaljs"
    )
    if [[ "$BUILD_CLUSTERED" == "true" ]]; then
      images_to_sign+=(
        "mockserver/mockserver:clustered-$FULL_TAG"
        "${ECR_REPO}:clustered-$FULL_TAG"
      )
    fi
    # The experimental -aot variant is soft-fail at build time, but once its tags ARE published
    # they carry the same "release images are cosign-signed" guarantee as every other variant, so
    # sign them whenever the build succeeded. Signing shares the key/registries with the images
    # above, so this adds no meaningful abort risk beyond what the mandatory images already carry.
    if [[ "${AOT_PUBLISHED:-false}" == "true" ]]; then
      images_to_sign+=(
        "mockserver/mockserver:$FULL_TAG-aot"
        "${ECR_REPO}:$FULL_TAG-aot"
      )
    fi
    if [[ "$BUILD_WEBHOOK" == "true" ]]; then
      images_to_sign+=(
        "mockserver/mockserver-webhook:$FULL_TAG"
      )
    fi
    # GHCR mirror refs (same digests as Docker Hub, but cosign stores the
    # signature in the registry holding the image, so GHCR needs its own sign).
    if [[ $# -gt 0 ]]; then
      images_to_sign=("$@")
    elif [[ "$MIRROR_GHCR" == "true" ]]; then
      images_to_sign+=(
        "${GHCR_REPO}:$FULL_TAG"
        "${GHCR_REPO}:$FULL_TAG-graaljs"
      )
      [[ "$BUILD_CLUSTERED" == "true" ]] && images_to_sign+=( "${GHCR_REPO}:clustered-$FULL_TAG" )
      [[ "$BUILD_WEBHOOK" == "true" ]]   && images_to_sign+=( "${GHCR_REPO}-webhook:$FULL_TAG" )
    fi
    for img in "${images_to_sign[@]}"; do
      # HARD with retry: all secrets (cosign-key, ghcr-token) exist, so signing
      # is expected to work. A transient blip (registry 5xx, digest-resolution
      # lag) is retried; a real signing failure sets rc=1 and is propagated so
      # the release aborts rather than silently publishing unsigned images.
      retry 3 5 -- cosign_sign_docker_image "$img" || {
        log_error "cosign signing failed for $img after retries"
        rc=1
      }
    done
    rm -f "$_cosign_key_file"
    unset COSIGN_PASSWORD
    return $rc
  }

  SIGN_DOCKER_IMAGES=false
  if aws secretsmanager describe-secret --region "$REGION" \
       --secret-id mockserver-release/cosign-key >/dev/null 2>&1; then
    SIGN_DOCKER_IMAGES=true
    log_info "Cosign-signing pushed Docker images (mockserver-release/cosign-key found)"
    # cosign signs on the HOST (the images are already pushed to a registry).
    # cosign_sign_docker_images resolves a cosign binary via ensure_cosign,
    # installing the pinned release into .tmp/ if one is not already on PATH.
    # HARD-fail: the presence-gate above decides WHETHER to sign; once the key
    # exists, a signing failure (after per-image retries) is a real error and
    # must abort the release rather than publishing some images unsigned.
    if cosign_sign_docker_images; then
      log_info "Docker images signed with cosign"
    else
      log_error "cosign signing failed — one or more Docker images could not be signed"
      exit 1
    fi
  else
    log_info "cosign key not configured (mockserver-release/cosign-key) — skipping Docker image signing"
  fi

  # ---- Sync the Docker Hub "Overview" from docker/DOCKERHUB.md --------------
  # Keeps the repo's Docker Hub landing page in sync with version control so it
  # never goes stale (it previously drifted: dead Trello board + Heroku Slack
  # link). The release-scoped Docker Hub token has repo-write scope, so the
  # token exchange + PATCH are expected to succeed. HARD-fail with retry: a
  # transient HTTP/network blip is retried, but a real failure (e.g. token
  # rejected, or a non-200 from the description PATCH) aborts the release rather
  # than being silently swallowed. The "no docker/DOCKERHUB.md" branch is a
  # legitimate capability gate (nothing to sync) and stays a clean skip.
  # Writes the bearer to $1 (a 0600 file) rather than stdout, so it can be
  # wrapped in `retry` without the retry's own log lines polluting the captured
  # value.
  exchange_dockerhub_bearer() {
    local out_file="$1" user token bearer
    user=$(load_secret "mockserver-release/dockerhub" "username") || return 1
    token=$(load_secret "mockserver-release/dockerhub" "token") || return 1
    # A Docker Hub Personal/Org Access Token must be exchanged for a bearer via
    # /v2/auth/token (it cannot be used as a bearer directly, and the legacy
    # /v2/users/login JWT lacks the scope to edit repo metadata).
    bearer=$(curl -sf --max-time 30 -H "Content-Type: application/json" \
      -d "{\"identifier\":\"${user}\",\"secret\":\"${token}\"}" \
      https://hub.docker.com/v2/auth/token \
      | python3 -c "import sys,json;print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null)
    [[ -n "$bearer" ]] || return 1
    ( umask 077; printf '%s' "$bearer" > "$out_file" )
  }
  patch_dockerhub_description() {
    local bearer="$1" desc_file="$2" code
    code=$(python3 - "$bearer" "$desc_file" <<'PY'
import sys, json, urllib.request, urllib.error
bearer, path = sys.argv[1], sys.argv[2]
body = json.dumps({"full_description": open(path).read()}).encode()
req = urllib.request.Request(
    "https://hub.docker.com/v2/repositories/mockserver/mockserver/",
    data=body, method="PATCH",
    headers={"Content-Type": "application/json", "Authorization": "Bearer " + bearer})
try:
    print(urllib.request.urlopen(req, timeout=30).status)
except urllib.error.HTTPError as e:
    print(e.code)
PY
)
    if [[ "$code" == "200" ]]; then
      log_info "  Docker Hub overview updated from docker/DOCKERHUB.md"
      return 0
    fi
    log_error "  Docker Hub overview update returned HTTP ${code:-?}"
    return 1
  }
  sync_dockerhub_description() {
    local desc_file="$REPO_ROOT/docker/DOCKERHUB.md" bearer_file bearer rc=0
    [[ -f "$desc_file" ]] || { log_info "  no docker/DOCKERHUB.md — skipping overview sync"; return 0; }
    mkdir -p "$REPO_ROOT/.tmp"
    bearer_file="$REPO_ROOT/.tmp/dockerhub-bearer.$$"
    retry 3 5 -- exchange_dockerhub_bearer "$bearer_file" \
      || { log_error "  Docker Hub token exchange failed after retries"; rm -f "$bearer_file"; return 1; }
    bearer=$(cat "$bearer_file")
    rm -f "$bearer_file"
    retry 3 5 -- patch_dockerhub_description "$bearer" "$desc_file" || rc=1
    return $rc
  }
  log_info "Sync Docker Hub overview from docker/DOCKERHUB.md"
  # HARD (with internal retry): the release token's account has repo-admin on the
  # mockserver Docker Hub repos, and the full_description PATCH returns 200 — the
  # old "needs repo:admin scope / 403" note was stale (it predates the account
  # being granted admin; verified live). sync_dockerhub_description retries the
  # token exchange + PATCH internally, so a genuine failure aborts under set -e.
  sync_dockerhub_description

  # ---- -http3 variant, LAST ------------------------------------------------
  # A deprecated alias of the standard image digest just pushed, which already carries the QUIC
  # native (docker/http3 adds nothing over it). It runs after every core image is pushed, mirrored
  # and signed, so a failure here stops only the -http3 publish. It is still HARD (existing -http3
  # users must get this release): both images are smoke-tested on the agent's arch first, then the
  # alias is pushed, mirrored and signed exactly like the core images.
  echo "--- :docker: Building, smoke-testing and pushing the -http3 image variant"
  resolve_http3_base_digest() {
    HTTP3_BASE_DIGEST="$(image_index_digest "${ECR_REPO}:$FULL_TAG")"
  }
  retry 3 5 -- resolve_http3_base_digest \
    || { log_error "could not resolve the digest of ${ECR_REPO}:$FULL_TAG for the -http3 base"; exit 1; }
  HTTP3_BASE="${ECR_REPO}@${HTTP3_BASE_DIGEST}"
  HTTP3_SMOKE_TAG="mockserver/mockserver:smoke-http3-$$"
  retry 3 5 -- docker pull "$HTTP3_BASE"
  retry 3 5 -- docker buildx build --load --build-arg BASE_IMAGE="$HTTP3_BASE" --tag "$HTTP3_SMOKE_TAG" docker/http3
  "$REPO_ROOT/.buildkite/scripts/steps/docker-http3-smoke.sh" "$HTTP3_SMOKE_TAG" "$HTTP3_BASE"
  docker rmi -f "$HTTP3_SMOKE_TAG" >/dev/null 2>&1 || true
  retry 3 5 -- docker buildx build \
    --platform "linux/amd64,linux/arm64" \
    --push \
    --build-arg BASE_IMAGE="$HTTP3_BASE" \
    --tag "mockserver/mockserver:$FULL_TAG-http3" \
    --tag "mockserver/mockserver:$SHORT_TAG-http3" \
    --tag "mockserver/mockserver:latest-http3" \
    --tag "${ECR_REPO}:$FULL_TAG-http3" \
    --tag "${ECR_REPO}:$SHORT_TAG-http3" \
    --tag "${ECR_REPO}:latest-http3" \
    docker/http3
  HTTP3_TO_SIGN=("mockserver/mockserver:$FULL_TAG-http3" "${ECR_REPO}:$FULL_TAG-http3")
  if [[ "$MIRROR_GHCR" == "true" ]]; then
    for t in "$FULL_TAG-http3" "$SHORT_TAG-http3" "latest-http3"; do
      retry 3 5 -- mirror_to_ghcr "${ECR_REPO}:$t" "${GHCR_REPO}:$t"
    done
    HTTP3_TO_SIGN+=("${GHCR_REPO}:$FULL_TAG-http3")
  fi
  if [[ "$SIGN_DOCKER_IMAGES" == "true" ]]; then
    cosign_sign_docker_images "${HTTP3_TO_SIGN[@]}" \
      || { log_error "cosign signing failed for the -http3 image"; exit 1; }
  fi
fi

log_info "Docker publish complete"
