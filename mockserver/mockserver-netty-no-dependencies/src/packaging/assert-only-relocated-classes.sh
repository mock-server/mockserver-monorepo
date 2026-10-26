#!/usr/bin/env bash
#
# Fail the build if a *-no-dependencies jar carries a class outside org/mockserver/ and
# shaded_package/, or a service registration for an unrelocated interface, that is not on the
# allow-list given as arguments. These jars promise every bundled dependency is relocated, so an
# unrelocated class clashes with the user's own copy of that library (issue #2772), and an
# unrelocated service file (for example org.slf4j.spi.SLF4JServiceProvider) is picked up by the
# user's ServiceLoader. Also fails if a service file names a class the jar does not contain.
#
# usage: assert-only-relocated-classes.sh <jar> [--package <path-prefix/>]... [--service <interface>]...
# Each --package / --service adds to the default allow-list below.
set -euo pipefail

JAR="${1:?usage: assert-only-relocated-classes.sh <jar> [--package <path-prefix/>]... [--service <interface>]...}"
shift
# Left unrelocated in every no-dependencies jar, each for a reason relocation cannot fix by itself:
#  org/apache/velocity/ - Velocity's bundled .properties files name its classes, and shade does not
#                         rewrite resource contents, so relocated Velocity cannot find its directives
#  org/mozilla/         - Rhino's compiler generates classes at runtime that name Rhino's own classes
#  org/xerial/snappy/, net/jpountz/, com/github/luben/zstd/
#                       - JNI natives bind to the unrelocated class names (zstd: issue #2869)
#  org/slf4j/           - the logging API is shared so MockServer logs through the user's SLF4J provider;
#                         a bundled provider must be relocated (DENIED_PACKAGES) and unregistered
ALLOWED_PACKAGES=(org/apache/velocity/ org/mozilla/ org/xerial/snappy/ net/jpountz/ com/github/luben/zstd/ org/slf4j/)
# SLF4J providers: unrelocated, they are found by the user's SLF4J and clash with its own provider (#2772).
DENIED_PACKAGES=(org/slf4j/jul/ org/slf4j/simple/ org/slf4j/nop/ org/slf4j/reload4j/)
# javax.script: Velocity's script engine; Rhino's RegExpLoader (both unrelocated, above); BlockHound:
# relocated Netty's own hook.
ALLOWED_SERVICES=(javax.script.ScriptEngineFactory org.mozilla.javascript.RegExpLoader reactor.blockhound.integration.BlockHoundIntegration)
while [ $# -gt 0 ]; do
  case "$1" in
    --package) ALLOWED_PACKAGES+=("${2:?--package needs a value}"); shift 2 ;;
    --service) ALLOWED_SERVICES+=("${2:?--service needs a value}"); shift 2 ;;
    *) echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
  esac
done

[ -f "$JAR" ] || { echo "ERROR: jar not found: $JAR" >&2; exit 1; }
ENTRIES="$(unzip -Z1 "$JAR")"
[ -n "$ENTRIES" ] || { echo "ERROR: $JAR lists no entries" >&2; exit 1; }
NAME="$(basename "$JAR")"
FAILED=0

starts_with_any() { # <value> <prefix>...
  local value="$1" prefix; shift
  for prefix in "$@"; do [ -n "$prefix" ] && [[ "$value" == "$prefix"* ]] && return 0; done
  return 1
}

# Multi-release entries (META-INF/versions/<n>/<class path>) are judged by their class path.
UNRELOCATED="$(grep '\.class$' <<<"$ENTRIES" | sed -E 's#^META-INF/versions/[0-9]+/##' \
  | grep -v -e '^org/mockserver/' -e '^shaded_package/' -e '^module-info\.class$' || true)"
if [ -n "$UNRELOCATED" ]; then
  REJECTED="$(awk -v allowed="${ALLOWED_PACKAGES[*]+"${ALLOWED_PACKAGES[*]}"}" -v denied="${DENIED_PACKAGES[*]}" '
    BEGIN { n = split(allowed, prefixes, " "); m = split(denied, deny, " ") }
    { for (j = 1; j <= m; j++) if (index($0, deny[j]) == 1) { print; next } }
    { for (i = 1; i <= n; i++) if (index($0, prefixes[i]) == 1) next; print }' <<<"$UNRELOCATED")"
  if [ -n "$REJECTED" ]; then
    echo "ERROR: $NAME carries $(wc -l <<<"$REJECTED" | tr -d ' ') unrelocated classes outside the allow-list; relocate their packages under shaded_package in the shade configuration. By package:" >&2
    awk -F/ '{ p = $1; for (i = 2; i < NF && i <= 2; i++) p = p "/" $i; print "  " p "/" }' <<<"$REJECTED" | sort | uniq -c >&2
    FAILED=1
  fi
fi

while IFS= read -r service; do
  case "$service" in
    shaded_package.*|org.mockserver.*|java.*) ;;
    *) starts_with_any "$service" "${ALLOWED_SERVICES[@]+"${ALLOWED_SERVICES[@]}"}" \
         || { echo "ERROR: $NAME registers META-INF/services/$service, an unrelocated interface the user's ServiceLoader will see" >&2; FAILED=1; } ;;
  esac
  for impl in $(unzip -p "$JAR" "META-INF/services/$service" | sed -e 's/#.*//' -e 's/[[:space:]]//g' | grep -v '^$' || true); do
    grep -qxF "$(tr '.' '/' <<<"$impl").class" <<<"$ENTRIES" \
      || { echo "ERROR: $NAME META-INF/services/$service names $impl, which is not in the jar" >&2; FAILED=1; }
  done
done < <(grep '^META-INF/services/.' <<<"$ENTRIES" | sed 's#^META-INF/services/##' || true)

[ "$FAILED" -eq 0 ] || exit 1
echo "OK: $NAME carries no unrelocated classes or service registrations outside its allow-list."
