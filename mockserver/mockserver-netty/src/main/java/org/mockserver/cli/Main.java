package org.mockserver.cli;

import com.google.common.base.Joiner;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.mockserver.authentication.ControlPlaneMutualTlsConfigurationException;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.mock.Expectation;
import org.mockserver.configuration.IntegerStringListParser;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.logging.Slf4jProviderFallback;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.dns.DnsStartupException;
import org.mockserver.netty.http3.Http3NativeUnavailableException;
import org.mockserver.netty.http3.Http3StartupException;
import org.mockserver.socket.NettyDirectMemoryLimit;
import org.mockserver.socket.RelocatedLibraryProperties;
import org.mockserver.socket.tls.DataPlaneMutualTlsConfigurationException;
import org.mockserver.version.Version;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.PrintStream;
import java.net.URI;
import java.util.*;

import static org.apache.commons.lang3.StringUtils.*;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.log.model.LogEntry.LogMessageType.SERVER_CONFIGURATION;
import static org.mockserver.mock.HttpState.setPort;
import static org.slf4j.event.Level.*;

/**
 * @author jamesdbloom
 */
@Command(
    name = "mockserver",
    mixinStandardHelpOptions = true,
    versionProvider = Main.MockServerVersionProvider.class,
    description = "MockServer — mock, proxy & record HTTP(S), gRPC, and more.",
    footer = {
        "",
        "Examples:",
        "  mockserver run -p 1080",
        "  mockserver ui -p 1080",
        "  mockserver proxy --to https://api.example.com",
        "  mockserver openapi ./petstore.yaml -p 1080",
        "  mockserver import ./expectations.json -p 1080",
        "  mockserver lint ./expectations.json",
        "  mockserver demo -p 1080",
        "  mockserver -p 1080",
        "",
        "Legacy flags (-serverPort, -proxyRemotePort, -proxyRemoteHost, -logLevel) are supported for backward compatibility."
    },
    subcommands = {
        Main.RunCommand.class,
        Main.UiCommand.class,
        Main.ProxyCommand.class,
        Main.OpenApiCommand.class,
        Main.ImportCommand.class,
        Main.LintCommand.class,
        Main.DemoCommand.class,
        Main.VersionCommand.class,
        CommandLine.HelpCommand.class,
    }
)
public class Main {

    // Must stay the first static initialiser: Netty reads the limit and its other properties once, when it first
    // initialises, and SLF4J chooses its provider once, when it first initialises (which Netty can trigger).
    static {
        Slf4jProviderFallback.selectBundledProviderIfNoneRegistered();
        RelocatedLibraryProperties.forwardUserProperties();
        NettyDirectMemoryLimit.applyDefault();
    }

    /**
     * The command shown in usage/help text. When MockServer is started via a bundled
     * launcher (the JVM-less binary), the launcher exports MOCKSERVER_LAUNCHER with its
     * own name (e.g. "mockserver"), so usage reads "mockserver -serverPort ..." instead
     * of the "java -jar <jar>" form. The "mockserver.launcherName" system property takes
     * precedence (used by tests). When neither is set it falls back to "java -jar <jar>".
     */
    private static String launcherName() {
        return System.getProperty("mockserver.launcherName", System.getenv("MOCKSERVER_LAUNCHER"));
    }

    static String launchCommand() {
        String launcher = launcherName();
        return isNotBlank(launcher) ? launcher : "java -jar <path to mockserver-netty-jar-with-dependencies.jar>";
    }

    static String launchExample() {
        String launcher = launcherName();
        return isNotBlank(launcher) ? launcher : "java -jar ./mockserver-netty-jar-with-dependencies.jar";
    }

    // Kept for backward compatibility with tests that reference Main.USAGE
    static final String USAGE = "" +
        "   version: " + Version.getVersion() + NEW_LINE +
        "    " + NEW_LINE +
        "   " + launchCommand() + " -serverPort <port> [-proxyRemotePort <port>] [-proxyRemoteHost <hostname>] [-logLevel <level>] " + NEW_LINE +
        "                                                                                                                                                                 " + NEW_LINE +
        "     valid options are:                                                                                                                                          " + NEW_LINE +
        "        -serverPort <port>           The HTTP, HTTPS, SOCKS and HTTP CONNECT                                                                                     " + NEW_LINE +
        "                                     port(s) for both mocking and proxying                                                                                       " + NEW_LINE +
        "                                     requests.  Port unification is used to                                                                                      " + NEW_LINE +
        "                                     support all protocols for proxying and                                                                                       " + NEW_LINE +
        "                                     mocking on the same port(s). Supports                                                                                       " + NEW_LINE +
        "                                     comma separated list for binding to                                                                                         " + NEW_LINE +
        "                                     multiple ports.                                                                                                             " + NEW_LINE +
        "                                                                                                                                                                 " + NEW_LINE +
        "        -proxyRemotePort <port>      Optionally enables port forwarding mode.                                                                                    " + NEW_LINE +
        "                                     When specified all requests received will                                                                                   " + NEW_LINE +
        "                                     be forwarded to the specified port, unless                                                                                  " + NEW_LINE +
        "                                     they match an expectation.                                                                                                  " + NEW_LINE +
        "                                                                                                                                                                 " + NEW_LINE +
        "        -proxyRemoteHost <hostname>  Specified the host to forward all proxy                                                                                     " + NEW_LINE +
        "                                     requests to when port forwarding mode has                                                                                   " + NEW_LINE +
        "                                     been enabled using the proxyRemotePort                                                                                      " + NEW_LINE +
        "                                     option.  This setting is ignored unless                                                                                     " + NEW_LINE +
        "                                     proxyRemotePort has been specified. If no                                                                                   " + NEW_LINE +
        "                                     value is provided for proxyRemoteHost when                                                                                  " + NEW_LINE +
        "                                     proxyRemotePort has been specified,                                                                                         " + NEW_LINE +
        "                                     proxyRemoteHost will default to \"localhost\".                                                                              " + NEW_LINE +
        "                                                                                                                                                                 " + NEW_LINE +
        "        -logLevel <level>            Optionally specify log level using SLF4J levels:                                                                            " + NEW_LINE +
        "                                     TRACE, DEBUG, INFO, WARN, ERROR, OFF or Java                                                                                " + NEW_LINE +
        "                                     Logger levels: FINEST, FINE, INFO, WARNING,                                                                                 " + NEW_LINE +
        "                                     SEVERE or OFF. If not specified default is INFO                                                                             " + NEW_LINE +
        "                                                                                                                                                                 " + NEW_LINE +
        "   i.e. " + launchExample() + " -serverPort 1080 -proxyRemotePort 80 -proxyRemoteHost www.mock-server.com -logLevel WARN                         " + NEW_LINE +
        "                                                                                                                                                                 " + NEW_LINE;
    private static final MockServerLogger MOCK_SERVER_LOGGER = new MockServerLogger(Main.class);
    private static final IntegerStringListParser INTEGER_STRING_LIST_PARSER = new IntegerStringListParser();
    static PrintStream systemErr = System.err;
    static PrintStream systemOut = System.out;
    static boolean usageShown = false;
    /**
     * The actual port(s) the most recent {@link #startServer} call bound the server to. When the
     * server is started with a requested port of 0 the OS assigns an ephemeral port, so the
     * requested value cannot be used to reach the server; this records the real listening port(s)
     * so a caller (a CLI wrapper, or an in-process test) can discover them. {@code null} until a
     * server has started in this JVM. See also {@link org.mockserver.mock.HttpState#getPort()},
     * which is set to the first of these.
     */
    static volatile List<Integer> lastStartedPorts;

