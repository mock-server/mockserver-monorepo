package org.mockserver.logging;

import com.google.common.annotations.VisibleForTesting;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.log.model.LogEntry;
import org.mockserver.mock.HttpState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.logging.LogManager;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.log.model.LogEntry.LogMessageType.*;
import static org.mockserver.log.model.LogEntry.LogMessageTypeCategory.resolveEffectiveLevel;
import static org.slf4j.event.Level.ERROR;

/**
 * @author jamesdbloom
 */
public class MockServerLogger {

    private static volatile Consumer<LogEntry> globalLogEventListener;

    static {
        // Only install the default java.util.logging format here. This static initializer
        // MUST NOT read ConfigurationProperties: MockServerLogger and ConfigurationProperties
        // reference each other, and if MockServerLogger.<clinit> touched ConfigurationProperties
        // two threads first-touching the two classes concurrently would deadlock on their class
        // init locks (the cause of the reverted parallel-Surefire deadlock). The configured log
        // level is applied separately by configureLogger(), invoked from ConfigurationProperties'
        // static initializer (once its properties are loaded) and from its log-level / system-out
        // setters.
        Slf4jProviderFallback.selectBundledProviderIfNoneRegistered();
        installDefaultJavaLoggingFormat();
    }

    /**
     * Installs the default java.util.logging handler and format. Deliberately free of any
     * {@link ConfigurationProperties} dependency so it is safe to run from this class's static
     * initializer. The format (including the MockServer version) is identical to the default
     * previously installed by {@link #configureLogger()}, so log output formatting is unchanged.
     */
    private static void installDefaultJavaLoggingFormat() {
        try {
            if (System.getProperty("java.util.logging.config.file") == null && System.getProperty("java.util.logging.config.class") == null) {
                LogManager.getLogManager().readConfiguration(new ByteArrayInputStream(("" +
                    "handlers=org.mockserver.logging.StandardOutConsoleHandler" + NEW_LINE +
                    "org.mockserver.logging.StandardOutConsoleHandler.level=ALL" + NEW_LINE +
                    "org.mockserver.logging.StandardOutConsoleHandler.formatter=org.mockserver.logging.MockServerLogFormatter" + NEW_LINE +
                    "org.mockserver.level=INFO" + NEW_LINE +
                    "io.netty.level=WARNING").getBytes(UTF_8)));
            }
        } catch (Throwable throwable) {
            // Deliberate stderr fallback (NOT a logging-convention violation): this is the logging
            // subsystem's own bootstrap failure path, before logging is even configured. Routing this
            // through logEvent()/the slf4j convention is impossible here because logEvent() reads
            // ConfigurationProperties (disableLogging, logLevel), which would re-introduce the
            // MockServerLogger <-> ConfigurationProperties class-init dependency this method exists to
            // avoid (the cause of the reverted parallel-Surefire deadlock). System.err is the only
            // safe sink at this point (explicit stream form to make the stderr target deliberate).
            throwable.printStackTrace(System.err);
        }
    }

    public static void configureLogger() {
        try {
            if (System.getProperty("java.util.logging.config.file") == null && System.getProperty("java.util.logging.config.class") == null) {
                installDefaultJavaLoggingFormat();
                if (isNotBlank(ConfigurationProperties.javaLoggerLogLevel())) {
                    String loggingConfiguration = "" +
                        (!ConfigurationProperties.disableSystemOut() ? "handlers=org.mockserver.logging.StandardOutConsoleHandler" + NEW_LINE +
                            "org.mockserver.logging.StandardOutConsoleHandler.level=ALL" + NEW_LINE +
                            "org.mockserver.logging.StandardOutConsoleHandler.formatter=org.mockserver.logging.MockServerLogFormatter" + NEW_LINE : "") +
                        "org.mockserver.level=" + ConfigurationProperties.javaLoggerLogLevel() + NEW_LINE +
                        "io.netty.level=" + (Arrays.asList("TRACE", "FINEST").contains(ConfigurationProperties.javaLoggerLogLevel()) ? "FINE" : "WARNING");
                    LogManager.getLogManager().readConfiguration(new ByteArrayInputStream(loggingConfiguration.getBytes(UTF_8)));
                }
            }
        } catch (Throwable throwable) {
            new MockServerLogger().logEvent(
                new LogEntry()
                    .setType(SERVER_CONFIGURATION)
                    .setLogLevel(ERROR)
                    .setMessageFormat("exception while configuring Java logging - " + throwable.getMessage())
                    .setThrowable(throwable)
            );
        }
    }

