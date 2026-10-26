#
# MockServer Dockerfile — standard image (built for BOTH releases and snapshots).
#
# This is the buildx context the release pipeline (scripts/release/components/docker.sh) and the
# per-merge snapshot step (.buildkite/scripts/steps/java-docker-push-snapshot.sh) push as
# mockserver/mockserver:<ver> / :snapshot. It bakes an Application Class Data Sharing (AppCDS)
# archive over the MockServer + library classes via a training run at build time, cutting container
# time-to-ready by roughly a third versus an untuned JVM while remaining the real HotSpot JVM
# (100% feature parity).
#
# Notes:
# - An AppCDS archive is tied to the exact JDK build it was trained with (same coupling as the
#   JDK 25 AOT cache in docker/aot/Dockerfile), so the trimmed JDK runtime is baked into this image
#   alongside the archive. Multi-arch builds each train + bake their own archive.
# - No distroless java base ships a matching CDS-capable runtime for a baked archive, so a
#   jlink-trimmed JDK 26 runtime (same module set as the binary bundle, see
#   scripts/build-binary-bundle.sh and docs/code/cli.md) is copied onto distroless/java-base — the
#   same base+digest as docker/aot/Dockerfile.
# - The JVM inside this image is JDK 26; the MockServer library itself is still compiled to the
#   Java 17 bytecode floor (AGENTS.md Java Compatibility Policy). Running that jar on a newer JVM is
#   fully supported — the JDK bump here is a runtime-only choice, independent of the compile target.
# - Runtime uses -XX:SharedArchiveFile with the default -Xshare:auto semantics, so a missing or
#   unusable archive logs a warning and starts normally (safe for the DEFAULT image — it never
#   hard-fails on the archive, unlike the AOT variant's strict modes).
# - netty-tcnative is intentionally omitted here (as in the prior docker/local image): TLS uses the
#   JDK provider. docker/Dockerfile (the public download-mode reference) still installs tcnative.
#
# https://github.com/mock-server/mockserver-monorepo
# https://www.mock-server.com
#

# jarprep: drop every native binary a linux/<arch> container cannot load, then split the jar into
# own.jar (org/mockserver/**) and deps.jar (the dependencies), both STORED and byte-reproducible, so
# a release that changes no dependency reuses deps.jar's layer. The logic is the shared
# mockserver-jarprep.sh (canonical copy docker/jarprep/): it takes the arch from this stage's
# uname -m, checks every kept .so's ELF machine, and fails the build if a required native is missing.
# ca-bundle.pem (empty in CI; staged by docker/ensure-ca-bundle.sh) is trusted before `apk add`.
FROM alpine:3.24 AS jarprep
COPY ca-bundle.pem /tmp/local-ca.pem
RUN if [ -s /tmp/local-ca.pem ]; then \
      mkdir -p /etc/ssl/certs && \
      cp /tmp/local-ca.pem /etc/ssl/cert.pem && \
      cp /tmp/local-ca.pem /etc/ssl/certs/ca-certificates.crt; \
    fi && \
    apk add --no-cache zip unzip
