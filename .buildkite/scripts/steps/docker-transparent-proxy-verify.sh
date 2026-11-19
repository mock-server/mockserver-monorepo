#!/usr/bin/env bash
# Prove a built MockServer image resolves a transparently intercepted connection's original
# destination with SO_ORIGINAL_DST (JNA getsockopt), not just that the resolver classes exist.
#
#   1. In the image's own JVM: SoOriginalDstResolver.isSupported() and
#      EbpfOriginalDestinationResolver.isPlatformSupported() are true (Linux + epoll + JNA loads).
#   2. End to end: iptables REDIRECTs a client's connection for 10.99.99.1:8080 to MockServer, which
#      has no expectations, so it forwards to the resolved original destination. The client sends a
#      WRONG Host header, so only a socket-level lookup reaches the origin (a busybox httpd on
#      10.99.99.1:8080). The resolver chain logs the same line whichever strategy answered; TPROXY and
#      eBPF are off, and the script fails unless conntrack is unreadable to MockServer in its network
#      namespace, so SO_ORIGINAL_DST is the only strategy that can have answered.
#
# The sidecars share the MockServer container's network namespace (the image is distroless, so
# it has no iptables or shell). Needs NET_ADMIN for the sidecars, so it is a local/opt-in check.
# usage: docker-transparent-proxy-verify.sh <image>
# Self-test: TPROXY_VERIFY_SKIP_PROBE=true skips step 1's assertion, to show step 2 alone goes red
# on an image whose resolvers do not load.
set -euo pipefail

IMAGE="${1:?usage: docker-transparent-proxy-verify.sh <image>}"
IN_IMAGE_JAVA="/usr/lib/jvm/temurin-trimmed/bin/java"
IN_IMAGE_CP="/mockserver.jar:/mockserver-deps.jar"
PROBE_JDK_IMAGE="eclipse-temurin:25-jdk-noble"
SUFFIX="$$"
TOOLS_IMAGE="mockserver-transparent-proxy-verify-tools:$SUFFIX"

fail() { echo "+++ :bangbang: $*" >&2; exit 1; }
CLEANUP=()
cleanup() {
  local item
  for item in ${CLEANUP[@]+"${CLEANUP[@]}"}; do
    case "$item" in
      container:*) docker rm -f "${item#container:}" >/dev/null 2>&1 || true ;;
      image:*)     docker rmi -f "${item#image:}" >/dev/null 2>&1 || true ;;
      dir:*)       rm -rf "${item#dir:}" || true ;;
    esac
  done
}
trap cleanup EXIT

PROBE_DIR="$(mktemp -d)"
CLEANUP+=("dir:$PROBE_DIR")
cat > "$PROBE_DIR/ResolverProbe.java" <<'JAVA'
public class ResolverProbe {
    public static void main(String[] a) throws Exception {
        if (a.length > 0 && a[0].equals("conntrack")) {
            // The conntrack resolver's sources; /proc/net is per network namespace.
            boolean readable = false;
            for (String p : new String[]{"/proc/net/nf_conntrack", "/proc/net/ip_conntrack"}) {
                try (java.io.InputStream in = new java.io.FileInputStream(p)) {
                    in.read();
                    readable = true;
                } catch (java.io.IOException ignored) {
                    // absent or unreadable
                }
            }
            System.out.println("PROBE: conntrackReadable=" + readable);
            return;
        }
        // Reflection: compiles with java.base only; the classes come from the image's classpath.
        Object so = Class.forName("org.mockserver.netty.proxy.SoOriginalDstResolver").getConstructor().newInstance();
        boolean soOk = (Boolean) so.getClass().getMethod("isSupported").invoke(so);
        Class<?> ebpfClass = Class.forName("org.mockserver.netty.proxy.EbpfOriginalDestinationResolver");
        Object ebpf = ebpfClass.getConstructors()[0].newInstance(new Object[]{null});
        boolean ebpfOk = (Boolean) ebpfClass.getMethod("isPlatformSupported").invoke(ebpf);
        System.out.println("PROBE: SoOriginalDstResolver.isSupported=" + soOk);
        System.out.println("PROBE: EbpfOriginalDestinationResolver.isPlatformSupported=" + ebpfOk);
        System.exit(soOk && ebpfOk ? 0 : 3);
    }
}
JAVA
docker run --rm -v "$PROBE_DIR":/probe "$PROBE_JDK_IMAGE" javac --release 17 /probe/ResolverProbe.java \
  || fail "probe compile failed"
# Runs the probe in the image's JVM as the image's own user; extra args go before the image ref.
run_probe() {
  local mode="$1"; shift
  docker run --rm "$@" --entrypoint "$IN_IMAGE_JAVA" -v "$PROBE_DIR":/probe:ro "$IMAGE" \
    --enable-native-access=ALL-UNNAMED -cp "$IN_IMAGE_CP:/probe" ResolverProbe "$mode" 2>&1
}

