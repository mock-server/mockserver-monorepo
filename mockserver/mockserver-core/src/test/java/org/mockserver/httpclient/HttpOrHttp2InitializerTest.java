package org.mockserver.httpclient;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.SslHandshakeTimeoutException;
import io.netty.util.internal.OutOfDirectMemoryError;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Message;
import org.slf4j.event.Level;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.exception.ExceptionHandling.boundedFaultDescriptionWithRootCause;
import static org.mockserver.httpclient.NettyHttpClient.REMOTE_SOCKET;
import static org.mockserver.httpclient.NettyHttpClient.RESPONSE_FUTURE;

/**
 * What the forward client logs for an exception that arrives before the protocol is negotiated with an upstream,
 * when {@link HttpOrHttp2Initializer} is the last handler of the pipeline: one entry, the connection closed, and
 * nothing passed on to the end of Netty's pipeline.
 */
public class HttpOrHttp2InitializerTest {

    private static final InetSocketAddress UPSTREAM = InetSocketAddress.createUnresolved("upstream.example", 8443);

    private final List<LogEntry> logged = new ArrayList<>();
    private Level logLevel = Level.DEBUG;
    private final MockServerLogger mockServerLogger = new MockServerLogger(HttpOrHttp2InitializerTest.class) {
        @Override
        public boolean isEnabledForInstance(Level level) {
            return isEnabled(level, logLevel);
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            if (isEnabledForInstance(logEntry.getLogLevel())) {
                logged.add(logEntry);
            }
        }
    };