COPY mockserver-netty-jar-with-dependencies.jar /mockserver-netty-jar-with-dependencies.jar
# Bare, no default (a default overrides BuildKit's per-leg value); the stage's own uname -m is the authority.
ARG TARGETARCH
COPY mockserver-jarprep.sh /usr/local/bin/mockserver-jarprep.sh
RUN sh /usr/local/bin/mockserver-jarprep.sh /mockserver-netty-jar-with-dependencies.jar "${TARGETARCH:-}" /jarprep
# HTTP/3: copy this arch's QUIC native (jarprep keeps exactly one, under the name the jar's Netty asks
# for) into /usr/lib, which is on java.library.path, so it loads without extracting to /tmp, and so
# also under --read-only. The -http3 alias image (docker/http3) sees it there and adds nothing.
RUN set -eu; \
    unzip -Z1 /jarprep/deps.jar > /tmp/entries; \
    grep -E '^META-INF/native/lib[a-z0-9_]*netty_quiche[0-9]*_linux_[a-z0-9_]+\.so$' /tmp/entries > /tmp/quic-entries || true; \
    if [ "$(grep -c . /tmp/quic-entries)" != "1" ]; then \
      echo "ERROR: expected exactly one QUIC native in deps.jar, found: '$(cat /tmp/quic-entries)'; build this image from the mockserver-netty-docker jar (the default jar-with-dependencies has none)" >&2; exit 1; \
    fi; \
    ENTRY="$(cat /tmp/quic-entries)"; \
    mkdir /quic; \
    unzip -p /jarprep/deps.jar "$ENTRY" > "/quic/${ENTRY#META-INF/native/}"; \
    chmod 0755 /quic/*.so; \
    rm /tmp/entries /tmp/quic-entries; \
    ls -l /quic

# AppCDS build stage — jlink-trimmed JDK 26 runtime + training run producing the AppCDS archive
FROM eclipse-temurin:26-jdk-noble AS appcds-build

# own.jar and deps.jar from the jarprep stage. The AppCDS archive records this exact classpath, so the
# training run and the runtime ENTRYPOINT MUST both list /mockserver.jar:/mockserver-deps.jar in that
# order or the archive does not map. The ENTRYPOINT appends /libs/* and /extensions/*: CDS accepts a
# runtime classpath that extends the dump-time one, and a wildcard over a missing directory adds nothing.
COPY --from=jarprep /jarprep/own.jar  /mockserver.jar
COPY --from=jarprep /jarprep/deps.jar /mockserver-deps.jar

# Same module set validated end-to-end for the jlink binary bundle (scripts/build-binary-bundle.sh,
# docs/code/cli.md): java.se aggregator + Netty Unsafe + TLS crypto + DNS + zipfs + jdk.management.
# jdk.management supplies com.sun.management.ThreadMXBean, which the Prometheus jvm_memory_allocated_bytes
# gauge (JvmMetricsCollector) needs — java.se aggregates only java.* modules, so without it that metric
# is silently suppressed. From JDK 25 onwards jlink
# uses the --compress=zip-<level> form (zip-6 = the default ZIP level, same spelling docker/aot uses
# on JDK 25 — that image genuinely is 25); the legacy numeric --compress=2 form was deprecated
# after JDK 17 and is removed from JDK 25 onwards. A jlink image does not carry the JDK's default CDS base archive, so -Xshare:dump
# regenerates it from the bundled lib/classlist — the dynamic AppCDS archive below is layered on
# that base, and the base is carried into the runtime image with /runtime.
RUN "$JAVA_HOME/bin/jlink" \
      --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.naming.dns,jdk.zipfs,jdk.management \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --output /runtime \
 && /runtime/bin/java -XX:+UseZGC --enable-native-access=ALL-UNNAMED -Xshare:dump

# Training run (dynamic AppCDS, JEP 350): start the server with -XX:ArchiveClassesAtExit, wait until
# the status endpoint answers via the bundled HealthCheck (which also drives the post-bind warmup so
# the first-request class burst is archived too), then stop cleanly so the JVM writes the archive at
# exit. The `ls` fails the build if the archive was not produced.
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

# runtime image — distroless base + trimmed JDK 26 runtime + jar + AppCDS archive
# (digest-pinned per repo convention; update the digest together with the Temurin 25 build, since
# the baked AppCDS archive is specific to the JDK build it was trained with — same base+digest as
# docker/aot/Dockerfile)
FROM gcr.io/distroless/java-base-debian12:nonroot@sha256:a9930cad62d02853d7f3dede7281c4b916cbf74493c2d8d38564121aad92bf6c

# maintainer details
LABEL org.opencontainers.image.authors="James Bloom <jamesdbloom@gmail.com>"

# MCP registry ownership annotation — proves this image backs the com.mock-server/mockserver
# entry on the official MCP registry (https://registry.modelcontextprotocol.io). REQUIRED for the
# server.json OCI package publish (scripts/release/components/mcp.sh): the registry rejects the
# publish with a 400 "missing required annotation" if the published image lacks this label. This is
# the Dockerfile the release actually builds + pushes as mockserver/mockserver:<ver> (docker/local
# is the buildx context in docker.sh), so the label MUST live here — not only in docker/Dockerfile.
# The namespace is DNS-verified against mock-server.com. See docs/operations/mcp-registry-publishing.md.
LABEL io.modelcontextprotocol.server.name="com.mock-server/mockserver"

# Source provenance (OCI convention). revision = the git commit this image was
# BUILT from; created = the RFC-3339 build time. Stamped by the build via
# --build-arg (java-docker-push-snapshot.sh for the snapshot tags), EMPTY on a
# plain `docker build` so the label is absent rather than wrong. Lets any consumer
# (and the perf harness) answer "which commit produced this binary?" from the
# image alone. This is the standard image the release + snapshot push both build.
ARG SOURCE_COMMIT=
ARG BUILD_DATE=
LABEL org.opencontainers.image.revision="${SOURCE_COMMIT}"
LABEL org.opencontainers.image.created="${BUILD_DATE}"

# expose ports.
EXPOSE 1080

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
COPY --from=jarprep /quic/ /usr/lib/

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
# and starts normally rather than failing — the graceful-degradation guarantee this DEFAULT image
# relies on (unlike the AOT variant, this image cannot soft-fail at release time).
# Garbage collector: ZGC is the default, supplied via the JAVA_TOOL_OPTIONS env var below, NOT a
# hard-coded ENTRYPOINT flag — JAVA_TOOL_OPTIONS is prepended, so a hard-coded -XX:+UseZGC would
# collide with any user GC flag ("multiple GCs selected", fatal). An ENV default is replaced wholesale
# when the user sets JAVA_TOOL_OPTIONS, keeping the collector overridable on this shell-less image.
# Switch to G1 with -e JAVA_TOOL_OPTIONS=-XX:+UseG1GC; keep -XX:+UseZGC if you set it for -Xmx etc.
# The AppCDS archive is trained under ZGC to match (collector-independent from JDK 22, so it still
# maps under an override). JDK 26 ZGC is generational — no -XX:+ZGenerational (obsolete post-JDK 23).
ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC"
ENTRYPOINT ["/usr/lib/jvm/temurin25-trimmed/bin/java", "-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED", "-XX:MaxRAMPercentage=45.0", "-XX:SharedArchiveFile=/mockserver.jsa", "-cp", "/mockserver.jar:/mockserver-deps.jar:/libs/*:/extensions/*", "-Dmockserver.propertyFile=/config/mockserver.properties", "org.mockserver.cli.Main"]

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
ENV MOCKSERVER_DASHBOARD_ANALYTICS_DISTRIBUTION=docker-standard

COPY --from=healthcheck /mockserver-healthcheck /mockserver-healthcheck
HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=3 \
  CMD ["/mockserver-healthcheck"]

CMD []
