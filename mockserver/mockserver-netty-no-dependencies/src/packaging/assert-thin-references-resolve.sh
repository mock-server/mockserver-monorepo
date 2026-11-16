#!/usr/bin/env bash
#
# Fail the build unless a thin integration jar (junit-jupiter, junit-rule, spring-test-listener
# -no-dependencies) works against the mockserver-netty-no-dependencies jar it depends on: every
# org.mockserver.* and shaded_package.* class it references must be in the thin jar or the netty
# jar, no reference may name a library class the netty jar only carries relocated (a missed
# relocation), and the thin jar must not repeat a class or a package the netty jar already carries
# (a split package stops both jars loading as automatic modules on the module path).
#
# usage: assert-thin-references-resolve.sh <thin-jar> <netty-no-dependencies-jar> [<jdeps>]
set -euo pipefail

THIN="${1:?usage: assert-thin-references-resolve.sh <thin-jar> <netty-no-dependencies-jar> [<jdeps>]}"
NETTY="${2:?usage: assert-thin-references-resolve.sh <thin-jar> <netty-no-dependencies-jar> [<jdeps>]}"
JDEPS="${3:-${JAVA_HOME:+$JAVA_HOME/bin/}jdeps}"
[ -f "$THIN" ] || { echo "ERROR: thin jar not found: $THIN" >&2; exit 1; }
[ -f "$NETTY" ] || { echo "ERROR: netty-no-dependencies jar not found: $NETTY" >&2; exit 1; }
NAME="$(basename "$THIN")"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
unzip -Z1 "$THIN" | grep '\.class$' | LC_ALL=C sort -u >"$WORK/thin" || true
unzip -Z1 "$NETTY" | grep '\.class$' | LC_ALL=C sort -u >"$WORK/netty" || true
[ -s "$WORK/thin" ] || { echo "ERROR: $NAME holds no classes" >&2; exit 1; }
[ -s "$WORK/netty" ] || { echo "ERROR: $(basename "$NETTY") holds no classes" >&2; exit 1; }

"$JDEPS" -verbose:class -filter:none "$THIN" >"$WORK/jdeps" \
  || { echo "ERROR: jdeps failed on $NAME" >&2; exit 1; }
awk '$2 == "->" && ($3 ~ /^org\.mockserver\./ || $3 ~ /^shaded_package\./) { gsub(/\./, "/", $3); print $3 ".class" }' \
  "$WORK/jdeps" | LC_ALL=C sort -u >"$WORK/referenced"
[ -s "$WORK/referenced" ] || { echo "ERROR: jdeps found no org.mockserver or shaded_package reference in $NAME" >&2; exit 1; }
# Other references are checked only against the netty jar's relocated copies; the invoker ITs cover the rest.
awk '$2 == "->" && $3 !~ /^(org\.mockserver|shaded_package)\./ { gsub(/\./, "/", $3); print "shaded_package/" $3 ".class" }' \
  "$WORK/jdeps" | LC_ALL=C sort -u >"$WORK/unrelocated"

FAILED=0
LC_ALL=C sort -u "$WORK/thin" "$WORK/netty" >"$WORK/available"
MISSING="$(LC_ALL=C comm -23 "$WORK/referenced" "$WORK/available")"
if [ -n "$MISSING" ]; then
  echo "ERROR: $NAME references $(wc -l <<<"$MISSING" | tr -d ' ') classes that neither it nor $(basename "$NETTY") contains:" >&2
  sed 's/^/  /' <<<"$MISSING" >&2
  FAILED=1
fi
UNRELOCATED="$(LC_ALL=C comm -12 "$WORK/unrelocated" "$WORK/netty" | sed 's#^shaded_package/##')"
if [ -n "$UNRELOCATED" ]; then
  echo "ERROR: $NAME references $(wc -l <<<"$UNRELOCATED" | tr -d ' ') classes by their unrelocated name, but $(basename "$NETTY") carries them only under shaded_package/; keep the shared relocations in the shade configuration:" >&2
  sed 's/^/  /' <<<"$UNRELOCATED" >&2
  FAILED=1
fi
DUPLICATED="$(LC_ALL=C comm -12 "$WORK/thin" "$WORK/netty")"
if [ -n "$DUPLICATED" ]; then
  echo "ERROR: $NAME repeats $(wc -l <<<"$DUPLICATED" | tr -d ' ') classes that $(basename "$NETTY") already carries; shade only the module's own artifact. First few:" >&2
  head -5 <<<"$DUPLICATED" | sed 's/^/  /' >&2
  FAILED=1
fi
# Regular files: uutils comm (the CI image's coreutils) wrongly reports "not in sorted order"
# when both inputs are pipes and one is larger than about 8 KB.
sed 's#/[^/]*$##' "$WORK/thin" | LC_ALL=C sort -u >"$WORK/thin-packages"
sed 's#/[^/]*$##' "$WORK/netty" | LC_ALL=C sort -u >"$WORK/netty-packages"
SPLIT="$(LC_ALL=C comm -12 "$WORK/thin-packages" "$WORK/netty-packages")"
if [ -n "$SPLIT" ]; then
  echo "ERROR: $NAME shares $(wc -l <<<"$SPLIT" | tr -d ' ') packages with $(basename "$NETTY"), which breaks the module path:" >&2
  sed 's/^/  /' <<<"$SPLIT" >&2
  FAILED=1
fi

[ "$FAILED" -eq 0 ] || exit 1
echo "OK: $NAME's $(wc -l <"$WORK/referenced" | tr -d ' ') org.mockserver/shaded_package references all resolve; no class or package repeats $(basename "$NETTY")"
