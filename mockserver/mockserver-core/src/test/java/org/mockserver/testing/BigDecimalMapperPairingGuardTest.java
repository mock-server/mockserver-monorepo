package org.mockserver.testing;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Build-time guard that stops the Jackson big-decimal footgun (issue #2658) from silently
 * recurring in a <em>new</em> mapper.
 *
 * <p><b>The footgun.</b> Enabling {@code DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS}
 * makes {@code readTree} build a {@code DecimalNode}, and Jackson's
 * {@code JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES} — default {@code true} since Jackson
 * 2.15 — normalises that node at construction ({@code 275.0} to {@code 275}, {@code 0.00} to
 * {@code 0}, {@code 100.0} to {@code 1E+2}). A JsonBody built from such a mapper stops matching a
 * byte-identical request. The feature must therefore always be paired, in the same statement, with
 * something that keeps exact BigDecimals — {@code setNodeFactory(JsonNodeFactory.withExactBigDecimals(true))},
 * the builder equivalent {@code JsonMapper.builder()...nodeFactory(JsonNodeFactory.withExactBigDecimals(true))},
 * or explicitly disabling {@code JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES}.
 *
 * <p><b>What the existing behavioural tests already cover, and the gap this closes.</b> The tests
 * added in {@code bdf1eb4d2} fail if {@code setNodeFactory(...)} is deleted from the two mappers
 * that exist today ({@code JsonBodySerializer}, {@code JsonBodyDTOSerializer}). What they cannot
 * see is a <em>third mapper added later</em> that enables the feature without the pairing — exactly
 * the shape of the original mistake in {@code 11561ad28}, which shipped with no test and regressed
 * whole-number doubles undetected until 7.6.0. This guard scans main sources for that shape.
 *
 * <p><b>Why the check is statement-scoped, not file-scoped.</b> A guard that merely greps the whole
 * file for {@code withExactBigDecimals} somewhere would pass even if a second, unpaired mapper were
 * added to the same file. So the pairing is required within the same {@code ;}-terminated statement
 * that enables the feature — the fluent-chain declaration is one statement. This binds the check to
 * the thing it guards. For the same reason a {@code @BigDecimalPairingGuardSuppress} marker is
 * scoped to the statement it sits on (see suppression, below), not to the whole file: a file
 * suppressed for one legitimate mapper must still be scanned for the next.
 *
 * <p><b>Live anchor — the guard structurally cannot silently fail open while the two real mappers
 * exist.</b> {@code JsonBodySerializer} and {@code JsonBodyDTOSerializer} in core main sources are
 * scanned like any other file, and they legitimately enable the feature <em>and</em> pair it. So if
 * a refactor ever broke {@link #ENABLE} (the feature stops being recognised as enabled) those two
 * statements fall to the "cannot classify" branch and the build goes RED; if it broke
 * {@link #PAIRING} they go RED as "without pairing". The guard cannot quietly stop detecting the bug
 * without one of its own regexes first tripping on a known-good mapper. {@link
 * #detectionPatternsMatchRepresentativeSnippets()} additionally pins the pattern behaviour so the
 * guarantee survives even if those two mappers are one day removed.
 *
 * <p><b>Honest limitations — this is a text scanner, not a Java parser, and is deliberately NOT
 * exhaustive.</b> It matches only shapes where the feature literal appears in an
 * {@code enable(...)}/{@code configure(..., true)} call and the pairing lives in that same
 * statement. Concretely:
 * <ul>
 *   <li><b>MISS (can fail open):</b> the feature enabled without the literal token in an
 *       enable/configure call — via reflection, a {@code DeserializationFeature} value held in a
 *       variable/constant/collection and applied later, or a mapper coming from another module or a
 *       third-party library not under this scan.</li>
 *   <li><b>FLAGGED, fail-closed (may be a false positive):</b> a mapper whose enable and pairing are
 *       split across separate statements, or whose pairing is applied in a different method — the
 *       enabling statement looks unpaired and is flagged, so the shape is caught rather than missed;
 *       resolve a genuine false positive with a scoped suppression.</li>
 * </ul>
 * It is a backstop against the <em>common</em> fluent-declaration shape, not a proof of absence. The
 * behavioural tests remain the primary protection for the two known mappers.
 *
 * <p><b>A stronger design exists but is out of scope here.</b> A single sanctioned factory method
 * that returns a mapper with the feature and its pairing both applied would make the unpaired shape
 * <em>unrepresentable</em> rather than merely detected. That is a larger change; this guard only
 * detects.
 *
 * <p><b>Fail-closed.</b> If the module root cannot be located, or the walk visits implausibly few
 * files, or a statement mentions the feature in a form the scanner cannot classify as enable/disable,
 * the guard fails loudly rather than passing green.
 *
 * <p><b>Suppression.</b> To suppress a genuine false positive, place a comment
 * {@code // @BigDecimalPairingGuardSuppress: <reason>} on the flagged statement — on the same line
 * as the feature, on any line the statement spans, or on the single line immediately above where the
 * statement starts. A marker elsewhere in the file does not suppress the statement.
 */
public class BigDecimalMapperPairingGuardTest {

    /**
     * Surefire runs with the module basedir ({@code mockserver-core}) as the working directory; its
     * parent is the {@code mockserver/} aggregator that holds every Java module. Scanning from there
     * covers a mapper added in any module, not just core.
     */
    private static final Path MODULES_ROOT = Paths.get("..");

    /** A broken walk (wrong CWD, glob failure) must fail closed, not scan nothing and pass. */
    private static final int MIN_EXPECTED_MAIN_FILES = 500;

    private static final String SUPPRESS_MARKER = "@BigDecimalPairingGuardSuppress";

    private static final String FEATURE = "USE_BIG_DECIMAL_FOR_FLOATS";
    private static final String STRIP = "STRIP_TRAILING_BIGDECIMAL_ZEROES";

    /**
     * Optional owning-type qualifier before an enum constant. Accepts a simple prefix
     * ({@code DeserializationFeature.}) AND a fully-qualified one
     * ({@code com.fasterxml.jackson.databind.DeserializationFeature.}), because {@code [\w.]*}
     * spans dots. Without this a legitimately-paired mapper written with fully-qualified names would
     * be flagged — a false positive whose absence the class comment above depends on.
     */
    private static final String QUALIFIER = "(?:[A-Za-z_][\\w.]*\\.)?";

    // Enabling the feature (either fluent .enable(...) or .configure(..., true)).
    private static final Pattern ENABLE = Pattern.compile(
        "\\benable\\s*\\(\\s*" + QUALIFIER + FEATURE + "\\s*\\)"
            + "|\\bconfigure\\s*\\(\\s*" + QUALIFIER + FEATURE + "\\s*,\\s*true\\s*\\)");

    // Disabling the feature (safe — no corruption possible).
    private static final Pattern DISABLE = Pattern.compile(
        "\\bdisable\\s*\\(\\s*" + QUALIFIER + FEATURE + "\\s*\\)"
            + "|\\bconfigure\\s*\\(\\s*" + QUALIFIER + FEATURE + "\\s*,\\s*false\\s*\\)");

    // Any accepted pairing that preserves exact BigDecimals. Accept several spellings — including
    // fully-qualified names — so a legitimate future refactor does not false-positive.
    private static final Pattern PAIRING = Pattern.compile(
        // setNodeFactory(...) or builder .nodeFactory(...) using withExactBigDecimals(true)
        "withExactBigDecimals\\s*\\(\\s*true\\s*\\)"
            // or explicitly turning off trailing-zero stripping
            + "|disable\\s*\\(\\s*" + QUALIFIER + STRIP + "\\s*\\)"
            + "|configure\\s*\\(\\s*" + QUALIFIER + STRIP + "\\s*,\\s*false\\s*\\)");

    @Test
    public void everyMapperEnablingBigDecimalForFloatsMustPairExactBigDecimals() throws IOException {
        assertTrue("expected the mockserver modules root at " + MODULES_ROOT.toAbsolutePath()
                + " (this guard must run with the mockserver-core module as the working directory)",
            Files.isDirectory(MODULES_ROOT));

        List<Violation> violations = new ArrayList<>();
        AtomicInteger mainFilesScanned = new AtomicInteger();

        Files.walkFileTree(MODULES_ROOT, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String path = file.toString().replace('\\', '/');
                if (!path.endsWith(".java") || !path.contains("/src/main/java/")) {
                    return FileVisitResult.CONTINUE;
                }
                mainFilesScanned.incrementAndGet();

                String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                if (!content.contains(FEATURE)) {
                    return FileVisitResult.CONTINUE;
                }
                violations.addAll(scan(path, content));
                return FileVisitResult.CONTINUE;
            }
        });

        assertTrue("guard walked only " + mainFilesScanned.get() + " main-source files (expected at least "
                + MIN_EXPECTED_MAIN_FILES + ") — the source walk is broken; failing closed rather than "
                + "reporting a false all-clear",
            mainFilesScanned.get() >= MIN_EXPECTED_MAIN_FILES);

        if (!violations.isEmpty()) {
            StringBuilder msg = new StringBuilder();
            msg.append("Found ").append(violations.size())
                .append(" mapper declaration(s) that risk the Jackson big-decimal corruption of issue #2658.\n")
                .append("Enabling ").append(FEATURE).append(" makes readTree build DecimalNodes that Jackson's\n")
                .append(STRIP).append(" (default true since 2.15) normalises, so 275.0 is stored\n")
                .append("as 275 and a byte-identical request stops matching.\n\n");
            for (Violation v : violations) {
                msg.append("  - ").append(v.filePath).append(" (line ").append(v.line).append(")\n");
                msg.append("    ").append(v.snippet).append("\n");
                msg.append("    ").append(v.reason).append("\n\n");
            }
            msg.append("FIX: in the SAME statement that enables the feature, add one of:\n")
                .append("  .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true))\n")
                .append("  JsonMapper.builder()....nodeFactory(JsonNodeFactory.withExactBigDecimals(true)).build()\n")
                .append("  .disable(JsonNodeFeature.").append(STRIP).append(")\n")
                .append("If the flag is a genuine false positive (e.g. the mapper is configured across several\n")
                .append("statements or in a helper method), add a comment ON THE FLAGGED STATEMENT:\n")
                .append("  // ").append(SUPPRESS_MARKER).append(": <reason>\n");
            fail(msg.toString());
        }
    }

    /**
     * Scans a single source file's content and returns any pairing violations. Extracted so the
     * detection logic can be unit-tested on synthetic content, independently of the file walk.
     */
    static List<Violation> scan(String path, String content) {
        List<Violation> violations = new ArrayList<>();
        // Blank out comments and string/char literals so a token inside them is never matched, while
        // preserving newlines so offsets/line numbers stay aligned with the original source.
        String code = stripCommentsAndLiterals(content);
        String[] originalLines = content.split("\n", -1);

        for (Statement statement : splitStatements(code)) {
            if (!statement.text.contains(FEATURE)) {
                continue;
            }
            boolean enabling = ENABLE.matcher(statement.text).find();
            boolean disabling = DISABLE.matcher(statement.text).find();

            if (disabling && !enabling) {
                continue; // safe: the statement only turns the feature off
            }

            String reason;
            if (!enabling) {
                // The feature is mentioned in a shape this scanner cannot classify. Fail closed:
                // we cannot prove it is safe, so demand a pairing or an explicit suppression.
                reason = "references " + FEATURE + " in a form the guard cannot classify as enable or disable";
            } else if (!PAIRING.matcher(statement.text).find()) {
                reason = "enables " + FEATURE + " without pairing it with exact BigDecimals in the same statement";
            } else {
                continue; // enabled and correctly paired
            }

            if (isStatementSuppressed(originalLines, statement)) {
                continue;
            }
            violations.add(new Violation(path, statement.lineOf(FEATURE), statement.snippet(), reason));
        }
        return violations;
    }

    /**
     * A suppression marker only silences the statement it belongs to. It counts when it sits on any
     * line the statement spans (its first content line through the flagged token) or on the single
     * line immediately above where the statement starts. A marker anywhere else in the file is
     * ignored — this preserves the statement-scoping the guard depends on.
     */
    private static boolean isStatementSuppressed(String[] originalLines, Statement statement) {
        int from = Math.max(1, statement.firstContentLine() - 1);
        int to = Math.max(statement.lineOf(FEATURE), statement.firstContentLine());
        for (int ln = from; ln <= to && ln <= originalLines.length; ln++) {
            if (originalLines[ln - 1].contains(SUPPRESS_MARKER)) {
                return true;
            }
        }
        return false;
    }

    // ---- self-tests: pin pattern and suppression behaviour so the guarantee survives refactors ----

    @Test
    public void detectionPatternsMatchRepresentativeSnippets() {
        // ENABLE: simple, fully-qualified, and configure(..., true)
        assertTrue(ENABLE.matcher(".enable(DeserializationFeature." + FEATURE + ")").find());
        assertTrue(ENABLE.matcher(".enable(com.fasterxml.jackson.databind.DeserializationFeature." + FEATURE + ")").find());
        assertTrue(ENABLE.matcher(".configure(DeserializationFeature." + FEATURE + ", true)").find());
        assertTrue(ENABLE.matcher(".enable(" + FEATURE + ")").find()); // statically imported
        assertFalse(ENABLE.matcher(".disable(DeserializationFeature." + FEATURE + ")").find());
        assertFalse(ENABLE.matcher(".configure(DeserializationFeature." + FEATURE + ", false)").find());

        // DISABLE
        assertTrue(DISABLE.matcher(".disable(DeserializationFeature." + FEATURE + ")").find());
        assertTrue(DISABLE.matcher(".configure(DeserializationFeature." + FEATURE + ", false)").find());
        assertFalse(DISABLE.matcher(".enable(DeserializationFeature." + FEATURE + ")").find());

        // PAIRING: all three accepted spellings, simple and fully-qualified
        assertTrue(PAIRING.matcher(".setNodeFactory(JsonNodeFactory.withExactBigDecimals(true))").find());
        assertTrue(PAIRING.matcher(".nodeFactory(JsonNodeFactory.withExactBigDecimals( true ))").find());
        assertTrue(PAIRING.matcher(".disable(JsonNodeFeature." + STRIP + ")").find());
        assertTrue(PAIRING.matcher(".disable(com.fasterxml.jackson.databind.cfg.JsonNodeFeature." + STRIP + ")").find());
        assertTrue(PAIRING.matcher(".configure(JsonNodeFeature." + STRIP + ", false)").find());
        assertFalse(PAIRING.matcher(".setNodeFactory(JsonNodeFactory.withExactBigDecimals(false))").find());
        assertFalse(PAIRING.matcher(".enable(JsonNodeFeature." + STRIP + ")").find());
    }

    @Test
    public void fullyQualifiedPairingIsAcceptedAndFullyQualifiedUnpairedIsFlagged() {
        String qualifiedPaired = "class X {\n"
            + "  ObjectMapper m = new ObjectMapper()\n"
            + "    .enable(com.fasterxml.jackson.databind.DeserializationFeature." + FEATURE + ")\n"
            + "    .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));\n"
            + "}\n";
        assertEquals("fully-qualified paired mapper must not be flagged", 0, scan("Q.java", qualifiedPaired).size());

        String qualifiedUnpaired = "class X {\n"
            + "  ObjectMapper m = new ObjectMapper()\n"
            + "    .enable(com.fasterxml.jackson.databind.DeserializationFeature." + FEATURE + ");\n"
            + "}\n";
        assertEquals("fully-qualified unpaired mapper must be flagged", 1, scan("Q.java", qualifiedUnpaired).size());
    }

    @Test
    public void perStatementSuppressionDoesNotHideADifferentUnpairedStatement() {
        String content = "class X {\n"
            + "  // " + SUPPRESS_MARKER + ": mapper a is configured across statements elsewhere\n"
            + "  ObjectMapper a = new ObjectMapper().enable(DeserializationFeature." + FEATURE + ");\n"
            + "  ObjectMapper b = new ObjectMapper().enable(DeserializationFeature." + FEATURE + ");\n"
            + "}\n";
        List<Violation> violations = scan("S.java", content);
        assertEquals("suppression on statement a must not hide the unpaired statement b", 1, violations.size());
        assertTrue("the surviving violation must be the unpaired mapper b",
            violations.get(0).snippet.contains("ObjectMapper b"));
    }

    /**
     * Replaces line comments, block comments, string literals, text blocks and char literals with
     * blanks, preserving every newline so line numbers computed against the result match the original.
     */
    private static String stripCommentsAndLiterals(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            char next = i + 1 < n ? src.charAt(i + 1) : '\0';

            if (c == '/' && next == '/') {                       // line comment
                i += 2;
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && next == '*') {                       // block comment
                i += 2;
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                i = Math.min(n, i + 2);
                continue;
            }
            if (c == '"' && next == '"' && i + 2 < n && src.charAt(i + 2) == '"') {  // text block
                i += 3;
                while (i + 2 < n && !(src.charAt(i) == '"' && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"')) {
                    if (src.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                i = Math.min(n, i + 3);
                out.append("\"\"");
                continue;
            }
            if (c == '"') {                                      // string literal
                i++;
                while (i < n && src.charAt(i) != '"') {
                    if (src.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i++;
                out.append("\"\"");
                continue;
            }
            if (c == '\'') {                                     // char literal
                i++;
                while (i < n && src.charAt(i) != '\'') {
                    if (src.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i++;
                out.append("' '");
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Splits code into {@code ;}-delimited statements, tracking the start offset of each. */
    private static List<Statement> splitStatements(String code) {
        List<Statement> statements = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) == ';') {
                statements.add(new Statement(code, start, i));
                start = i + 1;
            }
        }
        if (start < code.length()) {
            statements.add(new Statement(code, start, code.length()));
        }
        return statements;
    }

    private static final class Statement {
        final String fullCode;
        final int startOffset;
        final String text;

        Statement(String fullCode, int startOffset, int endOffset) {
            this.fullCode = fullCode;
            this.startOffset = startOffset;
            this.text = fullCode.substring(startOffset, endOffset);
        }

        /** 1-based line of the given token within this statement (or the statement start if absent). */
        int lineOf(String token) {
            int idx = text.indexOf(token);
            return lineAt(startOffset + Math.max(0, idx));
        }

        /** 1-based line of the first non-whitespace character of this statement. */
        int firstContentLine() {
            int idx = 0;
            while (idx < text.length() && Character.isWhitespace(text.charAt(idx))) {
                idx++;
            }
            return lineAt(startOffset + Math.min(idx, Math.max(0, text.length() - 1)));
        }

        private int lineAt(int absoluteOffset) {
            int line = 1;
            for (int i = 0; i < absoluteOffset && i < fullCode.length(); i++) {
                if (fullCode.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        }

        String snippet() {
            String collapsed = text.trim().replaceAll("\\s+", " ");
            return collapsed.length() > 200 ? collapsed.substring(0, 197) + "..." : collapsed;
        }
    }

    static final class Violation {
        final String filePath;
        final int line;
        final String snippet;
        final String reason;

        Violation(String filePath, int line, String snippet, String reason) {
            this.filePath = filePath;
            this.line = line;
            this.snippet = snippet;
            this.reason = reason;
        }
    }
}
