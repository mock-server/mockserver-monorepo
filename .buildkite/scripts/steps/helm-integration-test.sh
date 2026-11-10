#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# The three image-dependent k3d cases (helm_sidecar_injection,
# helm_clustered_convergence, helm_jgroups_dns_ping) need Java-built images the
# Maven reactor produces. This step runs on a Docker-only agent with NO JDK, so
# the jars are built by the upstream "build container-test images (jars)" step
# (container-tests-build-images.sh) and handed here as Buildkite artifacts. We
# download them and do cheap `docker build`s (COPY jar into distroless), then run
# the harness with REQUIRE_*_IMAGE=true so an absent image is a FAILURE, never a
# silent skip. Failing to download the jars fails this step closed — a missing
# build cannot revert to skipping.
# ---------------------------------------------------------------------------
NETTY_JAR_DIR="mockserver/mockserver-netty/target"
WEBHOOK_JAR_DIR="mockserver/mockserver-k8s-webhook/target"
CLUSTERED_LIBS_DIR="mockserver/mockserver-state-infinispan/target/clustered-libs"

if ! command -v buildkite-agent &>/dev/null; then
  echo ":x: buildkite-agent not found — cannot download the tree-built image jars, so the -clustered + webhook images cannot be built and the k3d image-dependent cases would silently skip. Failing closed." >&2
  exit 1
fi

echo "--- :buildkite: Downloading tree-built image jars"
buildkite-agent artifact download "$NETTY_JAR_DIR/mockserver-netty-*-jar-with-dependencies.jar" .
buildkite-agent artifact download "$WEBHOOK_JAR_DIR/mockserver-k8s-webhook-*-jar-with-dependencies.jar" .
buildkite-agent artifact download "$CLUSTERED_LIBS_DIR/*.jar" .

# Resolve the concrete fat jars (exclude sources/javadoc/original- classifiers).
shopt -s nullglob
NETTY_JAR=""
for f in "$NETTY_JAR_DIR"/mockserver-netty-*-jar-with-dependencies.jar; do
  case "$(basename "$f")" in *-sources.jar|*-javadoc.jar|original-*) continue ;; esac
  NETTY_JAR="$f"; break
done
WEBHOOK_JAR=""
for f in "$WEBHOOK_JAR_DIR"/mockserver-k8s-webhook-*-jar-with-dependencies.jar; do
  case "$(basename "$f")" in *-sources.jar|*-javadoc.jar|original-*) continue ;; esac
  WEBHOOK_JAR="$f"; break
done
shopt -u nullglob

CLUSTERED_LIB_COUNT=0
if [ -d "$CLUSTERED_LIBS_DIR" ]; then
  CLUSTERED_LIB_COUNT=$(find "$CLUSTERED_LIBS_DIR" -name '*.jar' -type f | wc -l | tr -d ' ')
fi

if [ -z "$NETTY_JAR" ] || [ ! -f "$NETTY_JAR" ]; then
  echo ":x: netty fat jar not found after artifact download — the upstream build step did not produce it. Failing closed." >&2
  exit 1
fi
if [ -z "$WEBHOOK_JAR" ] || [ ! -f "$WEBHOOK_JAR" ]; then
  echo ":x: webhook fat jar not found after artifact download — the upstream build step did not produce it. Failing closed." >&2
  exit 1
fi
if [ "$CLUSTERED_LIB_COUNT" -eq 0 ]; then
  echo ":x: clustered /libs jars not found after artifact download — the upstream build step did not produce them. Failing closed." >&2
  exit 1
fi

echo "--- :docker: Building mockserver/mockserver:integration_testing (base image)"
cp "$NETTY_JAR" docker/mockserver-netty-jar-with-dependencies.jar
# The base docker/Dockerfile COPYs ca-bundle.pem; stage a placeholder (or the
# corporate CA via MOCKSERVER_LOCAL_CA_BUNDLE) before building, clean up after.
CA_BUNDLE_STATE=$(docker/ensure-ca-bundle.sh docker)
docker build --no-cache -t mockserver/mockserver:integration_testing --build-arg source=copy docker
rm -f docker/mockserver-netty-jar-with-dependencies.jar
[ "$CA_BUNDLE_STATE" = "created" ] && rm -f docker/ca-bundle.pem

