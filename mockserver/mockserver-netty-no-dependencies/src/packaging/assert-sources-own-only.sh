#!/usr/bin/env bash
#
# Fail the build unless a *-no-dependencies module's -sources.jar holds only MockServer's own
# sources: present, carrying org/mockserver/ .java files, no entry outside the allowed roots below,
# and no larger than the size limit. The jar is built from the org.mock-server dependencies' own
# -sources.jar files (unpack-own-sources); a bundled third-party library's sources here would
# re-inflate every release on Maven Central.
#
# usage: assert-sources-own-only.sh <sources-jar> [--max-bytes <n>]
set -euo pipefail

JAR="${1:?usage: assert-sources-own-only.sh <sources-jar> [--max-bytes <n>]}"
shift
MAX_BYTES=$((20 * 1024 * 1024))
while [ $# -gt 0 ]; do
  case "$1" in
    --max-bytes) MAX_BYTES="${2:?--max-bytes needs a value}"; shift 2 ;;
    *) echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
  esac
done
# example_logging_configuration.properties: mockserver-netty's own resource, at the jar root.
ALLOWED_PREFIXES=(org/mockserver/ META-INF/)
ALLOWED_FILES=(example_logging_configuration.properties)

[ -f "$JAR" ] || { echo "ERROR: sources jar not found: $JAR" >&2; exit 1; }
NAME="$(basename "$JAR")"
SIZE="$(wc -c <"$JAR" | tr -d ' ')"
if [ "$SIZE" -gt "$MAX_BYTES" ]; then
  echo "ERROR: $NAME is $SIZE bytes, over the $MAX_BYTES byte limit; it should hold only MockServer's own sources" >&2
  exit 1
fi
ENTRIES="$(unzip -Z1 "$JAR")"
grep -q '^org/mockserver/.*\.java$' <<<"$ENTRIES" \
  || { echo "ERROR: $NAME has no org/mockserver/ .java sources" >&2; exit 1; }

# A directory entry that is a parent of an allowed root (org/) is allowed too.
REJECTED="$(awk -v prefixes="${ALLOWED_PREFIXES[*]}" -v files="${ALLOWED_FILES[*]}" '
  BEGIN { n = split(prefixes, p, " "); m = split(files, f, " ") }
  {
    for (i = 1; i <= n; i++) if (index($0, p[i]) == 1 || ($0 ~ /\/$/ && index(p[i], $0) == 1)) next
    for (j = 1; j <= m; j++) if ($0 == f[j]) next
    print
  }' <<<"$ENTRIES")"
if [ -n "$REJECTED" ]; then
  echo "ERROR: $NAME carries $(wc -l <<<"$REJECTED" | tr -d ' ') entries outside ${ALLOWED_PREFIXES[*]} ${ALLOWED_FILES[*]}; only MockServer's own sources belong in it. By top-level path:" >&2
  awk -F/ '{ print "  " $1 (NF > 1 ? "/" : "") }' <<<"$REJECTED" | sort | uniq -c >&2
  exit 1
fi
echo "OK: $NAME holds only MockServer's own sources ($SIZE bytes, $(grep -c '\.java$' <<<"$ENTRIES") .java files)"
