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

# jdk-download: sha256-pinned Temurin tarball, the same pin in every image; docs/infrastructure/docker.md#jdk-and-runtime
FROM alpine:3.24@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf AS jdk-download
# ca-bundle.pem is optional here (the [m] wildcard may match nothing): only a local build behind a
# TLS-inspecting proxy stages one, with docker/ensure-ca-bundle.sh.
COPY ca-bundle.pe[m] /tmp/ca/
RUN if [ -s /tmp/ca/ca-bundle.pem ]; then \
      mkdir -p /etc/ssl/certs && \
      cp /tmp/ca/ca-bundle.pem /etc/ssl/cert.pem && \
      cp /tmp/ca/ca-bundle.pem /etc/ssl/certs/ca-certificates.crt; \
    fi && \
    apk add --no-cache wget
ARG TARGETARCH
RUN case "$(uname -m)" in \
      x86_64) STAGE_ARCH=amd64; \
        JDK_URL=https://github.com/adoptium/temurin27-binaries/releases/download/jdk-27.0.1.1%2B4/OpenJDK27U-jdk_x64_linux_hotspot_27.0.1.1_4.tar.gz; \
        JDK_SHA256=005e57ee860978acfeeff1be2b30f770664fe2b941e9b803c61c76992086d107 ;; \
      aarch64|arm64) STAGE_ARCH=arm64; \
        JDK_URL=https://github.com/adoptium/temurin27-binaries/releases/download/jdk-27.0.1.1%2B4/OpenJDK27U-jdk_aarch64_linux_hotspot_27.0.1.1_4.tar.gz; \
        JDK_SHA256=fc6986815165fc9506eb6a01ace00942f5c91c993102355ab01cea3e2de9736d ;; \
      *) echo "ERROR: no Temurin 27 build is pinned for '$(uname -m)'" >&2; exit 1 ;; \
    esac \
 && if [ -n "${TARGETARCH:-}" ] && [ "$TARGETARCH" != "$STAGE_ARCH" ]; then \
      echo "ERROR: TARGETARCH='$TARGETARCH' but this stage runs on $STAGE_ARCH - declare 'ARG TARGETARCH' without a default" >&2; exit 1; \
    fi \
 && wget -q --tries=5 --timeout=60 --waitretry=10 --retry-connrefused -O /tmp/jdk.tar.gz "$JDK_URL" \
 && echo "$JDK_SHA256  /tmp/jdk.tar.gz" | sha256sum -c - \
 && mkdir /jdk \
 && tar -xzf /tmp/jdk.tar.gz -C /jdk --strip-components=1 \
 && rm /tmp/jdk.tar.gz

# jdk: jlink the run-time modules of the distroless Java image this one used before, then the
# default CDS archive the jlink image lacks; see docs/infrastructure/docker.md#jdk-and-runtime.
FROM debian:trixie-slim@sha256:a72b4433de1cf7d6bae0c4cee74d0547f58601a643b3375f070c51600d63b6b2 AS jdk
COPY --from=jdk-download /jdk /jdk
# --strip-java-debug-attributes, not --strip-debug: see docs/infrastructure/docker.md#jdk-and-runtime.
RUN /jdk/bin/jlink \
      --add-modules java.se,java.smartcardio,jdk.accessibility,jdk.charsets,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.dynalink,jdk.httpserver,jdk.incubator.vector,jdk.jdwp.agent,jdk.jfr,jdk.localedata,jdk.management,jdk.management.agent,jdk.management.jfr,jdk.naming.dns,jdk.naming.rmi,jdk.net,jdk.nio.mapmode,jdk.sctp,jdk.security.auth,jdk.security.jgss,jdk.unsupported,jdk.xml.dom,jdk.zipfs \
      --strip-java-debug-attributes --no-man-pages --no-header-files --compress=zip-6 \
      --output /runtime \
 && /runtime/bin/java -Xshare:dump
# cacerts -> the java-base image's Debian trust store: see docs/infrastructure/docker.md#jdk-and-runtime.
RUN ln -sf /etc/ssl/certs/java/cacerts /runtime/lib/security/cacerts \
 && mkdir /usr-bin && ln -s /usr/lib/jvm/temurin-trimmed/bin/java /usr-bin/java

# runtime image — distroless java-base + the jlink-trimmed Temurin 27 runtime from the jdk stage
FROM gcr.io/distroless/java-base-debian13:nonroot@sha256:4b56aef44dee352ec8321ac447db1592909c029183a41be1c41375ca8b936549

LABEL maintainer="James Bloom <jamesdbloom@gmail.com>"
LABEL description="MockServer Kubernetes Admission Webhook for automatic sidecar injection"

# The webhook listens on 8443 by default
EXPOSE 8443

COPY --from=jdk /runtime /usr/lib/jvm/temurin-trimmed
# /usr/bin/java, where the distroless Java image this one used to run on put it
COPY --from=jdk /usr-bin/ /usr/bin/

# Copy the fat jar (placed into the build context by CI or by local dev)
COPY mockserver-webhook.jar /mockserver-webhook.jar

# Don't run as root
USER nonroot

ENTRYPOINT ["/usr/lib/jvm/temurin-trimmed/bin/java", "-Dfile.encoding=UTF-8", "-jar", "/mockserver-webhook.jar"]

# Default environment (overridden by Helm values)
ENV PATH="/usr/lib/jvm/temurin-trimmed/bin:${PATH}"
ENV WEBHOOK_PORT=8443
ENV WEBHOOK_TLS_CERT_FILE=/etc/webhook/tls/tls.crt
ENV WEBHOOK_TLS_KEY_FILE=/etc/webhook/tls/tls.key

# No Docker HEALTHCHECK — the image is distroless (no shell, no curl).
# Health checking is handled by Kubernetes readiness/liveness probes
# configured in the Helm webhook-deployment.yaml (GET /healthz over HTTPS).
