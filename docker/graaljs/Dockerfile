#
# MockServer Dockerfile — GraalJS variant
#
# Includes GraalJS JavaScript engine for JavaScript templating support.
# Use this image if you need JavaScript-based response/forward templates.
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
ARG VERSION=RELEASE
ARG REPOSITORY_URL=https://repo1.maven.org/maven2/org/mock-server/mockserver-netty/${VERSION}/mockserver-netty-${VERSION}-jar-with-dependencies.jar
# Audit finding F-DKR-06: SHA256-verify all Maven Central downloads.
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

# download GraalJS JARs
FROM alpine:3.24 AS graaljs

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
ARG GRAALJS_VERSION=25.0.3
# GraalVM 25.x dropped the JSR-223 javax.script bridge (js-scriptengine.jar is gone).
# Coordinates also changed: icu4j is now under org.graalvm.shadowed instead of com.ibm.icu,
# and jniutils is a new artifact required by truffle-runtime.
RUN mkdir -p /graaljs && \
    wget -O /graaljs/polyglot.jar "https://repo1.maven.org/maven2/org/graalvm/polyglot/polyglot/$GRAALJS_VERSION/polyglot-$GRAALJS_VERSION.jar" && \
    wget -O /graaljs/js-language.jar "https://repo1.maven.org/maven2/org/graalvm/js/js-language/$GRAALJS_VERSION/js-language-$GRAALJS_VERSION.jar" && \
    wget -O /graaljs/truffle-api.jar "https://repo1.maven.org/maven2/org/graalvm/truffle/truffle-api/$GRAALJS_VERSION/truffle-api-$GRAALJS_VERSION.jar" && \
    wget -O /graaljs/truffle-runtime.jar "https://repo1.maven.org/maven2/org/graalvm/truffle/truffle-runtime/$GRAALJS_VERSION/truffle-runtime-$GRAALJS_VERSION.jar" && \
    wget -O /graaljs/truffle-compiler.jar "https://repo1.maven.org/maven2/org/graalvm/truffle/truffle-compiler/$GRAALJS_VERSION/truffle-compiler-$GRAALJS_VERSION.jar" && \
    wget -O /graaljs/collections.jar "https://repo1.maven.org/maven2/org/graalvm/sdk/collections/$GRAALJS_VERSION/collections-$GRAALJS_VERSION.jar" && \
    wget -O /graaljs/nativeimage.jar "https://repo1.maven.org/maven2/org/graalvm/sdk/nativeimage/$GRAALJS_VERSION/nativeimage-$GRAALJS_VERSION.jar" && \
    wget -O /graaljs/word.jar "https://repo1.maven.org/maven2/org/graalvm/sdk/word/$GRAALJS_VERSION/word-$GRAALJS_VERSION.jar" && \
    wget -O /graaljs/jniutils.jar "https://repo1.maven.org/maven2/org/graalvm/sdk/jniutils/$GRAALJS_VERSION/jniutils-$GRAALJS_VERSION.jar" && \
    wget -O /graaljs/regex.jar "https://repo1.maven.org/maven2/org/graalvm/regex/regex/$GRAALJS_VERSION/regex-$GRAALJS_VERSION.jar" && \
    wget -O /graaljs/icu4j.jar "https://repo1.maven.org/maven2/org/graalvm/shadowed/icu4j/$GRAALJS_VERSION/icu4j-$GRAALJS_VERSION.jar"

FROM ${source} AS intermediate

# jarprep: drop every native binary a linux/<arch> container cannot load, then split the jar into
# own.jar (org/mockserver/**) and deps.jar (the dependencies), both STORED and byte-reproducible, so
# a release that changes no dependency reuses deps.jar's layer. The logic is the shared
# mockserver-jarprep.sh (canonical copy docker/jarprep/): it takes the arch from this stage's
# uname -m, checks every kept .so's ELF machine, and fails the build if a required native is missing.
FROM intermediate AS jarprep
RUN apk add --no-cache zip unzip
# Bare, no default (a default overrides BuildKit's per-leg value); the stage's own uname -m is the authority.
ARG TARGETARCH
COPY mockserver-jarprep.sh /usr/local/bin/mockserver-jarprep.sh
RUN sh /usr/local/bin/mockserver-jarprep.sh /mockserver-netty-jar-with-dependencies.jar "${TARGETARCH:-}" /jarprep

# HEALTHCHECK probe — a ~2 MB static binary, NOT a second JVM: a probe JVM started inside the
# container's memory cgroup every 10s pushed a 512 MiB container over its limit under load. Source:
# docker/healthcheck/mockserver-healthcheck.go (copied byte-identically into each image context).
# Cross-compiled on the BUILD platform: the Go toolchain crashes under QEMU emulation, so it must
# never run as the non-native leg of a multi-arch build. Needs BuildKit (the classic builder has no
# BUILDPLATFORM). The ELF e_machine check fails the build on a wrong-arch binary.
FROM --platform=$BUILDPLATFORM golang:1.27-alpine@sha256:8a5910f31396cd4d89662f56c68b3ae31d374308270a1c3bd96672ee5ed43414 AS healthcheck
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

# runtime image https://console.cloud.google.com/gcr/images/distroless/global/java25
FROM gcr.io/distroless/java25:nonroot@sha256:28a3986989d7d74cb5cfd6ba369e64d3d634f83ac9fa562dac8db2d20827f90e

