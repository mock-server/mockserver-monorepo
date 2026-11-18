#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Build the MockServer Docker image (source=copy) and prove it actually works —
# not merely that `docker build` exited 0.
#
# WHY
# ---
# The tcnative native .so the image downloads is versioned from a stamp baked into
# the server jar (META-INF/mockserver-tcnative.version). Nothing in CI ever built
# an image on a `docker/**`-only change, so a broken stamp or a Dockerfile that
# stopped pairing the .so with the classes could ship silently (only human review
# caught the last one). This is the build backstop: it runs the fast two-jar
# derive check FIRST (verify-tcnative-stamp.sh), then builds a real image and
# asserts the native actually loads at runtime.
#
# THE SAME SCRIPT RUNS IN CI AND LOCALLY — that is the point. It detects rather
# than assumes its environment (host arch, corporate CA bundle, buildkite-agent),
# so a developer on an arm64 Mac and a Buildkite agent run identical logic.
#
# WHAT IT ASSERTS (fails closed — non-zero exit — on any):
#   * the two-jar tcnative stamp derive check passes (delegated);
#   * the runtime image contains the arch-correct tcnative .so under /usr/lib/,
#     named for this host's arch AND carrying the matching ELF e_machine (a
#     wrong-arch .so is a failure, not a pass);
#   * the native TLS provider actually LOADS in the image's own JVM
#     (OpenSsl.isAvailable() == true — proving the image reproduces source=download's
#     real native-TLS behaviour. NOTE the assembly jar BUNDLES the per-platform
#     natives, so Netty loads the native from the jar, not from the /usr/lib .so this
#     image downloads — this probe does NOT isolate that download. Wrong/empty/missing
#     stamps are caught upstream by verify-tcnative-stamp.sh and the Dockerfile's own
#     empty-check, and assert_so_present below separately asserts the arch-correct
#     /usr/lib .so exists);
#   * the native epoll transport loads (Epoll.isAvailable) — the jarprep trim keeps
#     only this arch's natives, so a wrong-arch trim degrades epoll to NIO silently;
#   * native TLS STILL loads under `docker run --read-only` (no --tmpfs /tmp) — the
#     one check that actually exercises the /usr/lib .so's purpose: with a read-only
#     /tmp Netty cannot extract the jar-bundled native, so BoringSSL survives only
#     via System.loadLibrary finding /usr/lib/libnetty_tcnative_linux_*.so. This is
#     what guards the `COPY ... /usr/lib/` line against a silent removal;
#   * the container starts, answers its health check, and completes a real TLS
#     handshake on its port;
#   * with metrics enabled, the Prometheus scrape includes jvm_memory_allocated_bytes
#     — the guard that the jlink runtime kept jdk.management (com.sun.management.ThreadMXBean).
#   * an extension JAR mounted read-only at /extensions is loaded and serves its expectation,
#     under -Xshare:on (the JVM exits if the AppCDS archive no longer maps).
#   * the ENTRYPOINT runs with UseBASE64Intrinsics = false {command line} (CVE-2026-114938), even
#     when JAVA_TOOL_OPTIONS asks for it on.
#
# ARCH TRAP
#   The image is built WITHOUT --build-arg TARGETARCH, exactly as the release and
#   snapshot pushes build it, so the arch comes from BuildKit's own injection. A
#   stage-level default (`ARG TARGETARCH=amd64`) overrides that injection — it is
#   how the arm64 images shipped x86_64 natives — and passing the arg here would hide
#   it. We DETECT the host arch only to know which .so and ELF machine to expect.
#
# CA STAGING
#   docker/ensure-ca-bundle.sh stages ca-bundle.pem into the build context (the
#   corporate root when MOCKSERVER_LOCAL_CA_BUNDLE / AWS_CA_BUNDLE point at one,
#   else an empty placeholder). We ALWAYS remove what we staged on exit — the
#   scratch bundle and the staged jar must never be left in the tree.
#
# SELF-TEST HOOKS (mirror check-certificate-expiry.sh's env overrides)
#   DOCKER_VERIFY_SKIP_BUILD=true   run the runtime assertions against an existing
#                                   image instead of building (fast red/green).
#   DOCKER_VERIFY_IMAGE=<ref>       assert against this image ref (point at a stock
#                                   image with no .so to prove the guards go red).
#   DOCKER_VERIFY_SO_OVERRIDE=<path-in-image>  force the expected .so path (point
#                                   at the wrong arch to prove the presence guard).
#
# Requires: docker, tar, and a jar (built on demand via mvnw if absent). Runs the
# probe compile in a throwaway temurin JDK container, so it needs no host JDK.
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO_ROOT"

