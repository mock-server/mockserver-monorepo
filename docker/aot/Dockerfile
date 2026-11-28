#
# MockServer Dockerfile — EXPERIMENTAL JDK 27 AOT-cache variant (Project Leyden, JEP 483/514)
#
# Bakes an ahead-of-time cache (class loading/linking + profiles) into the image via a
# training run at build time, roughly halving container time-to-ready versus the
# standard image while remaining the real HotSpot JVM (100% feature parity — unlike
# GraalVM native-image, which cannot support MockServer's runtime user-class loading).
#
# Notes:
# - The AOT cache is CPU-architecture and JDK-build specific; it is created in this image
#   build, so multi-arch builds each get their own cache.
# - A jlink-trimmed Temurin JDK 27 (the binary bundle's module set) is copied onto distroless/java-base:
#   the cache is trained against this exact runtime, so the two stay in lockstep.
# - netty-tcnative is intentionally omitted: TLS uses the JDK provider. Functionally
#   identical; TLS handshake throughput may be lower than the standard image.
#
# https://github.com/mock-server/mockserver-monorepo
# https://www.mock-server.com
#

ARG source=download

# build image — downloads release artifacts from Sonatype
FROM alpine:3.24@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf AS download

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
RUN printf 'tries=5\ntimeout=60\nwaitretry=10\nretry_on_host_error=on\nretry_connrefused=on\n' >> /etc/wgetrc
# SHA256-verify all downloads from Maven Central (audit finding F-DKR-06).
ARG VERSION=RELEASE
ARG REPOSITORY_URL=https://repo1.maven.org/maven2/org/mock-server/mockserver-netty/${VERSION}/mockserver-netty-${VERSION}-jar-with-dependencies.jar
RUN wget --max-redirect=10 -O mockserver-netty-jar-with-dependencies.jar "$REPOSITORY_URL" \
    && wget --max-redirect=10 -qO- "${REPOSITORY_URL}.sha256" > /tmp/expected.sha256 \
    && echo "$(cat /tmp/expected.sha256)  mockserver-netty-jar-with-dependencies.jar" | sha256sum -c -

# build image — copies JAR from context
FROM alpine:3.24@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf AS copy
COPY mockserver-netty-jar-with-dependencies.jar .

FROM ${source} AS intermediate

# jarprep: drop every native binary a linux/<arch> container cannot load, then split the jar into
# own.jar (org/mockserver/**) and deps.jar (the dependencies), both STORED and byte-reproducible, so
# a release that changes no dependency reuses deps.jar's layer. The logic is the shared
# mockserver-jarprep.sh (canonical copy docker/jarprep/): it takes the arch from this stage's
# uname -m, checks every kept .so's ELF machine, and fails the build if a required native is missing.
# ca-bundle.pem (empty in CI) is trusted before `apk add`: the copy stage does not set it up.
FROM intermediate AS jarprep
COPY ca-bundle.pem /tmp/local-ca.pem
RUN if [ -s /tmp/local-ca.pem ]; then \
      mkdir -p /etc/ssl/certs && \
      cp /tmp/local-ca.pem /etc/ssl/cert.pem && \
      cp /tmp/local-ca.pem /etc/ssl/certs/ca-certificates.crt; \
    fi && \
    apk add --no-cache zip unzip
# Bare, no default (a default overrides BuildKit's per-leg value); the stage's own uname -m is the authority.
ARG TARGETARCH
COPY mockserver-jarprep.sh /usr/local/bin/mockserver-jarprep.sh
RUN sh /usr/local/bin/mockserver-jarprep.sh /mockserver-netty-jar-with-dependencies.jar "${TARGETARCH:-}" /jarprep

# jdk-download: sha256-pinned Temurin tarball, the same pin in every image; docs/infrastructure/docker.md#jdk-and-runtime
FROM alpine:3.24@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf AS jdk-download
COPY ca-bundle.pem /tmp/local-ca.pem
RUN if [ -s /tmp/local-ca.pem ]; then \
      mkdir -p /etc/ssl/certs && \
      cp /tmp/local-ca.pem /etc/ssl/cert.pem && \
      cp /tmp/local-ca.pem /etc/ssl/certs/ca-certificates.crt; \
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

# AOT build stage — jlink-trimmed JDK 27 runtime + training run producing the AOT cache
FROM debian:trixie-slim@sha256:a72b4433de1cf7d6bae0c4cee74d0547f58601a643b3375f070c51600d63b6b2 AS aot-build
COPY --from=jdk-download /jdk /jdk

# The AOT cache records this exact classpath: training and ENTRYPOINT both use own:deps in this order.
# The ENTRYPOINT may append entries (/libs/*, /extensions/*); a cache still maps for an extended classpath.
COPY --from=jarprep /jarprep/own.jar  /mockserver.jar
COPY --from=jarprep /jarprep/deps.jar /mockserver-deps.jar

# Same module set validated end-to-end for the jlink binary bundle (docs/code/cli.md):
# java.se aggregator + Netty Unsafe + TLS crypto + DNS + zipfs + jdk.management. jdk.management supplies
# com.sun.management.ThreadMXBean, which the Prometheus jvm_memory_allocated_bytes gauge
# (JvmMetricsCollector) needs — java.se aggregates only java.* modules, so without it that metric is
# silently suppressed.
# --strip-java-debug-attributes, not --strip-debug: see docs/infrastructure/docker.md#jdk-and-runtime.
RUN /jdk/bin/jlink \
      --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.naming.dns,jdk.zipfs,jdk.management \
      --strip-java-debug-attributes --no-man-pages --no-header-files --compress=zip-6 \
      --output /runtime \
 && mkdir /jvm && ln -s temurin-trimmed /jvm/temurin25-trimmed

