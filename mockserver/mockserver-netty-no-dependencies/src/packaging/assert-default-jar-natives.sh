#!/usr/bin/env bash
#
# Fail the build if the DEFAULT jar-with-dependencies does not carry exactly the
# cross-platform Netty natives it should - every platform's tcnative and epoll, and
# NO QUIC natives.
#
# WHY THIS EXISTS
# ---------------
# This is the third half of the guard split by the HTTP/3 classifier work.
# assert-slim-jar-natives.sh guards the two single-arch slim jars; assert-http3-jar-natives.sh
# guards that the http3 classifier really carries the QUIC natives. Nothing guarded the
# DEFAULT jar-with-dependencies - the most-downloaded artifact and the one the Dockerfiles'
# `source=download` path fetches - even though it is subject to the same two silent failures:
#
#   * A netty rename, or an edit to the dependency tree, could drop a platform's tcnative or
#     epoll native. Maven's assembly plugin does NOT fail when an unpack set matches nothing,
#     so the jar would still build, install and start; on the affected platform tcnative would
#     fall back to the JDK SSL provider and epoll to NIO - silently.
#   * The default descriptor EXCLUDES "META-INF/native/*quiche*" because HTTP/3 ships in the
#     separate `-jar-with-dependencies-http3` classifier. If that exclude were dropped, the
#     default jar would silently regain the ~11 MiB of QUIC natives the split exists to remove.
#     quiche's ABSENCE is therefore an invariant to ENFORCE, not a regression to catch - the
#     same invariant assert-slim-jar-natives.sh enforces on the slim jars.
#
# The expected set is spelled out rather than counted so that a DROPPED platform is NAMED in
# the failure, not just totalled - exactly as assert-http3-jar-natives.sh spells out the QUIC
# platforms. epoll is Linux-only (two arches); tcnative ships for all five platforms. Note the
# epoll library is libnetty_transport_native_epoll_<arch>.so with NO "linux_" in its name.
set -euo pipefail

JAR="${1:?usage: assert-default-jar-natives.sh <jar>}"

[ -f "$JAR" ] || { echo "ERROR: default jar not found: $JAR" >&2; exit 1; }

NATIVES=$(unzip -Z1 "$JAR" 'META-INF/native/*' 2>/dev/null | grep -v '/$' | sed 's#.*/##' | sort)

# The default jar carries every platform's tcnative plus Linux epoll, and NOTHING else.
EXPECTED="libnetty_tcnative_linux_aarch_64.so
libnetty_tcnative_linux_x86_64.so
libnetty_tcnative_osx_aarch_64.jnilib
libnetty_tcnative_osx_x86_64.jnilib
libnetty_transport_native_epoll_aarch_64.so
libnetty_transport_native_epoll_x86_64.so
netty_tcnative_windows_x86_64.dll"

# HTTP/3 ships in its own classifier - a quiche native here means the default
# descriptor's exclude was dropped and the artifact has silently regrown ~11 MiB.
if grep -q 'quiche' <<<"$NATIVES"; then
  echo "ERROR: $(basename "$JAR") carries a QUIC native, which belongs only in the" >&2
  echo "  -jar-with-dependencies-http3 classifier:" >&2
  printf '  %s\n' "$NATIVES" >&2
  echo "  Restore <exclude>META-INF/native/*quiche*</exclude> in the default descriptor." >&2
  exit 1
fi

MISSING=""
while read -r expected; do
  [ -n "$expected" ] || continue
  grep -qx -- "$expected" <<<"$NATIVES" || MISSING="${MISSING}${expected}"$'\n'
done <<EOF
$EXPECTED
EOF

if [ -n "$MISSING" ]; then
  echo "ERROR: $(basename "$JAR") is missing expected native(s):" >&2
  printf '  %s\n' $MISSING >&2
  echo "  This is the most-downloaded artifact and the one the Dockerfiles fetch. Without a" >&2
  echo "  platform's native it still starts and serves, but on that platform tcnative degrades" >&2
  echo "  to the JDK SSL provider and epoll to NIO - silently. Check the default assembly" >&2
  echo "  descriptor's unpack sets and that netty's native artifacts are still dependencies." >&2
  echo "  found: ${NATIVES:-<none>}" >&2
  exit 1
fi

# Exact set: anything beyond the expected list is an unexpected native (a new platform, a
# renamed artifact, or the quiche exclude re-narrowed) that must be reviewed, not shipped blind.
# A loop, not `comm ... || true`: a comm error would leave UNEXPECTED empty and pass.
# The loop checks membership only, so duplicate basenames are listed separately.
UNEXPECTED="$(uniq -d <<<"$NATIVES")"
[ -z "$UNEXPECTED" ] || UNEXPECTED="${UNEXPECTED}"$'\n'
while read -r native; do
  [ -n "$native" ] || continue
  grep -qxF -- "$native" <<<"$EXPECTED" || UNEXPECTED="${UNEXPECTED}${native}"$'\n'
done <<EOF
$NATIVES
EOF
if [ -n "$UNEXPECTED" ]; then
  echo "ERROR: $(basename "$JAR") carries unexpected native(s):" >&2
  printf '  %s\n' $UNEXPECTED >&2
  echo "  The default jar's native payload is spelled out in assert-default-jar-natives.sh." >&2
  echo "  If netty added a platform, update EXPECTED there; otherwise the descriptor changed." >&2
  exit 1
fi

echo "OK: $(basename "$JAR") carries exactly the cross-platform natives (no QUIC):"
printf '%s\n' "$NATIVES" | sed 's/^/  /'