# nullglob at SCRIPT scope so an unmatched jar glob expands to NOTHING rather than
# staying literal. The jar globs below are expanded by THIS shell before the
# helper functions run, so enabling nullglob only inside a helper is too late: the
# literal pattern would already have been passed in, defeating both the
# build-on-demand detection AND the post-build "no jar after build" guard (a
# literal, non-existent path tests as non-empty and sails through). See the guards
# in locate_or_build_jars().
shopt -s nullglob

STEPS_DIR="$REPO_ROOT/.buildkite/scripts/steps"
DOCKER_CONTEXT="$REPO_ROOT/docker"
REACTOR_DIR="$REPO_ROOT/mockserver"
IMAGE_TAG="${DOCKER_VERIFY_IMAGE:-mockserver/mockserver:docker-build-verify}"
PROBE_JDK_IMAGE="eclipse-temurin:25-jdk-noble"
IN_IMAGE_JAVA="/usr/lib/jvm/temurin25-trimmed/bin/java"
# The image ships MockServer's own classes and its third-party dependencies as TWO jars on the
# classpath (docker/Dockerfile splits the fat jar for incremental image download), so the in-image
# classpath is both jars. Used below both for the OpenSsl probe (netty lives in deps) and for the
# HealthCheck invocation (org.mockserver.cli.HealthCheck lives in the own-classes jar).
IN_IMAGE_JAR="/mockserver.jar:/mockserver-deps.jar"

# ── Detect host arch → the .so and ELF machine the runtime must contain ─────────
case "$(uname -m)" in
  x86_64|amd64)  HOST_ARCH=amd64; ELF_MACHINE=62;  SO_PATH='usr/lib/libnetty_tcnative_linux_x86_64.so' ;;
  arm64|aarch64) HOST_ARCH=arm64; ELF_MACHINE=183; SO_PATH='usr/lib/libnetty_tcnative_linux_aarch_64.so' ;;
  *) echo "+++ :bangbang: unsupported host arch '$(uname -m)' — cannot map to a tcnative .so, failing closed" >&2; exit 1 ;;
esac
SO_PATH="${DOCKER_VERIFY_SO_OVERRIDE:-$SO_PATH}"

CLEANUP=()
cleanup() {
  local item
  # ${CLEANUP[@]+"${CLEANUP[@]}"}, not "${CLEANUP[@]}": under `set -u` bash 3.2 (which is what macOS
  # ships, and this script is explicitly meant to run identically locally and in CI) treats expanding
  # an EMPTY array as an unbound variable and aborts. This trap runs on EXIT, so it fires with CLEANUP
  # still empty whenever the script exits before staging anything - which is exactly the inner-gate
  # failure path a developer hits first. The abort then replaced the real error with
  # "CLEANUP[@]: unbound variable". Linux bash 4.4+ does not have the quirk, so CI never saw it.
  for item in ${CLEANUP[@]+"${CLEANUP[@]}"}; do
    case "$item" in
      container:*)  docker rm -f "${item#container:}"        >/dev/null 2>&1 || true ;;
      file:*)       rm -f "${item#file:}"                    || true ;;
      dir:*)        rm -rf "${item#dir:}"                    || true ;;
      image:*)      docker rmi -f "${item#image:}"           >/dev/null 2>&1 || true ;;
    esac
  done
}
trap cleanup EXIT

fail() { echo "+++ :bangbang: $*" >&2; exit 1; }

