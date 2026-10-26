package org.mockserver.netty.http3;

import org.junit.Assume;
import org.junit.Test;
import org.mockserver.version.Version;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * The HTTP/3 start-up failure is only reachable where the QUIC native is absent, which is no CI agent
 * or developer machine here, so the message it carries is asserted directly: it is the only thing
 * standing between a user and a server that will not start.
 */
public class Http3NativeUnavailableExceptionTest {

    @Test
    public void shouldNameEveryFixWithExactCoordinatesForThisRuntime() {
        String message = Http3NativeUnavailableException.message(8443, "8.1.0", "4.2.18.Final", "linux-aarch_64", false, null, null);

        assertThat(message, containsString("http3Port=8443"));
        assertThat(message, containsString("QUIC native library for linux-aarch_64"));
        assertThat("container route", message, containsString("mockserver/mockserver:8.1.0-http3"));
        assertThat("helm route", message, containsString("--set image.variant=http3"));
        assertThat("standalone jar route", message, containsString("mockserver-netty-8.1.0-jar-with-dependencies-http3.jar"));
        assertThat("maven route", message, containsString("io.netty:netty-codec-native-quic:4.2.18.Final:linux-aarch_64"));
        assertThat(message, containsString("remove http3Port"));
        assertThat(message, containsString("HTTP/1.1 and HTTP/2 need nothing extra"));
    }

    @Test
    public void shouldBeReadableLinesWithoutStackTraceNoise() {
        String plain = Http3NativeUnavailableException.message(8443, "8.1.0", "4.2.18.Final", "linux-x86_64", false, null, null);
        String relocated = Http3NativeUnavailableException.message(8443, "8.1.0", "4.2.18.Final", "linux-x86_64", true, null, null);

        assertThat(plain.split("\n").length, is(7));
        assertThat(relocated.split("\n").length, is(8));
        for (String line : (plain + "\n" + relocated).split("\n")) {
            assertThat("line too long to read: " + line, line.length(), lessThanOrEqualTo(140));
        }
        assertThat(plain, not(containsString("Exception")));
    }

    @Test
    public void shouldNotSuggestTheUnloadableStockNativeWhenNettyIsRelocated() {
        // mockserver-netty-no-dependencies (and so every published image) relocates Netty, which then looks
        // for a differently-named library: the jar bundles it, and adding the stock native never loads it
        String message = Http3NativeUnavailableException.message(8443, "8.1.0", "4.2.18.Final", "linux-x86_64", true, null, null);

        assertThat(message, not(containsString("add the runtime dependency")));
        assertThat(message, containsString("mockserver-netty-no-dependencies bundles this native"));
        assertThat(message, containsString("depend on org.mock-server:mockserver-netty, which brings"));
        assertThat(message, containsString("io.netty:netty-codec-native-quic:4.2.18.Final:linux-x86_64"));
        assertThat(message, containsString("mockserver/mockserver:8.1.0-http3"));
        assertThat(message, not(containsString("/libs")));
    }

    @Test
    public void shouldPointSnapshotBuildsAtTheSnapshotImageTag() {
        String message = Http3NativeUnavailableException.message(1081, "8.0.1-SNAPSHOT", "4.2.18.Final", "osx-aarch_64", false, null, null);

        assertThat(message, containsString("mockserver/mockserver:snapshot-http3"));
        assertThat(message, not(containsString("8.0.1-SNAPSHOT-http3")));
        assertThat(message, containsString("mockserver-netty-8.0.1-SNAPSHOT-jar-with-dependencies-http3.jar"));
        assertThat(message, containsString("http3Port=1081"));
    }

    @Test
    public void shouldOnlyOfferTheContainerOnAPlatformWithNoPublishedNative() {
        String message = Http3NativeUnavailableException.message(8443, "8.1.0", "4.2.18.Final", "linux-riscv64", false, null, null);

        assertThat(message, containsString("linux-riscv64"));
        assertThat(message, containsString("only for linux-x86_64, linux-aarch_64, osx-x86_64, osx-aarch_64, windows-x86_64"));
        assertThat(message, containsString("mockserver/mockserver:8.1.0-http3"));
        assertThat("no classifier exists for this platform", message, not(containsString("netty-codec-native-quic:")));
        assertThat(message, not(containsString("jar-with-dependencies-http3")));
    }

