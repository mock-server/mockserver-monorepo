#!/usr/bin/env bash
# Build a self-contained, JVM-less MockServer binary bundle.
#
# Produces a directory tree:
#   mockserver-<version>-<os>-<arch>/
#     runtime/            jlink-trimmed Java runtime (no separate JVM needed)
#     lib/mockserver.jar  the shaded MockServer jar (Main-Class: org.mockserver.cli.Main)
#     bin/mockserver      POSIX launcher  (or bin/mockserver.bat for windows)
# ...archived to mockserver-<version>-<os>-<arch>.tar.gz (.zip for windows) plus a .sha256.
#
# WHY jlink (not GraalVM native-image): MockServer loads user-supplied classes at
# runtime (initializationClass, RESPONSE_CLASS_CALLBACK, FORWARD_CLASS_CALLBACK),
# generates TLS certs dynamically via BouncyCastle, and embeds scripting engines —
# all of which break native-image's closed-world assumption. jlink ships the real
# HotSpot JVM, so feature parity is 100%. See docs/code/cli.md and
# docs/plans (binary distribution rationale).
#
# CROSS-BUILD: jlink can target another OS/arch from a single host by using the
# HOST jlink binary with the TARGET JDK's jmods (--jmods). The host and target JDK
# must share the same major version.
#
# Usage:
#   scripts/build-binary-bundle.sh --jar <path> --version <ver> \
#       [--os linux|darwin|windows] [--arch x86_64|aarch64] \
#       [--jlink-home <host JDK>] [--jmods <target jdk>/jmods] \
#       [--modules <comma list>] [--output <dir>] [--no-compress]
#
# Defaults: --os/--arch from the host, --jlink-home/--jmods from the current JDK,
# --output ./target/bundles.

set -euo pipefail

# ---- defaults --------------------------------------------------------------
JAR=""
VERSION=""
OS=""
ARCH=""
JLINK_HOME="${JAVA_HOME:-}"
JMODS=""
OUTPUT="target/bundles"
DO_COMPRESS=true
# Dashboard usage-analytics activation (baked into the launcher as -D system
# properties). Empty by default → launcher is byte-identical to a no-analytics
# build (forks / local runs / dry-run stay INERT). Populated only at release
# time from the mockserver-release/dashboard-analytics secret via the calling
# scripts (build-all-bundles.sh → release/components/binary.sh).
ANALYTICS_ENDPOINT=""
ANALYTICS_KEY=""
ANALYTICS_DISTRIBUTION=""

# Validated runtime module set (see the jlink spike: HTTP + HTTPS/BouncyCastle +
# DNS all confirmed working). java.se is the runtime aggregator; the jdk.* extras
# cover Netty's sun.misc.Unsafe, EC/PKCS11 crypto for dynamic TLS, DNS, zipfs, and
# jdk.management (com.sun.management.ThreadMXBean for the jvm_memory_allocated_bytes
# Prometheus gauge — java.se aggregates only java.* modules, so it is omitted otherwise).
MODULES="java.se,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.naming.dns,jdk.zipfs,jdk.management"

log()  { printf '\033[1;34m[bundle]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[bundle] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# ---- args ------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --jar)        JAR="$2"; shift 2 ;;
    --version)    VERSION="$2"; shift 2 ;;
    --os)         OS="$2"; shift 2 ;;
    --arch)       ARCH="$2"; shift 2 ;;
    --jlink-home) JLINK_HOME="$2"; shift 2 ;;
    --jmods)      JMODS="$2"; shift 2 ;;
    --modules)    MODULES="$2"; shift 2 ;;
    --output)     OUTPUT="$2"; shift 2 ;;
    --analytics-endpoint)     ANALYTICS_ENDPOINT="$2"; shift 2 ;;
    --analytics-key)          ANALYTICS_KEY="$2"; shift 2 ;;
    --analytics-distribution) ANALYTICS_DISTRIBUTION="$2"; shift 2 ;;
    --no-compress) DO_COMPRESS=false; shift ;;
    -h|--help)    grep '^# ' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "Unknown argument: $1" ;;
  esac
done

[[ -n "$JAR" ]]     || die "--jar is required"
[[ -f "$JAR" ]]     || die "jar not found: $JAR"
[[ -n "$VERSION" ]] || die "--version is required"
[[ -n "$OUTPUT" ]]  || die "--output must not be empty"
# Guard the rm -rf below: reject path separators / traversal in caller-controlled names.
[[ "$VERSION" =~ ^[A-Za-z0-9._-]+$ ]] || die "--version has invalid characters: $VERSION"
[[ "$OUTPUT" != *..* ]] || die "--output must not contain '..'"

# ---- resolve host JDK / jlink ---------------------------------------------
if [[ -z "$JLINK_HOME" ]]; then
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    JLINK_HOME="$(/usr/libexec/java_home)"
  else
    # derive from `java` on PATH
    JLINK_HOME="$(dirname "$(dirname "$(command -v java)")")"
  fi
