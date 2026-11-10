#!/usr/bin/env bash
# Sourced by perf-test-lint.sh around the perf self-tests. On a CI agent a self-test that reaches the
# real buildkite-agent posts to the lint's own build (a false annotation, meta-data, an artifact), so
# perf_agent_tripwire_install puts a recording buildkite-agent first on PATH (a self-test's own stub,
# earlier on PATH, still wins) and perf_agent_tripwire_check fails, naming each call that reached it.
# Checked by .buildkite/scripts/test/perf-agent-tripwire-test.sh.

perf_agent_tripwire_install() { # dir
  mkdir -p "$1/bin"
  : > "$1/calls.log"
  cat > "$1/bin/buildkite-agent" <<STUB
#!/usr/bin/env bash
# perf-agent-tripwire recorder
caller="\$(ps -o args= -p "\$PPID" 2>/dev/null || true)"
printf '%s  (called from: %s)\n' "\$*" "\$caller" >> "$1/calls.log"
# drain a piped annotation body; read -t bounds the wait on a stdin that never closes
if [ "\$1" = annotate ] && [ -p /dev/stdin ]; then while IFS= read -r -t 5 _; do :; done; fi
exit 0
STUB
  chmod +x "$1/bin/buildkite-agent"
  PERF_AGENT_TRIPWIRE_LOG="$1/calls.log"
  PERF_AGENT_TRIPWIRE_SAVED_PATH="$PATH"
  export PATH="$1/bin:$PATH"
}

perf_agent_tripwire_check() { # -> 0 when no self-test reached buildkite-agent
  [ -s "${PERF_AGENT_TRIPWIRE_LOG:?perf_agent_tripwire_install was not called}" ] || return 0
  echo "FAIL: a perf self-test called buildkite-agent without its own stub; in CI each call below would have reached this build:" >&2
  sed 's/^/  buildkite-agent /' "$PERF_AGENT_TRIPWIRE_LOG" >&2
  return 1
}

perf_agent_tripwire_remove() {
  export PATH="${PERF_AGENT_TRIPWIRE_SAVED_PATH:?perf_agent_tripwire_install was not called}"
}
