#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Skip when the parent pom is not on a -SNAPSHOT version. Master sometimes
# lands on a release version (e.g. between the release commit and the
# follow-up next-SNAPSHOT bump) — deploying a non-SNAPSHOT to the snapshot
# repository is invalid and surfaces as a misleading "Could not find
# artifact" error from maven-deploy-plugin.
POM_VERSION=$(grep -m1 -oE '<version>[^<]+</version>' mockserver/pom.xml | head -1 | sed -E 's#</?version>##g')
if [[ "$POM_VERSION" != *-SNAPSHOT ]]; then
  echo "--- :fast_forward: Skipping snapshot deploy — pom is on release version $POM_VERSION (not a -SNAPSHOT)"
  exit 0
fi

echo "--- :aws: Fetching Sonatype Central Portal credentials from Secrets Manager"
{ set +x; } 2>/dev/null  # F-BK-04: suppress xtrace before secret fetch
SECRET_JSON=$(aws secretsmanager get-secret-value \
  --secret-id "mockserver-build/sonatype" \
  --region eu-west-2 \
  --query SecretString \
  --output text)

SONATYPE_USERNAME=$(echo "$SECRET_JSON" | jq -r '.username')
SONATYPE_PASSWORD=$(echo "$SECRET_JSON" | jq -r '.password')

if [ -z "$SONATYPE_USERNAME" ] || [ "$SONATYPE_USERNAME" = "null" ]; then
  echo "Error: Sonatype credentials not found in AWS Secrets Manager."
  echo "Store a Central Portal user token in mockserver-build/sonatype with keys 'username' and 'password'."
  echo "Generate the token at: https://central.sonatype.com → View Account → Generate User Token"
  exit 1
fi

echo "--- :nexus: Deploying snapshot to Central Portal"
# Snapshot-deploy specific flags (the release path activates -P release separately
# and intentionally re-enables javadoc / sources / GPG / central-publishing):
#   -T 1C                       run module compile + package + upload in parallel,
#                                using 1 thread per available core. Sonatype Central
#                                Portal's snapshot endpoint handles concurrent uploads
#                                from the same authenticated session, and Maven 3.9
#                                serialises shared-state phases internally.
#   -Dmaven.javadoc.skip=true   release profile is not active here so the javadoc
#                                plugin does not run anyway, but make it explicit so
#                                no future profile-activation flag accidentally turns
#                                on the slow doclet for snapshot builds.
#   -Dmaven.source.skip=true    same rationale as javadoc — defensive, snapshots do
#                                not need a -sources.jar artifact.
#   -Dgpg.skip=true             snapshots are not signature-checked by Central Portal;
#                                skipping GPG removes signing latency if the release
#                                profile were ever auto-activated.
# -m 12g (raised from 7g): same OOM as the `:maven: build` step, and this deploy
# is where most of the exit-137 kills landed. `deploy -DskipTests` still runs the
# full default lifecycle up to deploy, so mockserver-netty's `build-ui` profile
# rebuilds the dashboard (npm ci + `vite build`) in generate-resources — a
# ~1.5-3g rolldown-native peak (NOT bounded by any Node heap flag) landing inside
# the same cgroup as the 6g-Xmx Maven JVM (mvnw applies mockserver/.mvn/jvm.config
# here too). 6g heap + JVM non-heap + node > 7g -> cgroup OOM at `vite
# transforming...`. 12g clears both; see java-build.sh for the full rationale.
# REQUIRES a >=32 GiB agent (default queue = 8 vCPU / 32 GiB class, see terraform/buildkite-agents) — coupled with the
# terraform instance-type change.
# -DskipITs as well as -DskipTests: -DskipTests silences surefire only, so without it
# failsafe re-ran every *IntegrationTest the green build already ran before this step.
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mockserver/mockserver:maven \
  -m 12g \
  -w /build/mockserver \
  -e "SONATYPE_USERNAME=$SONATYPE_USERNAME" \
  -e "SONATYPE_PASSWORD=$SONATYPE_PASSWORD" \
  -- ./mvnw -B --no-transfer-progress -T 1C deploy -DskipTests -DskipITs \
    -Dmaven.javadoc.skip=true \
    -Dmaven.source.skip=true \
    -Dgpg.skip=true \
    -Djava.security.egd=file:/dev/./urandom \
    --settings .buildkite-settings.xml
