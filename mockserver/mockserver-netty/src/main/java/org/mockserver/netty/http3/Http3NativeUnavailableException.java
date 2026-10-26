package org.mockserver.netty.http3;

import io.netty.handler.codec.quic.Quic;
import io.netty.util.internal.PlatformDependent;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.mockserver.version.Version;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.substringBefore;

/**
 * Thrown at start-up when {@code http3Port} is set but the QUIC native library cannot be loaded.
 * <p>
 * The message is the product here: it is the only thing between a user and a server that will not
 * start, so it names every fix with the exact image tag, jar name, Maven coordinate and platform
 * classifier for THIS runtime, and the CLI prints it without a stack trace. It stays an
 * {@link IllegalStateException} so embedded callers that already catch that keep working.
 */
public class Http3NativeUnavailableException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public static final List<String> SUPPORTED_PLATFORMS = List.of("linux-x86_64", "linux-aarch_64", "osx-x86_64", "osx-aarch_64", "windows-x86_64");

    // Built with '!' then replaced: maven-shade rewrites string constants that START with a relocated
    // package ("io.netty..." -> "shaded_package.io.netty..."), which would silently corrupt both the
    // relocation check and the Maven coordinate in the shaded jar. Netty's own loader uses this trick.
    private static final String NETTY_GROUP_ID = "io!netty".replace('!', '.');
    private static final String NATIVE_LIBRARY_LOADER = "io!netty!util!internal!NativeLibraryLoader".replace('!', '.');
    // Netty's own library stem (Quiche.loadNativeLibrary); if a Netty upgrade renames it, presence
    // detection only degrades to the "not available" advice.
    private static final String QUICHE_LIBRARY_STEM = "netty_quiche42";

    public Http3NativeUnavailableException(int http3Port, Throwable cause) {
        super(message(http3Port, Version.getVersion(), nettyVersionOnClasspath(), platformClassifier(), nettyIsRelocated(),
            presentNativeLocation(), underlyingError(cause)), cause);
    }

    /**
     * @param presentNative where a native for this platform was found despite failing to load, or null
     * @param underlyingError one line naming the root cause, or null
     */
    static String message(int http3Port, String mockServerVersion, String nettyVersion, String platformClassifier, boolean nettyRelocated,
                          String presentNative, String underlyingError) {
        String cause = isBlank(underlyingError) ? "" : "\nunderlying error: " + underlyingError;
        if (!isBlank(presentNative)) {
            return "HTTP/3 is enabled (http3Port=" + http3Port + ") but the QUIC native library for " + platformClassifier
                + " failed to load, so MockServer cannot start.\n"
                + "The native library is present but failed to load, so a different image or jar will not help.\n"
                + "Found: " + presentNative + "\n"
                + "Fix it with one of:\n"
                + "  - check it matches this platform and Netty " + (isBlank(nettyVersion) ? "version" : nettyVersion)
                + ", that java.library.path still includes its directory,\n"
                + "    and that Netty's native work directory (io.netty.native.workdir, default java.io.tmpdir) is writable and executable\n"
                + "  - or remove http3Port to run without HTTP/3"
                + cause;
        }
        String version = isBlank(mockServerVersion) ? "<version>" : mockServerVersion;
        String imageTag = version.endsWith("-SNAPSHOT") ? "snapshot-http3" : version + "-http3";
        String nativeCoordinate = NETTY_GROUP_ID + ":netty-codec-native-quic:" + (isBlank(nettyVersion) ? "<netty version>" : nettyVersion) + ":" + platformClassifier;
        boolean supportedPlatform = SUPPORTED_PLATFORMS.contains(platformClassifier);

        StringBuilder message = new StringBuilder()
            .append("HTTP/3 is enabled (http3Port=").append(http3Port).append(") but the QUIC native library for ")
            .append(platformClassifier).append(" is not available, so MockServer cannot start.\n")
            .append("HTTP/1.1 and HTTP/2 need nothing extra; HTTP/3 is experimental and its native library ships separately.\n");
        if (!supportedPlatform) {
            message.append("Netty publishes that library only for ").append(String.join(", ", SUPPORTED_PLATFORMS))
                .append(", so on ").append(platformClassifier).append(" run MockServer in the container image below.\n");
        }
        message.append("Fix it with one of:\n")
            .append("  - Docker, Docker Compose or Kubernetes: use the image mockserver/mockserver:").append(imageTag)
            .append(" (Helm: --set image.variant=http3)\n");
        if (supportedPlatform) {
            message.append("  - standalone jar: run mockserver-netty-").append(version)
                .append("-jar-with-dependencies-http3.jar instead of the default jar\n");
            if (nettyRelocated) {
                message.append("  - Maven or Gradle: mockserver-netty-no-dependencies bundles this native under its relocated name; if it is gone,\n")
                    .append("    depend on org.mock-server:mockserver-netty, which brings ").append(nativeCoordinate).append("\n");
            } else {
                message.append("  - Maven or Gradle: add the runtime dependency ").append(nativeCoordinate).append("\n");
            }
        }
        return message.append("  - or remove http3Port to run without HTTP/3").append(cause).toString();
    }

    static String underlyingError(Throwable cause) {
        Throwable root = cause == null ? null : ExceptionUtils.getRootCause(cause);
        if (root == null) {
            return null;
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (isBlank(message) ? "" : ": " + substringBefore(message.trim(), "\n"));
    }

    /**
     * Where a QUIC native for this platform, under the name this Netty asks for, is present: as a
     * classpath resource, in a java.library.path directory, or in /usr/lib (where the -http3 image
     * installs it, so a broken java.library.path is still recognised). Null when none is found.
     */
    static String presentNativeLocation() {
        try {
            String name = nativeLibraryPrefix() + QUICHE_LIBRARY_STEM + "_" + platformClassifier().replace('-', '_');
            List<String> fileNames = new ArrayList<>(Arrays.asList(System.mapLibraryName(name), "lib" + name + ".jnilib"));
            ClassLoader classLoader = Quic.class.getClassLoader();
            for (String fileName : fileNames) {
                if (classLoader != null && classLoader.getResource("META-INF/native/" + fileName) != null) {
                    return "classpath META-INF/native/" + fileName;
                }
            }
            List<String> directories = new ArrayList<>(Arrays.asList(System.getProperty("java.library.path", "").split(File.pathSeparator)));
            directories.add("/usr/lib");
            for (String directory : directories) {
                for (String fileName : fileNames) {
                    File file = new File(directory, fileName);
                    if (!directory.isEmpty() && file.isFile()) {
                        return file.getPath();
                    }
                }
            }
        } catch (Throwable ignore) {
            // detection is best effort
        }
        return null;
    }

    // The prefix Netty's NativeLibraryLoader adds under relocation: its package, '_' -> '_1', '.' -> '_'.
    private static String nativeLibraryPrefix() {
        String loader = io.netty.util.internal.NativeLibraryLoader.class.getName();
        String prefix = loader.endsWith(NATIVE_LIBRARY_LOADER) ? loader.substring(0, loader.length() - NATIVE_LIBRARY_LOADER.length()) : "";
        return prefix.replace("_", "_1").replace('.', '_');
    }

    /**
     * The version of the Netty QUIC classes actually on the classpath, which is what the native must
     * match; an embedding application can manage Netty to a version other than the one MockServer was
     * built with. The shaded jar strips Netty's version resources, so fall back to the build-time version.
     */
    static String nettyVersionOnClasspath() {
        try {
            Map<String, io.netty.util.Version> versions = io.netty.util.Version.identify(Quic.class.getClassLoader());
            for (String artifactId : Arrays.asList("netty-codec-classes-quic", "netty-codec-http3", "netty-common")) {
                io.netty.util.Version version = versions.get(artifactId);
                if (version != null && !isBlank(version.artifactVersion())) {
                    return version.artifactVersion();
                }
            }
            for (io.netty.util.Version version : versions.values()) {
                if (!isBlank(version.artifactVersion())) {
                    return version.artifactVersion();
                }
            }
        } catch (Throwable ignore) {
            // fall through to the build-time version
        }
        return Version.getNettyVersion();
    }

    static String platformClassifier() {
        try {
            return PlatformDependent.normalizedOs() + "-" + PlatformDependent.normalizedArch();
        } catch (Throwable throwable) {
            return "<os>-<arch>";
        }
    }

    static boolean nettyIsRelocated() {
        try {
            return !Quic.class.getName().startsWith(NETTY_GROUP_ID + ".");
        } catch (Throwable throwable) {
            return false;
        }
    }
}
