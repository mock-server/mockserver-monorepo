#!/usr/bin/env bash
#
# Fail the build if a classified slim fat jar does not carry exactly the native
# libraries its architecture needs.
#
# WHY THIS EXISTS
# ---------------
# The slim assemblies keep natives by an "*<arch>.so" SUFFIX include. Maven's
# assembly plugin does NOT fail when an <include> matches nothing, so a future
# netty rename - or an edit narrowing that pattern - would silently produce a slim
# jar with NO native libraries. Nothing would look wrong: the jar builds, installs
# and starts. epoll would quietly fall back to NIO and tcnative to the JDK SSL
# provider - performance and behaviour regressions that announce themselves nowhere.
#
# docker/Dockerfile's jarprep stage already guards its equivalent trim with a
# grep-or-exit. This is the same guard for the PUBLISHED artifacts, so the two
# cannot drift apart.
#
# The suffix form is deliberate and must never be narrowed to "linux_<arch>": the
# epoll library is libnetty_transport_native_epoll_<arch>.so with NO "linux_" in
# its name, so a linux_-anchored filter drops precisely the one that matters most.
#
# WHY TWO NATIVES, NOT THREE
# --------------------------
# The slim jars carry exactly the tcnative and epoll ELF .so for their arch. The
# QUIC/HTTP-3 native (libnetty_quiche42_linux_<arch>.so) is deliberately NOT here:
# the slim descriptors exclude "META-INF/native/*quiche*" because HTTP/3 ships in
# the separate `-jar-with-dependencies-http3` classifier. Its absence is therefore
# an invariant to ENFORCE, not a regression to catch - if quiche reappears in a
# slim jar the artifact silently regains ~11 MiB that the split exists to remove.
# assert-http3-jar-natives.sh guards the other side: that the http3 classifier
# really does carry the QUIC natives.
set -euo pipefail

JAR="${1:?usage: assert-slim-jar-natives.sh <jar> <arch>}"
ARCH="${2:?usage: assert-slim-jar-natives.sh <jar> <arch>}"

[ -f "$JAR" ] || { echo "ERROR: slim jar not found: $JAR" >&2; exit 1; }

# Read the natives WITHOUT letting the pipeline's exit status kill the script. `unzip -Z1` returns
# non-zero when nothing matches, and `grep -v` returns 1 on empty input, so under `set -euo pipefail`
# the plain assignment aborted here with status 1 and printed NOTHING AT ALL. That is exactly what
# happened in release build #76: a guard whose whole purpose is to explain a packaging mistake failed
# without saying anything, and the release had to be diagnosed from the absence of output. Every exit
# from this script must now carry a reason.
NATIVES=$(unzip -Z1 "$JAR" 'META-INF/native/*' 2>/dev/null | grep -v '/$' | sed 's#.*/##' | sort || true)
COUNT=$(printf '%s\n' "$NATIVES" | grep -c . || true)
if [ "$COUNT" -eq 0 ]; then
  echo "ERROR: $(basename "$JAR") contains NO META-INF/native/ entries at all." >&2
  echo "  Expected exactly 2 for ${ARCH}: tcnative and transport_native_epoll." >&2
  echo "  The jar was built but carries no natives, so epoll would degrade to NIO and tcnative to" >&2
  echo "  JDK SSL - silently - if this artifact shipped. Check that the assembly descriptor's" >&2
  echo "  add-back set still matches '*${ARCH}.so', and that the netty native dependencies" >&2
  echo "  resolved in this build." >&2
  echo "  jar entry count: $(unzip -Z1 "$JAR" 2>/dev/null | wc -l | tr -d ' ')" >&2
  exit 1
fi

# HTTP/3 ships in its own classifier - a quiche native here means the slim
# descriptor's exclude was dropped and the artifact has silently regrown.
if printf '%s\n' "$NATIVES" | grep -q 'quiche'; then
  echo "ERROR: $(basename "$JAR") carries a QUIC native, which belongs only in the" >&2
  echo "  -jar-with-dependencies-http3 classifier:" >&2
  printf '  %s\n' "$NATIVES" >&2
  echo "  Restore <exclude>META-INF/native/*quiche*</exclude> in the slim descriptor." >&2
  exit 1
fi

if [ "$COUNT" -ne 2 ]; then
  echo "ERROR: $(basename "$JAR") carries $COUNT native(s), expected 2" >&2
  echo "  found: ${NATIVES:-<none>}" >&2
  echo "  A slim jar with the wrong natives still starts and serves, but epoll degrades" >&2
  echo "  to NIO and tcnative to JDK SSL - silently. Check the <include> in the descriptor." >&2
  exit 1
fi

for required in tcnative transport_native_epoll; do
  printf '%s\n' "$NATIVES" | grep -q "${required}.*${ARCH}\.so$" || {
    echo "ERROR: $(basename "$JAR") is missing the ${required} native for ${ARCH}" >&2
    echo "  found: $NATIVES" >&2
    exit 1
  }
done

FOREIGN=$(printf '%s\n' "$NATIVES" | grep -v "${ARCH}\.so$" || true)
if [ -n "$FOREIGN" ]; then
  echo "ERROR: $(basename "$JAR") carries foreign native(s):" >&2
  printf '  %s\n' $FOREIGN >&2
  exit 1
fi

echo "OK: $(basename "$JAR") carries exactly the ${ARCH} natives:"
printf '  %s\n' $NATIVES