# ── Locate the assembly jar-with-dependencies, building it on demand if absent ──
# We stage the maven-assembly mockserver-netty-<ver>-jar-with-dependencies.jar —
# the SAME artifact the default source=download images pull from Sonatype, and the
# one integration_tests.sh stages for its source=copy build. Building source=copy
# from it is therefore a faithful local reproduction of the DEFAULT published
# image without needing a post-stamp release.
#
# WHY THIS JAR and not the shaded no-dependencies one: the assembly jar bundles the
# per-platform tcnative natives, so the native provider actually loads at runtime
# and the OpenSsl.isAvailable() assertion below is a real, green native-TLS check —
# which is exactly source=download's behaviour. (The shaded no-dependencies jar
# strips the natives; in the distroless base the standalone /usr/lib .so does not
# dlopen, so that path silently runs on the JDK provider — which is by design for
# docker/local, but is NOT the tcnative path this gate exists to protect.) The
# two-jar derive check still independently covers the shaded jar's stamp.
# nullglob is enabled at script scope (see top of file), so an unmatched caller
# glob arrives here as ZERO args and this returns non-zero — never the literal
# pattern. Do NOT toggle nullglob inside this function: the glob is expanded by the
# caller before we run, so a local shopt here would be too late to matter.
first_real_jar() {
  local f
  for f in "$@"; do
    case "$(basename "$f")" in
      *-sources.jar|*-javadoc.jar|original-*) continue ;;
    esac
    printf '%s\n' "$f"; return 0
  done
  return 1
}

# Build BOTH stamped jars (assembly + shaded no-dependencies) so the inner derive
# gate has both and the image build has the assembly jar. Uses host mvnw when a
# JDK is on the host (developer machine); otherwise builds inside the pinned
# mockserver/mockserver:maven image via run-in-docker.sh — the repo's sanctioned
# no-host-JDK path, since CI agents run Maven in that image rather than on the raw
# agent. Both write into the bind-mounted workspace, so the SAME script works in
# either environment.
build_jars() {
  # `install` (not `package`) so mockserver-core lands in the maven cache — the
  # inner derive gate's `dependency:list -pl mockserver-netty` resolves the sibling
  # SNAPSHOT modules from there. Mirrors ui-java-codegen-compile.sh.
  # shellcheck disable=SC2054  # the comma is inside the Maven -pl module list, not an array separator
  local mvn_args=(-q -B --no-transfer-progress
                  -pl mockserver-netty,mockserver-netty-no-dependencies -am
                  -DskipTests -DskipITs install)
  if command -v java >/dev/null 2>&1 && [ -x "$REACTOR_DIR/mvnw" ]; then
    echo "--- :maven: building server jars on host (mvnw ${mvn_args[*]})" >&2
    ( cd "$REACTOR_DIR" && ./mvnw "${mvn_args[@]}" ) >&2 \
      || fail "host mvnw build failed — cannot obtain the server jars, failing closed"
  else
    echo "--- :maven: no host JDK — building server jars inside mockserver/mockserver:maven" >&2
    "$REPO_ROOT/.buildkite/scripts/run-in-docker.sh" \
      -i mockserver/mockserver:maven -w /build/mockserver --cache maven \
      -- ./mvnw "${mvn_args[@]}" >&2 \
      || fail "in-image mvnw build failed — cannot obtain the server jars, failing closed"
  fi
}

# Ensure both jars exist (building on demand), then echo the assembly jar path.
locate_or_build_jars() {
  local asm shaded
  asm="$(first_real_jar "$REACTOR_DIR"/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar || true)"
  shaded="$(first_real_jar "$REACTOR_DIR"/mockserver-netty-no-dependencies/target/mockserver-netty-no-dependencies-*.jar || true)"
  if [ -z "$asm" ] || [ -z "$shaded" ]; then
    build_jars
    asm="$(first_real_jar "$REACTOR_DIR"/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar || true)"
  fi
  [ -n "$asm" ] || fail "no mockserver-netty-*-jar-with-dependencies.jar after build — failing closed"
  printf '%s\n' "$asm"
}

