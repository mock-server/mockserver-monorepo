#!/usr/bin/env bash
#
# Fail the build unless the shaded server jar's native libraries actually work on this build machine, and a user's
# library system properties reach the relocated libraries. Packaging checks cannot see the failure in issue #2869:
# zstd-jni's library loaded and netty's Zstd.isAvailable() was true, but every native call threw UnsatisfiedLinkError.
# So this runs the jar: a zstd, snappy and lz4 round trip, netty's QUIC, epoll and OpenSSL natives where the jar
# bundles them for this platform, -Dio.netty.leakDetection.level forwarded by the CLI entry point, and the bundled
# Kafka client still refusing the JDK JndiLoginModule (its deny-list names JDK classes that must not be relocated).
#
# usage: assert-shaded-natives-load.sh <shaded-jar> <java>
set -euo pipefail

JAR="${1:?usage: assert-shaded-natives-load.sh <shaded-jar> <java>}"
JAVA="${2:?usage: assert-shaded-natives-load.sh <shaded-jar> <java>}"
[ -f "$JAR" ] || { echo "ERROR: shaded jar not found: $JAR" >&2; exit 1; }
ENTRIES="$(unzip -Z1 "$JAR")"

case "$(uname -s)" in Linux) OS=linux ;; Darwin) OS=osx ;; *) OS=other ;; esac
case "$(uname -m)" in x86_64|amd64) ARCH=x86_64 ;; aarch64|arm64) ARCH=aarch_64 ;; *) ARCH=other ;; esac
# Which netty natives this jar carries for this platform, so a platform it has none for is not a failure.
bundled() { grep -qE "^META-INF/native/(lib)?(shaded_1package_)?netty_$1[^/]*_${ARCH}\.(so|jnilib|dylib)$" <<<"$ENTRIES" && echo true || echo false; }
EXPECT_QUIC="$(bundled "quiche[0-9]*_${OS}")"
EXPECT_EPOLL=false; [ "$OS" = linux ] && EXPECT_EPOLL="$(bundled transport_native_epoll)"
EXPECT_OPENSSL="$(bundled "tcnative_${OS}")"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
cat > "$WORK_DIR/NativesProbe.java" <<'JAVA'
import java.util.Arrays;

public class NativesProbe {
    static final byte[] DATA = "{\"hello\":\"world\",\"padding\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}".getBytes();
    static String netty;
    static int failures;

    interface Check { String run() throws Throwable; }

    static void check(String name, Check check) {
        try {
            System.out.println("ok   " + name + ": " + check.run());
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            System.out.println("FAIL " + name + ": " + root);
            failures++;
        }
    }

    static Object call(String className, String method, Class<?>[] types, Object target, Object... args) throws Exception {
        return Class.forName(className).getMethod(method, types).invoke(target, args);
    }

    static String available(String nettyClass) throws Exception {
        Class<?> type = Class.forName(netty + nettyClass);
        if (!(Boolean) type.getMethod("isAvailable").invoke(null)) {
            throw new IllegalStateException("isAvailable() is false: " + type.getMethod("unavailabilityCause").invoke(null));
        }
        return "isAvailable() is true";
    }

