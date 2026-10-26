package org.mockserver.socket;

import org.junit.Test;

import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class RelocatedLibraryPropertiesTest {

    private static final String PREFIX = "shaded_package.";

    @Test
    public void shouldCopyBundledLibraryPropertiesToTheRelocatedName() {
        Properties properties = new Properties();
        properties.setProperty("io.netty.allocator.type", "unpooled");
        properties.setProperty("org.apache.kafka.disallowed.login.modules", "com.example.Module");
        properties.setProperty("org.bouncycastle.rsa.max_size", "8192");

        int copied = RelocatedLibraryProperties.forward(properties, PREFIX);

        assertThat(copied, is(3));
        assertThat(properties.getProperty("shaded_package.io.netty.allocator.type"), is("unpooled"));
        assertThat(properties.getProperty("shaded_package.org.apache.kafka.disallowed.login.modules"), is("com.example.Module"));
        assertThat(properties.getProperty("shaded_package.org.bouncycastle.rsa.max_size"), is("8192"));
        assertThat(properties.getProperty("io.netty.allocator.type"), is("unpooled"));
    }

    @Test
    public void shouldNotOverrideAPropertyAlreadySetUnderTheRelocatedName() {
        Properties properties = new Properties();
        properties.setProperty("io.netty.leakDetection.level", "paranoid");
        properties.setProperty("shaded_package.io.netty.leakDetection.level", "disabled");

        int copied = RelocatedLibraryProperties.forward(properties, PREFIX);

        assertThat(copied, is(0));
        assertThat(properties.getProperty("shaded_package.io.netty.leakDetection.level"), is("disabled"));
    }

    @Test
    public void shouldIgnorePropertiesOfLibrariesThatAreNotRelocated() {
        Properties properties = new Properties();
        properties.setProperty("java.io.tmpdir", "/tmp");
        properties.setProperty("mockserver.serverPort", "1080");
        properties.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
        properties.setProperty("io.nettyish.value", "x");

        int copied = RelocatedLibraryProperties.forward(properties, PREFIX);

        assertThat(copied, is(0));
        assertThat(properties.getProperty("shaded_package.java.io.tmpdir"), nullValue());
        assertThat(properties.getProperty("shaded_package.io.nettyish.value"), nullValue());
    }

    @Test
    public void shouldDoNothingOutsideAShadedJar() {
        Properties properties = new Properties();
        properties.setProperty("io.netty.allocator.type", "unpooled");

        assertThat(RelocatedLibraryProperties.forward(properties, ""), is(0));
        assertThat(properties.size(), is(1));
        // unshaded here; in the shaded jar it is shaded_package. (see assert-shaded-natives-load.sh)
        assertThat(RelocatedLibraryProperties.relocationPrefix(), is(""));
    }
}
