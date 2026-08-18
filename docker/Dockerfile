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
# Audit finding F-DKR-06: SHA256-verify all downloads from Maven Central.
# Maven Central publishes .sha256 alongside every artifact.
ARG VERSION=RELEASE
ARG REPOSITORY_URL=https://repo1.maven.org/maven2/org/mock-server/mockserver-netty/${VERSION}/mockserver-netty-${VERSION}-jar-with-dependencies.jar
RUN wget --max-redirect=10 -O mockserver-netty-jar-with-dependencies.jar "$REPOSITORY_URL" \
    && wget --max-redirect=10 -qO- "${REPOSITORY_URL}.sha256" > /tmp/expected.sha256 \
    && echo "$(cat /tmp/expected.sha256)  mockserver-netty-jar-with-dependencies.jar" | sha256sum -c -
ARG TARGETARCH=amd64
RUN NETTY_TCNATIVE="$(unzip -p mockserver-netty-jar-with-dependencies.jar META-INF/mockserver-tcnative.version | tr -d '[:space:]')" \
    && if [ -z "$NETTY_TCNATIVE" ]; then echo "ERROR: META-INF/mockserver-tcnative.version missing or empty in mockserver-netty-jar-with-dependencies.jar" >&2; exit 1; fi \
    && if [ "$TARGETARCH" = "amd64" ]; \
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
# Audit finding F-DKR-06: SHA256-verify tcnative download from Maven Central.
RUN NETTY_TCNATIVE="$(unzip -p mockserver-netty-jar-with-dependencies.jar META-INF/mockserver-tcnative.version | tr -d '[:space:]')" \
    && if [ -z "$NETTY_TCNATIVE" ]; then echo "ERROR: META-INF/mockserver-tcnative.version missing or empty in mockserver-netty-jar-with-dependencies.jar" >&2; exit 1; fi \
    && if [ "$TARGETARCH" = "amd64" ]; \
    then TCNATIVE_URL="https://repo1.maven.org/maven2/io/netty/netty-tcnative-boringssl-static/$NETTY_TCNATIVE/netty-tcnative-boringssl-static-$NETTY_TCNATIVE-linux-x86_64.jar"; \
    else TCNATIVE_URL="https://repo1.maven.org/maven2/io/netty/netty-tcnative-boringssl-static/$NETTY_TCNATIVE/netty-tcnative-boringssl-static-$NETTY_TCNATIVE-linux-aarch_64.jar"; \
    fi \
    && wget -O netty-tcnative-boringssl-static.jar "$TCNATIVE_URL" \
    && wget -qO- "${TCNATIVE_URL}.sha256" > /tmp/expected-tcnative.sha256 \
    && echo "$(cat /tmp/expected-tcnative.sha256)  netty-tcnative-boringssl-static.jar" | sha256sum -c -
RUN unzip netty-tcnative-boringssl-static.jar

FROM ${source} AS intermediate

# AppCDS build stage — jlink-trimmed JDK 25 runtime + training run producing the AppCDS archive.
# Mirrors docker/local/Dockerfile (the release/snapshot artifact); this Dockerfile is the public
# download-mode reference and additionally ships netty-tcnative (see the runtime stage below).
# NOTE: the JVM inside this runtime image is JDK 25 — the MockServer library itself is still
# compiled to the Java 17 bytecode floor (see AGENTS.md Java Compatibility Policy); running that
# jar on a newer JVM is fully supported and is a runtime-only choice, independent of compilation.
FROM eclipse-temurin:26-jdk-noble AS appcds-build

COPY --from=intermediate mockserver-netty-jar-with-dependencies.jar /mockserver-netty-jar-with-dependencies.jar

# Same module set validated end-to-end for the jlink binary bundle (scripts/build-binary-bundle.sh,
# docs/code/cli.md). On JDK 25 jlink uses the --compress=zip-<level> form (zip-6 = the default ZIP
# level); the legacy numeric --compress=2 form was deprecated after JDK 17 and is removed on JDK 25.
# A jlink image does not carry the JDK's default CDS base archive, so -Xshare:dump regenerates it
# from the bundled lib/classlist — the dynamic AppCDS archive below is layered on that base, and the
# base is carried into the runtime image with /runtime.
RUN "$JAVA_HOME/bin/jlink" \
      --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.naming.dns,jdk.zipfs \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --output /runtime \
 && /runtime/bin/java -Xshare:dump