    private final Configuration configuration;
    private final Logger logger;
    private HttpState httpStateHandler;

    @VisibleForTesting
    public MockServerLogger() {
        this(MockServerLogger.class);
    }

    @VisibleForTesting
    public MockServerLogger(final Logger logger) {
        this.configuration = null;
        this.logger = logger;
        this.httpStateHandler = null;
    }

    @VisibleForTesting
    public MockServerLogger(final Configuration configuration, final Logger logger) {
        this.configuration = configuration;
        this.logger = logger;
        this.httpStateHandler = null;
    }

    public MockServerLogger(final Class<?> loggerClass) {
        this.configuration = null;
        this.logger = LoggerFactory.getLogger(loggerClass);
        this.httpStateHandler = null;
    }

    public MockServerLogger(final Configuration configuration, final Class<?> loggerClass) {
        this.configuration = configuration;
        this.logger = LoggerFactory.getLogger(loggerClass);
        this.httpStateHandler = null;
    }

    public MockServerLogger(final @Nullable HttpState httpStateHandler) {
        this.configuration = null;
        this.logger = null;
        this.httpStateHandler = httpStateHandler;
    }

    public MockServerLogger(final Configuration configuration, final @Nullable HttpState httpStateHandler) {
        this.configuration = configuration;
        this.logger = null;
        this.httpStateHandler = httpStateHandler;
    }

    /**
     * The {@link Configuration} this logger was constructed with, or {@code null} when it was built
     * without one (test-support constructors, client-side use).
     *
     * <p>Exposed because {@code MockServerLogger} is already the configuration carrier threaded
     * through the whole matcher graph: matchers such as {@code RegexStringMatcher},
     * {@code XPathMatcher} and {@code JsonStringMatcher} — and the shared
     * {@code MatchingTimeoutExecutor} — are constructed with a logger and nothing else, yet must
     * honour instance-scoped matching settings ({@code regexMatchingTimeoutMillis} and
     * {@code xpathMatchingTimeoutMillis}, settable over {@code PUT /mockserver/configuration}, and the
     * startup-only {@code customJsonUnitMatchersClass}). Reading the configuration off the logger they already
     * hold avoids threading a second parameter through ~15 matcher construction sites for values
     * those sites never otherwise touch.
     *
     * <p>Callers MUST treat {@code null} as "no instance configured" and fall back to
     * {@link ConfigurationProperties}, exactly as {@link #isEnabledForInstance(Level)} and
     * {@link #isDisableLogging()} already do.
     */
    public Configuration getConfiguration() {
        return configuration;
    }

    public MockServerLogger setHttpStateHandler(HttpState httpStateHandler) {
        this.httpStateHandler = httpStateHandler;
        return this;
    }

    public static void setGlobalLogEventListener(Consumer<LogEntry> listener) {
        globalLogEventListener = listener;
    }

    public void logEvent(LogEntry logEntry) {
        if (logEntry.getType() == RECEIVED_REQUEST
            || logEntry.getType() == FORWARDED_REQUEST
            || logEntry.getType() == EXPECTATION_RESPONSE
            || logEntry.isAlwaysLog()
            || isEnabledForInstance(logEntry.getLogLevel())) {
            Consumer<LogEntry> listener = globalLogEventListener;
            if (listener != null) {
                listener.accept(logEntry);
            }
            if (httpStateHandler != null) {
                httpStateHandler.log(logEntry);
            } else if (configuration != null) {
                writeToSystemOut(logger, logEntry, configuration);
            } else {
                writeToSystemOut(logger, logEntry);
            }
        }
    }

