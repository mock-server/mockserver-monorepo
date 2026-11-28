#
# MockServer Dockerfile
#
# https://github.com/mock-server/mockserver
# https://www.mock-server.com
#

ARG source=download

# build image — downloads snapshot artifacts from Sonatype, tcnative from Maven Central
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
# Harden Maven Central downloads against transient DNS/connection blips (e.g. flaky
# networks, CI). GNU wget retries host-resolution + connection-refused errors;
# BusyBox wget ignores /etc/wgetrc, so this is a safe no-op there.
RUN printf 'tries=5\ntimeout=60\nwaitretry=10\nretry_on_host_error=on\nretry_connrefused=on\n' >> /etc/wgetrc
# Audit finding F-DKR-06: SHA256-verify all Maven repo downloads.
ARG VERSION=5.16.0-SNAPSHOT
ARG REPOSITORY_URL=https://central.sonatype.com/repository/maven-snapshots/org/mock-server/mockserver-netty/${VERSION}/mockserver-netty-${VERSION}-jar-with-dependencies.jar
RUN wget --max-redirect=10 -O mockserver-netty-jar-with-dependencies.jar "$REPOSITORY_URL" \
    && wget --max-redirect=10 -qO- "${REPOSITORY_URL}.sha256" > /tmp/expected.sha256 \
    && echo "$(cat /tmp/expected.sha256)  mockserver-netty-jar-with-dependencies.jar" | sha256sum -c -
