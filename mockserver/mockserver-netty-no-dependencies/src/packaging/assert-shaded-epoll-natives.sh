#!/usr/bin/env bash
#
# Fail the build unless the shaded jar carries its epoll natives under the name relocated
# netty loads: META-INF/native/libshaded_1package_netty_transport_native_epoll_<arch>.so.
# Under netty's own name the .so is never found, Epoll.isAvailable() is false, and every
# Docker image built from this jar silently runs NIO. Only a check of the built jar sees it.
#
set -euo pipefail

JAR="${1:?usage: assert-shaded-epoll-natives.sh <shaded-jar>}"

[ -f "$JAR" ] || { echo "ERROR: shaded jar not found: $JAR" >&2; exit 1; }

ENTRIES="$(unzip -Z1 "$JAR")"

# The mangled prefix only applies while netty is relocated to shaded_package; if the
# relocation changes, the expected native name below must change with it.
LOADER="shaded_package/io/netty/util/internal/NativeLibraryLoader.class"
if ! grep -qxF "$LOADER" <<<"$ENTRIES"; then
  echo "ERROR: $(basename "$JAR") has no $LOADER - the io.netty relocation changed." >&2
  echo "  Update the epoll native relocation in mockserver/pom.xml and this guard to the new prefix." >&2
  exit 1
fi

FAILED=0
for arch in x86_64 aarch_64; do
  expected="META-INF/native/libshaded_1package_netty_transport_native_epoll_${arch}.so"
  if ! grep -qxF "$expected" <<<"$ENTRIES"; then
    echo "ERROR: $(basename "$JAR") is missing $expected" >&2
    FAILED=1
  fi
done

UNRENAMED="$(grep -E '^META-INF/native/libnetty_transport_native_epoll_' <<<"$ENTRIES" || true)"
if [ -n "$UNRENAMED" ]; then
  echo "ERROR: $(basename "$JAR") carries epoll natives under netty's unrelocated name, which relocated netty cannot load:" >&2
  printf '  %s\n' $UNRENAMED >&2
  FAILED=1
fi

if [ "$FAILED" -ne 0 ]; then
  echo "" >&2
  echo "  See the META-INF/native/libnetty_ relocation in mockserver/pom.xml." >&2
  exit 1
fi

echo "OK: $(basename "$JAR") carries epoll natives under the relocated name (x86_64, aarch_64)."
