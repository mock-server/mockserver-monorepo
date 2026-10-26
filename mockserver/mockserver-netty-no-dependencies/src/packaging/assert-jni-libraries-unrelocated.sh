#!/usr/bin/env bash
#
# Fail the build unless a shaded jar packages its JNI libraries the way they can load:
#  - zstd-jni, snappy-java and lz4-java stay UNRELOCATED, because their natives export entry points named after
#    the unrelocated classes (Java_com_github_luben_zstd_...). Relocated, the library still loads but every native
#    method throws UnsatisfiedLinkError (issue #2869). No class may be relocated or reference the relocated name.
#  - every Netty native (epoll, tcnative, QUIC) carries the package-mangled name relocated Netty loads
#    (libshaded_1package_netty_...); under Netty's own name it is never found.
# --require <family> also fails when that library is not bundled (zstd, snappy, lz4) or, for netty-natives, when
# no Netty native is bundled. Runtime loading is checked by assert-shaded-natives-load.sh.
#
# usage: assert-jni-libraries-unrelocated.sh <jar> [--require zstd|snappy|lz4|netty-natives]...
set -euo pipefail

JAR="${1:?usage: assert-jni-libraries-unrelocated.sh <jar> [--require <family>]...}"
shift
REQUIRED=" "
while [ $# -gt 0 ]; do
  case "$1" in
    --require) REQUIRED="$REQUIRED${2:?--require needs a value} "; shift 2 ;;
    *) echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
  esac
done

[ -f "$JAR" ] || { echo "ERROR: jar not found: $JAR" >&2; exit 1; }
ENTRIES="$(unzip -Z1 "$JAR")"
[ -n "$ENTRIES" ] || { echo "ERROR: $JAR lists no entries" >&2; exit 1; }
NAME="$(basename "$JAR")"
FAILED=0
err() { echo "ERROR: $NAME $*" >&2; FAILED=1; }

# family|package path|anchor class|linux x86_64 native (regex)|linux aarch64 native (regex)
FAMILIES="zstd|com/github/luben/zstd|Zstd.class|^linux/amd64/libzstd-jni-[^/]*\.so$|^linux/aarch64/libzstd-jni-[^/]*\.so$
snappy|org/xerial/snappy|Snappy.class|^org/xerial/snappy/native/Linux/x86_64/libsnappyjava\.so$|^org/xerial/snappy/native/Linux/aarch64/libsnappyjava\.so$
lz4|net/jpountz|lz4/LZ4Factory.class|^net/jpountz/util/linux/amd64/liblz4-java\.so$|^net/jpountz/util/linux/aarch64/liblz4-java\.so$"

PATTERNS=()
CHECKED=""
while IFS='|' read -r family path anchor x86 arm; do
  PATTERNS+=(-e "shaded_package/$path/" -e "shaded_package.$(tr '/' '.' <<<"$path")")
  if grep -q "^shaded_package/$path/" <<<"$ENTRIES"; then
    err "relocates $family ($(grep -c "^shaded_package/$path/" <<<"$ENTRIES") entries under shaded_package/$path/); its JNI natives only bind to the unrelocated classes"
  fi
  if grep -qxF "$path/$anchor" <<<"$ENTRIES"; then
    grep -qE "$x86" <<<"$ENTRIES" || err "bundles $family without its linux x86_64 native ($x86)"
    grep -qE "$arm" <<<"$ENTRIES" || err "bundles $family without its linux aarch64 native ($arm)"
    CHECKED="$CHECKED $family"
  elif [[ "$REQUIRED" == *" $family "* ]]; then
    err "does not bundle $family ($path/$anchor)"
  fi
done <<<"$FAMILIES"

# A class that still names a relocated JNI package (e.g. netty's ZstdDecoder calling shaded zstd) fails at runtime.
# grep -c reads the whole stream, so unzip never takes a SIGPIPE that pipefail would misread.
REFERENCES="$(unzip -p "$JAR" '*.class' | LC_ALL=C grep -caF "${PATTERNS[@]}" || true)"
if [ "${REFERENCES:-0}" != "0" ]; then
  err "has $REFERENCES class line(s) referencing relocated zstd-jni, snappy-java or lz4-java, which fail at runtime"
fi

NETTY_NATIVES="$(grep -E '^META-INF/native/[^/]+\.(so|jnilib|dylib|dll)$' <<<"$ENTRIES" | grep -E '/(lib)?(shaded_1package_)?netty_' || true)"
if grep -q '^shaded_package/io/netty/util/internal/NativeLibraryLoader\.class$' <<<"$ENTRIES"; then
  UNRENAMED="$(grep -E '^META-INF/native/(lib)?netty_' <<<"$NETTY_NATIVES" || true)"
  if [ -n "$UNRENAMED" ]; then
    err "carries Netty natives under Netty's own name, which relocated Netty never loads:"
    printf '  %s\n' $UNRENAMED >&2
  fi
  CHECKED="$CHECKED netty-natives($(grep -c . <<<"$NETTY_NATIVES" || true))"
fi
if [[ "$REQUIRED" == *" netty-natives "* ]] && [ -z "$NETTY_NATIVES" ]; then
  err "bundles no Netty native library"
fi

if [ "$FAILED" -ne 0 ]; then
  echo "" >&2
  echo "  See the com.github relocation excludes and the META-INF/native relocations in mockserver/pom.xml." >&2
  exit 1
fi
echo "OK: $NAME packages its JNI libraries loadably (checked:${CHECKED:- none bundled})."
