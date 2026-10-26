#!/bin/sh
# dataplane_ab.js at init, through `k6 inspect` (no load, no server): its window and warm-up shapes, and the
# knobs it refuses. POSIX sh: perf-test-lint.sh runs it inside the k6 container;
# --include-system-env-vars=false keeps the build's environment out.
# Locally: K6_BIN=/path/to/k6 .buildkite/scripts/test/k6-dataplane-ab-init-test.sh
set -eu

K6="${K6_BIN:-k6}"
HERE="$(cd "$(dirname "$0")" && pwd)"
JS="$HERE/../../../mockserver-performance-test/k6/dataplane_ab.js"
command -v "$K6" >/dev/null 2>&1 || { echo "ERROR: no k6 binary ('$K6'): set K6_BIN" >&2; exit 2; }
FAILS=0
bad() { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }

# shape <name> <expected "rate/duration/vus"> [-e VAR=value...]
shape() {
  name="$1"; want="$2"; shift 2
  if ! out="$("$K6" inspect --include-system-env-vars=false "$@" "$JS" 2>&1)"; then
    bad "$name: refused: $(printf '%s\n' "$out" | grep -o 'msg="[^"]*' | head -1)"; return 0
  fi
  got="$(printf '%s\n' "$out" | awk -F'[:,]' '/"rate":/ { r = $2 } /"duration": "/ { d = $2 } /"maxVUs":/ { v = $2 } END { gsub(/[ "]/, "", r); gsub(/[ "]/, "", d); gsub(/[ "]/, "", v); printf "%s/%s/%s", r, d, v }')"
  if [ "$got" = "$want" ]; then echo "  ok   $name"; else bad "$name: expected '$want', got '$got'"; fi
}
# refuses <name> <message> [-e VAR=value...]
refuses() {
  name="$1"; message="$2"; shift 2
  if out="$("$K6" inspect --include-system-env-vars=false "$@" "$JS" 2>&1)"; then bad "$name: accepted"; return 0; fi
  case "$out" in
    *"$message"*) echo "  ok   $name" ;;
    *) bad "$name: refused without '$message'" ;;
  esac
}

echo "--- dataplane_ab.js: the shapes the harness asks for"
shape "a 1kb window: 200 rps for 25 s, 40 VUs" "200/25s/40" -e K6_DP_ARM=none
shape "a 1mb window at the harness's 50 rps" "50/25s/40" -e K6_DP_ARM=h2 -e K6_DP_SIZE=1mb -e K6_DP_RATE=50
shape "a warm-up over both sizes: 20 s" "100/20s/40" -e K6_DP_ARM=h1 -e K6_DP_MODE=warmup -e K6_DP_SIZES=1kb,1mb -e K6_DP_RATE=100

echo "--- dataplane_ab.js: knobs it refuses"
refuses "no arm" "K6_DP_ARM"
refuses "an unknown arm" "K6_DP_ARM" -e K6_DP_ARM=h3
refuses "an unknown mode" "K6_DP_MODE" -e K6_DP_ARM=none -e K6_DP_MODE=soak
refuses "a size over 8mb" "is over 8mb" -e K6_DP_ARM=none -e K6_DP_SIZE=9mb
refuses "a size in bytes" "is not <n>kb or <n>mb" -e K6_DP_ARM=none -e K6_DP_SIZE=1024
refuses "a repeated warm-up size" "distinct" -e K6_DP_ARM=none -e K6_DP_MODE=warmup -e K6_DP_SIZES=1kb,1kb
refuses "a settle as long as the window" "K6_DP_SETTLE must be shorter" -e K6_DP_ARM=none -e K6_DP_SETTLE=25s
refuses "a fractional rate" "K6_DP_RATE" -e K6_DP_ARM=none -e K6_DP_RATE=1.5

if [ "$FAILS" -gt 0 ]; then echo "FAILED: $FAILS check(s)" >&2; exit 1; fi
echo "all dataplane_ab.js init checks passed"
