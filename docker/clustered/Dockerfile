#
# MockServer Clustered Dockerfile
#
# Image variant that includes the Infinispan state backend module and its
# transitive dependencies (JGroups, Infinispan, ProtoStream, etc.) so that
# operators can enable clustered state replication with:
#
#   MOCKSERVER_STATE_BACKEND=infinispan
#   MOCKSERVER_CLUSTER_ENABLED=true
#
# The default (non-clustered) image does NOT ship these libraries, keeping
# its size lean for the majority of users who run a single instance.
#
# Build locally (copy mode — from a local Maven build):
#   cp mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar docker/clustered/
#   mvn -pl mockserver-state-infinispan dependency:copy-dependencies \
#       -DincludeScope=runtime -DexcludeGroupIds=org.mock-server \
#       -DoutputDirectory=docker/clustered/libs
#   cp mockserver-state-infinispan/target/mockserver-state-infinispan-*.jar docker/clustered/libs/
#   docker build -t mockserver/mockserver:clustered docker/clustered
#
# https://github.com/mock-server/mockserver
# https://www.mock-server.com
#

# build image — downloads netty-tcnative from Maven Central (mirrors base image pattern)
FROM alpine:3.24 AS tcnative

# Optional: trust a corporate root CA before `apk add`. The build context contains
# ca-bundle.pem (empty in CI, populated locally behind a TLS-inspecting proxy).
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
# Bare, no default (a default overrides BuildKit's per-leg value); the stage's own uname -m is the authority.
ARG TARGETARCH
# Audit finding F-DKR-06: SHA256-verify tcnative download from Maven Central.
# bring in the server jar so the tcnative version can be read from it
COPY mockserver-netty-jar-with-dependencies.jar .
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

# jarprep: drop every native binary a linux/<arch> container cannot load, then split the jar into
# own.jar (org/mockserver/**) and deps.jar (the dependencies), both STORED and byte-reproducible, so
# a release that changes no dependency reuses deps.jar's layer. The logic is the shared
# mockserver-jarprep.sh (canonical copy docker/jarprep/): it takes the arch from this stage's
# uname -m, checks every kept .so's ELF machine, and fails the build if a required native is missing.
FROM tcnative AS jarprep
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

# runtime image https://console.cloud.google.com/gcr/images/distroless/global/java21
# Java 21, not 25: Infinispan 14.0.35 calls Subject.getSubject during cache-manager
# startup, which throws UnsupportedOperationException from JDK 24 onward (JEP 486
# permanently disabled the Security Manager). No JVM flag restores it; Infinispan 15
# fixes the call but requires a Java 21+ compile target, which the Java 17 library
# floor does not allow. 21 is the newest base on which clustering starts.
FROM gcr.io/distroless/java21:nonroot@sha256:51c242e7ddaba3666d47115a76a19cfa5076b2ad3ed6077b30d7bac689d98fa1

# maintainer details
LABEL org.opencontainers.image.authors="James Bloom <jamesdbloom@gmail.com>"

# Source provenance (OCI convention). revision = the git commit this image was
# BUILT from; created = the RFC-3339 build time. Both are stamped by the build via
# --build-arg (java-docker-push-snapshot.sh for the snapshot tags), and are EMPTY
# on a plain `docker build` (forks/self-hosters) so the label is simply absent
# rather than wrong. Mirrors docker/local + docker/graaljs.
#
# WHY THIS IMAGE NEEDS IT TOO: the perf harness runs the item-13 clustered A/B
# against THIS image while the run itself is attributed to the SUT (graaljs) image's
# revision. Without a revision here there is no way to prove the two came from the
# same commit, and a stale `mockserver-snapshot-clustered` tag would file a clustered
# ratio under a commit whose clustered code it never executed — a silently meaningless
# comparison. perf-test-run.sh compares this label with the SUT's and refuses to
# measure on a mismatch.
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
COPY --from=tcnative META-INF/native/libnetty_tcnative_linux_*.so /usr/lib/

# copy in the clustered state backend libraries (Infinispan, JGroups, etc.)
# These are placed on the classpath via the /libs/* glob in the ENTRYPOINT.
COPY libs/ /libs/

# don't run MockServer as root
USER nonroot

# -XX:MaxRAMPercentage=45.0 caps the JVM heap at 45% of the container memory limit so the in-memory
# request/expectation rings size off a bounded heap. The rest is not slack: under load ZGC grows
# to its full heap in memory the kernel cannot reclaim, and native JVM memory and kernel socket
# buffers do not shrink with the heap, so 60% was OOM-killed and 50% peaked above 90% at 512 MiB. With the
# Infinispan backend this image needs at least 768 MiB: at 512 MiB it thrashed even at 50%. Only
# applies when the user has NOT set an explicit heap; to change the cap set an explicit -Xmx (via JAVA_TOOL_OPTIONS) —
# that disables MaxRAMPercentage. A different MaxRAMPercentage via JAVA_TOOL_OPTIONS does NOT take
# effect: it is prepended before these args, so this entrypoint flag is applied last and wins.
# Garbage collector: generational ZGC is the default, supplied via the JAVA_TOOL_OPTIONS env var
# below, NOT a hard-coded ENTRYPOINT flag — JAVA_TOOL_OPTIONS is prepended, so a hard-coded GC flag
# would collide with any user GC flag ("multiple GCs selected", fatal). An ENV default is replaced
# wholesale when the user sets JAVA_TOOL_OPTIONS, keeping it overridable on this shell-less image.
# This is a JDK 21 base where ZGC is single-generational by default, so -XX:+ZGenerational is ALSO
# required (valid on JDK 21; obsolete from JDK 23, so absent on the JDK 25/26 images). Switch to G1
# with -e JAVA_TOOL_OPTIONS=-XX:+UseG1GC; keep both flags if you set JAVA_TOOL_OPTIONS for -Xmx etc.
ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC -XX:+ZGenerational"
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
ENV MOCKSERVER_DASHBOARD_ANALYTICS_DISTRIBUTION=docker-clustered

COPY --from=healthcheck /mockserver-healthcheck /mockserver-healthcheck
HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=3 \
  CMD ["/mockserver-healthcheck"]

CMD []