# Bare, no default (a default overrides BuildKit's per-leg value); the stage's own uname -m is the authority.
ARG TARGETARCH
RUN NETTY_TCNATIVE="$(unzip -p mockserver-netty-jar-with-dependencies.jar META-INF/mockserver-tcnative.version | tr -d '[:space:]')" \
    && if [ -z "$NETTY_TCNATIVE" ]; then echo "ERROR: META-INF/mockserver-tcnative.version missing or empty in mockserver-netty-jar-with-dependencies.jar" >&2; exit 1; fi \
    && case "$(uname -m)" in \
         x86_64)        STAGE_ARCH=amd64; TCNATIVE_ARCH=x86_64;   ELF_MACHINE=62 ;; \
         aarch64|arm64) STAGE_ARCH=arm64; TCNATIVE_ARCH=aarch_64; ELF_MACHINE=183 ;; \
         *) echo "ERROR: no netty-tcnative build for stage arch '$(uname -m)'" >&2; exit 1 ;; \
       esac \
    && if [ -n "${TARGETARCH:-}" ] && [ "$TARGETARCH" != "$STAGE_ARCH" ]; then \
         echo "ERROR: TARGETARCH='$TARGETARCH' but this stage runs on $STAGE_ARCH - declare 'ARG TARGETARCH' without a default" >&2; exit 1; \
       fi \
    && TCNATIVE_URL="https://repo1.maven.org/maven2/io/netty/netty-tcnative-boringssl-static/$NETTY_TCNATIVE/netty-tcnative-boringssl-static-$NETTY_TCNATIVE-linux-$TCNATIVE_ARCH.jar" \
    && wget -O netty-tcnative-boringssl-static.jar "$TCNATIVE_URL" \
    && wget -qO- "${TCNATIVE_URL}.sha256" > /tmp/expected-tcnative.sha256 \
    && echo "$(cat /tmp/expected-tcnative.sha256)  netty-tcnative-boringssl-static.jar" | sha256sum -c - \
    && unzip netty-tcnative-boringssl-static.jar \
    && for so in META-INF/native/*.so; do \
         [ -f "$so" ] || { echo "ERROR: no native .so in netty-tcnative-boringssl-static.jar" >&2; exit 1; }; \
         ACTUAL="$(od -An -tu2 -j18 -N2 "$so")"; \
         if [ "${ACTUAL##* }" != "$ELF_MACHINE" ]; then \
           echo "ERROR: $so ELF machine ${ACTUAL##* }, expected $ELF_MACHINE for $TCNATIVE_ARCH" >&2; exit 1; \
         fi; \
       done

# build image — copies JAR from context, downloads tcnative from Maven Central
FROM alpine:3.24@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf AS copy

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
# Bare, no default (a default overrides BuildKit's per-leg value); the stage's own uname -m is the authority.
ARG TARGETARCH
# Audit finding F-DKR-06: SHA256-verify tcnative download from Maven Central.
RUN NETTY_TCNATIVE="$(unzip -p mockserver-netty-jar-with-dependencies.jar META-INF/mockserver-tcnative.version | tr -d '[:space:]')" \
    && if [ -z "$NETTY_TCNATIVE" ]; then echo "ERROR: META-INF/mockserver-tcnative.version missing or empty in mockserver-netty-jar-with-dependencies.jar" >&2; exit 1; fi \
    && case "$(uname -m)" in \
         x86_64)        STAGE_ARCH=amd64; TCNATIVE_ARCH=x86_64;   ELF_MACHINE=62 ;; \
         aarch64|arm64) STAGE_ARCH=arm64; TCNATIVE_ARCH=aarch_64; ELF_MACHINE=183 ;; \
         *) echo "ERROR: no netty-tcnative build for stage arch '$(uname -m)'" >&2; exit 1 ;; \
       esac \
    && if [ -n "${TARGETARCH:-}" ] && [ "$TARGETARCH" != "$STAGE_ARCH" ]; then \
         echo "ERROR: TARGETARCH='$TARGETARCH' but this stage runs on $STAGE_ARCH - declare 'ARG TARGETARCH' without a default" >&2; exit 1; \
       fi \
    && TCNATIVE_URL="https://repo1.maven.org/maven2/io/netty/netty-tcnative-boringssl-static/$NETTY_TCNATIVE/netty-tcnative-boringssl-static-$NETTY_TCNATIVE-linux-$TCNATIVE_ARCH.jar" \
    && wget -O netty-tcnative-boringssl-static.jar "$TCNATIVE_URL" \
    && wget -qO- "${TCNATIVE_URL}.sha256" > /tmp/expected-tcnative.sha256 \
    && echo "$(cat /tmp/expected-tcnative.sha256)  netty-tcnative-boringssl-static.jar" | sha256sum -c - \
    && unzip netty-tcnative-boringssl-static.jar \
    && for so in META-INF/native/*.so; do \
         [ -f "$so" ] || { echo "ERROR: no native .so in netty-tcnative-boringssl-static.jar" >&2; exit 1; }; \
         ACTUAL="$(od -An -tu2 -j18 -N2 "$so")"; \
         if [ "${ACTUAL##* }" != "$ELF_MACHINE" ]; then \
           echo "ERROR: $so ELF machine ${ACTUAL##* }, expected $ELF_MACHINE for $TCNATIVE_ARCH" >&2; exit 1; \
         fi; \
       done

FROM ${source} AS intermediate

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

# jdk: jlink the run-time modules of the distroless Java image this one used before, then the
# default CDS archives the jlink image lacks (one per GC); see docs/infrastructure/docker.md#jdk-and-runtime.
FROM debian:trixie-slim@sha256:a72b4433de1cf7d6bae0c4cee74d0547f58601a643b3375f070c51600d63b6b2 AS jdk
COPY --from=jdk-download /jdk /jdk
# --strip-java-debug-attributes, not --strip-debug: see docs/infrastructure/docker.md#jdk-and-runtime.
RUN /jdk/bin/jlink \
      --add-modules java.se,java.smartcardio,jdk.accessibility,jdk.charsets,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.dynalink,jdk.httpserver,jdk.incubator.vector,jdk.jdwp.agent,jdk.jfr,jdk.localedata,jdk.management,jdk.management.agent,jdk.management.jfr,jdk.naming.dns,jdk.naming.rmi,jdk.net,jdk.nio.mapmode,jdk.sctp,jdk.security.auth,jdk.security.jgss,jdk.unsupported,jdk.xml.dom,jdk.zipfs \
      --strip-java-debug-attributes --no-man-pages --no-header-files --compress=zip-6 \
      --output /runtime \
 && /runtime/bin/java -XX:+UseZGC --enable-native-access=ALL-UNNAMED -Xshare:dump \
 && /runtime/bin/java --enable-native-access=ALL-UNNAMED -Xshare:dump
# cacerts -> the java-base image's Debian trust store: see docs/infrastructure/docker.md#jdk-and-runtime.
RUN ln -sf /etc/ssl/certs/java/cacerts /runtime/lib/security/cacerts \
 && mkdir /usr-bin && ln -s /usr/lib/jvm/temurin-trimmed/bin/java /usr-bin/java

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

# runtime image — distroless java-base + the jlink-trimmed Temurin 27 runtime from the jdk stage
FROM gcr.io/distroless/java-base-debian13:latest@sha256:999c2f47854a699b804cd6ea430502df32ca6d8dff65c14747316b9aa5d69aa6

# maintainer details
LABEL org.opencontainers.image.authors="James Bloom <jamesdbloom@gmail.com>"

# expose ports.
EXPOSE 1080

COPY --from=jdk /runtime /usr/lib/jvm/temurin-trimmed
# /usr/bin/java, where the distroless Java image this one used to run on put it
COPY --from=jdk /usr-bin/ /usr/bin/

# copy in jar
COPY --from=intermediate mockserver-netty-jar-with-dependencies.jar /
# Native-TLS fallback for read-only-rootfs deployments (docker run --read-only):
# normally /tmp is writable and Netty extracts the tcnative .so from the jar, so
# this copy is dormant; under --read-only that extraction fails and System.loadLibrary
# finding /usr/lib/libnetty_tcnative_linux_<arch>.so is the only thing keeping
# BoringSSL native TLS alive. Inert for the shaded no-dependencies jar (relocated
# tcnative symbols cannot bind this stock .so). Introduced by e4dd3a61b.
COPY --from=intermediate META-INF/native/libnetty_tcnative_linux_*.so /usr/lib/

# run MockServer as root
USER root

# -XX:MaxRAMPercentage=45.0 caps the JVM heap at 45% of the container memory limit so the in-memory
# request/expectation rings size off a bounded heap. The rest is not slack: under load ZGC grows
# to its full heap in memory the kernel cannot reclaim, and native JVM memory and kernel socket
# buffers do not shrink with the heap, so 60% was OOM-killed and 50% peaked above 90% at 512 MiB. Only
# applies when the user has NOT set an explicit heap; to change the cap set an explicit -Xmx (via JAVA_TOOL_OPTIONS) —
# that disables MaxRAMPercentage. A different MaxRAMPercentage via JAVA_TOOL_OPTIONS does NOT take
# effect: it is prepended before these args, so this entrypoint flag is applied last and wins.
# Garbage collector: ZGC is the default, supplied via the JAVA_TOOL_OPTIONS env var below, NOT a
# hard-coded ENTRYPOINT flag — JAVA_TOOL_OPTIONS is prepended, so a hard-coded -XX:+UseZGC would
# collide with any user GC flag ("multiple GCs selected", fatal). An ENV default is replaced wholesale
# when the user sets JAVA_TOOL_OPTIONS, keeping the collector overridable on this shell-less image.
# Switch to G1 with -e JAVA_TOOL_OPTIONS=-XX:+UseG1GC; keep -XX:+UseZGC if you set it for -Xmx etc.
# JDK 27 ZGC is generational — no -XX:+ZGenerational (obsolete post-JDK 23).
ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC"
ENTRYPOINT ["/usr/lib/jvm/temurin-trimmed/bin/java", "-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED", "-XX:MaxRAMPercentage=45.0", "-cp", "/mockserver-netty-jar-with-dependencies.jar:/libs/*:/extensions/*", "-Dmockserver.propertyFile=/config/mockserver.properties", "org.mockserver.cli.Main"]

ENV PATH="/usr/lib/jvm/temurin-trimmed/bin:${PATH}"
ENV SERVER_PORT=1080

COPY --from=healthcheck /mockserver-healthcheck /mockserver-healthcheck
HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=3 \
  CMD ["/mockserver-healthcheck"]

CMD []