# ── Assert the runtime image contains the arch-correct tcnative .so ─────────────
assert_so_present() {
  echo "--- :mag: asserting arch-correct tcnative .so is baked into the image ($SO_PATH)"
  local cid found
  cid="$(docker create "$IMAGE_TAG")" || fail "docker create '$IMAGE_TAG' failed"
  CLEANUP+=( "container:$cid" )
  # Export the flattened rootfs and look for the exact arch .so.
  found="$(docker export "$cid" | tar -tf - 2>/dev/null | grep -E "^${SO_PATH}\$" || true)"
  docker rm -f "$cid" >/dev/null 2>&1 || true
  if [ -z "$found" ]; then
    echo "    :bangbang: image does NOT contain $SO_PATH" >&2
    # Diagnostic: list the natives that ARE present. Capture the cid so this
    # throwaway container is removed too (even if the export itself fails).
    local dcid present
    if dcid="$(docker create "$IMAGE_TAG" 2>/dev/null)"; then
      CLEANUP+=( "container:$dcid" )
      present="$(docker export "$dcid" | tar -tf - 2>/dev/null | grep -E 'usr/lib/libnetty_tcnative' | tr '\n' ' ' || true)"
      docker rm -f "$dcid" >/dev/null 2>&1 || true
    fi
    echo "    (present tcnative natives: ${present:-none})" >&2
    fail "arch-correct tcnative .so missing from image — a wrong-arch or absent native would break TLS, failing closed"
  fi
  echo "    :white_check_mark: found $found"
  # The file NAME says nothing about its contents, so read the ELF e_machine (62 x86-64, 183 AArch64).
  local so_dir actual
  so_dir="$(mktemp -d)"
  CLEANUP+=( "dir:$so_dir" )
  cid="$(docker create "$IMAGE_TAG")" || fail "docker create '$IMAGE_TAG' failed"
  CLEANUP+=( "container:$cid" )
  docker cp -L "$cid:/$SO_PATH" "$so_dir/native.so" >/dev/null || fail "could not copy /$SO_PATH out of the image"
  docker rm -f "$cid" >/dev/null 2>&1 || true
  actual="$(od -An -tu2 -j18 -N2 "$so_dir/native.so" | tr -d '[:space:]')"
  [ "$actual" = "$ELF_MACHINE" ] \
    || fail "/$SO_PATH has ELF machine '${actual:-unreadable}', expected $ELF_MACHINE for $HOST_ARCH — wrong-arch native, failing closed"
  echo "    :white_check_mark: ELF machine $actual matches $HOST_ARCH"
}

# ── Compile the OpenSsl.isAvailable probe once, into a throwaway dir ─────────────
# Reused by every provider assertion below (default and --read-only) so the compile
# runs a single time. Sets PROBE_DIR (registered for cleanup).
PROBE_DIR=""
ensure_probe_compiled() {
  [ -n "$PROBE_DIR" ] && return 0
  PROBE_DIR="$(mktemp -d)"
  CLEANUP+=( "dir:$PROBE_DIR" )
  cat > "$PROBE_DIR/TlsProviderProbe.java" <<'JAVA'
public class TlsProviderProbe {
    public static void main(String[] a) throws Exception {
        // Reflection so this compiles with only java.base and works whether netty
        // is relocated (shaded no-dependencies jar -> shaded_package.io.netty) or
        // not (assembly jar-with-dependencies -> io.netty).
        String[] candidates = {"io.netty.handler.ssl.OpenSsl", "shaded_package.io.netty.handler.ssl.OpenSsl"};
        Class<?> k = null;
        for (String n : candidates) { try { k = Class.forName(n); break; } catch (Throwable ignore) {} }
        if (k == null) { System.out.println("PROBE: OpenSsl class not found on classpath"); System.exit(2); }
        boolean ok = (Boolean) k.getMethod("isAvailable").invoke(null);
        System.out.println("PROBE: OpenSsl.class=" + k.getName());
        System.out.println("PROBE: OpenSsl.isAvailable=" + ok);
        if (!ok) {
            System.out.println("PROBE: OpenSsl.unavailabilityCause=" + k.getMethod("unavailabilityCause").invoke(null));
            System.exit(3);
        }
        System.out.println("PROBE: OpenSsl.versionString=" + k.getMethod("versionString").invoke(null));
        if ("true".equals(System.getenv("PROBE_EPOLL"))) {
            Class<?> e = Class.forName("io.netty.channel.epoll.Epoll");
            boolean epoll = (Boolean) e.getMethod("isAvailable").invoke(null);
            System.out.println("PROBE: Epoll.isAvailable=" + epoll);
            if (!epoll) {
                System.out.println("PROBE: Epoll.unavailabilityCause=" + e.getMethod("unavailabilityCause").invoke(null));
                System.exit(4);
            }
        }
    }
}
JAVA
  # Compile with a throwaway full JDK (the runtime image is trimmed and has no compiler).
  docker run --rm -v "$PROBE_DIR":/probe "$PROBE_JDK_IMAGE" javac /probe/TlsProviderProbe.java \
    || fail "probe compile failed"
  [ -f "$PROBE_DIR/TlsProviderProbe.class" ] || fail "probe class not produced — failing closed"
}