    @Test
    public void shouldLogAFailedTlsHandshakeAsAWarningWithItsCause() {
        Throwable untrusted = new DecoderException(new SSLHandshakeException("PKIX path building failed"));
        EmbeddedChannel connection = connection();

        connection.pipeline().fireExceptionCaught(untrusted);

        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.WARN));
        assertThat(logged.get(0).getMessageFormat(), is("TLS could not be set up on connection to:{}"));
        assertThat(Arrays.asList(logged.get(0).getArguments()), contains(UPSTREAM));
        assertThat("the forward fails only as a closed connection", logged.get(0).getThrowable(), is(sameInstance(untrusted)));
        assertHandledAndClosed(connection);
    }

    @Test
    public void shouldLogAnyDecoderFaultAsAWarningAndNotAsAClosedConnection() {
        // the closed-connection check is false for every decoder fault, so the fault must be asked about first
        Throwable undecodable = new DecoderException("not a proxy's answer");
        EmbeddedChannel connection = connection();

        connection.pipeline().fireExceptionCaught(undecodable);

        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.WARN));
        assertThat(logged.get(0).getMessageFormat(), is("TLS could not be set up on connection to:{}"));
        assertThat(logged.get(0).getThrowable(), is(sameInstance(undecodable)));
        assertHandledAndClosed(connection);
    }

    @Test
    public void shouldLogAnAnswerThatIsNotTlsWithoutItsBytes() {
        // Netty's message for bytes that are not a TLS record is a hex dump of every one of them
        Throwable notTls = new DecoderException(new NotSslRecordException("not an SSL/TLS record: " + "41".repeat(60_000)));
        EmbeddedChannel connection = connection();

        connection.pipeline().fireExceptionCaught(notTls);

        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.WARN));
        assertThat(logged.get(0).getMessage(configuration()), not(containsString("4141")));
        assertThat(logged.get(0).getThrowable().toString(), is("io.netty.handler.codec.DecoderException: io.netty.handler.ssl.NotSslRecordException: not an SSL/TLS record: 60000 bytes"));
        assertThat(logged.get(0).getThrowable().getStackTrace(), is(notTls.getStackTrace()));
        assertHandledAndClosed(connection);
    }

    @Test
    public void shouldLogAConnectionThatFailedOrWasClosedAtDebugWithoutAStackTrace() {
        // a proxy that refuses the tunnel, and an upstream that resets: each fails the forward, which is logged there
        for (IOException cause : new IOException[]{new ProxyConnectException("http, none, proxy => upstream, status: 407 Proxy Authentication Required"), new SocketException("Connection reset"), new IOException("Broken pipe")}) {
            EmbeddedChannel connection = connection();
            logged.clear();

            connection.pipeline().fireExceptionCaught(cause);

            assertThat(logged, hasSize(1));
            assertThat(logged.get(0).getLogLevel(), is(Level.DEBUG));
            assertThat(logged.get(0).getMessageFormat(), is("connection to:{}failed or was closed before TLS was set up:{}"));
            assertThat(Arrays.asList(logged.get(0).getArguments()), contains(UPSTREAM, cause.getMessage()));
            assertThat(logged.get(0).getThrowable(), is(nullValue()));
            assertHandledAndClosed(connection);
        }
    }

    @Test
    public void shouldCloseTheConnectionWhateverTheLogLevel() {
        logLevel = Level.ERROR;
        for (Throwable cause : new Throwable[]{new DecoderException(new SSLHandshakeException("PKIX path building failed")), new SocketException("Connection reset")}) {
            EmbeddedChannel connection = connection();

            connection.pipeline().fireExceptionCaught(cause);

            assertThat(logged, empty());
            assertHandledAndClosed(connection);
        }
    }

    @Test
    public void shouldNotLogAgainWhatWasLoggedAsAProxyAnswerOverTheHeaderLimit() {
        EmbeddedChannel connection = connection();
        HeaderLimitExceededException refusal = ForwardHeaderLimit.responseOverLimit(mockServerLogger, connection, ForwardHeaderLimit.CONNECT_RESPONSE_HEADERS, 1024);
        logged.clear();

        connection.pipeline().fireExceptionCaught(refusal);

        assertThat(logged, empty());
        assertHandledAndClosed(connection);
    }

    @Test
    public void shouldLogNettysDirectMemoryLimitAsAnError() throws Exception {
        logLevel = Level.ERROR;
        Constructor<OutOfDirectMemoryError> constructor = OutOfDirectMemoryError.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        EmbeddedChannel connection = connection();

        connection.pipeline().fireExceptionCaught(new DecoderException(constructor.newInstance("failed to allocate 16777216 byte(s) of direct memory")));

        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.ERROR));
        assertThat(logged.get(0).getMessageFormat(), startsWith("direct memory limit (io.netty.maxDirectMemory) reached"));
        assertThat(logged.get(0).getThrowable(), is(nullValue()));
        assertHandledAndClosed(connection);
    }

    @Test
    public void shouldLogAnyOtherExceptionAsAnErrorWithItsCause() {
        logLevel = Level.ERROR;
        IllegalStateException unexpected = new IllegalStateException("unexpected");
        EmbeddedChannel connection = connection();

        connection.pipeline().fireExceptionCaught(unexpected);

        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.ERROR));
        assertThat(logged.get(0).getMessageFormat(), startsWith("exception caught before TLS was set up on connection to upstream "));
        assertThat(logged.get(0).getThrowable(), is(sameInstance(unexpected)));
        assertHandledAndClosed(connection);
    }

    @Test
    public void shouldFailAWaitingForwardWithEachKindOfFailedHandshakeAndLogTheConnectionOnlyAtDebug() {
        SSLHandshakeException untrusted = new SSLHandshakeException("PKIX path building failed");
        SSLHandshakeException hostnameMismatch = new SSLHandshakeException("No subject alternative names matching IP address 127.0.0.1 found");
        hostnameMismatch.initCause(new CertificateException("No subject alternative names matching IP address 127.0.0.1 found"));
        NotSslRecordException notTls = new NotSslRecordException("not an SSL/TLS record: " + "48".repeat(400));
        // as OpenSSL reports it, with the trust manager's reason as the cause
        SSLHandshakeException openSsl = new SSLHandshakeException("General OpenSslEngine problem");
        openSsl.initCause(new CertificateException("No subject alternative names present"));
        for (SSLException cause : new SSLException[]{untrusted, hostnameMismatch, notTls, openSsl}) {
            EmbeddedChannel connection = connection();
            CompletableFuture<Message> forward = waitingForward(connection);
            logged.clear();

            // as Netty's TLS handler reports it: the handshake's event, then the exception its decoder wraps
            connection.pipeline().fireUserEventTriggered(new SslHandshakeCompletionEvent(cause));
            connection.pipeline().fireExceptionCaught(new DecoderException(cause));

            Throwable failure = failureOf(forward);
            assertThat(failure, instanceOf(SocketConnectionException.class));
            assertThat(failure.getCause(), is(sameInstance(cause)));
            assertThat(failure.getMessage(), is("TLS handshake with upstream.example:8443 failed: " + boundedFaultDescriptionWithRootCause(cause)));
            assertThat(failure.getMessage(), not(containsString("4848")));
            assertThat("logged once, with the request, by what the forward fails with", logged, hasSize(1));
            assertThat(logged.get(0).getLogLevel(), is(Level.DEBUG));
            assertThat(logged.get(0).getMessageFormat(), is("TLS could not be set up on connection to:{}"));
            assertHandledAndClosed(connection);
        }
    }

    @Test
    public void shouldFailAWaitingForwardWithAHandshakeTimeoutThatNettyReportsOnlyAsAnEvent() {
        SslHandshakeTimeoutException timedOut = new SslHandshakeTimeoutException("handshake timed out after 1000ms");
        EmbeddedChannel connection = connection();
        CompletableFuture<Message> forward = waitingForward(connection);

        connection.pipeline().fireUserEventTriggered(new SslHandshakeCompletionEvent(timedOut));

        Throwable failure = failureOf(forward);
        assertThat(failure, instanceOf(SocketConnectionException.class));
        assertThat(failure.getCause(), is(sameInstance(timedOut)));
        assertThat(logged, empty());
        connection.finishAndReleaseAll();
    }

    @Test
    public void shouldFailAWaitingForwardWithAnUpstreamThatClosedTheConnectionDuringTheHandshake() {
        EmbeddedChannel connection = connection();
        CompletableFuture<Message> forward = waitingForward(connection);
        // as Netty's TLS handler reports a connection that closed while its handshake was in progress
        ClosedChannelException closed = new ClosedChannelException();
        closed.addSuppressed(new SSLHandshakeException("Connection closed while SSL/TLS handshake was in progress"));

        connection.pipeline().fireUserEventTriggered(new SslHandshakeCompletionEvent(closed));

        Throwable failure = failureOf(forward);
        assertThat(failure, instanceOf(SocketConnectionException.class));
        assertThat(failure.getCause(), instanceOf(SSLHandshakeException.class));
        assertThat(failure.getMessage(), is("TLS handshake with upstream.example:8443 failed: SSLHandshakeException: upstream closed the connection during the TLS handshake"));
        assertThat(logged, empty());
        connection.finishAndReleaseAll();
    }

    @Test
    public void shouldFailAWaitingForwardAndCloseWithoutLoggingWhenNettyReportsAFailedHandshakeDirectly() {
        // Netty 4.2.21+ calls handshakeFailure for a timeout or a close after the event, and logs it at WARN by default
        ClosedChannelException closed = new ClosedChannelException();
        closed.addSuppressed(new SSLHandshakeException("Connection closed while SSL/TLS handshake was in progress"));
        for (Throwable cause : new Throwable[]{new SslHandshakeTimeoutException("handshake timed out after 1000ms"), closed}) {
            for (boolean eventFirst : new boolean[]{true, false}) {
                EmbeddedChannel connection = connection();
                CompletableFuture<Message> forward = waitingForward(connection);
                HttpOrHttp2Initializer initializer = connection.pipeline().get(HttpOrHttp2Initializer.class);
                ChannelHandlerContext ctx = connection.pipeline().context(initializer);
                if (eventFirst) {
                    connection.pipeline().fireUserEventTriggered(new SslHandshakeCompletionEvent(cause));
                }

                initializer.handshakeFailure(ctx, cause);

                Throwable failure = failureOf(forward);
                assertThat(failure, instanceOf(SocketConnectionException.class));
                assertThat(failure.getMessage(), startsWith("TLS handshake with upstream.example:8443 failed: "));
                assertThat(logged, empty());
                assertHandledAndClosed(connection);
            }
        }
    }

    @Test
    public void shouldLeaveAWaitingForwardToTheTeardownWhenTheConnectionClosedBeforeAHandshakeStarted() {
        // without the suppressed handshake exception no handshake was in progress: a connection failure, as before
        EmbeddedChannel connection = connection();
        CompletableFuture<Message> forward = waitingForward(connection);

        connection.pipeline().fireUserEventTriggered(new SslHandshakeCompletionEvent(new ClosedChannelException()));

        assertThat(forward.isDone(), is(false));
        connection.finishAndReleaseAll();
    }

    @Test
    public void shouldLeaveAWaitingForwardToTheConnectionFailureUnderAHandshakeThatCouldNotBeWritten() {
        // a proxy that refuses the tunnel fails the handshake's write: the refusal reaches the forward itself
        EmbeddedChannel connection = connection();
        CompletableFuture<Message> forward = waitingForward(connection);
        SSLException writeFailed = new SSLException("failure when writing TLS control frames", new ProxyConnectException("http, none, proxy => upstream, status: 407 Proxy Authentication Required"));

        connection.pipeline().fireUserEventTriggered(new SslHandshakeCompletionEvent(writeFailed));

        assertThat(forward.isDone(), is(false));
        connection.finishAndReleaseAll();
    }

    @Test
    public void shouldStillWarnOfAFailedHandshakeWhenNoForwardIsWaitingForIt() {
        SSLHandshakeException untrusted = new SSLHandshakeException("PKIX path building failed");
        EmbeddedChannel connection = connection();
        CompletableFuture<Message> forward = waitingForward(connection);
        forward.completeExceptionally(new SocketConnectionException("failed already"));

        connection.pipeline().fireUserEventTriggered(new SslHandshakeCompletionEvent(untrusted));
        connection.pipeline().fireExceptionCaught(new DecoderException(untrusted));

        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.WARN));
        assertHandledAndClosed(connection);
    }

    private static CompletableFuture<Message> waitingForward(EmbeddedChannel connection) {
        CompletableFuture<Message> forward = new CompletableFuture<>();
        connection.attr(RESPONSE_FUTURE).set(forward);
        return forward;
    }

    private static Throwable failureOf(CompletableFuture<Message> forward) {
        assertThat("failed", forward.isCompletedExceptionally(), is(true));
        return assertThrows(ExecutionException.class, forward::get).getCause();
    }

    private EmbeddedChannel connection() {
        EmbeddedChannel connection = new EmbeddedChannel(new HttpOrHttp2Initializer(mockServerLogger, pipeline -> {
        }, pipeline -> {
        }));
        connection.attr(REMOTE_SOCKET).set(UPSTREAM);
        return connection;
    }

    /**
     * An {@link EmbeddedChannel} records what reaches the end of its pipeline and throws it from
     * {@code checkException()}.
     */
    private static void assertHandledAndClosed(EmbeddedChannel connection) {
        connection.checkException();
        assertThat("left open, with nothing to say what the connection speaks", connection.isOpen(), is(false));
        connection.finishAndReleaseAll();
    }
}
