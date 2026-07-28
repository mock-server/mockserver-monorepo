package org.mockserver.configuration;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.configuration.ConfigurationProperties.propertyFileExplicitlyConfigured;

/**
 * Guards which property file failures are worth telling the operator about
 * (<a href="https://github.com/mock-server/mockserver-monorepo/issues/2358">#2358</a>).
 *
 * <p>In that issue a mounted {@code mockserver.properties} existed but could not be read by the
 * MockServer process. Because "cannot read the property file" was logged only at DEBUG - and emitted
 * during static initialisation, before any log level had been applied - the server started silently with
 * none of the file's properties. The only visible symptom was that {@code initializationJsonPath} was
 * never set, so no expectations loaded, with nothing in the log to explain why.
 *
 * <p>A file the operator explicitly pointed at and that cannot be read is therefore reported at WARN. The
 * trap is the Docker image, whose entrypoint <em>always</em> passes
 * {@code -Dmockserver.propertyFile=/config/mockserver.properties}: treating that as an explicit choice
 * would make every container started without a mounted config warn on startup, which would train users to
 * ignore the very warning this adds. Only an environment variable can express intent there.
 *
 * <p>These call the pure overload so no system property or environment variable is touched - both are
 * global state and would make this unsafe to run in parallel.
 */
public class PropertyFileExplicitlyConfiguredTest {

    private static final String DOCKER_DEFAULT = "/config/mockserver.properties";

    @Test
    public void shouldNotBeExplicitWhenNothingIsSet() {
        assertThat("no property file configured at all is the default, so silence is correct",
            propertyFileExplicitlyConfigured(null, null), is(false));
    }

    @Test
    public void shouldNotBeExplicitWhenOnlyTheDockerEntrypointDefaultIsSet() {
        assertThat("the Docker entrypoint always passes this, so it expresses no operator intent",
            propertyFileExplicitlyConfigured(DOCKER_DEFAULT, null), is(false));
    }

    @Test
    public void shouldBeExplicitWhenSystemPropertyPointsSomewhereElse() {
        assertThat("a path the operator chose should be reported when it cannot be read",
            propertyFileExplicitlyConfigured("/mockserver/mockserver.properties", null), is(true));
    }

    @Test
    public void shouldBeExplicitWhenEnvironmentVariableOverridesTheDockerDefault() {
        // The documented way to redirect the property file inside the Docker image.
        assertThat("an environment variable is the only way to express intent in the Docker image",
            propertyFileExplicitlyConfigured(DOCKER_DEFAULT, "/mockserver/mockserver.properties"), is(true));
    }

    @Test
    public void shouldBeExplicitWhenOnlyEnvironmentVariableIsSet() {
        assertThat("an environment variable alone is an explicit choice",
            propertyFileExplicitlyConfigured(null, "/mockserver/mockserver.properties"), is(true));
    }

    @Test
    public void shouldIgnoreBlankValues() {
        assertThat("a blank system property is not a choice",
            propertyFileExplicitlyConfigured("   ", null), is(false));
        assertThat("a blank environment variable is not a choice",
            propertyFileExplicitlyConfigured(null, "   "), is(false));
        assertThat("a blank environment variable must not promote the Docker default",
            propertyFileExplicitlyConfigured(DOCKER_DEFAULT, ""), is(false));
    }
}
