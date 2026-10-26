#
# MockServer HTTP/3 image (mockserver/mockserver:<version>-http3). DEPRECATED alias, kept for one
# release: the standard image now carries the QUIC native in /usr/lib, so over it this adds nothing.
# Over a base without one (docker/Dockerfile) it adds the native for its architecture. Layered FROM
# the standard image digest (BASE_IMAGE, set by the pipelines). See docs/infrastructure/docker.md.
#

ARG BASE_IMAGE=mockserver/mockserver:latest

FROM ${BASE_IMAGE} AS base

FROM --platform=$BUILDPLATFORM alpine:3.24 AS quic

# Optional corporate root CA for the Maven Central fallback below (empty in CI).
COPY ca-bundle.pem /tmp/local-ca.pem
RUN if [ -s /tmp/local-ca.pem ]; then \
      cp /tmp/local-ca.pem /etc/ssl/cert.pem && \
      cp /tmp/local-ca.pem /etc/ssl/certs/ca-certificates.crt; \
    fi

# The base's own /usr/lib QUIC native, if any, comes along (an empty wildcard is allowed beside the jars).
COPY --from=base /mockserver*.jar /usr/lib/lib*netty_quiche*.so /base/
ARG TARGETARCH
ARG NETTY_VERSION=
# Unless the base already has it, install the native under the name the base's Netty asks for: a
# relocated (shaded) Netty prefixes it ('_' -> '_1', '.' -> '_'). /usr/lib is on java.library.path,
# which Netty tries before extracting to /tmp, so it also loads under --read-only. No pipefail
# (DL4006): each pipe's result is checked, and pipefail would fail `grep | head -1` on SIGPIPE.
# hadolint ignore=DL4006
RUN set -eu; \
    case "${TARGETARCH:-}" in \
      amd64) ARCH=x86_64;   ELF_MACHINE=3e ;; \
      arm64) ARCH=aarch_64; ELF_MACHINE=b7 ;; \
      *) echo "ERROR: unsupported or unset TARGETARCH '${TARGETARCH:-}' (build with BuildKit for linux/amd64 or linux/arm64)" >&2; exit 1 ;; \
    esac; \
    LOADER="$(for jar in /base/*.jar; do unzip -l "$jar" | awk '{print $NF}'; done | grep 'io/netty/util/internal/NativeLibraryLoader\.class$' | head -1)"; \
    if [ -z "$LOADER" ]; then echo "ERROR: no Netty NativeLibraryLoader in the base image jars: $(ls /base)" >&2; exit 1; fi; \
    PREFIX="$(echo "${LOADER%io/netty/util/internal/NativeLibraryLoader.class}" | sed 's/_/_1/g; s#/#_#g')"; \
    mkdir /out; \
    INSTALLED=""; for so in /base/lib"${PREFIX}"netty_quiche[0-9]*_linux_"${ARCH}".so; do [ -f "$so" ] && INSTALLED="$so"; done; \
    if [ -n "$INSTALLED" ]; then \
      if [ "$(od -An -c -N4 "$INSTALLED" | tr -d ' ')" != '177ELF' ] || [ "$(od -An -tx1 -j18 -N1 "$INSTALLED" | tr -d ' ')" != "$ELF_MACHINE" ]; then \
        echo "ERROR: the base's /usr/lib/${INSTALLED#/base/} is not a linux $ARCH ELF library" >&2; exit 1; \
      fi; \
      echo "the base already has /usr/lib/${INSTALLED#/base/}; adding nothing"; exit 0; \
    fi; \
    OPTIONAL_PREFIX=""; if [ -n "$PREFIX" ]; then OPTIONAL_PREFIX="(${PREFIX})?"; fi; \
    PATTERN="^META-INF/native/lib${OPTIONAL_PREFIX}netty_quiche[0-9]*_linux_${ARCH}\.so\$"; \
    SOURCE=""; ENTRY=""; \
    for jar in /base/*.jar; do \
      ENTRY="$(unzip -l "$jar" | awk '{print $NF}' | grep -E "$PATTERN" | head -1)"; \
      if [ -n "$ENTRY" ]; then SOURCE="$jar"; echo "using $ENTRY bundled in $jar (exact Netty build)"; break; fi; \
    done; \
    if [ -z "$SOURCE" ]; then \
      VERSION="${NETTY_VERSION:-$(for jar in /base/*.jar; do unzip -p "$jar" META-INF/io.netty.versions.properties 2>/dev/null || true; done | sed -n 's/^netty-[a-z0-9-]*\.version=//p' | head -1)}"; \
      if [ -z "$VERSION" ]; then echo "ERROR: base jars carry neither a QUIC native nor a Netty version; pass --build-arg NETTY_VERSION" >&2; exit 1; fi; \
      URL="https://repo1.maven.org/maven2/io/netty/netty-codec-native-quic/$VERSION/netty-codec-native-quic-$VERSION-linux-$ARCH.jar"; \
      echo "downloading $URL"; \
      for _ in 1 2 3; do wget -q -T 60 -O /tmp/quic.jar "$URL" && wget -q -T 60 -O /tmp/quic.jar.sha256 "$URL.sha256" && break; sleep 5; done; \
      echo "$(cat /tmp/quic.jar.sha256)  /tmp/quic.jar" | sha256sum -c -; \
      SOURCE=/tmp/quic.jar; \
      ENTRY="$(unzip -l "$SOURCE" | awk '{print $NF}' | grep -E "$PATTERN" | head -1)"; \
      if [ -z "$ENTRY" ]; then echo "ERROR: no linux $ARCH QUIC native in $URL" >&2; exit 1; fi; \
    fi; \
    unzip -p "$SOURCE" "$ENTRY" > /tmp/quic.so; \
    if [ "$(od -An -c -N4 /tmp/quic.so | tr -d ' ')" != '177ELF' ] || [ "$(od -An -tx1 -j18 -N1 /tmp/quic.so | tr -d ' ')" != "$ELF_MACHINE" ]; then \
      echo "ERROR: $ENTRY is not a linux $ARCH ELF library" >&2; exit 1; \
    fi; \
    STEM="${ENTRY#META-INF/native/lib}"; STEM="${STEM#"$PREFIX"}"; \
    cp /tmp/quic.so "/out/lib${PREFIX}${STEM}" && chmod 0755 /out/*.so; \
    ls -l /out

FROM ${BASE_IMAGE}

COPY --from=quic /out/ /usr/lib/

ENV MOCKSERVER_DASHBOARD_ANALYTICS_DISTRIBUTION=docker-http3
