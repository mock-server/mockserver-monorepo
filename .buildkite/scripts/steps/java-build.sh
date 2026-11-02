#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# WHY -m 12g (raised from 7g):
#
#   This container must simultaneously hold TWO memory consumers, and 7g could
#   not fit both — it OOM-killed (exit 137) roughly half of master's builds:
#     1. The Maven JVM. mvnw prepends mockserver/.mvn/jvm.config (-Xmx6144m) to
#        MAVEN_OPTS, so the reactor JVM may grow to a 6g heap, and under `-T 1C`
#        (mockserver/.mvn/maven.config) live heap really does climb toward that
#        ceiling. With metaspace/code-cache/thread-stacks/direct buffers that is
#        ~7g of RSS on its own — already the whole old limit.
#     2. The dashboard UI build. mockserver-netty's `build-ui` profile runs the
#        frontend-maven-plugin (npm ci + `vite build`) in generate-resources, as
#        a CHILD process of that same Maven JVM, inside this SAME cgroup. Vite 8
#        bundles with rolldown (native Rust), whose ~1.5-3g peak is NATIVE memory
#        — a NODE_OPTIONS/--max-old-space-size cap cannot bound it (measured: the
#        build completes even at --max-old-space-size=64). So the node peak lands
#        on TOP of the near-ceiling Maven heap: 7g + ~2-3g > 7g -> the cgroup OOM
#        killer SIGKILLs a process in the tree. The kills consistently ended on
#        `vite ... transforming...`, exactly where node RSS spikes.
#   --memory-swap equals --memory in run-in-docker.sh (no swap), so there is no
#   soft cushion — the hard limit must actually clear both consumers plus
#   overhead. 12g does (6g heap + ~1.5g JVM non-heap + ~3g node + headroom).
#
#   REQUIRES a >=32 GiB agent (default queue = 8 vCPU / 32 GiB class, see terraform/buildkite-agents; one
#   agent per instance). On the old 16 GiB c5.2xlarge a 12g container left too
#   little for the host; raising the limit and moving the queue to 32 GiB hosts
#   are one coupled change (see terraform/buildkite-agents/variables.tf).
#
# --cache-save: this full-reactor build is the ONLY saver of the shared maven cache
# and the java gradle cache, and only in the scheduled nightly (dependency-cache.sh).
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mockserver/mockserver:maven \
  -m 12g \
  --cache maven \
  --cache gradle \
  --cache-save \
  -e "BUILDKITE_BRANCH=${BUILDKITE_BRANCH:-}" \
  -e "MOCKSERVER_NETTY_ITS_IN_SHARDS=${MOCKSERVER_NETTY_ITS_IN_SHARDS:-false}" \
  -- /build/scripts/buildkite_quick_build.sh
