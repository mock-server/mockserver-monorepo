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

# runtime image — distroless java17 (nonroot, ~180 MB)
FROM gcr.io/distroless/java17:nonroot@sha256:2dcb2b21efab7e9250945985e0b0aab99ec7835db96a7bbc68edd1a0ad2e51ff

LABEL maintainer="James Bloom <jamesdbloom@gmail.com>"
LABEL description="MockServer Kubernetes Admission Webhook for automatic sidecar injection"

# The webhook listens on 8443 by default
EXPOSE 8443

# Copy the fat jar (placed into the build context by CI or by local dev)
COPY mockserver-webhook.jar /mockserver-webhook.jar

# Don't run as root
USER nonroot

ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "/mockserver-webhook.jar"]

# Default environment (overridden by Helm values)
ENV WEBHOOK_PORT=8443
ENV WEBHOOK_TLS_CERT_FILE=/etc/webhook/tls/tls.crt
ENV WEBHOOK_TLS_KEY_FILE=/etc/webhook/tls/tls.key

# No Docker HEALTHCHECK — the image is distroless (no shell, no curl).
# Health checking is handled by Kubernetes readiness/liveness probes
# configured in the Helm webhook-deployment.yaml (GET /healthz over HTTPS).
