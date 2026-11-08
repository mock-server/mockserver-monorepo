package org.mockserver.httpclient;

import io.netty.handler.codec.http.FullHttpRequest;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.MockServerHttpRequestToFullHttpRequest;
import org.mockserver.model.HttpRequest;
import org.mockserver.serialization.ObjectMapperFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * An expectation can give a forwarded request a header Netty refuses to encode, so the encoding failure is logged for
 * the request the forward client added {@code forwardCredentialHeadersByHost} credentials to.
 */
public class MockServerHttpRequestToFullHttpRequestForwardCredentialsTest {

    private static final String CREDENTIAL = "Bearer sk-live-forward-credential";

    private final List<LogEntry> entries = new CopyOnWriteArrayList<>();
    private final MockServerLogger logger = new MockServerLogger(configuration(), MockServerHttpRequestToFullHttpRequestForwardCredentialsTest.class) {
        @Override
        public void logEvent(LogEntry logEntry) {
            entries.add(logEntry);
        }
    };

    private HttpRequest withCredentials(HttpRequest request) {
        ForwardCredentialHeaders headers = ForwardCredentialHeaders.resolve("api.example.com=Authorization:API_TOKEN", Map.of("API_TOKEN", CREDENTIAL)::get, null);
        HttpRequest sent = headers.applyTo(request, InetSocketAddress.createUnresolved("api.example.com", 443));
        assertThat(sent.getFirstHeader("Authorization"), is(CREDENTIAL));
        return sent;
    }

    @Test
    public void shouldNotLogTheCredentialWhenAHeaderNameCannotBeEncoded() throws Exception {
        assertEncodingFailureLogsNoCredential(request("/path").withSecure(true).withHeader("Host", "api.example.com").withHeader("X B", "value"));
    }

    @Test
    public void shouldNotLogTheCredentialWhenAHeaderValueCannotBeEncoded() throws Exception {
        assertEncodingFailureLogsNoCredential(request("/path").withSecure(true).withHeader("Host", "api.example.com").withHeader("X-Split", "first\r\nX-Injected: yes"));
    }

    private void assertEncodingFailureLogsNoCredential(HttpRequest original) throws Exception {
        HttpRequest sent = withCredentials(original);

        FullHttpRequest encoded = new MockServerHttpRequestToFullHttpRequest(logger, null).mapMockServerRequestToNettyRequest(sent);
        encoded.release();

        assertThat(entries, hasSize(1));
        LogEntry entry = entries.get(0);
        assertThat(entry.getMessage(), startsWith("exception encoding request"));
        assertThat(entry.getThrowable(), is(notNullValue()));
        assertThat(entry.getHttpRequests()[0], sameInstance(original));
        StringWriter stackTrace = new StringWriter();
        entry.getThrowable().printStackTrace(new PrintWriter(stackTrace));
        String everything = entry.getMessage() + "\n" + stackTrace
            + "\n" + ObjectMapperFactory.createObjectMapper().writeValueAsString(entry.getHttpRequests())
            + "\n" + List.of(entry.getArguments());
        assertThat(everything, not(containsString("sk-live-forward-credential")));
    }
}