# Training run (dynamic AppCDS, JEP 350): start the server with -XX:ArchiveClassesAtExit, wait until
# the status endpoint answers via the bundled HealthCheck (which also drives the post-bind warmup),
# then stop cleanly so the JVM writes the archive at exit. The `ls` fails the build if the archive
# was not produced. tcnative is not on the training classpath (it is added only at runtime), so the
# archive covers the JDK-provider path; tcnative classes simply load normally at runtime.
RUN /runtime/bin/java -XX:ArchiveClassesAtExit=/mockserver.jsa \
      -jar /mockserver-netty-jar-with-dependencies.jar -p 1080 & \
    SERVER_PID=$!; \
    for i in $(seq 1 240); do \
      /runtime/bin/java -cp /mockserver-netty-jar-with-dependencies.jar org.mockserver.cli.HealthCheck && break; \
      sleep 0.5; \
    done; \
    kill -TERM "$SERVER_PID" && wait "$SERVER_PID" || true; \
    ls -l /mockserver.jsa

# runtime image — distroless base + trimmed JDK 25 runtime + jar + AppCDS archive + tcnative .so
# (digest-pinned per repo convention; update the digest together with the Temurin 25 build, since
# the baked AppCDS archive is specific to the JDK build it was trained with — same base+digest as
# docker/aot/Dockerfile)
FROM gcr.io/distroless/java-base-debian12:nonroot@sha256:a9930cad62d02853d7f3dede7281c4b916cbf74493c2d8d38564121aad92bf6c

# maintainer details
LABEL org.opencontainers.image.authors="James Bloom <jamesdbloom@gmail.com>"

# MCP registry ownership annotation — proves this image backs the com.mock-server/mockserver
# entry on the official MCP registry (https://registry.modelcontextprotocol.io). Required before the
# server.json OCI package can be published. The namespace is DNS-verified against mock-server.com so
# the release can publish non-interactively from CI. See docs/operations/mcp-registry-publishing.md.
LABEL io.modelcontextprotocol.server.name="com.mock-server/mockserver"

# expose ports.
EXPOSE 1080

# copy in trimmed runtime, jar, AppCDS archive, and (unlike docker/local) the netty-tcnative .so
COPY --from=appcds-build /runtime /usr/lib/jvm/temurin25-trimmed
COPY --from=appcds-build /mockserver-netty-jar-with-dependencies.jar /
COPY --from=appcds-build /mockserver.jsa /
# Native-TLS fallback for read-only-rootfs deployments (docker run --read-only):
# normally /tmp is writable and Netty extracts the tcnative .so from the jar, so
# this copy is dormant; under --read-only that extraction fails and System.loadLibrary
# finding /usr/lib/libnetty_tcnative_linux_<arch>.so is the only thing keeping
# BoringSSL native TLS alive. Inert for the shaded no-dependencies jar (relocated
# tcnative symbols cannot bind this stock .so). Introduced by e4dd3a61b.
COPY --from=intermediate META-INF/native/libnetty_tcnative_linux_*.so /usr/lib/

# don't run MockServer as root
USER nonroot

# -XX:MaxRAMPercentage=75.0 caps the JVM heap at 75% of the container memory limit so the
# in-memory request/expectation rings size off a bounded heap. It only applies when the user has
# NOT set an explicit heap, so it is a safe default. To change the cap, set an explicit -Xmx (via
# JAVA_TOOL_OPTIONS or -Xmx) — that disables MaxRAMPercentage. Setting a different MaxRAMPercentage
# via JAVA_TOOL_OPTIONS does NOT take effect: JAVA_TOOL_OPTIONS is prepended before these args, so
# this entrypoint flag is applied last and wins.
# -XX:SharedArchiveFile loads the baked AppCDS archive. -Xshare:auto (the JVM default) means a
# missing or unusable archive (e.g. bind-mounted away, corrupt, or an arch mismatch) logs a warning
# and starts normally rather than failing.
ENTRYPOINT ["/usr/lib/jvm/temurin25-trimmed/bin/java", "-Dfile.encoding=UTF-8", "-XX:MaxRAMPercentage=75.0", "-XX:SharedArchiveFile=/mockserver.jsa", "-cp", "/mockserver-netty-jar-with-dependencies.jar:/libs/*", "-Dmockserver.propertyFile=/config/mockserver.properties", "org.mockserver.cli.Main"]

ENV SERVER_PORT=1080

HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=3 \
  CMD ["/usr/lib/jvm/temurin25-trimmed/bin/java", "-cp", "/mockserver-netty-jar-with-dependencies.jar", "org.mockserver.cli.HealthCheck"]

CMD []
