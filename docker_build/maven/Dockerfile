#
# MockServer Build Dockerfile
#
# https://github.com/mock-server/mockserver
# http://www.mock-server.com
#

# Audit finding F-DKR-07: base image pinned by digest for reproducibility.
# Refresh via Dependabot (ecosystem: docker).
FROM ubuntu:25.10@sha256:7cc5e35f6567ee8c66d2abb4aab0fd866669e6207c237c3a8f0947a5c7f17092

LABEL maintainer="James Bloom <jamesdbloom@gmail.com>"

ENV DEBIAN_FRONTEND=noninteractive
ENV TZ=Europe/London

ARG MAVEN_VERSION=3.9.16
# Audit finding F-DKR-05: pin gh CLI version + verify SHA256.
# Update both values together when bumping. Source:
# https://github.com/cli/cli/releases/download/v2.83.0/gh_2.83.0_checksums.txt
ARG GH_VERSION=2.83.0
ARG GH_SHA256_AMD64=a5cf6cdb40fc67751adf561126b3314044779cea81ba4f254fbe8e9a69f1676f
ARG GH_SHA256_ARM64=12311e320d4cfdb54d7fa2d58cd1e3a2ccb4c12e1c3abb32b0a2e48bd0f991bf

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential \
        ca-certificates \
        curl \
        git \
        gnupg \
        jq \
        oathtool \
        ruby-dev \
        ruby-bundler \
        unzip \
        openjdk-17-jdk \
    && gem install jekyll --no-document \
    && rm -rf /var/lib/apt/lists/*

RUN ln -s /usr/lib/jvm/java-17-openjdk-$(dpkg --print-architecture) /usr/lib/jvm/java-17-openjdk
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk

COPY corporate-root-ca.pem /tmp/corporate-root-ca.pem
RUN CACERTS=$(find /usr/lib/jvm -name cacerts -path "*/security/cacerts" | head -1) \
    && if [ -s /tmp/corporate-root-ca.pem ]; then \
        cp /tmp/corporate-root-ca.pem /usr/local/share/ca-certificates/corporate-root-ca.crt \
        && update-ca-certificates \
        && keytool -noprompt -import -alias corporate_root_ca \
            -keystore "$CACERTS" \
            -storepass changeit \
            -file /tmp/corporate-root-ca.pem; \
    fi \
    && rm -f /tmp/corporate-root-ca.pem

RUN curl -fsSL https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
        | tar xz -C /opt \
    && ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn

COPY settings.xml /opt/apache-maven-${MAVEN_VERSION}/conf/settings.xml

RUN ARCH=$(dpkg --print-architecture) \
    && curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-$(uname -m).zip" -o /tmp/awscliv2.zip \
    && unzip -q /tmp/awscliv2.zip -d /tmp \
    && /tmp/aws/install \
    && rm -rf /tmp/awscliv2.zip /tmp/aws

RUN ARCH=$(dpkg --print-architecture) \
    && curl -fsSL "https://get.helm.sh/helm-v3.17.3-linux-${ARCH}.tar.gz" | tar xz -C /tmp \
    && mv /tmp/linux-${ARCH}/helm /usr/local/bin/helm \
    && rm -rf /tmp/linux-${ARCH}

# Audit finding F-DKR-05: pinned gh version + SHA256-verified download.
RUN ARCH=$(dpkg --print-architecture) \
    && case "$ARCH" in \
         amd64) EXPECTED_SHA="$GH_SHA256_AMD64" ;; \
         arm64) EXPECTED_SHA="$GH_SHA256_ARM64" ;; \
         *)     echo "Unsupported arch: $ARCH" >&2; exit 1 ;; \
       esac \
    && curl -fsSL "https://github.com/cli/cli/releases/download/v${GH_VERSION}/gh_${GH_VERSION}_linux_${ARCH}.tar.gz" -o /tmp/gh.tar.gz \
    && echo "${EXPECTED_SHA}  /tmp/gh.tar.gz" | sha256sum -c - \
    && tar xz -f /tmp/gh.tar.gz -C /tmp --strip-components=1 \
    && mv /tmp/bin/gh /usr/local/bin/gh \
    && rm -rf /tmp/bin /tmp/share /tmp/gh.tar.gz

# Audit finding F-DKR-04: the previous build cloned mock-server/mockserver and
# ran `mvn install -DskipTests` to warm the local Maven cache. This made the
# CI image depend on the public repo's HEAD, expanding the supply-chain attack
# surface. Removed — the long-running Buildkite agent's persistent `~/.m2`
# cache covers the same use case.