# Training run (JEP 514 one-step): start the server with -XX:AOTCacheOutput, wait until the
# status endpoint answers via the bundled HealthCheck, then stop cleanly so the JVM writes
# the cache at exit. The `ls` fails the build if the cache was not produced.
# GC: this variant intentionally stays on the JDK default collector (G1), NOT the -XX:+UseZGC the
# other server images set — ZGC's fixed start-up cost defeats this variant's minimal-time-to-ready
# purpose. Do not add -XX:+UseZGC here. See docs/code/startup-performance.md for the measurements.
RUN /runtime/bin/java --enable-native-access=ALL-UNNAMED -XX:AOTCacheOutput=/mockserver.aot \
      -cp /mockserver.jar:/mockserver-deps.jar org.mockserver.cli.Main -p 1080 & \
    SERVER_PID=$!; \
    for i in $(seq 1 240); do \
      /runtime/bin/java -cp /mockserver.jar:/mockserver-deps.jar org.mockserver.cli.HealthCheck && break; \
      sleep 0.5; \
    done; \
    kill -TERM "$SERVER_PID" && wait "$SERVER_PID" || true; \
    ls -l /mockserver.aot

# cacerts -> the java-base image's Debian trust store: see docs/infrastructure/docker.md#jdk-and-runtime.
RUN ln -sf /etc/ssl/certs/java/cacerts /runtime/lib/security/cacerts

# HEALTHCHECK probe — a ~2 MB static binary, NOT a second JVM: a probe JVM started inside the
# container's memory cgroup every 10s pushed a 512 MiB container over its limit under load. Source:
# docker/healthcheck/mockserver-healthcheck.go (copied byte-identically into each image context).
# Cross-compiled on the BUILD platform: the Go toolchain crashes under QEMU emulation, so it must
# never run as the non-native leg of a multi-arch build. Needs BuildKit (the classic builder has no
# BUILDPLATFORM). The ELF e_machine check fails the build on a wrong-arch binary.
FROM --platform=$BUILDPLATFORM golang:1.27-alpine@sha256:70bd7ddea8b6cbe5d6943cde279d6b67feb10cc924fb9100d1d8757c1575b280 AS healthcheck
ARG TARGETOS
ARG TARGETARCH
WORKDIR /src
COPY mockserver-healthcheck.go ./
RUN GOOS="${TARGETOS:-$(go env GOOS)}" \
 && GOARCH="${TARGETARCH:-$(go env GOARCH)}" \
 && case "$GOARCH" in amd64) ELF_MACHINE=62 ;; arm64) ELF_MACHINE=183 ;; \
      *) echo "ERROR: no ELF machine check for GOARCH=$GOARCH" >&2; exit 1 ;; esac \
 && CGO_ENABLED=0 GOTOOLCHAIN=local GOOS="$GOOS" GOARCH="$GOARCH" \
      go build -trimpath -buildvcs=false -ldflags="-s -w -buildid=" \
      -o /mockserver-healthcheck mockserver-healthcheck.go \
 && ACTUAL="$(od -An -tu2 -j18 -N2 /mockserver-healthcheck)" \
 && if [ "${ACTUAL##* }" != "$ELF_MACHINE" ]; then \
      echo "ERROR: /mockserver-healthcheck ELF machine ${ACTUAL##* }, expected $ELF_MACHINE for $GOARCH" >&2; exit 1; \
    fi

# runtime image — distroless base + trimmed JDK 27 runtime + jar + AOT cache
# (digest-pinned; the baked AOT cache is tied to the jdk-download pin, retrained when it moves)
FROM gcr.io/distroless/java-base-debian13:nonroot@sha256:4b56aef44dee352ec8321ac447db1592909c029183a41be1c41375ca8b936549

LABEL org.opencontainers.image.authors="James Bloom <jamesdbloom@gmail.com>"

EXPOSE 1080

COPY --from=aot-build /runtime /usr/lib/jvm/temurin-trimmed
# temurin25-trimmed: the pre-JDK-27 path, kept as a link for copied ENTRYPOINT overrides.
COPY --from=aot-build /jvm/ /usr/lib/jvm/
# deps.jar on its OWN layer, before own.jar, so an unchanged deps.jar layer is reused on upgrade.
COPY --from=aot-build /mockserver-deps.jar /
COPY --from=aot-build /mockserver.jar /
COPY --from=aot-build /mockserver.aot /

USER nonroot

# -XX:MaxRAMPercentage=45.0 caps the JVM heap at 45% of the container memory limit, as in the sibling
# images: native JVM memory and kernel socket buffers need the rest (512 MiB is the supported floor).
# -XX:AOTCache loads the baked cache; if the cache is incompatible (e.g.
# different CPU arch) the JVM logs a warning and starts normally.
# GC: kept on the JDK default (G1), unlike the sibling server images' -XX:+UseZGC — see the training
# stage above. To trade start-up for ZGC's lower tail latency, pass -XX:+UseZGC via JAVA_TOOL_OPTIONS.
ENTRYPOINT ["/usr/lib/jvm/temurin-trimmed/bin/java", "-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED", "-XX:MaxRAMPercentage=45.0", "-XX:AOTCache=/mockserver.aot", "-cp", "/mockserver.jar:/mockserver-deps.jar:/libs/*:/extensions/*", "-Dmockserver.propertyFile=/config/mockserver.properties", "org.mockserver.cli.Main"]

ENV PATH="/usr/lib/jvm/temurin-trimmed/bin:${PATH}"
ENV SERVER_PORT=1080

COPY --from=healthcheck /mockserver-healthcheck /mockserver-healthcheck
HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=3 \
  CMD ["/mockserver-healthcheck"]

CMD []