if [ "${TPROXY_VERIFY_SKIP_PROBE:-}" = "true" ]; then
  echo "--- :fast_forward: TPROXY_VERIFY_SKIP_PROBE=true - skipping the in-JVM resolver assertion"
else
  echo "--- :java: resolver platform support in the image JVM ($IMAGE)"
  probe_rc=0
  probe_out="$(run_probe support)" || probe_rc=$?
  echo "$probe_out" | sed 's/^/    /'
  [ "$probe_rc" -eq 0 ] || fail "the JNA-based original-destination resolvers are unsupported in $IMAGE (JNA or epoll did not load)"
fi

echo "--- :shield: SO_ORIGINAL_DST end to end through $IMAGE"
docker build -q -t "$TOOLS_IMAGE" - >/dev/null <<'DOCKERFILE' || fail "could not build the iptables/curl sidecar image"
FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y iptables curl iproute2 && rm -rf /var/lib/apt/lists/*
DOCKERFILE
CLEANUP+=("image:$TOOLS_IMAGE")

MS="tproxy-verify-ms-$SUFFIX"
docker run -d --name "$MS" -e MOCKSERVER_TRANSPARENT_PROXY_ENABLED=true -e MOCKSERVER_LOG_LEVEL=DEBUG \
  "$IMAGE" >/dev/null || fail "could not start $IMAGE"
CLEANUP+=("container:$MS")

conntrack_out="$(run_probe conntrack --network "container:$MS")" || true
echo "$conntrack_out" | sed 's/^/    /'
grep -qx 'PROBE: conntrackReadable=false' <<<"$conntrack_out" \
  || fail "conntrack is readable (or unchecked) in $IMAGE's network namespace, so a success could not be attributed to SO_ORIGINAL_DST"

ORIGIN="tproxy-verify-origin-$SUFFIX"
docker run -d --name "$ORIGIN" --network "container:$MS" --cap-add=NET_ADMIN busybox:latest sh -c \
  'ip addr add 10.99.99.1/32 dev lo && mkdir -p /www && echo origin-reached > /www/index.html && exec httpd -f -p 10.99.99.1:8080 -h /www' \
  >/dev/null || fail "could not start the origin sidecar"
CLEANUP+=("container:$ORIGIN")

# The REDIRECT matches only root-owned sockets (the curl below); MockServer runs as nonroot, so its
# forward to 10.99.99.1:8080 reaches the origin instead of looping back to itself.
run_rc=0
out="$(docker run --rm --network "container:$MS" --cap-add=NET_ADMIN "$TOOLS_IMAGE" bash -c '
  for i in $(seq 1 60); do
    [ "$(curl -s -o /dev/null -w "%{http_code}" -X PUT http://127.0.0.1:1080/mockserver/status)" = 200 ] && break
    sleep 1
  done
  iptables -t nat -A OUTPUT -d 10.99.99.1 -p tcp --dport 8080 -m owner --uid-owner 0 -j REDIRECT --to-port 1080
  code=$(curl -s -o /tmp/body -w "%{http_code}" --max-time 20 -H "Host: wrong.invalid" http://10.99.99.1:8080/index.html)
  echo "HTTP_CODE=$code"
  echo "BODY=$(cat /tmp/body)"' 2>&1)" || run_rc=$?
echo "$out" | sed 's/^/    /'
if grep -qiE 'incompatible with user namespaces|operation not permitted' <<<"$out"; then
  fail "the daemon refused NET_ADMIN to the sidecar, so nothing was tested (run on a daemon without user-namespace remapping)"
fi
[ "$run_rc" -eq 0 ] || fail "the client sidecar exited $run_rc"
if ! grep -qx 'HTTP_CODE=200' <<<"$out" || ! grep -qx 'BODY=origin-reached' <<<"$out"; then
  docker logs "$MS" 2>&1 | grep -iE 'transparent proxy|original destination|forward' | tail -10 | sed 's/^/    ms: /' >&2 || true
  fail "the intercepted request did not reach its original destination - $IMAGE fell back to the Host header"
fi
resolved="$(docker logs "$MS" 2>&1 | grep -F 'transparent proxy: resolved original destination' | head -1 || true)"
[ -n "$resolved" ] || fail "MockServer did not log that its resolver chain found the original destination"
echo "    ms: ${resolved:0:160}"
echo "    :white_check_mark: $IMAGE resolved 10.99.99.1:8080 with SO_ORIGINAL_DST (conntrack unreadable, TPROXY and eBPF off, Host header wrong) and forwarded to it"