# Run the compiled probe in the IMAGE's own JVM. Any args passed to this function
# are inserted as `docker run` OPTIONS before the image ref (e.g. --read-only), so
# callers can vary the runtime sandbox without duplicating the invocation. Prints
# the probe output (indented) and returns non-zero unless it reports
# OpenSsl.isAvailable=true (a container that will not start also returns non-zero).
run_provider_probe() {
  ensure_probe_compiled
  local out rc=0
  out="$(docker run --rm "$@" --entrypoint "$IN_IMAGE_JAVA" -v "$PROBE_DIR":/probe:ro \
        "$IMAGE_TAG" -cp "$IN_IMAGE_JAR:/probe" TlsProviderProbe 2>&1)" || rc=$?
  echo "$out" | sed 's/^/    /'
  [ "$rc" -eq 0 ] || return 1
  grep -q 'OpenSsl.isAvailable=true' <<<"$out"
}

# ── Assert the native provider actually LOADS in the image's own JVM ────────────
# NOTE: the assembly jar bundles the per-platform tcnative natives, so Netty loads
# the native FROM THE JAR — this proves the image reproduces source=download's real
# native-TLS behaviour, but does NOT isolate the /usr/lib .so this image downloads
# (that would stay true even against a wrong-version standalone .so). The download
# is covered separately: verify-tcnative-stamp.sh + the Dockerfile's empty-check
# guard the stamp, and assert_so_present asserts the arch-correct .so is present.
# The /usr/lib copy's actual PURPOSE — the read-only-rootfs fallback — is exercised
# by assert_native_provider_readonly below, not here (with a writable /tmp Netty
# just extracts the native from the jar and never touches /usr/lib).
assert_native_provider() {
  echo "--- :lock: asserting the native TLS provider and epoll transport load in the image JVM (OpenSsl.isAvailable, Epoll.isAvailable)"
  run_provider_probe -e PROBE_EPOLL=true \
    || fail "native TLS provider or epoll transport did not load — a native is missing, wrong-arch, or a version mismatch, failing closed"
  echo "    :white_check_mark: native TLS provider and epoll transport are available in the image JVM"
}

# ── Assert native TLS survives a READ-ONLY rootfs — the /usr/lib copy's whole job ─
# THIS is the guard that protects the `COPY ... /usr/lib/` line. Under `docker run
# --read-only` (deliberately WITHOUT `--tmpfs /tmp`), Netty's NativeLibraryLoader
# cannot extract the bundled native to a read-only /tmp, so BoringSSL native TLS
# survives ONLY because System.loadLibrary finds /usr/lib/libnetty_tcnative_linux_*.so
# on the default java.library.path. Remove or break that copy and this — and only
# this — goes red; assert_so_present (file existence) and assert_native_provider
# (jar-bundled native on a writable /tmp) both stay green, which is exactly how a
# refactor could silently downgrade --read-only deployments to the JDK provider.
# We add NO writable path: the probe needs none (the JVM tolerates a read-only /tmp
# for hsperfdata), and adding `--tmpfs /tmp` would re-enable jar extraction and
# defeat the test.
assert_native_provider_readonly() {
  echo "--- :lock: asserting native TLS still loads under a READ-ONLY rootfs (docker run --read-only, no --tmpfs /tmp) — this is what exercises the /usr/lib tcnative .so fallback"
  run_provider_probe --read-only \
    || fail "native TLS provider did NOT load under --read-only (OpenSsl.isAvailable=false) — the /usr/lib tcnative .so fallback is broken or missing; read-only-rootfs deployments would silently downgrade to the JDK provider, failing closed"
  echo "    :white_check_mark: native TLS provider is available under --read-only — the /usr/lib .so fallback works"
}

