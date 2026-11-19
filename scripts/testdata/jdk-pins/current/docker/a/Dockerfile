FROM scratch AS jdk-download
ARG TARGETARCH
RUN case "$(uname -m)" in \
      x86_64) STAGE_ARCH=amd64; \
        JDK_URL=https://github.com/adoptium/temurin27-binaries/releases/download/jdk-27.0.1.1%2B4/OpenJDK27U-jdk_x64_linux_hotspot_27.0.1.1_4.tar.gz; \
        JDK_SHA256=005e57ee860978acfeeff1be2b30f770664fe2b941e9b803c61c76992086d107 ;; \
      aarch64|arm64) STAGE_ARCH=arm64; \
        JDK_URL=https://github.com/adoptium/temurin27-binaries/releases/download/jdk-27.0.1.0.2%2B1/OpenJDK27U-jdk_aarch64_linux_hotspot_27.0.1.0.2_1.tar.gz; \
        JDK_SHA256=5ddacf925244a00b60cac828b61ff2f67a68cd32b8dfe5cee1405e333b4589e5 ;; \
      *) exit 1 ;; \
    esac
FROM scratch
