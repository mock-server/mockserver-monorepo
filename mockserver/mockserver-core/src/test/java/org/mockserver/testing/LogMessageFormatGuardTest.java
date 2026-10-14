package org.mockserver.testing;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Build-time guard: a log entry's message FORMAT must not be built from values that can carry captured traffic.
 *
 * <h2>Why</h2>
 * <p>{@code redactSecretsInLog} masks a request, a response or a {@code SensitiveLogValue} passed as an argument (or
 * attached to the entry) field by field, and scrubs the rest of the entry's text of the credential values those carry.
 * A request, a response, a Netty message or a raw payload concatenated into the format is plain text with nothing to
 * redact it against. So every non-literal operand of a
 * {@code .setMessageFormat(...)} argument, in every module's main sources, must match {@link #ALLOWED} with a stated
 * reason; anything else fails the build, listing the offending sites. Pass the value as an argument ({@code "...:{}"}
 * with {@code setArguments(value)}), attach the request or response, or wrap raw payload text in
 * {@code SensitiveLogValue}.
 *
 * <p>The same holds for what is passed to {@code .setArguments(...)} and {@code .setBecause(...)}: an argument is
 * redacted structurally only while it is still an object, so an argument must not be string concatenation of a value
 * outside {@link #ALLOWED}, nor turn a value into text ({@code String.valueOf}, {@code .toString()},
 * {@code String.format}, {@code .formatted}, {@code formatLogMessage}) unless {@link #ALLOWED_ARGUMENT_TEXT} says why.
 *
 * <h2>Limits</h2>
 * <p>A textual check of these calls' own arguments: a variable is judged by its name, not by what was concatenated into
 * it earlier, which is why a pass-through variable is allowed only in the named file whose callers were reviewed, and a
 * value turned into text before it reaches the call is not seen.
 */
public class LogMessageFormatGuardTest {

    private static final Path MODULES_ROOT = Paths.get("..");

    private static final List<Allowed> ALLOWED = List.of(
        anywhere("(?:[A-Z]\\w*\\.)*[A-Z][A-Z0-9_]*", "a constant message fragment"),
        anywhere("\\d+|null", "a literal"),
        anywhere("(?:throwable|exception|e|ex|t|cause|iae|pse|jpe|sce|ioe|murle|error|nfe)(?:\\.getCause\\(\\))?\\.getMessage\\(\\)|\\w+(?:\\.\\w+\\(\\))*\\.cause\\(\\)\\.getMessage\\(\\)|isNotBlank\\(\\w+\\.getMessage\\(\\)\\)|ree",
            "an exception message: scrubbed at render time against the entry's requests, responses and arguments, exactly as a String argument would be"),
        anywhere("(?:\\w+(?:\\.\\w+\\(\\))*\\.)?getClass\\(\\)(?:\\.get(?:Simple)?Name\\(\\))?|\\w+\\.class\\.getName\\(\\)|(?:uncapitalize\\()?\\w+\\.get(?:Simple)?Name\\(\\)\\)?|httpClassCallback\\.getCallbackClass\\(\\)|className",
            "a class, scenario or definition name"),
        anywhere("clientId|webSocketCorrelationId|correlationId|id|expectationId|expectation\\.getId\\(\\)|openAPIDefinition\\.getOperationId\\(\\)|label",
            "a generated or configured identifier"),
        anywhere("ctx\\.channel\\(\\)|target|sniDescription\\([^()]*(?:\\([^()]*\\))?[^()]*\\)|[\\w.()]*(?:local|remote)Address\\(\\)|host|port|mockServerPort|portBindings?|certInfo|diagnosticHint|remoteSocket",
            "a channel, network endpoint or TLS diagnostic, not request content"),
        anywhere("filePath(?:\\.toString\\(\\))?|file|csvFile|x509CertificatePath|keyStoreFileAbsolutePath|propertyFile\\(\\)|tempPath|metaPath|prefix",
            "a configured file path"),
        in("FilePath.java", "pattern", "a configured file glob"),
        in("RequestMatchers.java", "expectationsChecked|expectationsConsidered|entriesNotLogged", "a count of expectations checked for the closest match, or of did-not-match entries not logged"),
        anywhere("\\w+(?:\\.\\w+\\(\\))*\\.size\\(\\)|i \\+ 1|inputCount|drainMillis|remaining|loaded|matchedFields|totalFields|namespaceSkipped|contentLength|bodyLength|lineNumber|skippedLineCount|integer|effective\\.get\\w+\\(\\)|getLocalPorts\\(\\)(?:\\.get\\(0\\))?",
            "a count, size or duration"),
        anywhere("configuration\\.\\w+\\(\\)|ConfigurationProperties\\.\\w+\\(\\)", "a configuration value"),
        anywhere("verification\\.getTimes\\(\\)|format\\.name\\(\\)\\.toLowerCase\\(\\)|action\\.toLowerCase\\(\\)|\\w*[aA]ction\\.getType\\(\\)|decision\\.getAction\\(\\)|parameter\\.getIn\\(\\)|stringValue\\(req\\.getMethod\\(\\), \"\"\\)",
            "an enum, type or HTTP method"),
        in("ConfigurationProperties.java", "explicit|description|key|systemPropertyKey|unknownKey|value|proxyParts\\[1\\]|readPropertyHierarchically\\(PROPERTIES, key, environmentVariableKey, (?:\"\" \\+ )?defaultValue\\)|propertiesLogDump\\.toString\\(\\)",
            "configuration property names, sources and values, not captured traffic"),
        in("HttpState.java", "property|reason|suppliedValue|valueInForce|error", "a configuration property and its values, or a load-scenario validation error"),
        in("HttpState.java", "messageFormat", "logScenario's and the configuration loggers' parameter: every caller passes a literal"),
        in("MatchingTimeoutExecutor.java", "description", "the literal name of the matcher kind"),
        in("FileCreator.java", "type", "the kind of file being created"),
        in("BCKeyAndCertificateFactory.java", "type", "the kind of key or certificate"),
        in("MockServerClient.java", "reason", "the client's own stop-failure description"),
        in("HttpActionHandler.java", "logMessageFormat", "a format chosen from literals by the caller"),
        in("ExpectationInitializerLoader.java", "initialLogMessage|completedLogMessage|exceptionLogMessage|expectationLogMessage", "formats built from literals and the initializer's class name"),
        in("MediaType.java", "charset|parameters", "a Content-Type charset or parameter string: not a credential header"),
        in("LogEntry.java", "getMessageFormat\\(\\)", "copying an entry's own format"),
        in("DnsRequestPropertiesMatcher.java", "didNotMatchRequestBecause|didNotMatchExpectationWithoutBecause", "formats built from literals"),
        in("BinaryRequestPropertiesMatcher.java", "didNotMatchRequestBecause|didNotMatchExpectationWithoutBecause", "formats built from literals"),
        in("XmlStringMatcher.java", "matcher", "the user-authored matcher"),
        in("JsonPathMatcher.java", "matcher", "the user-authored matcher"),
        in("StatusCodeMatcher.java", "statusCodeRange", "the user-authored status code range"),
        in("OpenAPIValidationErrors.java", "context", "the OpenAPI validation context"),
        in("StrictBodyDTODeserializer.java", "entry\\.getValue\\(\\)", "a field of a control-plane body definition"),
        in("BodyDTODeserializer.java", "entry\\.getValue\\(\\)", "a field of a control-plane body definition"),
        in("BodyWithContentTypeDTODeserializer.java", "entry\\.getValue\\(\\)", "a field of a control-plane body definition"),
        in("LifeCycle.java", "message|proxySetupInfo\\.copyPasteText\\(\\)", "server lifecycle and proxy-setup text built from literals and ports"),
        in("ProxyProtocolOriginalDestinationHandler.java", "format", "logWarning's parameter: every caller passes a literal"),
        in("MockServerEventLog.java", "s", "the verification-sequence failure format: every caller passes a literal"),
        in("PortUnificationHandler.java", "message", "logStage's parameter: every caller passes a literal"),
        in("FilesystemBlobStore.java", "message", "logError's parameter: literals and blob paths"),
        in("ExpectationFileSystemPersistence.java", "message", "logEvent's parameter: literals and configured paths"),
        in("JavaScriptTemplateEngine.java", "message", "a literal message"),
        in("PolyglotRunner.java", "message", "a literal message and the configured timeout"),
        in("WebSocketClientHandler.java", "message", "the callback registry's own registration error text"),
        in("RelayConnectHandler.java", "message", "failure's parameter: literals and the CONNECT target address"),
        in("BasicLogger.java", "message", "a public logging API: the caller owns the text"),
        in("MockServerPropertyCustomizer.java", "message", "logWarning's parameter: test-time property names and parse errors"),
        in("MatchDifference.java", "messageFormat", "a matcher's literal difference format; the compared values are arguments"),
        in("HttpRequestPropertiesMatcher.java", "messageFormat", "built from literals, the matcher description and the expectation id"),
        in("CustomJsonUnitMatcherLoader.java", "messageFormat", "a literal format"),
        in("MockServerExtension.java", "messageFormat", "a literal format"),
        in("MockServerRule.java", "messageFormat", "a literal format"),
        in("HttpState.java", "operation|authorizer\\.grantedRoles\\(scopes\\)|authorizer\\.requiredRole\\(isRead\\)|method|sourceAddress|principalAndSource\\[[01]\\]|outcome",
            "a control-plane audit field: operation, role, method, principal and source address"),
        in("NettyHttpClient.java", "httpRequest\\.getMethod\\(\"\"\\)|httpRequest\\.getPath\\(\\)", "the forwarded request's method and path, without its query string"),
        in("JsonSchemaBodyDecoder.java", "prettyPrint\\(errorEntry\\.getKey\\(\\)\\)|errorEntry\\.getValue\\(\\)", "an XML parse error; the entry attaches the request it was parsed from"),
        in("Main.java", "Joiner\\.on\\(\"\"\\)\\.withKeyValueSeparator\\(\"\"\\)\\.join\\(\\w+Arguments\\)", "the server's own command line, environment and system properties")
    );

    /**
     * Arguments turned into text before they are logged, each matched whole; the entry's redaction then only scrubs the
     * text, so each needs a reason why structural redaction is not lost.
     */
    private static final List<Allowed> ALLOWED_ARGUMENT_TEXT = List.of(
        in("MustacheTemplateEngine.java", "(?:generatedObject != null \\? generatedObject : )?writer\\.toString\\(\\)", "template output: text by nature; the entry attaches the request it was rendered from"),
        in("VelocityTemplateEngine.java", "(?:generatedObject != null \\? generatedObject : )?writer\\.toString\\(\\)", "template output: text by nature; the entry attaches the request it was rendered from"),
        in("ExampleBuilder.java", "StringUtils\\.substringBeforeLast\\(location\\.toString\\(\\), \"\"\\)", "a location in the user's OpenAPI document"),
        in("PortUnificationHandler.java", "ctx\\.channel\\(\\)\\.toString\\(\\)", "a channel description")
    );

    private static final Pattern TEXT_CONVERSION = Pattern.compile("String\\.valueOf\\(|\\.toString\\(\\)|String\\.format\\(|\\.formatted\\(|formatLogMessage\\(");

    @Test
    public void shouldNotBuildLogMessageFormatsFromValues() throws IOException {
        List<Path> sources = mainSources();
        Set<String> modules = sources.stream().map(LogMessageFormatGuardTest::module).collect(Collectors.toCollection(TreeSet::new));
        assertThat("must scan the core and netty main sources: " + modules, modules, hasItems("mockserver-core", "mockserver-netty"));
        assertThat("must scan a representative source tree", sources.size(), greaterThan(500));

        Set<Allowed> used = new HashSet<>();
        List<String> offenders = new ArrayList<>();
        int formats = 0;
        for (Path source : sources) {
            String fileName = source.getFileName().toString();
            String code = stripComments(new String(Files.readAllBytes(source), StandardCharsets.UTF_8));
            for (Format format : formats(code)) {
                formats++;
                for (String problem : problems(format.expression, fileName, used)) {
                    offenders.add(MODULES_ROOT.relativize(source) + ":" + lineOf(code, format.offset) + " " + problem);
                }
            }
            for (Format arguments : calls(code, "\\.(?:setArguments|setBecause)\\(")) {
                for (String problem : argumentProblems(arguments.expression, fileName, used)) {
                    offenders.add(MODULES_ROOT.relativize(source) + ":" + lineOf(code, arguments.offset) + " " + problem);
                }
            }
        }
        assertThat("must find the log message formats to check", formats, greaterThan(500));
        assertThat("log message formats built from values that can carry captured traffic - pass the value as an argument "
                + "(\"...:{}\" with setArguments), attach the request/response, wrap raw payload text in SensitiveLogValue, "
                + "or add a reasoned entry to ALLOWED:\n" + String.join("\n", offenders),
            offenders, is(empty()));
        List<String> unused = Stream.concat(ALLOWED.stream(), ALLOWED_ARGUMENT_TEXT.stream()).filter(allowed -> !used.contains(allowed)).map(Object::toString).collect(Collectors.toList());
        assertThat("allow-list entries that no longer match anything - remove them:\n" + String.join("\n", unused), unused, is(empty()));
    }

    @Test
    public void shouldFlagAValueConcatenatedIntoAFormat() {
        Set<Allowed> used = new HashSet<>();
        assertThat(problems("\"exception forwarding request \" + request", "Any.java", used), contains("operand 'request'"));
        assertThat(problems("\"exception \" + throwable.getMessage() + \" writing \" + msg", "Any.java", used), contains("operand 'msg'"));
        assertThat(problems("String.format(\"%s\", uri)", "Any.java", used), contains("String.format / formatLogMessage in a format"));
        assertThat(problems("\"x\" + (flag ? request : \"none\")", "Any.java", used), contains("operand 'request'"));
        assertThat(problems("\"x\" + (a ? b ? request : \"y\" : \"z\")", "Any.java", used), contains("operand 'request'"));
        assertThat(problems("\"literal\" + NEW_LINE + \"more\"", "Any.java", used), is(empty()));
        assertThat(problems("\"exception \" + throwable.getMessage() + \" for client \" + clientId", "Any.java", used), is(empty()));
        assertThat(problems("message", "Other.java", used), contains("operand 'message'"));
        assertThat(argumentProblems("\"found \" + request, action", "Any.java", used), contains("argument operand 'request'"));
        assertThat(argumentProblems("String.valueOf(request)", "Any.java", used), contains("argument turned into text 'String.valueOf(request)'"));
        assertThat(argumentProblems("request.toString()", "Any.java", used), contains("argument turned into text 'request.toString()'"));
        assertThat(argumentProblems("\"%s\".formatted(request)", "Any.java", used), contains("argument turned into text '\"\".formatted(request)'"));
        assertThat(argumentProblems("request, flag ? response : request, size + 1, throwable.getMessage()", "Any.java", used), is(empty()));
        assertThat(argumentProblems("\"for client \" + clientId", "Any.java", used), is(empty()));
    }

    private static List<Path> mainSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> modules = Files.list(MODULES_ROOT)) {
            for (Path module : modules.filter(Files::isDirectory).collect(Collectors.toList())) {
                Path main = module.resolve(Paths.get("src", "main", "java"));
                if (Files.isDirectory(main)) {
                    try (Stream<Path> files = Files.walk(main)) {
                        files.filter(file -> file.toString().endsWith(".java")).forEach(sources::add);
                    }
                }
            }
        }
        return sources;
    }

    private static String module(Path source) {
        return MODULES_ROOT.relativize(source).getName(0).toString();
    }

    private static final class Format {
        private final int offset;
        private final String expression;

        private Format(int offset, String expression) {
            this.offset = offset;
            this.expression = expression;
        }
    }

    private static List<Format> formats(String code) {
        return calls(code, "\\.setMessageFormat\\(");
    }

    private static List<Format> calls(String code, String callPattern) {
        List<Format> formats = new ArrayList<>();
        Matcher call = Pattern.compile(callPattern).matcher(code);
        while (call.find()) {
            int end = closingParenthesis(code, call.end());
            formats.add(new Format(call.start(), code.substring(call.end(), end)));
        }
        return formats;
    }

    static List<String> problems(String expression, String fileName, Set<Allowed> used) {
        String stripped = stripLiterals(expression).replaceAll("\\s+", " ").trim();
        if (stripped.contains("String.format(") || stripped.contains("formatLogMessage(")) {
            return Collections.singletonList("String.format / formatLogMessage in a format");
        }
        List<String> problems = new ArrayList<>();
        for (String operand : operands(stripped)) {
            if (operand.isEmpty() || operand.equals("\"\"") || operand.equals("''") || operand.equals("NEW_LINE")) {
                continue;
            }
            Optional<Allowed> allowed = ALLOWED.stream().filter(entry -> entry.allows(fileName, operand)).findFirst();
            if (allowed.isPresent()) {
                used.add(allowed.get());
            } else {
                problems.add("operand '" + operand + "'");
            }
        }
        return problems;
    }

    /**
     * Each top-level argument of a {@code setArguments} / {@code setBecause} call: a conversion of a value to text must be
     * in {@link #ALLOWED_ARGUMENT_TEXT}, and string concatenation (a {@code +} chain with a literal in it) must only join
     * operands {@link #ALLOWED} in a format. A value passed as itself, or chosen by a condition, is not flagged.
     */
    static List<String> argumentProblems(String expression, String fileName, Set<Allowed> used) {
        List<String> problems = new ArrayList<>();
        for (String part : splitTopLevel(stripLiterals(expression).replaceAll("\\s+", " ").trim(), ',')) {
            String argument = part.trim();
            if (TEXT_CONVERSION.matcher(argument).find()) {
                Optional<Allowed> allowed = ALLOWED_ARGUMENT_TEXT.stream().filter(entry -> entry.allows(fileName, argument)).findFirst();
                if (allowed.isPresent()) {
                    used.add(allowed.get());
                } else {
                    problems.add("argument turned into text '" + argument + "'");
                }
                continue;
            }
            List<String> concatenated = splitTopLevel(argument, '+');
            if (concatenated.size() > 1 && concatenated.stream().anyMatch(operand -> operand.trim().equals("\"\""))) {
                for (String problem : problems(argument, fileName, used)) {
                    problems.add("argument " + problem);
                }
            }
        }
        return problems;
    }

    /**
     * The top-level operands of a {@code +} chain; a parenthesised or conditional operand contributes the operands of
     * its branches (its condition yields no text).
     */
    private static List<String> operands(String expression) {
        List<String> operands = new ArrayList<>();
        for (String part : splitTopLevel(expression, '+')) {
            String operand = part.trim();
            while (operand.startsWith("(") && operand.endsWith(")") && balanced(operand.substring(1, operand.length() - 1))) {
                operand = operand.substring(1, operand.length() - 1).trim();
            }
            int question = topLevelIndex(operand, '?', 0);
            if (question >= 0) {
                int colon = matchingColon(operand, question + 1);
                operands.addAll(operands(operand.substring(question + 1, colon)));
                operands.addAll(operands(operand.substring(colon + 1)));
            } else {
                operands.add(operand);
            }
        }
        return operands;
    }

    private static int matchingColon(String expression, int from) {
        int depth = 0;
        int pendingQuestions = 0;
        for (int i = from; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (depth == 0 && c == '?') {
                pendingQuestions++;
            } else if (depth == 0 && c == ':') {
                if (pendingQuestions == 0) {
                    return i;
                }
                pendingQuestions--;
            }
        }
        return expression.length();
    }

    private static int topLevelIndex(String expression, char wanted, int from) {
        int depth = 0;
        for (int i = from; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (depth == 0 && c == wanted) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitTopLevel(String expression, char separator) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int index;
        while ((index = topLevelIndex(expression, separator, start)) >= 0) {
            parts.add(expression.substring(start, index));
            start = index + 1;
        }
        parts.add(expression.substring(start));
        return parts;
    }

    private static boolean balanced(String expression) {
        int depth = 0;
        for (char c : expression.toCharArray()) {
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth < 0) {
                return false;
            }
        }
        return depth == 0;
    }

    private static String stripLiterals(String expression) {
        return expression.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"").replaceAll("'(\\\\.|[^'\\\\])'", "''");
    }

    private static int closingParenthesis(String code, int from) {
        int depth = 1;
        char quote = 0;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return code.length();
    }

    /**
     * Comments blanked (line breaks kept, so line numbers hold); string and character literals left intact.
     */
    private static String stripComments(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < code.length() && code.charAt(j) != c) {
                    if (code.charAt(j) == '\\') {
                        j++;
                    }
                    j++;
                }
                out.append(code, i, Math.min(j + 1, code.length()));
                i = j + 1;
            } else if (code.startsWith("//", i)) {
                int j = code.indexOf('\n', i);
                j = j < 0 ? code.length() : j;
                i = j;
            } else if (code.startsWith("/*", i)) {
                int j = code.indexOf("*/", i + 2);
                j = j < 0 ? code.length() : j + 2;
                out.append(code.substring(i, j).replaceAll("[^\n]", " "));
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int lineOf(String code, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static Allowed anywhere(String operand, String reason) {
        return new Allowed(null, operand, reason);
    }

    private static Allowed in(String fileName, String operand, String reason) {
        return new Allowed(fileName, operand, reason);
    }

    static final class Allowed {
        private final String fileName;
        private final Pattern operand;
        private final String reason;

        private Allowed(String fileName, String operand, String reason) {
            this.fileName = fileName;
            this.operand = Pattern.compile(operand);
            this.reason = reason;
        }

        private boolean allows(String file, String candidate) {
            return (fileName == null || fileName.equals(file)) && operand.matcher(candidate).matches();
        }

        @Override
        public String toString() {
            return (fileName == null ? "*" : fileName) + " " + operand.pattern() + " (" + reason + ")";
        }
    }
}