# ── Assert the container starts, is healthy, and serves a real TLS handshake ────
assert_starts_and_serves_tls() {
  echo "--- :whale: asserting the container starts, is healthy, and serves TLS"
  local cname port
  cname="docker-build-verify-$$"
  docker run -d --name "$cname" -p 0:1080 "$IMAGE_TAG" >/dev/null || fail "docker run failed"
  CLEANUP+=( "container:$cname" )
  # Readiness via the image's own HealthCheck (http status endpoint).
  local healthy=0 attempt
  for attempt in $(seq 1 60); do
    : "$attempt"
    if docker exec "$cname" "$IN_IMAGE_JAVA" -cp "$IN_IMAGE_JAR" org.mockserver.cli.HealthCheck >/dev/null 2>&1; then
      healthy=1; break
    fi
    sleep 1
  done
  [ "$healthy" -eq 1 ] || { docker logs "$cname" 2>&1 | tail -30 >&2; fail "container never became healthy — failing closed"; }
  echo "    :white_check_mark: container healthy (HealthCheck returned 200)"
  # Real TLS handshake on the mapped port (MockServer serves HTTP+HTTPS on one port).
  port="$(docker port "$cname" 1080/tcp | head -n1 | sed 's/.*://')"
  [ -n "$port" ] || fail "could not determine mapped port — failing closed"
  local code
  code="$(curl -sk -o /dev/null -w '%{http_code}' -X PUT "https://localhost:${port}/mockserver/status" --max-time 15 || echo 000)"
  if [ "$code" != "200" ]; then
    docker logs "$cname" 2>&1 | tail -30 >&2
    fail "TLS request to /mockserver/status returned HTTP '$code' (expected 200) — TLS did not serve, failing closed"
  fi
  echo "    :white_check_mark: TLS handshake + PUT /mockserver/status returned 200"
}

# ── Assert the jvm_memory_allocated_bytes Prometheus metric is actually emitted ──
# This is the guard for the jlink module set. jvm_memory_allocated_bytes comes from
# com.sun.management.ThreadMXBean, which lives in the jdk.management module; java.se
# aggregates only java.* modules, so a jlink runtime that forgets jdk.management drops
# the class, JvmMetricsCollector returns -1, and the metric line vanishes from the
# scrape with NO other symptom (the server starts, every other jvm_* metric is present).
# It went unnoticed in every shipped image until it was found by hand. We start the
# image with metrics enabled and assert the line is present in the Prometheus scrape —
# remove jdk.management from the jlink --add-modules and this goes red.
assert_jvm_memory_allocated_metric() {
  echo "--- :bar_chart: asserting jvm_memory_allocated_bytes is emitted (guards the jdk.management jlink module)"
  local cname port
  cname="docker-build-verify-metrics-$$"
  docker run -d --name "$cname" -e MOCKSERVER_METRICS_ENABLED=true -p 0:1080 "$IMAGE_TAG" >/dev/null \
    || fail "docker run (metrics) failed"
  CLEANUP+=( "container:$cname" )
  local healthy=0 attempt
  for attempt in $(seq 1 60); do
    : "$attempt"
    if docker exec "$cname" "$IN_IMAGE_JAVA" -cp "$IN_IMAGE_JAR" org.mockserver.cli.HealthCheck >/dev/null 2>&1; then
      healthy=1; break
    fi
    sleep 1
  done
  [ "$healthy" -eq 1 ] || { docker logs "$cname" 2>&1 | tail -30 >&2; fail "metrics container never became healthy — failing closed"; }
  port="$(docker port "$cname" 1080/tcp | head -n1 | sed 's/.*://')"
  [ -n "$port" ] || fail "could not determine mapped port for metrics — failing closed"
  local body
  body="$(curl -s "http://localhost:${port}/mockserver/metrics" --max-time 15 || true)"
  if ! grep -q 'jvm_memory_allocated_bytes' <<<"$body"; then
    echo "    scrape (first 40 lines):" >&2
    head -40 <<<"$body" | sed 's/^/    /' >&2
    fail "jvm_memory_allocated_bytes MISSING from the Prometheus scrape — the jlink runtime is likely missing jdk.management (com.sun.management.ThreadMXBean), failing closed"
  fi
  echo "    :white_check_mark: jvm_memory_allocated_bytes present in the Prometheus scrape"
}

