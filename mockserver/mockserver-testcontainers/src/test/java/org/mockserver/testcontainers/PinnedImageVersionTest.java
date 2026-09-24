package org.mockserver.testcontainers;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;

/**
 * Docker-free guard that enforces the invariant a code comment used to only ask for by hand: the
 * explicitly pinned {@code mockserver/mockserver:mockserver-<version>} image used by
 * {@link MockServerContainerIntegrationTest} must share its major.minor with the project version.
 * <p>
 * MockServerClient refuses to talk to a server whose major or minor differs, so a pin left behind at
 * a previous release (e.g. {@code 7.6.0} while the repo has rolled to {@code 8.0.1-SNAPSHOT}) turns
 * the integration test red with a confusing client-vs-server version exception ~10s into a container
 * start. This unit-level check catches that in milliseconds, without Docker, and would have caught
 * the post-8.0.0-release breakage before it hit CI.
 * <p>
 * The project version is supplied by Surefire as the {@code project.version} system property (see
 * the {@code mockserver-testcontainers} module pom). When it is absent — e.g. a bare IDE run with no
 * Maven filtering — the test falls back to the client jar's {@code Implementation-Version} and only
 * skips (rather than failing) if neither is available, so it never produces a spurious red off Maven
 * while still being fully enforced in every Maven/CI run.
 */
class PinnedImageVersionTest {

    private static final Pattern TAG_PATTERN =
        Pattern.compile("^mockserver/mockserver:mockserver-(\\d+)\\.(\\d+)\\.\\d+.*$");

    @Test
    void pinnedImageIsWellFormed() {
        assertThat(
            "The pinned image must be a concrete mockserver/mockserver:mockserver-<major.minor.patch> tag",
            TestcontainersImages.PINNED_MOCKSERVER_IMAGE,
            matchesPattern("^mockserver/mockserver:mockserver-\\d+\\.\\d+\\.\\d+.*$")
        );
    }

    @Test
    void pinnedImageMajorMinorMatchesProjectVersion() {
        String projectVersion = resolveProjectVersion();
        Assumptions.assumeTrue(
            projectVersion != null && !projectVersion.isEmpty(),
            "Neither the 'project.version' system property nor the client jar Implementation-Version "
                + "is available (bare IDE run) — skipping the pin/version lockstep check"
        );

        String pinnedMajorMinor = majorMinorOfPinnedImage();
        String projectMajorMinor = majorMinor(projectVersion);

        // The pin may be EQUAL to the project version, or exactly one minor behind it; it may never
        // be AHEAD. Requiring strict equality is unsatisfiable during a release: `prepare` sets the
        // project version to the version being released (8.1.0) while the newest image that exists
        // is still the previous one (8.0.0), and no image can be pinned before it is published. That
        // is not hypothetical - it failed release build #76 at the first step. One minor of lag is
        // therefore the correct invariant, and it still catches a pin left to rot for several
        // releases, which is what this guard is for.
        assertThat(
            "The pinned Testcontainers image (" + TestcontainersImages.PINNED_MOCKSERVER_IMAGE
                + ") is more than one minor behind, or ahead of, the project version ("
                + projectVersion + "). Bump TestcontainersImages.PINNED_MOCKSERVER_IMAGE to the"
                + " latest RELEASED image - it may lag the project version by one minor while a"
                + " release is in flight, but never lead it.",
            isPinAcceptableFor(pinnedMajorMinor, projectMajorMinor),
            is(true)
        );
    }

    /**
     * True when the pinned image is the same minor as the project, or exactly one minor behind it.
     * A pin ahead of the project version is always wrong - that image cannot exist yet.
     */
    private static boolean isPinAcceptableFor(String pinned, String project) {
        String[] p = pinned.split("\\.");
        String[] v = project.split("\\.");
        int pinMajor = Integer.parseInt(p[0]), pinMinor = Integer.parseInt(p[1]);
        int prjMajor = Integer.parseInt(v[0]), prjMinor = Integer.parseInt(v[1]);
        if (pinMajor == prjMajor) {
            return pinMinor == prjMinor || pinMinor == prjMinor - 1;
        }
        // A major bump resets the minor, so the last release of the previous major is acceptable.
        return pinMajor == prjMajor - 1 && prjMinor == 0;
    }

    private static String majorMinorOfPinnedImage() {
        Matcher matcher = TAG_PATTERN.matcher(TestcontainersImages.PINNED_MOCKSERVER_IMAGE);
        if (!matcher.matches()) {
            throw new AssertionError(
                "Pinned image is not a mockserver/mockserver:mockserver-<version> tag: "
                    + TestcontainersImages.PINNED_MOCKSERVER_IMAGE);
        }
        return matcher.group(1) + "." + matcher.group(2);
    }

    /**
     * Reduces a version string to {@code major.minor}, tolerating a {@code -SNAPSHOT} (or any other)
     * qualifier on the patch component (e.g. {@code 8.0.1-SNAPSHOT} -> {@code 8.0}).
     */
    private static String majorMinor(String version) {
        String[] parts = version.split("\\.");
        if (parts.length < 2) {
            throw new AssertionError("Unparseable version string: " + version);
        }
        return parts[0] + "." + parts[1];
    }

    private static String resolveProjectVersion() {
        String fromProperty = System.getProperty("project.version");
        if (fromProperty != null && !fromProperty.isEmpty()) {
            return fromProperty;
        }
        return org.mockserver.client.MockServerClient.class.getPackage().getImplementationVersion();
    }
}