fi
JLINK="$JLINK_HOME/bin/jlink"
[[ -x "$JLINK" ]] || die "jlink not found at $JLINK (set --jlink-home)"
[[ -n "$JMODS" ]] || JMODS="$JLINK_HOME/jmods"
[[ -d "$JMODS" ]] || die "jmods dir not found: $JMODS (set --jmods for cross-build)"

# ---- resolve target os/arch (default: host) -------------------------------
if [[ -z "$OS" ]]; then
  case "$(uname -s)" in
    Linux)  OS="linux" ;;
    Darwin) OS="darwin" ;;
    MINGW*|MSYS*|CYGWIN*) OS="windows" ;;
    *) die "cannot detect OS; pass --os" ;;
  esac
fi
if [[ -z "$ARCH" ]]; then
  case "$(uname -m)" in
    x86_64|amd64)  ARCH="x86_64" ;;
    arm64|aarch64) ARCH="aarch64" ;;
    *) die "cannot detect arch; pass --arch" ;;
  esac
fi

# windows archives require the `zip` tool — fail loudly rather than silently emit a .tar.gz
if [[ "$OS" == "windows" ]]; then
  command -v zip >/dev/null 2>&1 || die "'zip' is required to build windows bundles"
fi

# cross-build sanity: the target jmods platform must match --os/--arch, else the bundle is
# mislabelled (e.g. a macOS runtime inside a *-linux-*.tar.gz). Warn loudly.
REL="$JMODS/../release"
if [[ -f "$REL" ]]; then
  jos=$(grep -E '^OS_NAME=' "$REL" | cut -d= -f2 | tr -d '"\r' | tr 'A-Z' 'a-z' || true)
  jarch=$(grep -E '^OS_ARCH=' "$REL" | cut -d= -f2 | tr -d '"\r' | tr 'A-Z' 'a-z' || true)
  case "$jos" in *darwin*|*mac*) jos=darwin ;; *linux*) jos=linux ;; *windows*) jos=windows ;; esac
  case "$jarch" in amd64|x86_64) jarch=x86_64 ;; arm64|aarch64) jarch=aarch64 ;; esac
  if [[ -n "$jos" && -n "$jarch" && ( "$jos" != "$OS" || "$jarch" != "$ARCH" ) ]]; then
    log "WARNING: jmods platform is ${jos}-${jarch} but bundle is labelled ${OS}-${ARCH} — pass --jmods <target JDK>/jmods for a real cross-build"
  fi
fi

BUNDLE="mockserver-${VERSION}-${OS}-${ARCH}"
STAGE="$OUTPUT/$BUNDLE"
log "Building $BUNDLE (jlink-home=$JLINK_HOME, jmods=$JMODS)"

# ---- jlink trimmed runtime -------------------------------------------------
rm -rf "$STAGE"
mkdir -p "$STAGE/lib" "$STAGE/bin"
JLINK_ARGS=(--module-path "$JMODS" --add-modules "$MODULES"
            --no-header-files --no-man-pages --strip-debug
            --output "$STAGE/runtime")
if $DO_COMPRESS; then
  # JDK 17–20 (and Zulu 21) accept --compress=2; Oracle JDK 21+ requires --compress=zip-N.
  # Capture first: under pipefail `jlink --help | grep -q` fails when grep exits before
  # jlink finishes writing, which would silently pick the wrong --compress form.
  JLINK_HELP="$("$JLINK" --help 2>&1 || true)"
  if grep -q 'compress=<0|1|2>' <<<"$JLINK_HELP"; then
    JLINK_ARGS+=(--compress=2)
  else
    JLINK_ARGS+=(--compress=zip-6)
  fi
fi
"$JLINK" "${JLINK_ARGS[@]}"
log "runtime: $(du -sh "$STAGE/runtime" | cut -f1)"

# ---- app jar + launcher ----------------------------------------------------
cp "$JAR" "$STAGE/lib/mockserver.jar"

# Build the dashboard-analytics -D system-property prefix that gets BAKED into
# the generated launcher. Only when BOTH endpoint and key are non-empty (i.e.
# the release scripts loaded them from the secret) do we inject anything; in
# forks / local runs / dry-run they are empty, so this stays "" and the launcher
# line is byte-identical to a no-analytics build (analytics INERT). The literal
# values are expanded HERE (bundle-build time) and substituted into the launcher
# as fixed text — they are NOT left as shell variables in the generated script.
ANALYTICS_DPROPS=""        # POSIX form: -Dk=v -Dk=v -Dk=v
ANALYTICS_DPROPS_BAT=""    # .bat form (identical flags; assembled separately for clarity)
if [[ -n "$ANALYTICS_ENDPOINT" && -n "$ANALYTICS_KEY" ]]; then
  ANALYTICS_DPROPS="-Dmockserver.dashboardAnalyticsEndpoint=${ANALYTICS_ENDPOINT} -Dmockserver.dashboardAnalyticsKey=${ANALYTICS_KEY} -Dmockserver.dashboardAnalyticsDistribution=${ANALYTICS_DISTRIBUTION}"
  ANALYTICS_DPROPS_BAT="$ANALYTICS_DPROPS"