    public boolean isEnabledForInstance(final Level level) {
        if (configuration != null) {
            return isEnabled(level, configuration.logLevel());
        }
        return isEnabled(level, ConfigurationProperties.logLevel());
    }

    public boolean isDisableLogging() {
        if (configuration != null) {
            return configuration.disableLogging();
        }
        return ConfigurationProperties.disableLogging();
    }

    /**
     * Writes the entry's message to {@code logger} when the configuration enables its level. The message is not
     * rendered when nothing would print it, such as a java.util.logging logger with no handler once
     * {@code disableSystemOut} is set; every other reader renders it from the entry when it reads it.
     */
    public static void writeToSystemOut(Logger logger, LogEntry logEntry, Configuration configuration) {
        if (!configuration.disableLogging()) {
            Level effectiveLevel = resolveEffectiveLevel(logEntry.getType(), configuration.logLevelOverrides(), configuration.logLevel());
            if ((logEntry.isAlwaysLog() || isEnabled(logEntry.getLogLevel(), effectiveLevel)) && !LogOutputProbe.discards(logger, logEntry.getLogLevel())) {
                LogEntry.RedactedView redacted = logEntry.redactedView(configuration);
                String message = redacted.getMessage();
                if (isNotBlank(message)) {
                    writeLogEntry(logger, logEntry, redacted, message, configuration.compactLogFormat());
                }
            }
        }
    }

    public static void writeToSystemOut(Logger logger, LogEntry logEntry) {
        if (!ConfigurationProperties.disableLogging()) {
            Level effectiveLevel = resolveEffectiveLevel(logEntry.getType(), ConfigurationProperties.logLevelOverrides(), ConfigurationProperties.logLevel());
            if ((logEntry.isAlwaysLog() || isEnabled(logEntry.getLogLevel(), effectiveLevel)) && !LogOutputProbe.discards(logger, logEntry.getLogLevel())) {
                LogEntry.RedactedView redacted = logEntry.redactedView(null);
                String message = redacted.getMessage();
                if (isNotBlank(message)) {
                    writeLogEntry(logger, logEntry, redacted, message, ConfigurationProperties.compactLogFormat());
                }
            }
        }
    }

    // the rendered message is passed in: an entry quoting a request does not memoise it, so rendering twice costs twice
    private static void writeLogEntry(Logger logger, LogEntry logEntry, LogEntry.RedactedView redacted, String renderedMessage, boolean compact) {
        String message = compact ? redacted.getCompactMessage() : renderedMessage;
        Throwable throwable = redacted.getThrowable();
        switch (logEntry.getLogLevel()) {
            case ERROR:
                logger.error(portInformation(logEntry) + message, throwable);
                break;
            case WARN:
                logger.warn(portInformation(logEntry) + message, throwable);
                break;
            case INFO:
                logger.info(portInformation(logEntry) + message, throwable);
                break;
            case DEBUG:
                logger.debug(portInformation(logEntry) + message, throwable);
                break;
            case TRACE:
                logger.trace(portInformation(logEntry) + message, throwable);
                break;
        }
    }

    private static String portInformation(LogEntry logEntry) {
        Integer port = logEntry.getPort();
        if (port != null) {
            return port + " ";
        } else {
            return "";
        }
    }

    public static boolean isEnabled(final Level level) {
        return isEnabled(level, ConfigurationProperties.logLevel());
    }

    public static boolean isEnabled(final Level level, final Level configuredLevel) {
        return configuredLevel != null && level.toInt() >= configuredLevel.toInt();
    }
}
