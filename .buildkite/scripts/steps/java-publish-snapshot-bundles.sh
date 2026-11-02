#!/usr/bin/env bash
# Build per-platform, JVM-less MockServer binary bundles and upload them to S3.
# Bundles are published to s3://aws-binaries-mockserver/mockserver-<POM_VERSION>/
# and served via https://downloads.mock-server.com/mockserver-<POM_VERSION>/...
#
# Guard: skips when the pom version is NOT *-SNAPSHOT (mirrors java-deploy-snapshot.sh).
#
# Agent requirements: curl, tar, unzip, aws, and ~2 GB scratch. A host JDK 21
# (for jlink only) is bootstrapped on demand if not already present, so the Maven
# build keeps running on JDK 17 (this step never touches the Maven JDK). Runs on
# the default queue.
#
# Auth: uses the agent's IAM instance role — no tokens or secrets required. The
# default-queue role needs PutObject on s3://aws-binaries-mockserver/* (provisioned
# via Terraform in terraform/buildkite-agents/).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# ---------------------------------------------------------------------------
# Guard: skip when the pom is not on a -SNAPSHOT version
# ---------------------------------------------------------------------------
POM_VERSION=$(grep -m1 -oE '<version>[^<]+</version>' mockserver/pom.xml | head -1 | sed -E 's#</?version>##g')
if [[ "$POM_VERSION" != *-SNAPSHOT ]]; then
  echo "--- :fast_forward: Skipping snapshot bundles — pom is on release version $POM_VERSION (not a -SNAPSHOT)"
  buildkite-agent meta-data set snapshot_bundles_done true
  exit 0
fi
echo "--- :package: Building snapshot binary bundles for $POM_VERSION"

# ---------------------------------------------------------------------------
# Locate the shaded JAR (built by the earlier Maven build step)
# ---------------------------------------------------------------------------
echo "--- :buildkite: Downloading shaded JAR artifact"
buildkite-agent artifact download "mockserver/mockserver-netty-no-dependencies/target/mockserver-netty-no-dependencies-*.jar" .

shopt -s nullglob
SHADED_JAR=""
for f in mockserver/mockserver-netty-no-dependencies/target/mockserver-netty-no-dependencies-*.jar; do
  case "$(basename "$f")" in
    *-sources.jar|*-javadoc.jar|original-*) continue ;;
  esac
  SHADED_JAR="$f"
  break
done
shopt -u nullglob

if [[ -z "$SHADED_JAR" ]]; then
  echo "Error: shaded JAR not found after artifact download."
  echo "The Maven build step must have completed successfully and uploaded the artifact."
  exit 1
fi
echo "Using JAR: $SHADED_JAR"

# ---------------------------------------------------------------------------
# Ensure a host JDK 21 jlink (the bundle cross-build needs jlink major 21).
# IMPORTANT: this is used ONLY by this step. The Maven build keeps running on
# JDK 17 inside the maven Docker image — we never change its JDK. We bootstrap
# Temurin 21 on demand (no agent AMI change required) so adding 21 here cannot
# pull the 17 build onto a newer JDK.
# ---------------------------------------------------------------------------
echo "--- :java: Ensuring host JDK 21 for jlink (Maven build stays on 17)"
# Shared bootstrap helper — same logic the release `binary` component uses, so
# the two paths cannot drift. See scripts/lib/ensure-host-jdk21.sh.
source "$REPO_ROOT/scripts/lib/ensure-host-jdk21.sh"
JDK21_HOME="$(ensure_host_jdk21 "$REPO_ROOT/.tmp/jdks")" \
  || { echo "Error: could not provision a JDK 21 jlink"; exit 1; }
echo "Using JDK 21 for jlink (Maven unaffected): $JDK21_HOME"

# ---------------------------------------------------------------------------
# Build all platform bundles
# ---------------------------------------------------------------------------
echo "--- :gear: Building bundles for all platforms"
BUNDLE_OUT="$REPO_ROOT/.tmp/bundles"
rm -rf "$BUNDLE_OUT"
mkdir -p "$BUNDLE_OUT"

