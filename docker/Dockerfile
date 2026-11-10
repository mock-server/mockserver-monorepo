#
# MockServer Dockerfile
#
# https://github.com/mock-server/mockserver
# https://www.mock-server.com
#

ARG source=download

# build image — downloads release artifacts from Sonatype, tcnative from Maven Central
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
# Audit finding F-DKR-06: SHA256-verify all downloads from Maven Central.
# Maven Central publishes .sha256 alongside every artifact.
ARG VERSION=RELEASE
ARG REPOSITORY_URL=https://repo1.maven.org/maven2/org/mock-server/mockserver-netty/${VERSION}/mockserver-netty-${VERSION}-jar-with-dependencies.jar
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

# jarprep: drop every native binary a linux/<arch> container cannot load, then split the jar into
# own.jar (org/mockserver/**) and deps.jar (the dependencies), both STORED and byte-reproducible, so
# a release that changes no dependency reuses deps.jar's layer. The logic is the shared
# jarprep/mockserver-jarprep.sh (every published image runs a byte-identical copy): it takes the arch
# from this stage's uname -m, checks every kept .so's ELF machine, and fails the build if a required
# native is missing.
FROM intermediate AS jarprep
RUN apk add --no-cache zip unzip
# Bare, no default (a default overrides BuildKit's per-leg value); the stage's own uname -m is the authority.
ARG TARGETARCH
COPY jarprep/mockserver-jarprep.sh /usr/local/bin/mockserver-jarprep.sh
RUN sh /usr/local/bin/mockserver-jarprep.sh /mockserver-netty-jar-with-dependencies.jar "${TARGETARCH:-}" /jarprep

# AppCDS build stage — jlink-trimmed JDK 26 runtime + training run producing the AppCDS archive.
# Mirrors docker/local/Dockerfile (the release/snapshot artifact); this Dockerfile is the public
# download-mode reference and additionally ships netty-tcnative (see the runtime stage below).
# NOTE: the JVM inside this runtime image is JDK 26 — the MockServer library itself is still
# compiled to the Java 17 bytecode floor (see AGENTS.md Java Compatibility Policy); running that
# jar on a newer JVM is fully supported and is a runtime-only choice, independent of compilation.
FROM eclipse-temurin:26-jdk-noble@sha256:9c0e8eddecccc0d5679b317b0c90fa7103ad25cae33df1c00280675eb681c7f8 AS appcds-build

# Split artifacts (see the jarprep stage): own classes and third-party deps as two jars, both
# on the classpath. The AppCDS classpath is derived from this exact -cp, so the runtime ENTRYPOINT
# below MUST present the same two entries in the same order for the archive to map. Entries appended
# after them (/libs/*, /extensions/*) are allowed: CDS accepts a runtime classpath that extends the
# dump-time one, and a wildcard over a missing or empty directory adds nothing.
COPY --from=jarprep /jarprep/own.jar  /mockserver.jar
COPY --from=jarprep /jarprep/deps.jar /mockserver-deps.jar

# Same module set validated end-to-end for the jlink binary bundle (scripts/build-binary-bundle.sh,
# docs/code/cli.md). jdk.management supplies com.sun.management.ThreadMXBean, which the Prometheus
# jvm_memory_allocated_bytes gauge (JvmMetricsCollector) needs — java.se aggregates only java.*
# modules, so without it that metric is silently suppressed. From JDK 25 onwards jlink uses the
# --compress=zip-<level> form (zip-6 = the default ZIP level); the legacy numeric --compress=2 form
# was deprecated after JDK 17 and is removed from JDK 25 onwards.
# A jlink image does not carry the JDK's default CDS base archive, so -Xshare:dump regenerates it
# from the bundled lib/classlist — the dynamic AppCDS archive below is layered on that base, and the
# base is carried into the runtime image with /runtime.
RUN "$JAVA_HOME/bin/jlink" \
      --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.naming.dns,jdk.zipfs,jdk.management \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --output /runtime \
 && /runtime/bin/java -XX:+UseZGC --enable-native-access=ALL-UNNAMED -Xshare:dump

# Training run (dynamic AppCDS, JEP 350): start the server with -XX:ArchiveClassesAtExit, wait until
# the status endpoint answers via the bundled HealthCheck (which also drives the post-bind warmup),
# then stop cleanly so the JVM writes the archive at exit. The `ls` fails the build if the archive
# was not produced. tcnative is not on the training classpath (it is added only at runtime), so the
# archive covers the JDK-provider path; tcnative classes simply load normally at runtime.
# -XX:+UseZGC MUST match the runtime collector (the ENTRYPOINT below): the base archive was dumped
# under ZGC above and the dynamic archive layers on it, so training under the same collector keeps
# the archive mappable. Under a non-ZGC override (e.g. JAVA_TOOL_OPTIONS=-XX:+UseG1GC) the archive is
# not loaded: -Xshare:auto logs a CDS error and the JVM starts without it (slower cold start, still works).
RUN /runtime/bin/java --enable-native-access=ALL-UNNAMED -XX:+UseZGC -XX:ArchiveClassesAtExit=/mockserver.jsa \
      -cp /mockserver.jar:/mockserver-deps.jar org.mockserver.cli.Main -p 1080 & \
    SERVER_PID=$!; \
    for i in $(seq 1 240); do \
      /runtime/bin/java -cp /mockserver.jar:/mockserver-deps.jar org.mockserver.cli.HealthCheck && break; \
      sleep 0.5; \
    done; \
    kill -TERM "$SERVER_PID" && wait "$SERVER_PID" || true; \
    ls -l /mockserver.jsa

