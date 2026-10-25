package org.mockserver.testing.corpus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Golden transcripts for a differential corpus suite, kept under the module's
 * {@code src/test/resources/dataplane-corpus/<suite>/} and compared byte for byte.
 * <p>
 * {@code -Dmockserver.regenerateDataPlaneCorpus=true} (or {@code MOCKSERVER_REGENERATE_DATAPLANE_CORPUS=true})
 * rewrites the goldens instead of comparing. Regenerate only from the code the corpus is meant to pin - the
 * baseline before a data-plane change - never to make a red corpus green after one.
 */
public final class GoldenCorpus {

    public static final String REGENERATE_PROPERTY = "mockserver.regenerateDataPlaneCorpus";
    private static final String REGENERATE_ENVIRONMENT_VARIABLE = "MOCKSERVER_REGENERATE_DATAPLANE_CORPUS";

    private final Path directory;

    private GoldenCorpus(Path directory) {
        this.directory = directory;
    }

    /**
     * @param suite the test class; tests run with the module directory as the working directory, as Maven runs them
     */
    public static GoldenCorpus forSuite(Class<?> suite) {
        Path testSources = Paths.get("src", "test").toAbsolutePath();
        if (!Files.isDirectory(testSources)) {
            throw new IllegalStateException("run from the module directory: no " + testSources);
        }
        return new GoldenCorpus(testSources.resolve("resources").resolve("dataplane-corpus").resolve(suite.getSimpleName()));
    }

    public static boolean isRegenerating() {
        return "true".equalsIgnoreCase(System.getProperty(REGENERATE_PROPERTY))
            || "true".equalsIgnoreCase(System.getenv(REGENERATE_ENVIRONMENT_VARIABLE));
    }

    public void assertMatches(String scenario, String transcript) {
        Path golden = directory.resolve(scenario + ".txt");
        byte[] actual = transcript.getBytes(StandardCharsets.UTF_8);
        try {
            if (isRegenerating()) {
                Files.createDirectories(directory);
                Files.write(golden, actual);
                return;
            }
            if (!Files.exists(golden)) {
                throw new AssertionError("no golden transcript " + golden + "; record it with -D" + REGENERATE_PROPERTY + "=true from the baseline code");
            }
            byte[] expected = Files.readAllBytes(golden);
            if (!Arrays.equals(expected, actual)) {
                throw new AssertionError("transcript of " + scenario + " differs from " + golden + " " + firstDifference(new String(expected, StandardCharsets.UTF_8), transcript)
                    + "\n--- actual transcript ---\n" + transcript);
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not access " + golden, e);
        }
    }

    private static String firstDifference(String expected, String actual) {
        String[] expectedLines = expected.split("\n", -1);
        String[] actualLines = actual.split("\n", -1);
        for (int i = 0; i < Math.max(expectedLines.length, actualLines.length); i++) {
            String expectedLine = i < expectedLines.length ? expectedLines[i] : "<none>";
            String actualLine = i < actualLines.length ? actualLines[i] : "<none>";
            if (!expectedLine.equals(actualLine)) {
                return "at line " + (i + 1) + ":\n  expected: " + expectedLine + "\n  actual:   " + actualLine;
            }
        }
        return "";
    }
}