    /** @return the actual port(s) bound by the most recent successful {@link #startServer} call, or {@code null} if none has started. */
    static List<Integer> getLastStartedPorts() {
        return lastStartedPorts;
    }
    /**
     * When true (the default for the real jar entry point), {@link #main(String...)} terminates the JVM
     * with a non-zero exit status when a command reports a failure (e.g. a failed {@code import}, a parse
     * error, or a startup exception) so that shell/CI callers can detect it. In-process tests that invoke
     * {@link #main(String...)} directly set this to {@code false} so a non-zero command does not kill the
     * test JVM. Successful long-running commands ({@code run}/{@code ui}/{@code proxy}/{@code openapi})
     * always return exit code 0 and never trigger an exit, so the server keeps running on its own threads.
     */
    static boolean exitOnNonZeroCode = true;

    /**
     * The exit code produced by the most recent {@link #main(String...)} invocation (the value picocli's
     * {@code execute()} resolved from a command's {@link CommandLine.IExitCodeGenerator} or an exception
     * handler). In-process tests set {@link #exitOnNonZeroCode} to {@code false} so a non-zero command
     * does not kill the test JVM, which also means {@link System#exit} never fires — so this field is the
     * only in-process signal of the code a real shell/CI caller would have observed. Reset to 0 at the
     * start of every {@link #main(String...)} call.
     */
    static volatile int lastExitCode = 0;

