#!/bin/sh
# Prepares the MockServer server jar for a linux image: keeps only the natives this stage's arch can
# load (ELF-checked), then splits it into own.jar (org/mockserver/** + manifest) and deps.jar, both
# STORED and byte-reproducible. Canonical copy: docker/jarprep/; each image context carries a
# byte-identical copy (docker-validate-sync.sh). Details: docs/infrastructure/docker.md.
# usage: mockserver-jarprep.sh <server-jar (assembly or shaded)> <TARGETARCH or ""> <out-dir>
set -eu
fail() { echo "ERROR: $*" >&2; exit 1; }

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <server-jar> <TARGETARCH or \"\"> <out-dir>" >&2
  exit 2
fi
case "$1" in /*) JAR="$1" ;; *) JAR="$PWD/$1" ;; esac
mkdir -p "$3"
OUT="$(cd "$3" && pwd)"

# The arch this stage RUNS on is the image's arch (buildx emulates foreign platforms), so it is the
# authority. A non-empty TARGETARCH must agree: `ARG TARGETARCH=amd64` (a default) silently beats the
# value BuildKit injects, even on the arm64 leg of a multi-arch build, and would ship x86_64 natives.
case "$(uname -m)" in
  x86_64) STAGE_ARCH=amd64; ELF_MACHINE=62 ;;
  aarch64|arm64) STAGE_ARCH=arm64; ELF_MACHINE=183 ;;
  *) echo "ERROR: unsupported build-stage arch '$(uname -m)'" >&2; exit 1 ;;
esac
if [ -n "$2" ] && [ "$2" != "$STAGE_ARCH" ]; then
  echo "ERROR: TARGETARCH='$2' but this stage runs on $STAGE_ARCH - declare 'ARG TARGETARCH' without a default" >&2
  exit 1
fi
TARGETARCH="$STAGE_ARCH"

case "$TARGETARCH" in
  amd64)
    ARCH=x86_64
    KEEP_ZSTD="linux/amd64/"
    KEEP_SNAPPY="org/xerial/snappy/native/Linux/x86_64/"
    JNA_ARCH="linux-x86-64"
    KEEP_LZ4="net/jpountz/util/linux/amd64/"
    ;;
  arm64)
    ARCH=aarch_64
    KEEP_ZSTD="linux/aarch64/"
    KEEP_SNAPPY="org/xerial/snappy/native/Linux/aarch64/"
    JNA_ARCH="linux-aarch64"
    KEEP_LZ4="net/jpountz/util/linux/aarch64/"
    ;;
esac

# The shaded (mockserver-netty-docker) jar relocates dependencies under shaded_package/, renames the
# epoll .so to the name relocated netty loads, and strips the tcnative natives; the assembly jar does
# none of this. Both carry JNA unrelocated: libjnidispatch cannot bind to relocated JNA classes, so the
# mockserver-netty-no-dependencies library jar (which relocates it) is refused.
if unzip -Z1 "$JAR" 'shaded_package/io/netty/channel/epoll/Epoll.class' >/dev/null 2>&1; then
  if unzip -Z1 "$JAR" 'shaded_package/com/sun/jna/Native.class' >/dev/null 2>&1; then
    fail "$JAR relocates JNA (the mockserver-netty-no-dependencies library jar), so SO_ORIGINAL_DST and eBPF lookups cannot load - stage the mockserver-netty-docker jar"
  fi
  FLAVOUR=shaded
  EPOLL_SO="libshaded_1package_netty_transport_native_epoll_$ARCH.so"
elif unzip -Z1 "$JAR" 'io/netty/channel/epoll/Epoll.class' >/dev/null 2>&1; then
  FLAVOUR=assembly
  EPOLL_SO="libnetty_transport_native_epoll_$ARCH.so"
else
  echo "ERROR: $JAR is neither the assembly nor the shaded MockServer server jar" >&2
  exit 1
fi
JNA_ROOT="com/sun/jna/"
KEEP_JNA="${JNA_ROOT}${JNA_ARCH}/"
echo "jarprep: $JAR is the $FLAVOUR jar; keeping linux $TARGETARCH natives only"

WORK="$(mktemp -d)"
mkdir "$WORK/ex"
(cd "$WORK/ex" && unzip -q "$JAR")
cd "$WORK/ex"
find . -type f | sed 's#^\./##' | LC_ALL=C sort > "$WORK/all.txt"

# netty's natives are kept by the "<arch>.so" SUFFIX, never by "linux_<arch>": the epoll .so is
# libnetty_transport_native_epoll_<arch>.so (no "linux_"), so a "linux_<arch>" filter would
# silently drop it and degrade epoll to NIO. The other four families keep a directory prefix.
is_native() {
  case "$1" in *.so|*.dll|*.dylib|*.jnilib) return 0 ;; *) return 1 ;; esac
}
native_outside() {
  is_native "$1" || return 1
  case "$1" in "$2"*) return 1 ;; *) return 0 ;; esac
}
# The assembly jar's QUIC natives are dropped outright (jars up to 8.0.0 still bundle them). The shaded
# jar keeps this arch's, which the standard image (docker/local) also copies into /usr/lib.
while IFS= read -r e; do
  drop=false
  case "$e" in
    META-INF/native/*quiche*)
      case "$e" in *"$ARCH".so) [ "$FLAVOUR" = assembly ] && drop=true ;; *) drop=true ;; esac ;;
    META-INF/native/*) case "$e" in *"$ARCH".so) ;; *) drop=true ;; esac ;;
    *libzstd-jni*) native_outside "$e" "$KEEP_ZSTD" && drop=true ;;
    org/xerial/snappy/native/*) native_outside "$e" "$KEEP_SNAPPY" && drop=true ;;
    "$JNA_ROOT"*) native_outside "$e" "$KEEP_JNA" && drop=true ;;
    net/jpountz/util/*) native_outside "$e" "$KEEP_LZ4" && drop=true ;;
  esac
  if [ "$drop" = true ]; then
    echo "trim $e"
    rm -f "$e"
  fi
done < "$WORK/all.txt"

# Every surviving .so must be an ELF library for THIS arch: a wrong keep rule, or a jar whose natives
# are mislabelled, would otherwise ship binaries the container cannot load.
find . -type f -name '*.so' > "$WORK/natives.txt"
if [ ! -s "$WORK/natives.txt" ]; then
  echo "ERROR: no .so native survived the trim" >&2
  exit 1
fi
while IFS= read -r so; do
  ACTUAL="$(od -An -tu2 -j18 -N2 "$so")"
  if [ "${ACTUAL##* }" != "$ELF_MACHINE" ]; then
    echo "ERROR: $so ELF machine ${ACTUAL##* }, expected $ELF_MACHINE for $ARCH" >&2
    exit 1
  fi
done < "$WORK/natives.txt"
echo "ELF machine $ELF_MACHINE confirmed for $(wc -l < "$WORK/natives.txt") kept .so file(s)"

# own.jar's manifest gains "Class-Path: mockserver-deps.jar" (resolved next to own.jar), so
# `java -jar /mockserver.jar` still runs with the dependencies after the split.
[ -f META-INF/MANIFEST.MF ] || fail "the jar has no META-INF/MANIFEST.MF"
grep -q '^Main-Class: ' META-INF/MANIFEST.MF || fail "the manifest has no Main-Class, so java -jar cannot run own.jar"
if grep -qi '^Class-Path:' META-INF/MANIFEST.MF; then
  fail "the manifest already has a Class-Path attribute"
fi
# It goes at the end of the MAIN section (before the first blank line): after a "Name:" section it
# would be a per-entry attribute, which the JVM ignores for the classpath.
tr -d '\r' < META-INF/MANIFEST.MF > "$WORK/manifest.txt"
awk '!done && $0 == "" { printf "Class-Path: mockserver-deps.jar\r\n"; done = 1 }
     { printf "%s\r\n", $0 }
     END { if (!done) printf "Class-Path: mockserver-deps.jar\r\n\r\n" }' \
  "$WORK/manifest.txt" > META-INF/MANIFEST.MF

find . -exec touch -h -t 200001010000.00 {} +
find . -type f | sed 's#^\./##' | LC_ALL=C sort > "$WORK/kept.txt"
# MANIFEST.MF goes FIRST in own.jar: a streaming JarInputStream only looks at the first entries,
# and the shaded jar's manifest carries the MockServer version, so it must not sit in deps.jar.
grep    '^org/mockserver/' "$WORK/kept.txt"                                    > "$WORK/own.txt" || true
grep -v '^org/mockserver/' "$WORK/kept.txt" | grep -vx 'META-INF/MANIFEST.MF' > "$WORK/deps.txt" || true
rm -f "$OUT/own.jar" "$OUT/deps.jar"
zip -q -0 -X "$OUT/own.jar" META-INF/MANIFEST.MF
zip -q -0 -X -@ "$OUT/own.jar"  < "$WORK/own.txt"
zip -q -0 -X -@ "$OUT/deps.jar" < "$WORK/deps.txt"
touch -t 200001010000.00 "$OUT/deps.jar" "$OUT/own.jar"
cd /

OWN_LIST="$(unzip -Z1 "$OUT/own.jar")"
DEPS_LIST="$(unzip -Z1 "$OUT/deps.jar")"
echo "own.jar: $(echo "$OWN_LIST" | wc -l) entries; deps.jar: $(echo "$DEPS_LIST" | wc -l) entries"
echo "$OWN_LIST" | grep -qx "org/mockserver/cli/Main.class" || fail "own.jar is missing MockServer's own classes"
LEAK="$(echo "$OWN_LIST" | grep -v '^org/mockserver/' | grep -vx 'META-INF/MANIFEST.MF' || true)"
[ -z "$LEAK" ] || fail "own.jar contains non-org/mockserver entries - the split predicate is wrong: $LEAK"
if echo "$DEPS_LIST" | grep -q '^org/mockserver/'; then
  fail "deps.jar contains org/mockserver entries - the split predicate is wrong"
fi
[ "$(echo "$OWN_LIST" | wc -l)" -eq "$(( $(wc -l < "$WORK/own.txt") + 1 ))" ] || fail "own.jar entry count differs from the split list"
[ "$(echo "$DEPS_LIST" | wc -l)" -eq "$(wc -l < "$WORK/deps.txt")" ] || fail "deps.jar entry count differs from the split list"

# One assertion PER FAMILY: a combined check passes while any one family survives, which is how a
# broken filter for another would go unnoticed and silently give back the size win.
echo "kept natives:"; echo "$DEPS_LIST" | grep -E '\.(so|dll|dylib|jnilib)$' || true
echo "$DEPS_LIST" | grep -qx "META-INF/native/$EPOLL_SO" || fail "no netty epoll native $EPOLL_SO survived the trim"
echo "$DEPS_LIST" | grep -q "^${KEEP_ZSTD}libzstd-jni" || fail "no zstd native for $TARGETARCH survived the trim"
# zstd-jni's natives bind only to its unrelocated classes (issue #2869), so a relocated copy fails every call.
echo "$DEPS_LIST" | grep -qx "com/github/luben/zstd/Zstd.class" || fail "no unrelocated zstd-jni (com/github/luben/zstd/Zstd.class) in deps.jar"
if echo "$DEPS_LIST" | grep -q '^shaded_package/com/github/luben/'; then
  fail "deps.jar carries relocated zstd-jni, which cannot bind its native"
fi
echo "$DEPS_LIST" | grep -q "^${KEEP_SNAPPY}libsnappyjava" || fail "no snappy native for $TARGETARCH survived the trim"
echo "$DEPS_LIST" | grep -q "^${KEEP_JNA}libjnidispatch" || fail "no JNA native for $TARGETARCH survived the trim"
echo "$DEPS_LIST" | grep -qx "${JNA_ROOT}Native.class" || fail "no unrelocated JNA (${JNA_ROOT}Native.class) in deps.jar"
if echo "$DEPS_LIST" | grep -q '^shaded_package/com/sun/jna/'; then
  fail "deps.jar carries relocated JNA, which cannot load its native"
fi
echo "$DEPS_LIST" | grep -q "^${KEEP_LZ4}liblz4-java" || fail "no lz4 native for $TARGETARCH survived the trim"
unzip -p "$OUT/own.jar" META-INF/MANIFEST.MF | tr -d '\r' \
  | awk '$0 == "" { exit } $0 == "Class-Path: mockserver-deps.jar" { found = 1 } END { exit !found }' \
  || fail "own.jar's manifest lacks Class-Path: mockserver-deps.jar in its main section"
if [ "$FLAVOUR" = assembly ]; then
  echo "$DEPS_LIST" | grep -qx "META-INF/native/libnetty_tcnative_linux_$ARCH.so" || fail "no netty tcnative $ARCH native survived the trim"
fi
rm -rf "$WORK"
