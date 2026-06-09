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
FROM alpine:3.24 as tcnative

RUN apk add --update ca-certificates wget
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

# runtime image https://console.cloud.google.com/gcr/images/distroless/global/java17
FROM gcr.io/distroless/java17:nonroot@sha256:81d09cac6ec47f6a13c61a941557f95079213320f3ddbf9d353de9317669aab5

# maintainer details
MAINTAINER James Bloom "jamesdbloom@gmail.com"

# expose ports.
EXPOSE 1080

# copy in the main fat jar
COPY mockserver-netty-jar-with-dependencies.jar /

# copy in netty-tcnative native library for native TLS (BoringSSL)
COPY --from=tcnative META-INF/native/libnetty_tcnative_linux_*.so /usr/lib/

# copy in the clustered state backend libraries (Infinispan, JGroups, etc.)
# These are placed on the classpath via the /libs/* glob in the ENTRYPOINT.
COPY libs/ /libs/

# don't run MockServer as root
USER nonroot

ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-cp", "/mockserver-netty-jar-with-dependencies.jar:/libs/*", "-Dmockserver.propertyFile=/config/mockserver.properties", "org.mockserver.cli.Main"]

ENV SERVER_PORT 1080

HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=3 \
  CMD ["java", "-cp", "/mockserver-netty-jar-with-dependencies.jar:/libs/*", "org.mockserver.cli.HealthCheck"]

CMD []