fi

# JVM flags placed AFTER MOCKSERVER_JAVA_OPTS so it and JAVA_TOOL_OPTIONS cannot undo them (the JVM keeps the
# last value), as the Docker ENTRYPOINT does. -XX:-UseBASE64Intrinsics works around CVE-2026-114938 in
# the bundled Temurin runtime. mockserver-node reads these back from the script (bakedJavaOptions).
TRAILING_JAVA_OPTS="-XX:-UseBASE64Intrinsics"

if [[ "$OS" == "windows" ]]; then
  # Header is literal (quoted heredoc); the final java line is written separately
  # so $ANALYTICS_DPROPS_BAT EXPANDS (baked-in values) while %MOCKSERVER_JAVA_OPTS%
  # and %* stay literal in the generated .bat.
  cat > "$STAGE/bin/mockserver.bat" <<'BAT'
@echo off
setlocal
set "DIR=%~dp0.."
rem Tell MockServer it was started via this launcher so usage/help text reads
rem "mockserver ..." instead of the "java -jar <jar>" form.
if not defined MOCKSERVER_LAUNCHER set "MOCKSERVER_LAUNCHER=%~n0"
BAT
  if [[ -n "$ANALYTICS_DPROPS_BAT" ]]; then
    printf '"%%DIR%%\\runtime\\bin\\java.exe" %s %%MOCKSERVER_JAVA_OPTS%% %s -jar "%%DIR%%\\lib\\mockserver.jar" %%*\n' \
      "$ANALYTICS_DPROPS_BAT" "$TRAILING_JAVA_OPTS" >> "$STAGE/bin/mockserver.bat"
  else
    printf '"%%DIR%%\\runtime\\bin\\java.exe" %%MOCKSERVER_JAVA_OPTS%% %s -jar "%%DIR%%\\lib\\mockserver.jar" %%*\n' \
      "$TRAILING_JAVA_OPTS" >> "$STAGE/bin/mockserver.bat"
  fi
else
  # Header is literal (quoted heredoc); the final exec line is written separately
  # so $ANALYTICS_DPROPS EXPANDS (baked-in values) while ${MOCKSERVER_JAVA_OPTS:-}
  # and "$@" stay literal in the generated script.
  cat > "$STAGE/bin/mockserver" <<'SH'
#!/bin/sh
# MockServer self-contained launcher — runs on the bundled Java runtime,
# so no separate JVM installation is required. Override JVM options via
# the MOCKSERVER_JAVA_OPTS environment variable (except -XX:-UseBASE64Intrinsics,
# the CVE-2026-114938 workaround, which is applied after it).
DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
# Tell MockServer it was started via this launcher so usage/help text reads
# "mockserver ..." instead of the "java -jar <jar>" form.
export MOCKSERVER_LAUNCHER="${MOCKSERVER_LAUNCHER:-$(basename -- "$0")}"
SH
  if [[ -n "$ANALYTICS_DPROPS" ]]; then
    printf 'exec "$DIR/runtime/bin/java" %s ${MOCKSERVER_JAVA_OPTS:-} %s -jar "$DIR/lib/mockserver.jar" "$@"\n' \
      "$ANALYTICS_DPROPS" "$TRAILING_JAVA_OPTS" >> "$STAGE/bin/mockserver"
  else
    printf 'exec "$DIR/runtime/bin/java" ${MOCKSERVER_JAVA_OPTS:-} %s -jar "$DIR/lib/mockserver.jar" "$@"\n' \
      "$TRAILING_JAVA_OPTS" >> "$STAGE/bin/mockserver"
  fi
  chmod +x "$STAGE/bin/mockserver"
fi

# ---- archive + checksum ----------------------------------------------------
sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi; }

if [[ "$OS" == "windows" ]]; then
  ARCHIVE="$OUTPUT/$BUNDLE.zip"
  rm -f "$ARCHIVE"
  ( cd "$OUTPUT" && zip -qr "$BUNDLE.zip" "$BUNDLE" )
else
  ARCHIVE="$OUTPUT/$BUNDLE.tar.gz"
  tar -czf "$ARCHIVE" -C "$OUTPUT" "$BUNDLE"
fi
( cd "$OUTPUT" && sha256 "$(basename "$ARCHIVE")" > "$(basename "$ARCHIVE").sha256" )

log "Bundle:   $ARCHIVE ($(du -sh "$ARCHIVE" | cut -f1))"
log "Checksum: $ARCHIVE.sha256"
echo "$ARCHIVE"
