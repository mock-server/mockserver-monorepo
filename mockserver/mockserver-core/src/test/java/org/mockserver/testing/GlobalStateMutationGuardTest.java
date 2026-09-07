package org.mockserver.testing;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Build-time guard that detects test classes which mutate JVM-global static state but are NOT listed
 * in the sequential Surefire phase of {@code mockserver-core/pom.xml}. This closes the gap left by
 * {@link ParallelStaticStateGuardTest}, which only checks that the exclude/include lists are symmetric
 * but cannot detect a brand-new stateful test that is missing from BOTH lists.
 *
 * <p>Detection patterns (high-signal, curated to minimise false positives):
 * <ul>
 *   <li>{@code ConfigurationProperties.<setter>(<non-empty-arg>)} — static setter call with an argument
 *       (a no-arg call is a getter and is not flagged)</li>
 *   <li>{@code System.setProperty(} / {@code System.clearProperty(}</li>
 *   <li>{@code .getInstance().reset(} / {@code .getInstance().clear(} — singleton state mutation</li>
 *   <li>{@code Metrics.resetAdditionalMetricsForTesting(} / {@code PrometheusRegistry.defaultRegistry}
 *       — Prometheus global state</li>
 *   <li>{@code <Type>.set<Property>(} — a static setter on ANY capitalised type (e.g.
 *       {@code FileWatcher.setPollPeriod(500)}, {@code Locale.setDefault(...)}). This is the general
 *       backstop that stops the guard being a hard-coded allow-list of known offenders; it is what
 *       the earlier curated list lacked, and is why the Sept 2026 {@code FileWatcher.setPollPeriod}
 *       flake slipped through into the parallel phase</li>
 * </ul>
 *
 * <p><strong>KNOWN LIMITS - this guard is a textual heuristic, NOT exhaustive.</strong> Treat a green
 * run as "none of the shapes below were found", never as "this test mutates no global state". The
 * guard already failed open once by being read as exhaustive: its patterns were a curated allow-list
 * of named classes, so {@code FileWatcher.setPollPeriod} was invisible and the resulting flake
 * reddened master builds 6914/6918 and PR #2655. The general {@code <Type>.set<Property>(} backstop
 * closes that particular hole, but these shapes still evade detection:
 * <ul>
 *   <li>a mutator called through a STATIC IMPORT, so it has no receiver at all
 *       ({@code import static ...MockServerLogger.setGlobalLogEventListener;} then a bare
 *       {@code setGlobalLogEventListener(null)}) - no capitalised receiver token to match</li>
 *   <li>direct assignment to a public static field ({@code Foo.bar = x}) - there is no setter</li>
 *   <li>{@code Type.setFoo (arg)} with whitespace before the parenthesis</li>
 *   <li>mutation performed indirectly inside a helper class that the test merely calls</li>
 * </ul>
 * Conversely, a static-final instance held in an UPPERCASE constant ({@code CONSTANT.setFoo(...)})
 * is a false positive. When adding a test that touches process-wide state, place it in the sequential
 * phase deliberately rather than relying on this guard to notice.
 *
 * <p>To suppress a false positive, add a class-level comment:
 * {@code // @ParallelStateGuardSuppress: <reason>}
 *
 * @see ParallelStaticStateGuardTest
 */
// @ParallelStateGuardSuppress: this guard test references mutation patterns only as detection regexes, not actual calls
public class GlobalStateMutationGuardTest {

    private static final Path POM = Paths.get("pom.xml");
    private static final Path TEST_ROOT = Paths.get("src", "test", "java");

    /** Files that are themselves part of the guard infrastructure and must not self-flag. */
    private static final Set<String> GUARD_FILES = Set.of(
        "GlobalStateMutationGuardTest.java",
        "ParallelStaticStateGuardTest.java"
    );

    /**
     * Each pattern is a compiled regex matched against individual source lines (after stripping
     * leading/trailing whitespace). The pattern name is used in violation messages.
     */
    private static final List<DetectionPattern> PATTERNS = List.of(
        // ConfigurationProperties setter: method call with at least one argument.
        // Matches: ConfigurationProperties.fooBar(something)
        // Does NOT match: ConfigurationProperties.fooBar()  (getter)
        // Does NOT match: ConfigurationProperties.fooBar()  inside another call like when(...ConfigurationProperties.x())
        new DetectionPattern(
            "ConfigurationProperties.<setter>(<arg>)",
            Pattern.compile("(?<![\\w])ConfigurationProperties\\.[a-z]\\w*\\((?!\\))[^)]+\\)")
        ),
        new DetectionPattern(
            "System.setProperty(",
            Pattern.compile("System\\.setProperty\\(")
        ),
        new DetectionPattern(
            "System.clearProperty(",
            Pattern.compile("System\\.clearProperty\\(")
        ),
        new DetectionPattern(
            ".getInstance().reset(",
            Pattern.compile("\\.getInstance\\(\\)\\.reset\\(")
        ),
        new DetectionPattern(
            ".getInstance().clear(",
            Pattern.compile("\\.getInstance\\(\\)\\.clear\\(")
        ),
        new DetectionPattern(
            "Metrics.resetAdditionalMetricsForTesting(",
            Pattern.compile("Metrics\\.resetAdditionalMetricsForTesting\\(")
        ),
        new DetectionPattern(
            "PrometheusRegistry.defaultRegistry",
            Pattern.compile("PrometheusRegistry\\.defaultRegistry")
        ),
        // Static setter/mutator on ANY type: `TypeName.setSomething(...)`.
        //
        // This is the general backstop the original curated list lacked. Every pattern above
        // names a SPECIFIC class or an exact call shape, which makes the guard an allow-list of
        // known offenders: a brand-new test class that mutates static state on any OTHER class is
        // invisible to it by construction. That is exactly how the Sept 2026 flake slipped
        // through and reddened master builds 6914/6918 and Dependabot PR #2655 — FileWatcherTest
        // and ExpectationFileWatcherTest called `FileWatcher.setPollPeriod(500)` /
        // `FileWatcher.setPollPeriodUnits(MILLISECONDS)` (plain static setters on a class none of
        // the specific patterns named) while running in the PARALLEL phase, and each class's
        // @AfterClass restored the 5-second default under whichever class was still running.
        //
        // Precision comes from Java naming: a receiver that starts with an UPPERCASE letter and is
        // immediately followed by `.set<Something>(` is a static call on a *type*. Instance calls
        // go through lower-case variable names (`watcher.setPollPeriod(...)`), and chained/builder
        // calls (`new Expectation().setBody(...)`, `foo.bar().setBaz(...)`) put a `)` — not a
        // capitalised type name — directly before the setter, so none of those match. This is a
        // deliberately broad net; a genuinely parallel-safe match (e.g. a static builder/factory
        // that happens to be named set*) is suppressed per-class with @ParallelStateGuardSuppress.
        new DetectionPattern(
            "<Type>.set<Property>( — static setter on any type",
            Pattern.compile("\\b[A-Z]\\w*\\.set[A-Z]\\w*\\(")
        )
    );

    /** Suppression marker: if present anywhere in the file, the file is skipped. */
    private static final String SUPPRESS_MARKER = "@ParallelStateGuardSuppress";

    /**
     * Call shapes that MATCH the broad "&lt;Type&gt;.set&lt;Property&gt;(" static-setter pattern but are NOT
     * JVM-global mutation: static <em>utility</em> methods that mutate the object passed as an
     * argument rather than any process-wide static field. Each entry must name a specific method
     * and carry a justification — this is an individually-reviewed allow-list, never a broad
     * escape hatch, so it is kept deliberately tiny and only the broad static-setter pattern
     * consults it.
     */
    private static final Set<String> BENIGN_STATIC_MUTATOR_CALLS = Set.of(
        // io.netty.handler.codec.http.HttpUtil.setTransferEncodingChunked(message, chunked) sets the
        // Transfer-Encoding header ON THE PASSED HttpMessage; it mutates that local object, not any
        // process-wide state, so a test that calls it is parallel-safe.
        // (StreamingAwareHttpObjectAggregatorTest)
        "HttpUtil.setTransferEncodingChunked("
    );

    /** The broad static-setter pattern (see PATTERNS) — the only one that consults the benign allow-list. */
    private static final String STATIC_SETTER_PATTERN_NAME = "<Type>.set<Property>( — static setter on any type";

    @Test
    public void allGlobalStateMutatingTestsMustBeInSequentialPhase() throws IOException {
        assertTrue("expected to find mockserver-core/pom.xml at " + POM.toAbsolutePath(), Files.exists(POM));
        assertTrue("expected to find src/test/java at " + TEST_ROOT.toAbsolutePath(), Files.isDirectory(TEST_ROOT));

        // Parse the sequential-phase includes from pom.xml
        String pom = new String(Files.readAllBytes(POM));
        Set<String> sequentialClassNames = extractSequentialClassNames(pom);

        // Scan all test .java files
        List<Violation> violations = new ArrayList<>();
        Files.walkFileTree(TEST_ROOT, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith("Test.java") && !fileName.endsWith("Tests.java")) {
                    return FileVisitResult.CONTINUE;
                }
                if (GUARD_FILES.contains(fileName)) {
                    return FileVisitResult.CONTINUE;
                }

                String content = new String(Files.readAllBytes(file));

                // Check suppression marker
                if (content.contains(SUPPRESS_MARKER)) {
                    return FileVisitResult.CONTINUE;
                }

                // Extract class name (without .java)
                String className = fileName.replace(".java", "");

                // Check if already in the sequential phase
                if (sequentialClassNames.contains(className)) {
                    return FileVisitResult.CONTINUE;
                }

                // Scan line by line for mutation patterns
                String[] lines = content.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim();

                    // Skip single-line comments
                    if (line.startsWith("//")) {
                        continue;
                    }
                    // Skip lines that are inside block comments (crude but effective)
                    // — look for lines starting with * (Javadoc/block comment body)
                    if (line.startsWith("*") || line.startsWith("/*")) {
                        continue;
                    }
                    // Skip import statements (they reference ConfigurationProperties but don't call setters)
                    if (line.startsWith("import ")) {
                        continue;
                    }
                    // Skip string literals containing the pattern — check if the match is inside quotes
                    // (This is a heuristic: if the line has the pattern but the whole line is a string
                    // assignment like `"ConfigurationProperties.foo(bar)"`, skip it. We do this by
                    // stripping quoted strings before matching.)
                    String lineWithoutStrings = line.replaceAll("\"[^\"]*\"", "\"\"");

                    for (DetectionPattern dp : PATTERNS) {
                        if (dp.pattern.matcher(lineWithoutStrings).find()) {
                            // The broad static-setter pattern consults a tiny, individually-justified
                            // allow-list of static UTILITY calls that mutate a passed argument rather
                            // than global state (e.g. Netty's HttpUtil.setTransferEncodingChunked).
                            if (dp.name.equals(STATIC_SETTER_PATTERN_NAME) && isBenignStaticMutator(lineWithoutStrings)) {
                                continue;
                            }
                            violations.add(new Violation(className, file.toString(), i + 1, line, dp.name));
                            break; // one violation per file is enough to flag it
                        }
                    }
                    // Once we've found one violation for this file, stop scanning it
                    if (!violations.isEmpty() && violations.get(violations.size() - 1).className.equals(className)) {
                        break;
                    }
                }

                return FileVisitResult.CONTINUE;
            }
        });

        if (!violations.isEmpty()) {
            StringBuilder msg = new StringBuilder();
            msg.append("Found ").append(violations.size())
                .append(" test class(es) that mutate JVM-global static state but are NOT in the sequential ")
                .append("Surefire phase. These will cause flaky failures under parallel=classes execution.\n\n");
            for (Violation v : violations) {
                msg.append("  - ").append(v.className).append(" (").append(v.filePath).append(")\n");
                msg.append("    line ").append(v.lineNumber).append(": ").append(v.lineContent.trim()).append("\n");
                msg.append("    matched pattern: ").append(v.patternName).append("\n\n");
            }
            msg.append("FIX: add **/").append("<ClassName>.java to BOTH the parallel <excludes> AND sequential ")
                .append("<includes> in mockserver-core/pom.xml (keep them symmetric). If the test is genuinely ")
                .append("parallel-safe (e.g. it only reads, uses a local Configuration instance, or the match ")
                .append("is a false positive), add a class-level comment:\n")
                .append("  // @ParallelStateGuardSuppress: <reason>\n");
            fail(msg.toString());
        }
    }

    /**
     * Extracts the simple class names (without extension) from the sequential-tests execution
     * {@code <includes>} in the pom.xml.
     */
    private Set<String> extractSequentialClassNames(String pom) {
        Matcher m = Pattern
            .compile("(?s)<execution>\\s*<id>sequential-tests</id>(.*?)</execution>")
            .matcher(pom);
        assertTrue("could not find <execution> with <id>sequential-tests</id> in pom.xml", m.find());
        String sequentialBlock = m.group(1);

        Set<String> classNames = new TreeSet<>();
        Matcher im = Pattern.compile("<include>\\s*(.*?)\\s*</include>").matcher(sequentialBlock);
        while (im.find()) {
            String pattern = im.group(1).trim();
            // Pattern is like **/ConfigurationTest.java — extract the class name
            String fileName = pattern;
            if (fileName.contains("/")) {
                fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
            }
            if (fileName.startsWith("**/")) {
                fileName = fileName.substring(3);
            }
            if (fileName.endsWith(".java")) {
                fileName = fileName.substring(0, fileName.length() - 5);
            }
            // Skip glob patterns like **/*IntegrationTest.java (these are excludes in the sequential phase)
            if (!fileName.contains("*")) {
                classNames.add(fileName);
            }
        }
        return classNames;
    }

    /**
     * True if the (string-stripped) line contains a known static-utility call that mutates the
     * object passed as an argument rather than any JVM-global static field, and is therefore
     * parallel-safe despite matching the broad static-setter regex.
     */
    private static boolean isBenignStaticMutator(String lineWithoutStrings) {
        for (String benign : BENIGN_STATIC_MUTATOR_CALLS) {
            if (lineWithoutStrings.contains(benign)) {
                return true;
            }
        }
        return false;
    }

    /** Locates the compiled regex for a named {@link DetectionPattern}. */
    private static Pattern patternNamed(String name) {
        return PATTERNS.stream()
            .filter(dp -> dp.name.equals(name))
            .map(dp -> dp.pattern)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no DetectionPattern named: " + name));
    }

    /**
     * Self-test of the broad static-setter detector. This is the regression test for the Sept 2026
     * miss: a plain {@code Type.setFoo(...)} static setter (as in {@code FileWatcher.setPollPeriod(500)})
     * must be detected, while instance and chained/builder setters must NOT be — so the guard neither
     * regresses to a hard-coded allow-list nor floods the build with false positives. It asserts the
     * regex directly (not via any live call site) so it stays valid once {@code FileWatcher.setPollPeriod}
     * is removed.
     */
    @Test
    public void staticSetterPatternMatchesStaticCallsButNotInstanceOrChainedCalls() {
        Pattern p = patternNamed(STATIC_SETTER_PATTERN_NAME);

        // MUST match: static setter on a capitalised type (the shape the guard missed)
        assertTrue("static setter must match", p.matcher("Foo.setBar(1)").find());
        assertTrue("the exact missed call must match", p.matcher("FileWatcher.setPollPeriod(500);").find());
        assertTrue("the exact missed call must match", p.matcher("FileWatcher.setPollPeriodUnits(MILLISECONDS);").find());
        assertTrue("static setter on a fully-qualified type must match",
            p.matcher("org.example.Foo.setBar(1)").find());

        // MUST NOT match: instance setter through a lower-case variable
        assertFalse("instance setter via variable must NOT match", p.matcher("watcher.setPollPeriod(500);").find());
        assertFalse("instance setter via variable must NOT match", p.matcher("foo.setBar(1)").find());
        // MUST NOT match: chained / builder setter (a ')' precedes the setter, not a type name)
        assertFalse("builder setter must NOT match", p.matcher("new Expectation().setBody(body)").find());
        assertFalse("chained setter must NOT match", p.matcher("foo.getThing().setBar(1)").find());
        // MUST NOT match: a getter (no 'set' + uppercase)
        assertFalse("getter must NOT match", p.matcher("Foo.getBar()").find());

        // The Netty utility that mutates its argument matches the regex but is allow-listed as benign
        String httpUtil = "HttpUtil.setTransferEncodingChunked(response, true);";
        assertTrue("regex is intentionally broad enough to match the utility call", p.matcher(httpUtil).find());
        assertTrue("but the benign allow-list must exclude it", isBenignStaticMutator(httpUtil));
    }

    private static class DetectionPattern {
        final String name;
        final Pattern pattern;

        DetectionPattern(String name, Pattern pattern) {
            this.name = name;
            this.pattern = pattern;
        }
    }

    private static class Violation {
        final String className;
        final String filePath;
        final int lineNumber;
        final String lineContent;
        final String patternName;

        Violation(String className, String filePath, int lineNumber, String lineContent, String patternName) {
            this.className = className;
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.lineContent = lineContent;
            this.patternName = patternName;
        }
    }
}