# HEALTHCHECK probe — a ~2 MB static binary, NOT a second JVM: a probe JVM started inside the
# container's memory cgroup every 10s pushed a 512 MiB container over its limit under load. Source:
# docker/healthcheck/mockserver-healthcheck.go (copied byte-identically into each image context).
# Cross-compiled on the BUILD platform: the Go toolchain crashes under QEMU emulation, so it must
# never run as the non-native leg of a multi-arch build. Needs BuildKit (the classic builder has no
# BUILDPLATFORM). The ELF e_machine check fails the build on a wrong-arch binary.
FROM --platform=$BUILDPLATFORM golang:1.27-alpine@sha256:f92b6ef800e499660581efdabdf25d9d817a9d124eaf900924f0504e7e27e12d AS healthcheck
ARG TARGETOS
ARG TARGETARCH
WORKDIR /src
COPY healthcheck/ ./
RUN CGO_ENABLED=0 GOTOOLCHAIN=local go vet mockserver-healthcheck.go mockserver-healthcheck_test.go \
 && CGO_ENABLED=0 GOTOOLCHAIN=local go test mockserver-healthcheck.go mockserver-healthcheck_test.go
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

# runtime image — distroless base + trimmed JDK 26 runtime + jar + AppCDS archive + tcnative .so
# (digest-pinned per repo convention; update the digest together with the Temurin 25 build, since
# the baked AppCDS archive is specific to the JDK build it was trained with — same base+digest as
# docker/aot/Dockerfile)
FROM gcr.io/distroless/java-base-debian13:nonroot@sha256:fe40972ca2d78f7eb58dd93354c2b01f1c1ae55fd4dd03ea6367769cb91c7160

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
# NOTE: the /usr/lib/jvm/temurin25-trimmed path name is HISTORICAL and does NOT track the JDK
# version - this image ships JDK 26. It is referenced by the ENTRYPOINT below
# and by sibling Dockerfiles, so it is left as-is rather than churned; read the FROM line above
# for the actual version.
COPY --from=appcds-build /runtime /usr/lib/jvm/temurin25-trimmed
# deps.jar on its OWN layer, before own.jar: a release whose dependencies are unchanged produces a
# byte-identical deps.jar, so `docker pull` reuses that layer instead of re-fetching it.
COPY --from=appcds-build /mockserver-deps.jar /
COPY --from=appcds-build /mockserver.jar /
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

# -XX:MaxRAMPercentage=45.0 caps the JVM heap at 45% of the container memory limit so the in-memory
# request/expectation rings size off a bounded heap. The rest is not slack: under load ZGC grows
# to its full heap in memory the kernel cannot reclaim, and native JVM memory and kernel socket
# buffers do not shrink with the heap, so 60% was OOM-killed and 50% peaked above 90% at 512 MiB. Only
# applies when the user has NOT set an explicit heap; to change the cap set an explicit -Xmx (via JAVA_TOOL_OPTIONS) —
# that disables MaxRAMPercentage. A different MaxRAMPercentage via JAVA_TOOL_OPTIONS does NOT take
# effect: it is prepended before these args, so this entrypoint flag is applied last and wins.
# -XX:SharedArchiveFile loads the baked AppCDS archive. -Xshare:auto (the JVM default) means a
# missing or unusable archive (e.g. bind-mounted away, corrupt, or an arch mismatch) logs a warning
# and starts normally rather than failing.
# Garbage collector: ZGC is the default, supplied via the JAVA_TOOL_OPTIONS env var below, NOT a
# hard-coded ENTRYPOINT flag. JAVA_TOOL_OPTIONS is PREPENDED to these args, so a hard-coded
# -XX:+UseZGC would collide with any GC flag a user prepends ("multiple GCs selected" — fatal). As an
# ENV default it is instead REPLACED wholesale when the user sets JAVA_TOOL_OPTIONS, keeping the
# collector overridable on this shell-less distroless image. Switch to G1 with
# -e JAVA_TOOL_OPTIONS=-XX:+UseG1GC; keep -XX:+UseZGC if you set JAVA_TOOL_OPTIONS for -Xmx etc. The
# AppCDS archive above is trained under ZGC to match this default; a non-ZGC override starts without it. ZGC is generational on JDK 26 — no -XX:+ZGenerational (obsolete
# post-JDK 23). See docs/code/startup-performance.md and the memory programme.
ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC"
ENTRYPOINT ["/usr/lib/jvm/temurin25-trimmed/bin/java", "-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED", "-XX:MaxRAMPercentage=45.0", "-XX:SharedArchiveFile=/mockserver.jsa", "-cp", "/mockserver.jar:/mockserver-deps.jar:/libs/*:/extensions/*", "-Dmockserver.propertyFile=/config/mockserver.properties", "org.mockserver.cli.Main"]

ENV SERVER_PORT=1080

COPY --from=healthcheck /mockserver-healthcheck /mockserver-healthcheck
HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=3 \
  CMD ["/mockserver-healthcheck"]

CMD []