    public static void main(String[] args) throws Exception {
        Class.forName("org.mockserver.cli.Main");
        try {
            Class.forName("shaded_package.io.netty.util.internal.NativeLibraryLoader", false, NativesProbe.class.getClassLoader());
            netty = "shaded_package.io.netty.";
        } catch (ClassNotFoundException unshaded) {
            netty = "io.netty.";
        }
        check("zstd-jni round trip", () -> {
            byte[] compressed = (byte[]) call("com.github.luben.zstd.Zstd", "compress", new Class<?>[]{byte[].class}, null, (Object) DATA);
            byte[] restored = (byte[]) call("com.github.luben.zstd.Zstd", "decompress", new Class<?>[]{byte[].class, int.class}, null, compressed, DATA.length);
            if (!Arrays.equals(DATA, restored)) throw new IllegalStateException("decompressed bytes differ");
            return "recommendedDInSize=" + call("com.github.luben.zstd.ZstdInputStreamNoFinalizer", "recommendedDInSize", new Class<?>[0], null);
        });
        check("netty zstd codec", () -> available("handler.codec.compression.Zstd"));
        check("snappy-java round trip", () -> {
            byte[] compressed = (byte[]) call("org.xerial.snappy.Snappy", "compress", new Class<?>[]{byte[].class}, null, (Object) DATA);
            if (!Arrays.equals(DATA, (byte[]) call("org.xerial.snappy.Snappy", "uncompress", new Class<?>[]{byte[].class}, null, (Object) compressed))) {
                throw new IllegalStateException("decompressed bytes differ");
            }
            return "ok";
        });
        check("lz4-java native", () -> {
            Object factory = call("net.jpountz.lz4.LZ4Factory", "nativeInstance", new Class<?>[0], null);
            Object compressor = factory.getClass().getMethod("fastCompressor").invoke(factory);
            return compressor.getClass().getMethod("compress", byte[].class).invoke(compressor, (Object) DATA).getClass().getSimpleName();
        });
        if (Boolean.parseBoolean(args[0])) check("netty QUIC native", () -> available("handler.codec.quic.Quic"));
        if (Boolean.parseBoolean(args[1])) check("netty epoll native", () -> available("channel.epoll.Epoll"));
        if (Boolean.parseBoolean(args[2])) check("netty OpenSSL native", () -> available("handler.ssl.OpenSsl"));
        check("-Dio.netty.leakDetection.level reaches relocated Netty", () -> {
            Object level = Class.forName(netty + "util.ResourceLeakDetector").getMethod("getLevel").invoke(null);
            if (!"PARANOID".equals(String.valueOf(level))) throw new IllegalStateException("level is " + level);
            return "level is " + level;
        });
        Class<?> jaas = null;
        for (String name : new String[]{"shaded_package.org.apache.kafka.common.security.JaasContext", "org.apache.kafka.common.security.JaasContext"}) {
            try { jaas = Class.forName(name, false, NativesProbe.class.getClassLoader()); break; } catch (ClassNotFoundException absent) { }
        }
        if (jaas != null) {
            Class<?> jaasContext = jaas;
            check("Kafka refuses the JDK JndiLoginModule", () -> {
                java.lang.reflect.Method guard = jaasContext.getDeclaredMethod("throwIfLoginModuleIsNotAllowed", javax.security.auth.login.AppConfigurationEntry.class);
                guard.setAccessible(true);
                try {
                    guard.invoke(null, new javax.security.auth.login.AppConfigurationEntry("com.sun.security.auth.module.JndiLoginModule",
                        javax.security.auth.login.AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, java.util.Map.of()));
                } catch (java.lang.reflect.InvocationTargetException refused) {
                    return "refused: " + refused.getCause().getClass().getSimpleName();
                }
                throw new IllegalStateException("allowed - its deny-list names a relocated com.sun class");
            });
        }
        if (failures > 0) System.exit(1);
    }
}
JAVA

if ! OUTPUT="$(env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS "$JAVA" -Dio.netty.leakDetection.level=paranoid \
    --enable-native-access=ALL-UNNAMED -cp "$JAR" "$WORK_DIR/NativesProbe.java" "$EXPECT_QUIC" "$EXPECT_EPOLL" "$EXPECT_OPENSSL" 2>&1)"; then
  echo "ERROR: $(basename "$JAR") fails its runtime relocation checks on $OS-$ARCH:" >&2
  grep -E '^(ok|FAIL) ' <<<"$OUTPUT" >&2 || printf '%s\n' "$OUTPUT" >&2
  echo "" >&2
  echo "  See assert-jni-libraries-unrelocated.sh and the relocations in mockserver/pom.xml." >&2
  exit 1
fi
grep -E '^ok ' <<<"$OUTPUT" | sed 's/^/  /'
echo "OK: $(basename "$JAR") loads its natives on $OS-$ARCH (QUIC: $EXPECT_QUIC, epoll: $EXPECT_EPOLL, OpenSSL: $EXPECT_OPENSSL)."