# ── Assert the real ENTRYPOINT runs with the Base64 intrinsic off (CVE-2026-114938) ──
# JAVA_TOOL_OPTIONS is prepended to the ENTRYPOINT args, so the flag must still read false from it.
assert_base64_intrinsic_disabled() {
  echo "--- :lock: asserting the ENTRYPOINT runs with UseBASE64Intrinsics = false, even when JAVA_TOOL_OPTIONS enables it"
  local out
  out="$(docker run --rm -e JAVA_TOOL_OPTIONS="-XX:+UseZGC -XX:+UseBASE64Intrinsics -XX:+PrintFlagsFinal" "$IMAGE_TAG" --version 2>&1 || true)"
  if ! grep -qE '^[[:space:]]*bool UseBASE64Intrinsics[[:space:]]+= false[[:space:]]+\{product\} \{command line\}' <<<"$out"; then
    grep -E 'UseBASE64Intrinsics|Error' <<<"$out" | sed 's/^/    /' >&2
    fail "UseBASE64Intrinsics is not false from the ENTRYPOINT's command line — failing closed"
  fi
  echo "    :white_check_mark: UseBASE64Intrinsics = false {command line}"
}

# ── Assert an extension JAR mounted read-only at /extensions loads, with AppCDS still mapped ──
# Every image appends /extensions/* to its classpath. -Xshare:on makes the JVM exit when the AppCDS
# archive cannot map, so a classpath that no longer extends the archive's training classpath fails here.
assert_extension_loads_with_cds() {
  echo "--- :jigsaw: asserting a JAR mounted read-only at /extensions loads, with the AppCDS archive mapped (-Xshare:on)"
  local ext_dir cid cname port body="" attempt
  ext_dir="$(mktemp -d)"
  CLEANUP+=( "dir:$ext_dir" )
  mkdir -p "$ext_dir/src/verify" "$ext_dir/classes" "$ext_dir/extensions"
  cid="$(docker create "$IMAGE_TAG")" || fail "docker create '$IMAGE_TAG' failed"
  CLEANUP+=( "container:$cid" )
  docker cp "$cid:/mockserver.jar" "$ext_dir/mockserver.jar" >/dev/null || fail "could not copy /mockserver.jar out of the image"
  docker rm -f "$cid" >/dev/null 2>&1 || true
  cat > "$ext_dir/src/verify/VerifyExtension.java" <<'JAVA'
package verify;

import org.mockserver.extension.Extension;
import org.mockserver.mock.Expectation;
import org.mockserver.server.initialize.ExpectationInitializer;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class VerifyExtension implements Extension, ExpectationInitializer {
    public String name() {
        return "verify";
    }

    public Expectation[] initializeExpectations() {
        String source = String.valueOf(VerifyExtension.class.getProtectionDomain().getCodeSource().getLocation());
        return new Expectation[]{new Expectation(request().withPath("/verify-extension")).thenRespond(response().withBody("loaded from " + source))};
    }
}
JAVA
  docker run --rm --user "$(id -u):$(id -g)" -v "$ext_dir":/ext "$PROBE_JDK_IMAGE" sh -c \
    'javac --release 17 -cp /ext/mockserver.jar -d /ext/classes /ext/src/verify/VerifyExtension.java && jar cf /ext/extensions/verify-extension.jar -C /ext/classes .' \
    || fail "extension compile failed"
  # the image runs as nonroot, and mktemp -d is private to this user
  chmod a+rx "$ext_dir" "$ext_dir/extensions" && chmod a+r "$ext_dir/extensions/verify-extension.jar"
  cname="docker-build-verify-extension-$$"
  docker run -d --name "$cname" -p 0:1080 -v "$ext_dir/extensions":/extensions:ro \
    -e MOCKSERVER_EXTENSIONS=verify.VerifyExtension -e JAVA_TOOL_OPTIONS="-XX:+UseZGC -Xshare:on" "$IMAGE_TAG" >/dev/null \
    || fail "docker run (extension) failed"
  CLEANUP+=( "container:$cname" )
  port="$(docker port "$cname" 1080/tcp | head -n1 | sed 's/.*://')"
  [ -n "$port" ] || fail "could not determine mapped port for the extension container — failing closed"
  for attempt in $(seq 1 60); do
    : "$attempt"
    body="$(curl -s "http://localhost:${port}/verify-extension" --max-time 5 || true)"
    [ -n "$body" ] && break
    sleep 1
  done
  if [ "$body" != "loaded from file:/extensions/verify-extension.jar" ]; then
    docker logs "$cname" 2>&1 | tail -30 >&2
    fail "GET /verify-extension returned '${body:-nothing}', expected 'loaded from file:/extensions/verify-extension.jar' — the /extensions classpath entry or the AppCDS archive is broken, failing closed"
  fi
  echo "    :white_check_mark: extension loaded from /extensions with -Xshare:on (AppCDS archive mapped)"
}

