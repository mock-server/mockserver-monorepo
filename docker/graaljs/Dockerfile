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
FROM alpine:3.24 as download

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
ARG VERSION=RELEASE
ARG REPOSITORY_URL=https://repo1.maven.org/maven2/org/mock-server/mockserver-netty/${VERSION}/mockserver-netty-${VERSION}-jar-with-dependencies.jar
# Audit finding F-DKR-06: SHA256-verify all Maven Central downloads.
RUN wget --max-redirect=10 -O mockserver-netty-jar-with-dependencies.jar "$REPOSITORY_URL" \
    && wget --max-redirect=10 -qO- "${REPOSITORY_URL}.sha256" > /tmp/expected.sha256 \
    && echo "$(cat /tmp/expected.sha256)  mockserver-netty-jar-with-dependencies.jar" | sha256sum -c -
ARG TARGETARCH=amd64
ARG NETTY_TCNATIVE=2.0.77.Final
RUN if [ "$TARGETARCH" = "amd64" ]; \
    then TCNATIVE_URL="https://repo1.maven.org/maven2/io/netty/netty-tcnative-boringssl-static/$NETTY_TCNATIVE/netty-tcnative-boringssl-static-$NETTY_TCNATIVE-linux-x86_64.jar"; \
    else TCNATIVE_URL="https://repo1.maven.org/maven2/io/netty/netty-tcnative-boringssl-static/$NETTY_TCNATIVE/netty-tcnative-boringssl-static-$NETTY_TCNATIVE-linux-aarch_64.jar"; \
    fi \
    && wget -O netty-tcnative-boringssl-static.jar "$TCNATIVE_URL" \
    && wget -qO- "${TCNATIVE_URL}.sha256" > /tmp/expected-tcnative.sha256 \
    && echo "$(cat /tmp/expected-tcnative.sha256)  netty-tcnative-boringssl-static.jar" | sha256sum -c -
RUN unzip netty-tcnative-boringssl-static.jar

# build image — copies JAR from context, downloads tcnative from Maven Central
FROM alpine:3.24 as copy

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
COPY mockserver-netty-jar-with-dependencies.jar .
ARG TARGETARCH=amd64
ARG NETTY_TCNATIVE=2.0.77.Final
# Audit finding F-DKR-06: SHA256-verify tcnative download from Maven Central.
RUN if [ "$TARGETARCH" = "amd64" ]; \
    then TCNATIVE_URL="https://repo1.maven.org/maven2/io/netty/netty-tcnative-boringssl-static/$NETTY_TCNATIVE/netty-tcnative-boringssl-static-$NETTY_TCNATIVE-linux-x86_64.jar"; \
    else TCNATIVE_URL="https://repo1.maven.org/maven2/io/netty/netty-tcnative-boringssl-static/$NETTY_TCNATIVE/netty-tcnative-boringssl-static-$NETTY_TCNATIVE-linux-aarch_64.jar"; \
    fi \
    && wget -O netty-tcnative-boringssl-static.jar "$TCNATIVE_URL" \
    && wget -qO- "${TCNATIVE_URL}.sha256" > /tmp/expected-tcnative.sha256 \
    && echo "$(cat /tmp/expected-tcnative.sha256)  netty-tcnative-boringssl-static.jar" | sha256sum -c -
RUN unzip netty-tcnative-boringssl-static.jar

# download GraalJS JARs
FROM alpine:3.24 as graaljs

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

FROM ${source} as intermediate

# runtime image https://console.cloud.google.com/gcr/images/distroless/global/java17
FROM gcr.io/distroless/java17:nonroot@sha256:81d09cac6ec47f6a13c61a941557f95079213320f3ddbf9d353de9317669aab5

# maintainer details
MAINTAINER James Bloom "jamesdbloom@gmail.com"

# expose ports.
EXPOSE 1080

# copy in jar
COPY --from=intermediate mockserver-netty-jar-with-dependencies.jar /
COPY --from=intermediate META-INF/native/libnetty_tcnative_linux_*.so /usr/lib/

# copy GraalJS JARs into /libs
COPY --from=graaljs /graaljs/*.jar /libs/

# don't run MockServer as root
USER nonroot

ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-cp", "/mockserver-netty-jar-with-dependencies.jar:/libs/*", "-Dmockserver.propertyFile=/config/mockserver.properties", "org.mockserver.cli.Main"]

ENV SERVER_PORT 1080

HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=3 \
  CMD ["java", "-cp", "/mockserver-netty-jar-with-dependencies.jar", "org.mockserver.cli.HealthCheck"]

CMD []