    /**
     * Run the MockServer directly providing the arguments as specified below.
     *
     * @param arguments the entries are in pairs:
     *                  - "-serverPort"       followed by the mandatory server local port,
     *                  - "-proxyRemotePort"  followed by the optional proxyRemotePort port that enabled port forwarding mode,
     *                  - "-proxyRemoteHost"  followed by the optional proxyRemoteHost port (ignored unless proxyRemotePort is specified)
     *                  - "-logLevel"         followed by the log level
     */
    public static void main(String... arguments) {
        lastExitCode = 0;
        try {
            // --print-config is a diagnostic that prints the effective configuration (each property's
            // resolved value and the source tier that supplied it) and exits, like --help/--version.
            // Handle it before picocli parsing so it works regardless of subcommand position.
            if (arguments != null) {
                for (String argument : arguments) {
                    if ("--print-config".equals(argument)) {
                        systemOut.print(ConfigurationProperties.effectiveConfigurationAsText());
                        systemOut.flush();
                        return;
                    }
                }
            }

            // Preprocess: if the first non-option argument is not a known subcommand, prepend "run"
            String[] processedArgs = preprocessArguments(arguments);

            CommandLine cmd = new CommandLine(new Main());
            cmd.setOut(new java.io.PrintWriter(systemOut, true));
            cmd.setErr(new java.io.PrintWriter(systemErr, true));
            cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
                logStartupFailure(ex, ConfigurationProperties.disableSystemOut());
                return 1;
            });
            cmd.setParameterExceptionHandler((ex, args) -> {
                // Print the error message + picocli's concise usage for the offending (sub)command
                systemErr.println("ERROR: " + ex.getMessage());
                systemErr.flush();
                systemOut.print(ex.getCommandLine().getUsageMessage());
                systemOut.flush();
                return 2;
            });
            // picocli's execute() returns the exit code (from a command's IExitCodeGenerator or an
            // exception handler) but does NOT terminate the JVM itself. Propagate a non-zero code so
            // shell/CI callers can detect failures (e.g. a failed `import`). A successful long-running
            // server command returns 0 and stays alive on its own (non-daemon) threads, so we must NOT
            // exit on 0. In-process tests disable the exit via exitOnNonZeroCode so a failure does not
            // kill the test JVM.
            int exitCode = cmd.execute(processedArgs);
            lastExitCode = exitCode;
            if (exitCode != 0 && exitOnNonZeroCode) {
                System.exit(exitCode);
            }
        } catch (Throwable throwable) {
            logStartupFailure(throwable, ConfigurationProperties.disableSystemOut());
        }
    }

    /**
     * A missing HTTP/3 native, an HTTP/3 or DNS port that cannot be used, and an unusable control-plane or data-plane
     * mTLS set-up are configuration problems whose fixes the message already spells out, so print just that
     * message: a stack trace buries the fix.
     */
    static void logStartupFailure(Throwable throwable, boolean disableSystemOut) {
        String http3Failure = http3FailureItsMessageExplains(throwable);
        if (http3Failure != null) {
            logFailureItsMessageExplains(new LogEntry().setMessageFormat("HTTP/3 start-up failed:{}"), http3Failure, disableSystemOut);
            return;
        }
        String dnsFailure = dnsFailureItsMessageExplains(throwable);
        if (dnsFailure != null) {
            logFailureItsMessageExplains(new LogEntry().setMessageFormat("DNS start-up failed:{}"), dnsFailure, disableSystemOut);
            return;
        }
        ControlPlaneMutualTlsConfigurationException controlPlaneMutualTls = ExceptionUtils.throwableOfType(throwable, ControlPlaneMutualTlsConfigurationException.class);
        if (controlPlaneMutualTls != null) {
            logFailureItsMessageExplains(new LogEntry().setMessageFormat("control plane mTLS configuration refused:{}"), controlPlaneMutualTls.getMessage(), disableSystemOut);
            return;
        }
        DataPlaneMutualTlsConfigurationException dataPlaneMutualTls = ExceptionUtils.throwableOfType(throwable, DataPlaneMutualTlsConfigurationException.class);
        if (dataPlaneMutualTls != null) {
            logFailureItsMessageExplains(new LogEntry().setMessageFormat("mTLS configuration refused:{}"), dataPlaneMutualTls.getMessage(), disableSystemOut);
            return;
        }
        MOCK_SERVER_LOGGER.logEvent(
            new LogEntry()
                .setType(SERVER_CONFIGURATION)
                .setLogLevel(ERROR)
                .setMessageFormat("exception while starting:{}")
                .setThrowable(throwable)
        );
        if (disableSystemOut) {
            new RuntimeException("exception while starting: " + throwable.getMessage()).printStackTrace(System.err);
        }
    }

    // a bare "{}" format renders nothing, and the message must not be the format (it may contain "{}")
    private static void logFailureItsMessageExplains(LogEntry withItsFormat, String message, boolean disableSystemOut) {
        MOCK_SERVER_LOGGER.logEvent(withItsFormat.setType(SERVER_CONFIGURATION).setLogLevel(ERROR).setArguments(message));
        if (disableSystemOut) {
            System.err.println(message);
        }
    }

    private static String http3FailureItsMessageExplains(Throwable throwable) {
        Http3NativeUnavailableException nativeUnavailable = ExceptionUtils.throwableOfType(throwable, Http3NativeUnavailableException.class);
        if (nativeUnavailable != null) {
            return nativeUnavailable.getMessage();
        }
        Http3StartupException startFailed = ExceptionUtils.throwableOfType(throwable, Http3StartupException.class);
        return startFailed != null && startFailed.isPortUnavailable() ? startFailed.getMessage() : null;
    }

    private static String dnsFailureItsMessageExplains(Throwable throwable) {
        DnsStartupException startFailed = ExceptionUtils.throwableOfType(throwable, DnsStartupException.class);
        return startFailed != null && startFailed.isPortUnavailable() ? startFailed.getMessage() : null;
    }

    /**
     * Preprocess arguments: if the first non-help/version token is not a known subcommand,
     * prepend "run" so that bare "mockserver -p 1080" and legacy "-serverPort 1080" work.
     */
    static String[] preprocessArguments(String... arguments) {
        if (arguments == null || arguments.length == 0) {
            return new String[]{"run"};
        }
        Set<String> subcommands = Set.of("run", "ui", "proxy", "openapi", "import", "lint", "demo", "version", "help");
        // Top-level help/version flags should NOT be prepended with "run"
        Set<String> topLevelFlags = Set.of("--help", "-h", "--version", "-V");
        String first = arguments[0];
        // Normalise legacy single-dash long forms so "mockserver -help"/"-version" behave like
        // "--help"/"--version" (a top-level overview) instead of clustering into "run -h".
        if (first.equals("-help")) {
            arguments = arguments.clone();
            arguments[0] = first = "--help";
        } else if (first.equals("-version")) {
            arguments = arguments.clone();
            arguments[0] = first = "--version";
        }
        // If the first token is a known subcommand, leave it alone
        if (subcommands.contains(first)) {
            return arguments;
        }
        // If the first token is a top-level help/version flag, leave it alone
        if (topLevelFlags.contains(first)) {
            return arguments;
        }
        // Otherwise it's an option or positional arg → prepend "run"
        String[] result = new String[arguments.length + 1];
        result[0] = "run";
        System.arraycopy(arguments, 0, result, 1, arguments.length);
        return result;
    }

    /**
     * Resolve configuration from CLI args, system properties, environment variables,
     * and properties file following the existing precedence:
     * CLI > system property > env var (long MOCKSERVER_* then short) > properties file.
     *
     * Then start the MockServer.
     */
    static boolean startServer(String serverPortValue, String proxyRemotePortValue,
                               String proxyRemoteHostValue, String logLevelValue) {
        Map<String, String> parsedArguments = new HashMap<>();
        Map<String, String> commandLineArguments = new HashMap<>();
        Map<String, String> environmentVariableArguments = new HashMap<>();
        Map<String, String> systemPropertyArguments = new HashMap<>();

        if (isNotBlank(serverPortValue)) {
            parsedArguments.put(Arguments.serverPort.name(), serverPortValue);
            commandLineArguments.put(Arguments.serverPort.name(), serverPortValue);
        }
        if (isNotBlank(proxyRemotePortValue)) {
            parsedArguments.put(Arguments.proxyRemotePort.name(), proxyRemotePortValue);
            commandLineArguments.put(Arguments.proxyRemotePort.name(), proxyRemotePortValue);
        }
        if (isNotBlank(proxyRemoteHostValue)) {
            parsedArguments.put(Arguments.proxyRemoteHost.name(), proxyRemoteHostValue);
            commandLineArguments.put(Arguments.proxyRemoteHost.name(), proxyRemoteHostValue);
        }
        if (isNotBlank(logLevelValue)) {
            parsedArguments.put(Arguments.logLevel.name(), logLevelValue);
            commandLineArguments.put(Arguments.logLevel.name(), logLevelValue);
        }

        System.getenv().forEach((key, value) -> {
            // MOCKSERVER_LAUNCHER is an internal hint set by the binary launcher for usage
            // text, not a configuration value, so keep it out of the resolved-config dump.
            if (key.startsWith("MOCKSERVER_") && !key.equals("MOCKSERVER_LAUNCHER") && isNotBlank(value)) {
                environmentVariableArguments.put(key, value);
            }
        });
        System.getProperties().forEach((key, value) -> {
            if (key instanceof String && value instanceof String) {
                if (((String) key).startsWith("mockserver") && !key.equals("mockserver.launcherName") && isNotBlank((String) value)) {
                    systemPropertyArguments.put((String) key, (String) value);
                }
            }
        });

        for (Arguments parsedArgument : Arrays.asList(Arguments.serverPort, Arguments.proxyRemoteHost, Arguments.proxyRemotePort)) {
            if (!parsedArguments.containsKey(parsedArgument.name())) {
                if (systemPropertyArguments.containsKey(parsedArgument.systemPropertyName())) {
                    parsedArguments.put(parsedArgument.name(), systemPropertyArguments.get(parsedArgument.systemPropertyName()));
                    environmentVariableArguments.remove(parsedArgument.longEnvironmentVariableName());
                    environmentVariableArguments.remove(parsedArgument.shortEnvironmentVariableName());
                } else {
                    if (environmentVariableArguments.containsKey(parsedArgument.longEnvironmentVariableName())) {
                        environmentVariableArguments.remove(parsedArgument.shortEnvironmentVariableName());
                        parsedArguments.put(parsedArgument.name(), environmentVariableArguments.get(parsedArgument.longEnvironmentVariableName()));
                    } else if (isNotBlank(System.getenv(parsedArgument.shortEnvironmentVariableName()))) {
                        if (!(parsedArgument == Arguments.serverPort && "1080".equals(System.getenv(Arguments.serverPort.shortEnvironmentVariableName())) && ConfigurationProperties.PROPERTIES.containsKey(Arguments.serverPort.systemPropertyName()))) {
                            environmentVariableArguments.put(parsedArgument.shortEnvironmentVariableName(), System.getenv(parsedArgument.shortEnvironmentVariableName()));
                            parsedArguments.put(parsedArgument.name(), environmentVariableArguments.get(parsedArgument.shortEnvironmentVariableName()));
                        }
                    }
                }
            } else {
                systemPropertyArguments.remove(parsedArgument.systemPropertyName());
                environmentVariableArguments.remove(parsedArgument.longEnvironmentVariableName());
                environmentVariableArguments.remove(parsedArgument.shortEnvironmentVariableName());
            }
            if (!parsedArguments.containsKey(parsedArgument.name()) && ConfigurationProperties.PROPERTIES.containsKey(parsedArgument.systemPropertyName())) {
                parsedArguments.put(parsedArgument.name(), String.valueOf(ConfigurationProperties.PROPERTIES.get(parsedArgument.systemPropertyName())));
            }
        }

        if (parsedArguments.size() > 0 && parsedArguments.containsKey(Arguments.serverPort.name())) {
            // Only log the resolved configuration when MockServer is actually starting, so the
            // no-port error path below stays clean (no empty "using environment variables: []
            // and system properties: [] ..." dump preceding a CLI usage error).
            if (MockServerLogger.isEnabled(INFO)) {
                MOCK_SERVER_LOGGER.logEvent(
                    new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(INFO)
                        .setMessageFormat("using environment variables:{}and system properties:{}and command line options:{}")
                        .setArguments(
                            "[\n\t" + Joiner.on(",\n\t").withKeyValueSeparator("=").join(environmentVariableArguments) + "\n]",
                            "[\n\t" + Joiner.on(",\n\t").withKeyValueSeparator("=").join(systemPropertyArguments) + "\n]",
                            "[\n\t" + Joiner.on(",\n\t").withKeyValueSeparator("=").join(commandLineArguments) + "\n]"
                        )
                );
            }
            if (parsedArguments.containsKey(Arguments.logLevel.name())) {
                ConfigurationProperties.logLevel(parsedArguments.get(Arguments.logLevel.name()));
            }
            Integer[] localPorts = INTEGER_STRING_LIST_PARSER.toArray(parsedArguments.get(Arguments.serverPort.name()));
            final MockServer mockServer;
            if (parsedArguments.containsKey(Arguments.proxyRemotePort.name())) {
                String remoteHost = parsedArguments.get(Arguments.proxyRemoteHost.name());
                if (isBlank(remoteHost)) {
                    remoteHost = "localhost";
                }
                mockServer = new MockServer(Integer.parseInt(parsedArguments.get(Arguments.proxyRemotePort.name())), remoteHost, localPorts);
            } else {
                mockServer = new MockServer(localPorts);
            }
            // Record the ACTUAL bound port(s), not the requested value(s). Starting with a
            // requested port of 0 asks the OS for an ephemeral port; recording the requested 0
            // would leave a caller (a CLI user, or an in-process test) unable to discover the
            // port the server is actually listening on. getLocalPorts() blocks until the ports
            // are bound (the MockServer constructor already waits for binding), so this is the
            // real listening port. For explicit non-zero ports the resolved value equals the
            // requested value, so this is behaviour-preserving.
            lastStartedPorts = mockServer.getLocalPorts();
            setPort(lastStartedPorts);
            if (MockServerLogger.isEnabled(INFO)) {
                MOCK_SERVER_LOGGER.logEvent(
                    new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(INFO)
                        .setMessageFormat("netty direct memory limit:{}")
                        .setArguments(NettyDirectMemoryLimit.describe())
                );
            }

            if (ConfigurationProperties.logLevel() != null) {
                MOCK_SERVER_LOGGER.logEvent(
                    new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(ConfigurationProperties.logLevel())
                        .setMessageFormat("logger level is " + ConfigurationProperties.logLevel() + ", change using:\n - 'ConfigurationProperties.logLevel(String level)' in Java code,\n - '-logLevel' command line argument,\n - 'mockserver.logLevel' JVM system property or,\n - 'mockserver.logLevel' property value in 'mockserver.properties'")
                );
            }
            return true;
        } else {
            // No serverPort could be resolved from any source (CLI flag, system property, env
            // var, or properties file). Report it like a normal CLI usage error — picocli's
            // concise "run" usage plus a concise, actionable message — rather than the legacy
            // "java -jar" blob.
            systemOut.print(new CommandLine(new RunCommand()).getUsageMessage());
            systemOut.flush();
            systemErr.print(NEW_LINE + "ERROR:  no port specified — set a port with -p/--port (e.g. "
                + launchCommand() + " -p 1080), the " + Arguments.serverPort.longEnvironmentVariableName()
                + " environment variable, or the " + Arguments.serverPort.systemPropertyName() + " property" + NEW_LINE + NEW_LINE);
            systemErr.flush();
            return false;
        }
    }

    /**
     * Open the MockServer dashboard for the given port in the user's default browser. Best-effort:
     * the URL is always printed; the browser launch is attempted via AWT Desktop, falling back to
     * the platform "open" command. On a headless host (no display — server, CI, SSH session) no
     * launch is attempted and the printed URL is the user's hook, so there is no need for a flag to
     * suppress the browser — that is just `mockserver run`.
     */
    static void openDashboard(int port) {
        String url = "http://localhost:" + port + "/mockserver/dashboard";
        systemOut.println(NEW_LINE + "Dashboard UI: " + url + NEW_LINE);
        systemOut.flush();
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignore) {
            // fall through to the platform launcher
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder processBuilder;
            if (os.contains("mac")) {
                processBuilder = new ProcessBuilder("open", url);
            } else if (os.contains("win")) {
                processBuilder = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
            } else {
                processBuilder = new ProcessBuilder("xdg-open", url);
            }
            processBuilder.start();
        } catch (Exception ignore) {
            systemOut.println("Could not launch a browser automatically — open the dashboard manually: " + url);
            systemOut.flush();
        }
    }

    static void showUsage(String errorMessage) {
        if (!usageShown) {
            usageShown = true;
            systemOut.print(USAGE);
            systemOut.flush();
        }
        if (isNotBlank(errorMessage)) {
            systemErr.print("\nERROR:  " + errorMessage + "\n\n");
            systemErr.flush();
        }
    }

    // ---- Subcommands ----

    @Command(
        name = "run",
        description = "Start MockServer (default subcommand).",
        mixinStandardHelpOptions = true
    )
    static class RunCommand implements Runnable, CommandLine.IExitCodeGenerator {

        /** Set true after the server has actually started, so callers (e.g. UiCommand) can tell a
         *  successful start from a no-port/validation/startup failure that run() handled internally. */
        boolean started;

        // Set to a non-zero value when the server fails to START (a failed port bind or any other
        // startup exception caught below), so picocli returns it and a shell/CI caller gets a real
        // failure signal instead of a false 0 from a server that never came up. A validation / no-port
        // usage error deliberately leaves this at 0 to preserve the documented exit-code behaviour.
        private int exitCode = 0;

        @Override
        public int getExitCode() {
            return exitCode;
        }

        @Option(names = {"-p", "--port"}, description = "Port(s) to listen on (comma-separated list, e.g. 1080,1081). Required unless set via the MOCKSERVER_SERVER_PORT environment variable, the mockserver.serverPort system property, or a properties file.")
        String port;

        @Option(names = "-D", paramLabel = "<key=value>", description = "Set a JVM system property before startup, e.g. -Dmockserver.metricsEnabled=true (repeatable). Equivalent to a JVM -D but accepted after the launcher/jar. Any system property is accepted; use mockserver.* keys for MockServer configuration.")
        Map<String, String> systemProperties = new LinkedHashMap<>();

        @Option(names = "--proxy-to", description = "Forward unmatched requests to host[:port] (enables port-forwarding mode).")
        String proxyTo;

        @Option(names = "--openapi", description = "Initialize expectations from an OpenAPI spec URL or file path.")
        String openapi;

        @Option(names = "--init", description = "Initialize expectations from a JSON file path or glob pattern.")
        String init;

        @Option(names = "--persist", description = "Enable expectation persistence and set the file path.")
        String persist;

        @Option(names = {"-l", "--log-level"}, description = "Log level: TRACE, DEBUG, INFO, WARN, ERROR, OFF (or Java Logger equivalents).")
        String logLevel;

        @Option(names = "--dev", description = "Enable developer-friendly defaults: reduced memory caps (maxLogEntries=1000, maxExpectations=1000) for laptop/test-suite use. Explicit config (system property, env var, or properties file) overrides dev-mode defaults.")
        boolean dev;

        @Option(names = "--watch", description = "Watch the initializer/expectations file(s) (from --init / --openapi) and live-reload expectations when they change, without a restart (~5s poll). Equivalent to MOCKSERVER_WATCH_INITIALIZATION_JSON=true or -Dmockserver.watchInitializationJson=true.")
        boolean watch;

        @Option(names = "--proxy-setup", description = "Set MockServer up as a TLS-intercepting proxy: generate a unique private Certificate Authority (instead of the public CA published in the MockServer repository) and print an OS-specific copy-paste setup block (CA path plus HTTPS_PROXY / NODE_EXTRA_CA_CERTS / SSL_CERT_FILE / REQUESTS_CA_BUNDLE exports). Equivalent to MOCKSERVER_PROXY_SETUP=true or -Dmockserver.proxySetup=true.")
        boolean proxySetup;

        @Option(names = "--validate-openapi", description = "Validate forwarded/proxied requests and responses against the given OpenAPI spec (URL, file path, or inline payload). Violations are logged; combine with --validate-enforce to block non-conformant traffic.")
        String validateOpenapi;

        @Option(names = "--validate-enforce", description = "When combined with --validate-openapi, reject requests that violate the spec (400) and replace non-conformant upstream responses (502). Without this flag, violations are report-only.")
        boolean validateEnforce;

        @Option(names = {"--extensions", "-extensions"}, description = "Comma-separated extension class names (each implementing org.mockserver.extension.Extension) to load and start at startup; their JARs must be on the classpath. Equivalent to MOCKSERVER_EXTENSIONS or -Dmockserver.extensions.")
        String extensions;

        // Legacy hidden flags — exact single-token names so picocli matches them as long options
        @Option(names = "-serverPort", hidden = true)
        String legacyServerPort;

        @Option(names = "-proxyRemotePort", hidden = true)
        String legacyProxyRemotePort;

        @Option(names = "-proxyRemoteHost", hidden = true)
        String legacyProxyRemoteHost;

        @Option(names = "-logLevel", hidden = true)
        String legacyLogLevel;

        @Override
        public void run() {
            try {
                // Apply -D system properties first so they are visible to every downstream
                // ConfigurationProperties read (port resolution, dev mode, server startup).
                if (systemProperties != null) {
                    systemProperties.forEach((key, value) -> {
                        if (isNotBlank(key)) {
                            System.setProperty(key, value == null ? "" : value);
                        }
                    });
                }

                // Merge new flags with legacy flags (new flags take precedence)
                String resolvedPort = isNotBlank(port) ? port : legacyServerPort;
                String resolvedLogLevel = isNotBlank(logLevel) ? logLevel : legacyLogLevel;

                String resolvedProxyRemotePort = legacyProxyRemotePort;
                String resolvedProxyRemoteHost = legacyProxyRemoteHost;

                if (isNotBlank(proxyTo)) {
                    try {
                        String[] parsed = parseProxyTarget(proxyTo);
                        resolvedProxyRemoteHost = parsed[0];
                        resolvedProxyRemotePort = parsed[1];
                    } catch (IllegalArgumentException proxyTargetEx) {
                        // Validation error already printed by parseProxyTarget (bordered box).
                        // Show picocli's concise usage for the "run" command, not the legacy blob.
                        systemOut.print(new CommandLine(new RunCommand()).getUsageMessage());
                        systemOut.flush();
                        return;
                    }
                }

                // Wire --dev (apply early so explicit config overrides dev defaults)
                if (dev) {
                    ConfigurationProperties.devMode(true);
                }

                // Wire --watch (must be set before startup so the expectation file watcher is created)
                if (watch) {
                    ConfigurationProperties.watchInitializationJson(true);
                }

                // Standalone launch (jar/Docker/CLI): print the proxy setup block at startup by default,
                // unless the user explicitly configured proxySetupLogging in ANY source (-D, env var, or
                // the mockserver.properties file). Embedded ClientAndServer keeps the global default
                // (off) so it stays silent.
                if (!ConfigurationProperties.proxySetupLoggingConfigured()) {
                    ConfigurationProperties.proxySetupLogging(true);
                }

                // Wire --proxy-setup (forces a unique dynamic CA and ensures the proxy setup block prints)
                if (proxySetup) {
                    ConfigurationProperties.proxySetup(true);
                    ConfigurationProperties.proxySetupLogging(true);
                }

                // Wire --openapi
                if (isNotBlank(openapi)) {
                    ConfigurationProperties.initializationOpenAPIPath(openapi);
                }

                // Wire --init
                if (isNotBlank(init)) {
                    ConfigurationProperties.initializationJsonPath(init);
                }

                // Wire --persist
                if (isNotBlank(persist)) {
                    ConfigurationProperties.persistExpectations(true);
                    ConfigurationProperties.persistedExpectationsPath(persist);
                }

                // Wire --validate-openapi / --validate-enforce
                if (isNotBlank(validateOpenapi)) {
                    ConfigurationProperties.validateProxyOpenAPISpec(validateOpenapi);
                }
                if (validateEnforce) {
                    ConfigurationProperties.validateProxyEnforce(true);
                }

                // Wire --extensions (startup-only: extensions load once, before anything else is built)
                if (isNotBlank(extensions)) {
                    ConfigurationProperties.extensions(extensions);
                }

                // Validate legacy arguments inline (matching old behavior for error messages)
                List<String> errorMessages = validateArguments(resolvedPort, resolvedProxyRemotePort,
                    resolvedProxyRemoteHost, resolvedLogLevel);
                if (!errorMessages.isEmpty()) {
                    printValidationError(errorMessages);
                    throw new IllegalArgumentException(errorMessages.toString());
                }

                started = startServer(resolvedPort, resolvedProxyRemotePort, resolvedProxyRemoteHost, resolvedLogLevel);
            } catch (IllegalArgumentException e) {
                // Already handled — validation errors printed and usage shown via startServer. This is a
                // usage error, not a startup failure, so keep exitCode at 0 (unchanged documented behaviour).
                showUsage(null);
            } catch (Throwable throwable) {
                // A genuine startup failure (e.g. a failed port bind rethrown from MockServer.createServerBootstrap).
                // Report a non-zero exit code so a shell/CI caller can detect that the server never started.
                exitCode = 1;
                logStartupFailure(throwable, ConfigurationProperties.disableSystemOut());
            }
        }
    }

    @Command(
        name = "ui",
        description = "Start MockServer and open the dashboard UI in a browser.",
        mixinStandardHelpOptions = true
    )
    static class UiCommand implements Runnable, CommandLine.IExitCodeGenerator {

        @Option(names = {"-p", "--port"}, description = "Port(s) to listen on (comma-separated list). Defaults to 1080 if not specified.")
        String port;

        @Option(names = "-D", paramLabel = "<key=value>", description = "Set a JVM system property before startup, e.g. -Dmockserver.metricsEnabled=true (repeatable). Any system property is accepted; use mockserver.* keys for MockServer configuration.")
        Map<String, String> systemProperties = new LinkedHashMap<>();

        @Option(names = {"-l", "--log-level"}, description = "Log level.")
        String logLevel;

        @Option(names = "--dev", description = "Enable developer-friendly defaults.")
        boolean dev;

        // Delegate to RunCommand for the actual start; hold it so its startup exit code (non-zero on a
        // failed bind) propagates to picocli instead of the subcommand silently reporting success.
        private final RunCommand runCmd = new RunCommand();

        @Override
        public int getExitCode() {
            return runCmd.getExitCode();
        }

        @Override
        public void run() {
            String resolvedPort = isNotBlank(port) ? port : "1080";
            runCmd.port = resolvedPort;
            runCmd.logLevel = logLevel;
            runCmd.dev = dev;
            runCmd.systemProperties = systemProperties;
            runCmd.run();
            // Only open the dashboard if the server actually started — run() handles (and prints)
            // no-port, validation, and bind failures internally without re-throwing, so a failed
            // start must not produce a misleading "Dashboard UI: ..." line pointing at nothing.
            if (runCmd.started) {
                // Use the ACTUAL bound port, not the requested value, so `ui -p 0` prints a
                // dashboard URL for the ephemeral port the server is really listening on.
                List<Integer> boundPorts = getLastStartedPorts();
                if (boundPorts != null && !boundPorts.isEmpty()) {
                    openDashboard(boundPorts.get(0));
                }
            }
        }
    }

    @Command(
        name = "proxy",
        description = "Start MockServer in port-forwarding (proxy) mode.",
        mixinStandardHelpOptions = true
    )
    static class ProxyCommand implements Runnable, CommandLine.IExitCodeGenerator {

        @Option(names = "--to", required = true, description = "Forward unmatched requests to host[:port].")
        String to;

        @Option(names = {"-p", "--port"}, description = "Port(s) to listen on (comma-separated list).")
        String port;

        @Option(names = "-D", paramLabel = "<key=value>", description = "Set a JVM system property before startup, e.g. -Dmockserver.metricsEnabled=true (repeatable). Any system property is accepted; use mockserver.* keys for MockServer configuration.")
        Map<String, String> systemProperties = new LinkedHashMap<>();

        @Option(names = {"-l", "--log-level"}, description = "Log level.")
        String logLevel;

        @Option(names = "--dev", description = "Enable developer-friendly defaults.")
        boolean dev;

        @Option(names = "--validate-openapi", description = "Validate forwarded/proxied traffic against the given OpenAPI spec (URL, file path, or inline payload).")
        String validateOpenapi;

        @Option(names = "--validate-enforce", description = "When combined with --validate-openapi, block non-conformant traffic (400 for requests, 502 for responses).")
        boolean validateEnforce;

        // Delegate to RunCommand for the actual start; hold it so its startup exit code (non-zero on a
        // failed bind) propagates to picocli instead of the subcommand silently reporting success.
        private final RunCommand runCmd = new RunCommand();

        @Override
        public int getExitCode() {
            return runCmd.getExitCode();
        }

        @Override
        public void run() {
            // Delegate to RunCommand logic by building equivalent args
            runCmd.port = port;
            runCmd.proxyTo = to;
            runCmd.logLevel = logLevel;
            runCmd.dev = dev;
            runCmd.systemProperties = systemProperties;
            runCmd.validateOpenapi = validateOpenapi;
            runCmd.validateEnforce = validateEnforce;
            runCmd.run();
        }
    }

    @Command(
        name = "openapi",
        description = "Start MockServer and initialize expectations from an OpenAPI spec.",
        mixinStandardHelpOptions = true
    )
    static class OpenApiCommand implements Runnable, CommandLine.IExitCodeGenerator {

        @Parameters(index = "0", description = "OpenAPI spec URL or file path.")
        String specPath;

        @Option(names = {"-p", "--port"}, description = "Port(s) to listen on (comma-separated list).")
        String port;

        @Option(names = "-D", paramLabel = "<key=value>", description = "Set a JVM system property before startup, e.g. -Dmockserver.metricsEnabled=true (repeatable). Any system property is accepted; use mockserver.* keys for MockServer configuration.")
        Map<String, String> systemProperties = new LinkedHashMap<>();

        @Option(names = {"-l", "--log-level"}, description = "Log level.")
        String logLevel;

        @Option(names = "--dev", description = "Enable developer-friendly defaults.")
        boolean dev;

        // Delegate to RunCommand for the actual start; hold it so its startup exit code (non-zero on a
        // failed bind) propagates to picocli instead of the subcommand silently reporting success.
        private final RunCommand runCmd = new RunCommand();

        @Override
        public int getExitCode() {
            return runCmd.getExitCode();
        }

        @Override
        public void run() {
            runCmd.port = port;
            runCmd.openapi = specPath;
            runCmd.logLevel = logLevel;
            runCmd.dev = dev;
            runCmd.systemProperties = systemProperties;
            runCmd.run();
        }
    }

    @Command(
        name = "import",
        description = "Load expectations from a JSON file into a running MockServer.",
        mixinStandardHelpOptions = true
    )
    static class ImportCommand implements Runnable, CommandLine.IExitCodeGenerator {

        @Parameters(index = "0", description = "Path to a JSON file containing a single expectation or an array of expectations.")
        String file;

        @Option(names = {"-p", "--port"}, required = true, description = "Port of the running MockServer to load the expectations into.")
        int port;

        @Option(names = {"-H", "--host"}, description = "Host of the running MockServer (default: localhost).")
        String host = "localhost";

        // Set to a non-zero value when the import fails so the process exits with a failure code
        // for scripting, without printing the legacy "run" usage blob (this command never starts a server).
        private int exitCode = 0;

        @Override
        public int getExitCode() {
            return exitCode;
        }

        @Override
        public void run() {
            // NOTE: deliberately do NOT call mockServerClient.stop()/close() — the MockServerClient
            // "stop" sends a shutdown request to the remote MockServer, which must never happen when
            // we are only loading expectations into it. The client's event-loop threads are daemon
            // threads, so the short-lived CLI process exits cleanly without an explicit close.
            try {
                MockServerClient mockServerClient = new MockServerClient(host, port);
                Expectation[] imported = mockServerClient.importExpectationsFromFile(file);
                systemOut.println("Imported " + imported.length + " expectation(s) from " + file + " into " + host + ":" + port);
                systemOut.flush();
            } catch (Throwable throwable) {
                exitCode = 1;
                MOCK_SERVER_LOGGER.logEvent(
                    new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(ERROR)
                        .setMessageFormat("exception while importing expectations from " + file + " into " + host + ":" + port + ":{}")
                        .setThrowable(throwable)
                );
                // Some exceptions carry no message (e.g. a bare NPE); fall back to the exception type
                // so the user-facing line never renders a literal "null".
                String reason = isNotBlank(throwable.getMessage()) ? throwable.getMessage() : throwable.getClass().getSimpleName();
                systemErr.println(NEW_LINE + "ERROR:  could not import expectations from " + file + " into " + host + ":" + port + " — " + reason + NEW_LINE);
                systemErr.flush();
            }
        }
    }

    @Command(
        name = "lint",
        description = "Check expectation JSON files for expectations that can never match or will not match as written. Advisory: nothing is changed, and no server is needed.",
        mixinStandardHelpOptions = true
    )
    static class LintCommand implements Runnable, CommandLine.IExitCodeGenerator {

        @Parameters(arity = "1..*", paramLabel = "<file>", description = "JSON file(s), each a single expectation or an array of expectations. Several files are checked together, in the order given, as --init loads them.")
        List<String> files;

        @Option(names = "--json", description = "Print the report as JSON, in the PUT /mockserver/lint format, with each issue's file.")
        boolean json;

        @Option(names = "--strict", description = "Also exit with status 1 when a warning is found (by default only an error, such as an invalid expectation or an unreadable file, does).")
        boolean strict;

        // an invalid entry is reported in the lint output, so the serializer's own log would only repeat it
        private static final MockServerLogger QUIET_LOGGER = new MockServerLogger(Main.class) {
            @Override
            public void logEvent(LogEntry logEntry) {
            }
        };

        private int exitCode = 0;

        @Override
        public int getExitCode() {
            return exitCode;
        }

        @Override
        public void run() {
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = org.mockserver.serialization.ObjectMapperFactory.createObjectMapper();
            List<com.fasterxml.jackson.databind.JsonNode> entries = new ArrayList<>();
            List<String> entryFiles = new ArrayList<>();
            List<Integer> entryIndexes = new ArrayList<>();
            List<Integer> entryFileOrdinals = new ArrayList<>();
            for (int fileOrdinal = 0; fileOrdinal < files.size(); fileOrdinal++) {
                String file = files.get(fileOrdinal);
                try {
                    com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(org.mockserver.file.FileReader.readFileFromClassPathOrPath(file));
                    if (root == null || !(root.isArray() || root.isObject())) {
                        throw new IllegalArgumentException("expected an expectation or an array of expectations");
                    }
                    List<com.fasterxml.jackson.databind.JsonNode> fileEntries = new ArrayList<>();
                    if (root.isArray()) {
                        root.forEach(fileEntries::add);
                    } else {
                        fileEntries.add(root);
                    }
                    for (int i = 0; i < fileEntries.size(); i++) {
                        entries.add(fileEntries.get(i));
                        entryFiles.add(file);
                        entryIndexes.add(i);
                        entryFileOrdinals.add(fileOrdinal);
                    }
                } catch (Throwable throwable) {
                    exitCode = 1;
                    String reason = isNotBlank(throwable.getMessage()) ? throwable.getMessage() : throwable.getClass().getSimpleName();
                    systemErr.println("ERROR:  could not read expectations from " + file + " — " + reason);
                    systemErr.flush();
                }
            }
            if (exitCode != 0) {
                return;
            }
            com.fasterxml.jackson.databind.node.ObjectNode report = org.mockserver.mock.ExpectationLinter.lintInitializationFileEntries(objectMapper, new org.mockserver.serialization.ExpectationSerializer(QUIET_LOGGER), entries, entryFileOrdinals);
            for (com.fasterxml.jackson.databind.JsonNode issue : report.path("issues")) {
                com.fasterxml.jackson.databind.node.ObjectNode issueNode = (com.fasterxml.jackson.databind.node.ObjectNode) issue;
                int index = issue.path("index").asInt();
                issueNode.put("file", entryFiles.get(index));
                issueNode.put("index", entryIndexes.get(index));
                if (issue.has("relatedIndex")) {
                    int related = issue.path("relatedIndex").asInt();
                    issueNode.put("relatedFile", entryFiles.get(related));
                    issueNode.put("relatedIndex", entryIndexes.get(related));
                }
            }
            if (json) {
                systemOut.println(report.toPrettyString());
            } else {
                for (com.fasterxml.jackson.databind.JsonNode issue : report.path("issues")) {
                    systemOut.println(issue.path("file").asText() + " [" + issue.path("index").asInt() + "] " + issue.path("summary").asText() + NEW_LINE
                        + "  " + issue.path("severity").asText() + " " + issue.path("rule").asText() + ": " + issue.path("message").asText()
                        + (issue.has("relatedFile") ? NEW_LINE + "  see " + issue.path("relatedFile").asText() + " [" + issue.path("relatedIndex").asInt() + "] " + issue.path("relatedSummary").asText() : ""));
                }
                systemOut.println("checked " + report.path("expectationsChecked").asInt() + " expectation(s) in " + files.size() + " file(s): "
                    + report.path("errorCount").asInt() + " error(s), " + report.path("warningCount").asInt() + " warning(s), " + report.path("infoCount").asInt() + " info"
                    + (report.path("openApiExpectationsSkipped").asInt() > 0 ? ", " + report.path("openApiExpectationsSkipped").asInt() + " OpenAPI expectation(s) not checked" : "")
                    + (report.path("shadowCheckTruncated").asBoolean() ? "; the shadowing check stopped at its budget" : "")
                    + (report.path("issuesTruncated").asBoolean() ? "; only the first " + report.path("issueCount").asInt() + " issues are listed" : ""));
            }
            systemOut.flush();
            if (report.path("errorCount").asInt() > 0 || strict && report.path("warningCount").asInt() > 0) {
                exitCode = 1;
            }
        }
    }

    @Command(
        name = "demo",
        description = "Start MockServer pre-loaded with a small set of example expectations and print a getting-started URL and sample curl.",
        mixinStandardHelpOptions = true
    )
    static class DemoCommand implements Runnable, CommandLine.IExitCodeGenerator {

        @Option(names = {"-p", "--port"}, description = "Port(s) to listen on (comma-separated list). Defaults to 1080 if not specified.")
        String port;

        @Option(names = "-D", paramLabel = "<key=value>", description = "Set a JVM system property before startup, e.g. -Dmockserver.metricsEnabled=true (repeatable). Any system property is accepted; use mockserver.* keys for MockServer configuration.")
        Map<String, String> systemProperties = new LinkedHashMap<>();

        @Option(names = {"-l", "--log-level"}, description = "Log level.")
        String logLevel;

        @Option(names = "--dev", description = "Enable developer-friendly defaults.")
        boolean dev;

        // Delegate to RunCommand for the actual start; hold it so its startup exit code (non-zero on a
        // failed bind) propagates to picocli instead of the subcommand silently reporting success.
        private final RunCommand runCmd = new RunCommand();

        @Override
        public int getExitCode() {
            return runCmd.getExitCode();
        }

        @Override
        public void run() {
            String resolvedPort = isNotBlank(port) ? port : "1080";
            runCmd.port = resolvedPort;
            runCmd.logLevel = logLevel;
            runCmd.dev = dev;
            runCmd.systemProperties = systemProperties;
            runCmd.run();
            // Only seed examples and print instructions if the server actually started — run()
            // handles (and prints) no-port, validation, and bind failures internally, so a failed
            // start must not produce misleading "try this curl" guidance pointing at nothing.
            if (runCmd.started) {
                // Use the ACTUAL bound port, not the requested value, so `demo -p 0` seeds and
                // prints instructions for the ephemeral port the server is really listening on.
                List<Integer> boundPorts = getLastStartedPorts();
                if (boundPorts != null && !boundPorts.isEmpty()) {
                    seedDemoExpectations(boundPorts.get(0));
                    printDemoInstructions(boundPorts.get(0));
                }
            }
        }
    }

    /**
     * Register a tiny set of example expectations so that a fresh `mockserver demo` immediately
     * answers requests — the onboarding equivalent of "hello world". Best-effort: a seeding
     * failure is logged and the server still runs (the user can add their own expectations).
     */
    static void seedDemoExpectations(int port) {
        // NB: do NOT use try-with-resources — MockServerClient.close() sends a stop request that
        // would shut the demo server down immediately. Just register the expectations and leave
        // the server running.
        try {
            MockServerClient client = new MockServerClient("localhost", port);
            client
                .when(org.mockserver.model.HttpRequest.request().withMethod("GET").withPath("/hello"))
                .respond(
                    org.mockserver.model.HttpResponse.response()
                        .withStatusCode(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Hello from MockServer!\"}")
                );
            client
                .when(org.mockserver.model.HttpRequest.request().withMethod("GET").withPath("/users/{id}")
                    .withPathParameter("id", "[0-9]+"))
                .respond(
                    org.mockserver.model.HttpResponse.response()
                        .withStatusCode(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"name\":\"Example User\"}")
                );
        } catch (Throwable throwable) {
            if (MOCK_SERVER_LOGGER.isEnabledForInstance(WARN)) {
                MOCK_SERVER_LOGGER.logEvent(
                    new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(WARN)
                        .setMessageFormat("exception while seeding demo expectations:{}")
                        .setThrowable(throwable)
                );
            }
        }
    }

    static void printDemoInstructions(int port) {
        String base = "http://localhost:" + port;
        systemOut.println(NEW_LINE + "MockServer demo is running with example expectations." + NEW_LINE);
        systemOut.println("  Getting started: " + base + "/hello");
        systemOut.println("  Dashboard UI:    " + base + "/mockserver/dashboard" + NEW_LINE);
        systemOut.println("  Try it:");
        systemOut.println("    curl " + base + "/hello");
        systemOut.println("    curl " + base + "/users/1" + NEW_LINE);
        systemOut.flush();
    }

    @Command(
        name = "version",
        description = "Print MockServer version and exit."
    )
    static class VersionCommand implements Runnable {
        @Override
        public void run() {
            systemOut.println("MockServer " + Version.getVersion());
        }
    }

    // ---- Version Provider ----

    static class MockServerVersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"MockServer " + Version.getVersion()};
        }
    }

    // ---- Proxy target parsing ----

    /**
     * Parse a proxy target value (from --proxy-to or --to) into [host, port].
     * Accepts:
     *   host:port           → literal host and port
     *   https://host        → host, port 443
     *   http://host         → host, port 80
     *   https://host:port   → host, port (explicit overrides scheme default)
     *   http://host/path    → host, port 80 (path stripped)
     *
     * Rejects values with neither a scheme nor an explicit port.
     */
    static String[] parseProxyTarget(String value) {
        String raw = value.trim();
        String schemeDefaultPort = null;

        // Extract and strip scheme
        if (raw.startsWith("https://")) {
            schemeDefaultPort = "443";
            raw = raw.substring("https://".length());
        } else if (raw.startsWith("http://")) {
            schemeDefaultPort = "80";
            raw = raw.substring("http://".length());
        }

        // Strip trailing path (everything from the first '/')
        int slashIndex = raw.indexOf('/');
        if (slashIndex >= 0) {
            raw = raw.substring(0, slashIndex);
        }

        // Now raw is either "host", "host:port", or "[ipv6]:port"
        String host;
        String port = null;

        if (raw.startsWith("[")) {
            // IPv6 bracket notation: [::1]:port or [::1]
            int closeBracket = raw.indexOf(']');
            if (closeBracket < 0) {
                throw new IllegalArgumentException(
                    "invalid proxy target \"" + value + "\": unclosed IPv6 bracket — use the format host:port or a http(s):// URL");
            }
            host = raw.substring(1, closeBracket);
            String remainder = raw.substring(closeBracket + 1);
            if (remainder.startsWith(":")) {
                port = remainder.substring(1);
            }
        } else if (raw.contains(":")) {
            // host:port — split on the LAST colon (safe because we already stripped scheme)
            host = substringBeforeLast(raw, ":");
            port = substringAfterLast(raw, ":");
        } else {
            host = raw;
        }

        // Determine final port
        if (isBlank(port)) {
            if (schemeDefaultPort != null) {
                port = schemeDefaultPort;
            } else {
                List<String> errorMessages = List.of(
                    "proxy target \"" + value + "\" has no port — specify a port explicitly (e.g. --proxy-to " + host + ":8080) or use a http(s):// URL (e.g. --proxy-to https://" + host + ")"
                );
                printValidationError(errorMessages);
                throw new IllegalArgumentException(errorMessages.get(0));
            }
        }

        return new String[]{host, port};
    }

    // ---- Validation (preserves old error messages) ----

    private static List<String> validateArguments(String serverPortValue, String proxyRemotePortValue,
                                                  String proxyRemoteHostValue, String logLevelValue) {
        List<String> errorMessages = new ArrayList<>();

        if (isNotBlank(serverPortValue) && !serverPortValue.matches("^\\d+(,\\d+)*$")) {
            errorMessages.add("serverPort value \"" + serverPortValue + "\" is invalid, please specify a comma separated list of ports i.e. \"1080,1081,1082\"");
        }
        if (isNotBlank(proxyRemotePortValue) && !proxyRemotePortValue.matches("^\\d+$")) {
            errorMessages.add("proxyRemotePort value \"" + proxyRemotePortValue + "\" is invalid, please specify a port i.e. \"1080\"");
        }
        if (isNotBlank(proxyRemoteHostValue)) {
            String validIpAddressRegex = "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$";
            String validHostnameRegex = "^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)*([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9\\-]*[A-Za-z0-9])$";
            if (!(proxyRemoteHostValue.matches(validIpAddressRegex) || proxyRemoteHostValue.matches(validHostnameRegex))) {
                errorMessages.add("proxyRemoteHost value \"" + proxyRemoteHostValue + "\" is invalid, please specify a host name i.e. \"localhost\" or \"127.0.0.1\"");
            }
        }
        if (isNotBlank(logLevelValue) && !Arrays.asList("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF", "FINEST", "FINE", "WARNING", "SEVERE").contains(logLevelValue)) {
            errorMessages.add("logLevel value \"" + logLevelValue + "\" is invalid, please specify one of SL4J levels: \"TRACE\", \"DEBUG\", \"INFO\", \"WARN\", \"ERROR\", \"OFF\" or the Java Logger levels: \"FINEST\", \"FINE\", \"INFO\", \"WARNING\", \"SEVERE\", \"OFF\"");
        }

        return errorMessages;
    }

    private static void printValidationError(List<String> errorMessages) {
        int maxLengthMessage = 0;
        for (String errorMessage : errorMessages) {
            if (errorMessage.length() > maxLengthMessage) {
                maxLengthMessage = errorMessage.length();
            }
        }
        systemOut.println(NEW_LINE + "   " + com.google.common.base.Strings.padEnd("", maxLengthMessage, '='));
        for (String errorMessage : errorMessages) {
            systemOut.println("   " + errorMessage);
        }
        systemOut.println("   " + com.google.common.base.Strings.padEnd("", maxLengthMessage, '=') + NEW_LINE);
    }

    // ---- Arguments enum (preserved for backward compatibility and env/sysprop resolution) ----

    public enum Arguments {
        serverPort("SERVER_PORT"),
        proxyRemoteHost("PROXY_REMOTE_HOST"),
        proxyRemotePort("PROXY_REMOTE_PORT"),
        logLevel("LOG_LEVEL");

        private final String shortEnvironmentVariableName;

        Arguments(String shortEnvironmentVariableName) {
            this.shortEnvironmentVariableName = shortEnvironmentVariableName;
        }

        public String shortEnvironmentVariableName() {
            return shortEnvironmentVariableName;
        }

        public String longEnvironmentVariableName() {
            return "MOCKSERVER_" + shortEnvironmentVariableName;
        }

        public String systemPropertyName() {
            return "mockserver." + name();
        }
    }

}
