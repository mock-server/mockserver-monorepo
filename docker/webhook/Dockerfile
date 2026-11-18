#
# MockServer Admission Webhook Dockerfile
#
# Builds the admission webhook server that handles MutatingAdmissionWebhook
# requests for automatic sidecar injection.
#
# Build (CI / release — jar copied into docker/webhook/ by the pipeline):
#   docker build -t mockserver/mockserver-webhook:6.1.0 docker/webhook
#
# Build (local dev — build the jar first, then copy it):
#   cd mockserver && ./mvnw package -pl mockserver-k8s-webhook -DskipTests && cd ..
#   cp mockserver/mockserver-k8s-webhook/target/mockserver-k8s-webhook-*-jar-with-dependencies.jar \
#       docker/webhook/mockserver-webhook.jar
#   docker build -t mockserver/mockserver-webhook:6.1.1-SNAPSHOT docker/webhook
#

# runtime image — distroless java25 (nonroot, ~180 MB)
FROM gcr.io/distroless/java25:nonroot@sha256:1c4ced761d17961c900cba60c33c8c3c46edbdadaa9d5ef9806eb5a6824fd406

LABEL maintainer="James Bloom <jamesdbloom@gmail.com>"
LABEL description="MockServer Kubernetes Admission Webhook for automatic sidecar injection"

# The webhook listens on 8443 by default
EXPOSE 8443

# Copy the fat jar (placed into the build context by CI or by local dev)
COPY mockserver-webhook.jar /mockserver-webhook.jar

# Don't run as root
USER nonroot

# -XX:-UseBASE64Intrinsics works around CVE-2026-114938 (memory corruption in the x86-64 AVX-512
# Base64 decode intrinsic) on the same affected JDK as the server images. It is an ENTRYPOINT flag,
# not JAVA_TOOL_OPTIONS, so a user-set JAVA_TOOL_OPTIONS cannot drop it.
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-XX:-UseBASE64Intrinsics", "-jar", "/mockserver-webhook.jar"]

# Default environment (overridden by Helm values)
ENV WEBHOOK_PORT=8443
ENV WEBHOOK_TLS_CERT_FILE=/etc/webhook/tls/tls.crt
ENV WEBHOOK_TLS_KEY_FILE=/etc/webhook/tls/tls.key

# No Docker HEALTHCHECK — the image is distroless (no shell, no curl).
# Health checking is handled by Kubernetes readiness/liveness probes
# configured in the Helm webhook-deployment.yaml (GET /healthz over HTTPS).
