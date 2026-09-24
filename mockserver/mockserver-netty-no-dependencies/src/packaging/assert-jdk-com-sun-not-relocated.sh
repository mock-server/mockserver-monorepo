#!/usr/bin/env bash
#
# Fail the build if the shade plugin relocated a JDK-owned com.sun.* package.
#
# Relocation exists to bundle third-party com.sun libraries (JNA, istack, txw2).
# Applied to a JDK package it rewrites the reference to shaded_package.*, which
# never exists at runtime: an instanceof silently turns false, a class-name string
# compare never matches, and the feature dies with no error. A unit test cannot
# catch it - it only manifests after relocation - so this runs over the built jar.
#
set -euo pipefail

JAR="${1:?usage: assert-jdk-com-sun-not-relocated.sh <shaded-jar>}"

[ -f "$JAR" ] || { echo "ERROR: shaded jar not found: $JAR" >&2; exit 1; }

# JDK-owned subpackages that must never appear relocated. Matched in both the
# bytecode ('/' separated, e.g. a constant-pool class ref) and string-constant
# ('.' separated, e.g. "com.sun.nio.sctp.SctpChannel") forms.
JDK_SUBPACKAGES="management net/httpserver nio/sctp"

# Build the forbidden tokens: each JDK subpackage in both its bytecode ('/') and
# string-constant ('.') form.
PATTERNS=()
for sub in $JDK_SUBPACKAGES; do
  PATTERNS+=(-e "shaded_package/com/sun/${sub}")
  PATTERNS+=(-e "shaded_package.com.sun.$(echo "$sub" | tr '/' '.')")
done

# Detection streams every decompressed .class through one grep (seconds), rather
# than extracting the whole jar to disk (minutes). grep -c (not -q) consumes the
# whole stream, so `unzip` never takes a SIGPIPE that pipefail would misread as a
# clean scan. Offenders are then named from the MockServer classes only - the code
# we own and the realistic regression source.
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
unzip -q -o "$JAR" 'org/mockserver/*' -d "$WORK"

MATCHES=$(unzip -p "$JAR" '*.class' | grep -caF "${PATTERNS[@]}" || true)

if [ "$MATCHES" -ne 0 ]; then
  echo "ERROR: $(basename "$JAR") relocated a JDK com.sun.* package into shaded_package.* ($MATCHES class line(s))." >&2
  for sub in $JDK_SUBPACKAGES; do
    slash="shaded_package/com/sun/${sub}"
    dot="shaded_package.com.sun.$(echo "$sub" | tr '/' '.')"
    hits="$(grep -rlaF -e "$slash" -e "$dot" "$WORK" 2>/dev/null | sed "s#^$WORK/##" || true)"
    if [ -n "$hits" ]; then
      echo "  com.sun.${sub//\//.} relocated in:" >&2
      printf '    %s\n' $hits >&2
    fi
  done
  echo "" >&2
  echo "  A relocated reference to a JDK class resolves to nothing at runtime, so the" >&2
  echo "  feature using it fails silently (e.g. jvm_memory_allocated_bytes disappears)." >&2
  echo "  Add the subpackage to the <excludes> of the com.sun relocation in mockserver/pom.xml." >&2
  exit 1
fi

# Positive sentinel: the allocated-bytes metric's HotSpot probe must still carry the
# REAL com.sun.management.ThreadMXBean reference. This fails if someone re-broke the
# relocation OR removed the metric, so the guard cannot pass vacuously.
SENTINEL="$WORK/org/mockserver/metrics/JvmMetricsCollector.class"
if [ ! -f "$SENTINEL" ]; then
  echo "ERROR: JvmMetricsCollector.class not found in $(basename "$JAR")." >&2
  echo "  The sentinel that proves the com.sun.management probe is intact is gone." >&2
  exit 1
fi
if ! grep -qaF "com/sun/management/ThreadMXBean" "$SENTINEL"; then
  echo "ERROR: JvmMetricsCollector no longer references com/sun/management/ThreadMXBean." >&2
  echo "  The jvm_memory_allocated_bytes probe is gone or was renamed; the guard has nothing" >&2
  echo "  to protect. Restore the HotSpot ThreadMXBean probe or update this sentinel." >&2
  exit 1
fi

echo "OK: $(basename "$JAR") relocates no JDK com.sun.* package (checked: ${JDK_SUBPACKAGES})."