# JAVA_HOME points build-all-bundles.sh at the bootstrapped JDK 21 jlink for THIS
# invocation only (a subshell env), so the Maven 17 build is never affected.
JAVA_HOME="$JDK21_HOME" "$REPO_ROOT/scripts/build-all-bundles.sh" \
  --jar "$SHADED_JAR" \
  --version "$POM_VERSION" \
  --jdk-version 21 \
  --cache "$REPO_ROOT/.tmp/jdks" \
  --output "$BUNDLE_OUT"

# Collect produced assets
ASSETS=()
while IFS= read -r _asset; do
  [[ -n "$_asset" ]] && ASSETS+=("$_asset")
done < <(ls "$BUNDLE_OUT"/mockserver-"$POM_VERSION"-*.tar.gz \
            "$BUNDLE_OUT"/mockserver-"$POM_VERSION"-*.zip \
            "$BUNDLE_OUT"/mockserver-"$POM_VERSION"-*.sha256 2>/dev/null || true)

if [[ ${#ASSETS[@]} -eq 0 ]]; then
  echo "Error: no bundle assets were produced."
  exit 1
fi

echo "Built ${#ASSETS[@]} assets:"
printf '    %s\n' "${ASSETS[@]##*/}"

# ---------------------------------------------------------------------------
# Upload bundles to S3
# ---------------------------------------------------------------------------
echo "--- :s3: Uploading bundles to s3://aws-binaries-mockserver/mockserver-${POM_VERSION}/"

S3_BUCKET="aws-binaries-mockserver"
S3_PREFIX="mockserver-${POM_VERSION}"
PUBLIC_BASE_URL="https://downloads.mock-server.com/${S3_PREFIX}"

# Content-type helper: .tar.gz → application/gzip, .zip → application/zip, .sha256 → text/plain
content_type_for() {
  case "$1" in
    *.tar.gz) echo "application/gzip" ;;
    *.zip)    echo "application/zip" ;;
    *.sha256) echo "text/plain" ;;
    *)        echo "application/octet-stream" ;;
  esac
}

# Snapshots are mutable (each snapshot publish overwrites), so use no-cache to
# prevent CloudFront / browser from serving stale bundles.
CACHE_CONTROL="no-cache, no-store, must-revalidate"

UPLOAD_FAILED=0
for asset in "${ASSETS[@]}"; do
  filename="$(basename "$asset")"
  ct="$(content_type_for "$filename")"
  echo "  Uploading $filename (${ct})..."
  if ! aws s3 cp "$asset" "s3://${S3_BUCKET}/${S3_PREFIX}/${filename}" \
       --content-type "$ct" \
       --cache-control "$CACHE_CONTROL" \
       --region eu-west-2; then
    UPLOAD_FAILED=1
  fi
done

if [[ "$UPLOAD_FAILED" -ne 0 ]]; then
  echo ""
  echo "=========================================================================="
  echo "ERROR: Failed to upload one or more bundles to s3://${S3_BUCKET}/"
  echo ""
  echo "The default-queue agent's IAM instance role must have s3:PutObject"
  echo "permission on arn:aws:s3:::${S3_BUCKET}/*."
  echo ""
  echo "This grant is provisioned by Terraform in terraform/buildkite-agents/."
  echo "Run 'terraform apply' in that directory to apply the IAM policy."
  echo ""
  echo "This step is soft_fail so master will not be reddened."
  echo "=========================================================================="
  echo ""
  exit 1
fi

echo ""
echo "--- :white_check_mark: Snapshot bundles published to S3"
echo ""
echo "Public download URLs:"
for asset in "${ASSETS[@]}"; do
  filename="$(basename "$asset")"
  echo "  ${PUBLIC_BASE_URL}/${filename}"
done

# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------
rm -rf "$BUNDLE_OUT"

# java-snapshot-publish-record.sh records the publish only when this is set.
buildkite-agent meta-data set snapshot_bundles_done true