echo "--- :docker: Building mockserver/mockserver:integration_testing_clustered"
# Assemble the clustered build context exactly as build_clustered_docker() does
# for local dev: the netty fat jar as the base + the Infinispan module and its
# runtime deps under /libs (the clustered ENTRYPOINT globs /libs/* onto the
# classpath).
cp "$NETTY_JAR" docker/clustered/mockserver-netty-jar-with-dependencies.jar
rm -rf docker/clustered/libs && mkdir -p docker/clustered/libs
cp "$CLUSTERED_LIBS_DIR"/*.jar docker/clustered/libs/
CLUSTERED_CA_STATE=$(docker/ensure-ca-bundle.sh docker/clustered)
docker build --no-cache -t mockserver/mockserver:integration_testing_clustered docker/clustered
rm -f docker/clustered/mockserver-netty-jar-with-dependencies.jar
rm -rf docker/clustered/libs
[ "$CLUSTERED_CA_STATE" = "created" ] && rm -f docker/clustered/ca-bundle.pem

echo "--- :docker: Building mockserver/mockserver-webhook:integration_testing"
# docker/webhook is single-stage distroless and does NOT COPY a ca-bundle.
cp "$WEBHOOK_JAR" docker/webhook/mockserver-webhook.jar
docker build --no-cache -t mockserver/mockserver-webhook:integration_testing docker/webhook
rm -f docker/webhook/mockserver-webhook.jar

echo "--- :helm: Installing helm (if needed)"
# Build agents are spot instances and helm pre-installation is inconsistent
# across AMI snapshots (build #46 missing helm while #45 had it on a different
# agent of the same fleet). Install + SHA256-verify explicitly so behaviour
# is deterministic. SHA256 from
# https://get.helm.sh/helm-${HELM_VERSION}-linux-${ARCH}.tar.gz.sha256sum
HELM_VERSION="v3.16.4"
HELM_BIN_DIR="${PWD}/.tmp/bin"
export PATH="${HELM_BIN_DIR}:${PATH}"

declare -A HELM_SHA256=(
  [amd64]="fc307327959aa38ed8f9f7e66d45492bb022a66c3e5da6063958254b9767d179"
  [arm64]="d3f8f15b3d9ec8c8678fbf3280c3e5902efabe5912e2f9fcf29107efbc8ead69"
)

if ! command -v helm &>/dev/null || [[ "$(helm version --short 2>/dev/null)" != *"${HELM_VERSION}"* ]]; then
  mkdir -p "$HELM_BIN_DIR"
  ARCH=$(uname -m); case "$ARCH" in x86_64) ARCH=amd64;; aarch64) ARCH=arm64;; esac
  HELM_TGZ="${HELM_BIN_DIR}/helm.tar.gz"
  curl -fsSL --max-time 300 --connect-timeout 10 --retry 3 --retry-delay 5 "https://get.helm.sh/helm-${HELM_VERSION}-linux-${ARCH}.tar.gz" -o "$HELM_TGZ"

  EXPECTED_SHA="${HELM_SHA256[$ARCH]:-}"
  if [[ -z "$EXPECTED_SHA" ]]; then
    echo "ERROR: no SHA256 pin for helm on $ARCH - refusing to install untrusted binary" >&2
    exit 1
  fi
  echo "${EXPECTED_SHA}  ${HELM_TGZ}" | sha256sum -c -

  tar -xzf "$HELM_TGZ" -C "$HELM_BIN_DIR" --strip-components=1 "linux-${ARCH}/helm"
  chmod +x "$HELM_BIN_DIR/helm"
  rm -f "$HELM_TGZ"
  helm version --short
fi

echo "--- :k8s: Installing k3d (if needed)"
K3D_VERSION="v5.7.5"
K3D_DIR="${PWD}/.tmp/bin"
export PATH="${K3D_DIR}:${PATH}"

# F-BK-05: pin and verify the k3d binary by SHA256. Update these values when
# bumping K3D_VERSION — published at
# https://github.com/k3d-io/k3d/releases/download/${K3D_VERSION}/checksums.txt
declare -A K3D_SHA256=(
  [amd64]="5d3f22817d9e163ab6ed43572189dd49fe724d7a6948075b570067747eca8d3f" # k3d-linux-amd64
  [arm64]="ac12fcf8e35481769e173c96d3fa70dc581826482d927b94a560a3375df2621e" # k3d-linux-arm64
)

if ! command -v k3d &>/dev/null || [[ "$(k3d version 2>/dev/null | head -1)" != *"${K3D_VERSION#v}"* ]]; then
  mkdir -p "$K3D_DIR"
  ARCH=$(uname -m); case "$ARCH" in x86_64) ARCH=amd64;; aarch64) ARCH=arm64;; esac
  curl -fsSL --max-time 300 --connect-timeout 10 --retry 3 --retry-delay 5 "https://github.com/k3d-io/k3d/releases/download/${K3D_VERSION}/k3d-linux-${ARCH}" -o "$K3D_DIR/k3d"

  EXPECTED_SHA="${K3D_SHA256[$ARCH]:-}"
  if [[ -z "$EXPECTED_SHA" ]]; then
    echo "ERROR: no SHA256 pin for k3d on $ARCH — refusing to install untrusted binary" >&2
    exit 1
  fi
  echo "${EXPECTED_SHA}  ${K3D_DIR}/k3d" | sha256sum -c -

  chmod +x "$K3D_DIR/k3d"
  k3d version
fi

echo "--- :k8s: Installing kubectl (if needed)"
# The helm test harness (helm-deploy.sh, logging.sh) shells out to kubectl, which
# — like helm and k3d above — is not reliably present on the spot-instance AMI
# snapshots, so install + SHA256-verify it explicitly. SHA256 published at
# https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${ARCH}/kubectl.sha256
KUBECTL_VERSION="v1.31.4"
KUBECTL_DIR="${PWD}/.tmp/bin"
export PATH="${KUBECTL_DIR}:${PATH}"

declare -A KUBECTL_SHA256=(
  [amd64]="298e19e9c6c17199011404278f0ff8168a7eca4217edad9097af577023a5620f"
  [arm64]="b97e93c20e3be4b8c8fa1235a41b4d77d4f2022ed3d899230dbbbbd43d26f872"
)

if ! command -v kubectl &>/dev/null || [[ "$(kubectl version --client 2>/dev/null)" != *"${KUBECTL_VERSION}"* ]]; then
  mkdir -p "$KUBECTL_DIR"
  ARCH=$(uname -m); case "$ARCH" in x86_64) ARCH=amd64;; aarch64) ARCH=arm64;; esac
  curl -fsSL --max-time 300 --connect-timeout 10 --retry 3 --retry-delay 5 "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${ARCH}/kubectl" -o "$KUBECTL_DIR/kubectl"

  EXPECTED_SHA="${KUBECTL_SHA256[$ARCH]:-}"
  if [[ -z "$EXPECTED_SHA" ]]; then
    echo "ERROR: no SHA256 pin for kubectl on $ARCH — refusing to install untrusted binary" >&2
    exit 1
  fi
  echo "${EXPECTED_SHA}  ${KUBECTL_DIR}/kubectl" | sha256sum -c -

  chmod +x "$KUBECTL_DIR/kubectl"
  kubectl version --client
fi

echo "--- :helm: Running Helm integration tests"
export SKIP_JAVA_BUILD=true
export SKIP_DOCKER_BUILD_MOCKSERVER=true
export SKIP_DOCKER_REBUILD_CLIENT=true
export SKIP_DOCKER_TESTS=true
export DELETE_CLUSTER=true
# CI fail-closed: the -clustered + webhook images were just built above, so the
# three image-dependent cases MUST run blocking. If either image is somehow
# absent the harness records a FAILURE (not a skip) — a skip in CI is impossible.
export REQUIRE_CLUSTERED_IMAGE=true
export REQUIRE_WEBHOOK_IMAGE=true

exec container_integration_tests/integration_tests.sh