    @Test
    public void shouldStillReadWhenVersionsAreUnknown() {
        String message = Http3NativeUnavailableException.message(8443, "", "", "linux-x86_64", false, null, null);

        assertThat(message, containsString("mockserver/mockserver:<version>-http3"));
        assertThat(message, containsString("io.netty:netty-codec-native-quic:<netty version>:linux-x86_64"));
    }

    @Test
    public void shouldEndWithTheUnderlyingErrorOnOneLine() {
        String message = Http3NativeUnavailableException.message(8443, "8.1.0", "4.2.18.Final", "linux-x86_64", true,
            null, Http3NativeUnavailableException.underlyingError(
                new UnsatisfiedLinkError("could not load").initCause(new java.io.FileNotFoundException("META-INF/native/libx.so\nsecond line"))));

        String[] lines = message.split("\n");
        assertThat(lines[lines.length - 1], is("underlying error: FileNotFoundException: META-INF/native/libx.so"));
        assertThat(message, not(containsString("second line")));
    }

    @Test
    public void shouldSayAPresentButUnloadableNativeIsNotFixedByAnotherArtifact() {
        // e.g. the -http3 image run with java.library.path overridden: it already IS the fix the other message offers
        String message = Http3NativeUnavailableException.message(8443, "8.1.0", "4.2.18.Final", "linux-aarch_64", true,
            "/usr/lib/libshaded_1package_netty_quiche42_linux_aarch_64.so", "FileNotFoundException: META-INF/native/libshaded_1package_netty_quiche42_linux_aarch_64.so");

        assertThat(message, containsString("QUIC native library for linux-aarch_64 failed to load"));
        assertThat(message, containsString("native library is present but failed to load"));
        assertThat(message, containsString("Found: /usr/lib/libshaded_1package_netty_quiche42_linux_aarch_64.so"));
        assertThat(message, containsString("java.library.path"));
        assertThat(message, containsString("remove http3Port"));
        assertThat(message, not(containsString("-http3")));
        assertThat(message, not(containsString("netty-codec-native-quic:")));
        assertThat(message, endsWith("underlying error: FileNotFoundException: META-INF/native/libshaded_1package_netty_quiche42_linux_aarch_64.so"));
    }

    @Test
    public void shouldDetectThisRuntime() {
        assumeNettyPublishesANativeForThisPlatform();
        // the unit-test classpath carries unrelocated Netty with its version resources, so every value is real
        assertThat(Http3NativeUnavailableException.nettyIsRelocated(), is(false));
        assertThat(Http3NativeUnavailableException.nettyVersionOnClasspath(), is(Version.getNettyVersion()));
        assertThat(Http3NativeUnavailableException.SUPPORTED_PLATFORMS, hasItem(Http3NativeUnavailableException.platformClassifier()));
        // the test classpath carries netty-codec-native-quic for this platform
        assertThat(Http3NativeUnavailableException.presentNativeLocation(), startsWith("classpath META-INF/native/libnetty_quiche"));
    }

    @Test
    public void shouldBuildTheMessageForThisRuntimeAndKeepTheCause() {
        assumeNettyPublishesANativeForThisPlatform();
        Throwable cause = new UnsatisfiedLinkError("no native");

        Http3NativeUnavailableException exception = new Http3NativeUnavailableException(8443, cause);

        assertThat(exception, instanceOf(IllegalStateException.class));
        assertThat(exception.getCause(), sameInstance(cause));
        // the native IS on this classpath, so the message must not send the reader to another artifact
        assertThat(exception.getMessage(), containsString("The native library is present but failed to load"));
        assertThat(exception.getMessage(), endsWith("underlying error: UnsatisfiedLinkError: no native"));
    }

    // the same guard as Http3NativeStartupIntegrationTest: elsewhere no native is on the test classpath
    private static void assumeNettyPublishesANativeForThisPlatform() {
        Assume.assumeTrue("no QUIC native is published for " + Http3NativeUnavailableException.platformClassifier(),
            Http3NativeUnavailableException.SUPPORTED_PLATFORMS.contains(Http3NativeUnavailableException.platformClassifier()));
    }
}
