#
# MockServer Dockerfile
#
# https://github.com/mock-server/mockserver
# https://www.mock-server.com
#

ARG source=download

# build image — downloads release artifacts from Sonatype, tcnative from Maven Central
FROM alpine:3.24 AS download

# Optional: trust a corporate root CA before `apk add`. The build context contains
# ca-bundle.pem (empty in CI, populated locally behind a TLS-inspecting proxy).
COPY ca-bundle.pem /tmp/local-ca.pem
RUN if [ -s /tmp/local-ca.pem ]; then \
      mkdir -p /etc/ssl/certs && \
      cp /tmp/local-ca.pem /etc/ssl/cert.pem && \
      cp /tmp/local-ca.pem /etc/ssl/certs/ca-certificates.crt; \
    fi && \
    apk add --update openssl ca-certificates bash wget && \
    if [ -s /tmp/local-ca.pem ]; then \
      mkdir -p /usr/local/share/ca-certificates && \
      cp /tmp/local-ca.pem /usr/local/share/ca-certificates/local-ca.crt && \
      update-ca-certificates >/dev/null 2>&1 || true; \
    fi
# Harden Maven Central downloads against transient DNS/connection blips (e.g. flaky
# networks, CI). GNU wget retries host-resolution + connection-refused errors;
# BusyBox wget ignores /etc/wgetrc, so this is a safe no-op there.
RUN printf 'tries=5\ntimeout=60\nwaitretry=10\nretry_on_host_error=on\nretry_connrefused=on\n' >> /etc/wgetrc
# Audit finding F-DKR-06: SHA256-verify all Maven Central downloads.
ARG VERSION=RELEASE
ARG REPOSITORY_URL=https://repo1.maven.org/maven2/org/mock-server/mockserver-netty/${VERSION}/mockserver-netty-${VERSION}-jar-with-dependencies.jar
RUN wget --max-redirect=10 -O mockserver-netty-jar-with-dependencies.jar "$REPOSITORY_URL" \
    && wget --max-redirect=10 -qO- "${REPOSITORY_URL}.sha256" > /tmp/expected.sha256 \
    && echo "$(cat /tmp/expected.sha256)  mockserver-netty-jar-with-dependencies.jar" | sha256sum -c -
ARG TARGETARCH=amd64
ARG NETTY_TCNATIVE=2.0.78.Final
RUN if [ "$TARGETARCH" = "amd64" ]; \
    then TCNATIVE_URL="https://repo1.maven.org/maven2/io/netty/netty-tcnative-boringssl-static/$NETTY_TCNATIVE/netty-tcnative-boringssl-static-$NETTY_TCNATIVE-linux-x86_64.jar"; \
    else TCNATIVE_URL="https://repo1.maven.org/maven2/io/netty/netty-tcnative-boringssl-static/$NETTY_TCNATIVE/netty-tcnative-boringssl-static-$NETTY_TCNATIVE-linux-aarch_64.jar"; \
    fi \
    && wget -O netty-tcnative-boringssl-static.jar "$TCNATIVE_URL" \
    && wget -qO- "${TCNATIVE_URL}.sha256" > /tmp/expected-tcnative.sha256 \
    && echo "$(cat /tmp/expected-tcnative.sha256)  netty-tcnative-boringssl-static.jar" | sha256sum -c -
RUN unzip netty-tcnative-boringssl-static.jar

# build image — copies JAR from context, downloads tcnative from Maven Central
FROM alpine:3.24 AS copy

COPY ca-bundle.pem /tmp/local-ca.pem
RUN if [ -s /tmp/local-ca.pem ]; then \
      mkdir -p /etc/ssl/certs && \
      cp /tmp/local-ca.pem /etc/ssl/cert.pem && \
      cp /tmp/local-ca.pem /etc/ssl/certs/ca-certificates.crt; \
    fi && \
    apk add --update ca-certificates wget && \
    if [ -s /tmp/local-ca.pem ]; then \
      mkdir -p /usr/local/share/ca-certificates && \
      cp /tmp/local-ca.pem /usr/local/share/ca-certificates/local-ca.crt && \
      update-ca-certificates >/dev/null 2>&1 || true; \
    fi
# Harden Maven Central downloads against transient DNS/connection blips (e.g. flaky
# networks, CI). GNU wget retries host-resolution + connection-refused errors;
# BusyBox wget ignores /etc/wgetrc, so this is a safe no-op there.
RUN printf 'tries=5\ntimeout=60\nwaitretry=10\nretry_on_host_error=on\nretry_connrefused=on\n' >> /etc/wgetrc
COPY mockserver-netty-jar-with-dependencies.jar .
ARG TARGETARCH=amd64
ARG NETTY_TCNATIVE=2.0.78.Final
# Audit finding F-DKR-06: SHA256-verify tcnative download from Maven Central.
RUN if [ "$TARGETARCH" = "amd64" ]; \
    then TCNATIVE_URL="https://repo1.maven.org/maven2/io/netty/netty-tcnative-boringssl-static/$NETTY_TCNATIVE/netty-tcnative-boringssl-static-$NETTY_TCNATIVE-linux-x86_64.jar"; \
    else TCNATIVE_URL="https://repo1.maven.org/maven2/io/netty/netty-tcnative-boringssl-static/$NETTY_TCNATIVE/netty-tcnative-boringssl-static-$NETTY_TCNATIVE-linux-aarch_64.jar"; \
    fi \
    && wget -O netty-tcnative-boringssl-static.jar "$TCNATIVE_URL" \
    && wget -qO- "${TCNATIVE_URL}.sha256" > /tmp/expected-tcnative.sha256 \
    && echo "$(cat /tmp/expected-tcnative.sha256)  netty-tcnative-boringssl-static.jar" | sha256sum -c -
RUN unzip netty-tcnative-boringssl-static.jar

FROM ${source} AS intermediate

# runtime image https://console.cloud.google.com/gcr/images/distroless/global/java17
FROM gcr.io/distroless/java17:latest@sha256:cf7080f552165a1b5586349fcce84a62aa1ae9ac1a6811f9c515accbe9dcc125

# maintainer details
LABEL org.opencontainers.image.authors="James Bloom <jamesdbloom@gmail.com>"

# expose ports.
EXPOSE 1080

# copy in jar
COPY --from=intermediate mockserver-netty-jar-with-dependencies.jar /
COPY --from=intermediate META-INF/native/libnetty_tcnative_linux_*.so /usr/lib/

# run MockServer as root
USER root

# -XX:MaxRAMPercentage=75.0 caps the JVM heap at 75% of the container memory limit so the
# in-memory request/expectation rings size off a bounded heap. It only applies when the user has
# NOT set an explicit heap, so it is a safe default. To change the cap, set an explicit -Xmx (via
# JAVA_TOOL_OPTIONS or -Xmx) — that disables MaxRAMPercentage. Setting a different MaxRAMPercentage
# via JAVA_TOOL_OPTIONS does NOT take effect: JAVA_TOOL_OPTIONS is prepended before these args, so
# this entrypoint flag is applied last and wins.
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-XX:MaxRAMPercentage=75.0", "-cp", "/mockserver-netty-jar-with-dependencies.jar:/libs/*", "-Dmockserver.propertyFile=/config/mockserver.properties", "org.mockserver.cli.Main"]

ENV SERVER_PORT=1080

HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=3 \
  CMD ["java", "-cp", "/mockserver-netty-jar-with-dependencies.jar", "org.mockserver.cli.HealthCheck"]

CMD []