# ══ 1. Build the image (unless asserting against an existing one) ═══════════════
if [ "${DOCKER_VERIFY_SKIP_BUILD:-}" = "true" ]; then
  echo "--- :fast_forward: DOCKER_VERIFY_SKIP_BUILD=true — asserting against existing image '$IMAGE_TAG' (no build, no derive)"
else
  # Ensure both stamped jars exist, then run the fast inner gate over them.
  JAR="$(locate_or_build_jars)"

  echo "--- :zap: inner gate: two-jar tcnative stamp derive check"
  "$STEPS_DIR/verify-tcnative-stamp.sh"

  echo "--- :package: staging $(basename "$JAR") into the build context"
  cp "$JAR" "$DOCKER_CONTEXT/mockserver-netty-jar-with-dependencies.jar"
  CLEANUP+=( "file:$DOCKER_CONTEXT/mockserver-netty-jar-with-dependencies.jar" )

  ca_state="$("$REPO_ROOT/docker/ensure-ca-bundle.sh" "$DOCKER_CONTEXT")"
  if [ "$ca_state" = "created" ]; then
    CLEANUP+=( "file:$DOCKER_CONTEXT/ca-bundle.pem" )
  fi

  echo "--- :docker: building source=copy image (host arch $HOST_ARCH, no TARGETARCH build-arg) → $IMAGE_TAG"
  docker build --no-cache \
    --build-arg source=copy \
    -t "$IMAGE_TAG" \
    "$DOCKER_CONTEXT" \
    || fail "docker build failed"
  # Remove the image we built on exit unless the caller wants to keep it.
  [ "${DOCKER_VERIFY_KEEP_IMAGE:-}" = "true" ] || CLEANUP+=( "image:$IMAGE_TAG" )
fi

# ══ 2. Runtime assertions ═══════════════════════════════════════════════════════
assert_so_present
assert_native_provider
assert_native_provider_readonly
assert_starts_and_serves_tls
assert_jvm_memory_allocated_metric
assert_extension_loads_with_cds
assert_base64_intrinsic_disabled

echo "--- :white_check_mark: docker build verify PASSED (image builds, native tcnative loads incl. under --read-only, TLS serves, jvm_memory_allocated_bytes emitted, /extensions JAR loads with AppCDS mapped, Base64 intrinsic off)"