# maintainer details
LABEL org.opencontainers.image.authors="James Bloom <jamesdbloom@gmail.com>"

# Source provenance (OCI convention). revision = the git commit this image was
# BUILT from; created = the RFC-3339 build time. Both are stamped by the build via
# --build-arg (java-docker-push-snapshot.sh for the snapshot tags), and are EMPTY
# on a plain `docker build` (forks/self-hosters) so the label is simply absent
# rather than wrong. The perf harness reads image.revision back off the running
# SUT (perf-test-run.sh) and refuses to attribute a run to a commit the measured
# binary did not come from — a floating tag on a cached agent otherwise leaves a
# perf result with no evidence it measured the change it was filed under.
ARG SOURCE_COMMIT=
ARG BUILD_DATE=
LABEL org.opencontainers.image.revision="${SOURCE_COMMIT}"
LABEL org.opencontainers.image.created="${BUILD_DATE}"

# expose ports.
EXPOSE 1080

# deps.jar on its OWN layer, before own.jar: a release whose dependencies are unchanged produces a
# byte-identical deps.jar, so `docker pull` reuses that layer instead of re-fetching it.
COPY --from=jarprep /jarprep/deps.jar /mockserver-deps.jar
COPY --from=jarprep /jarprep/own.jar /mockserver.jar
# Native-TLS fallback for read-only-rootfs deployments (docker run --read-only):
# normally /tmp is writable and Netty extracts the tcnative .so from the jar, so
# this copy is dormant; under --read-only that extraction fails and System.loadLibrary
# finding /usr/lib/libnetty_tcnative_linux_<arch>.so is the only thing keeping
# BoringSSL native TLS alive. Inert for the shaded no-dependencies jar (relocated
# tcnative symbols cannot bind this stock .so). Introduced by e4dd3a61b.
COPY --from=intermediate META-INF/native/libnetty_tcnative_linux_*.so /usr/lib/

# copy GraalJS JARs into /libs
COPY --from=graaljs /graaljs/*.jar /libs/

# don't run MockServer as root
USER nonroot

# -XX:MaxRAMPercentage=45.0 caps the JVM heap at 45% of the container memory limit so the in-memory
# request/expectation rings size off a bounded heap. The rest is not slack: under load ZGC grows to
# its full heap in memory the kernel cannot reclaim, and native JVM memory (larger here than in the
# other images, for GraalJS/Truffle) and kernel socket buffers do not shrink with the heap; 50% left
# too little headroom at the 512 MiB floor. Only applies when the
# user has NOT set an explicit heap; to change the cap set an explicit -Xmx (via JAVA_TOOL_OPTIONS) —
# that disables MaxRAMPercentage. A different MaxRAMPercentage via JAVA_TOOL_OPTIONS does NOT take
# effect: it is prepended before these args, so this entrypoint flag is applied last and wins.
# Garbage collector: ZGC is the default, supplied via the JAVA_TOOL_OPTIONS env var below, NOT a
# hard-coded ENTRYPOINT flag — JAVA_TOOL_OPTIONS is prepended, so a hard-coded -XX:+UseZGC would
# collide with any user GC flag ("multiple GCs selected", fatal). An ENV default is replaced wholesale
# when the user sets JAVA_TOOL_OPTIONS, keeping the collector overridable on this shell-less image.
# Switch to G1 with -e JAVA_TOOL_OPTIONS=-XX:+UseG1GC; keep -XX:+UseZGC if you set it for -Xmx etc.
# JDK 25 ZGC is generational — no -XX:+ZGenerational (obsolete post-JDK 23).
ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC"
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED", "-XX:MaxRAMPercentage=45.0", "-cp", "/mockserver.jar:/mockserver-deps.jar:/libs/*", "-Dmockserver.propertyFile=/config/mockserver.properties", "org.mockserver.cli.Main"]

ENV SERVER_PORT=1080

# Dashboard usage analytics (PostHog Cloud EU, cookieless). EMPTY by default so a plain
# `docker build` — i.e. forks / self-hosters building this Dockerfile directly — ships analytics
# INERT (the dashboard's activation gate requires a non-empty endpoint AND key). Only the
# project's official release build injects the real values via --build-arg
# (scripts/release/components/docker.sh). The key is a write-only public ingest key, safe to
# expose. A user can always override at runtime with `-e MOCKSERVER_DASHBOARD_ANALYTICS_*`.
ARG DASHBOARD_ANALYTICS_ENDPOINT=
ARG DASHBOARD_ANALYTICS_KEY=
ENV MOCKSERVER_DASHBOARD_ANALYTICS_ENDPOINT=${DASHBOARD_ANALYTICS_ENDPOINT}
ENV MOCKSERVER_DASHBOARD_ANALYTICS_KEY=${DASHBOARD_ANALYTICS_KEY}
# Labels analytics events by the artefact they came from. A plain, non-secret constant — safe to
# commit directly: it is only ever sent when analytics is already active (which still requires the
# injected endpoint+key above), so forks building this Dockerfile directly stay inert.
ENV MOCKSERVER_DASHBOARD_ANALYTICS_DISTRIBUTION=docker-graaljs

COPY --from=healthcheck /mockserver-healthcheck /mockserver-healthcheck
HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=3 \
  CMD ["/mockserver-healthcheck"]

CMD []
