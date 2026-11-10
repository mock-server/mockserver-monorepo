package org.mockserver.netty.proxy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * A real request proxied through MockServer, carrying credentials in every place a request or response can,
 * must leave no credential in anything the proxy's log is retrieved as once {@code redactSecretsInLog} is
 * enabled over {@code PUT /mockserver/configuration}. Asserts on whole response bodies, and includes an
 * exchange recorded (and its message rendered) before redaction was enabled.
 */
public class RedactSecretsInLogProxyIntegrationTest {

    private static final List<String> SECRETS = Arrays.asList(
        "AUTHZ-SECRET-1",
        "PROXYAUTH-SECRET-2",
        "APIKEY-SECRET-3",
        "COOKIE-SECRET-4",
        "QUERY-SECRET-5",
        "BODY-SECRET-6",
        "SETCOOKIE-SECRET-7",
        "RESPBODY-SECRET-8"
    );
    private static final String PATH = "/v1/chat";

    private MockServer upstream;
    private MockServer proxy;

    @Before
    public void startServers() throws IOException {
        upstream = new MockServer(configuration(), 0);
        proxy = new MockServer(configuration(), 0);
        send(upstream.getLocalPort(), "PUT", "/mockserver/expectation", "{"
            + "\"httpRequest\": {\"path\": \"" + PATH + "\"},"
            + "\"httpResponse\": {\"statusCode\": 200,"
            + " \"headers\": {\"Set-Cookie\": [\"sid=SETCOOKIE-SECRET-7; Path=/\"], \"Content-Type\": [\"application/json\"]},"
            + " \"body\": \"{\\\"ok\\\":true,\\\"password\\\":\\\"RESPBODY-SECRET-8\\\"}\"}"
            + "}");
    }

    @After
    public void stopServers() {
        if (proxy != null) {
            proxy.stop();
        }
        if (upstream != null) {
            upstream.stop();
        }
    }

    @Test
    public void shouldRedactEverySurfaceOfAProxiedExchangeOnceEnabledOverTheConfigurationEndpoint() throws IOException {
        // Origin credentials remain visible when redaction is off; the proxy login never does.
        assertThat(proxiedRequest(), containsString("RESPBODY-SECRET-8"));
        String before = retrieve("LOGS", null);
        for (String secret : SECRETS) {
            if ("PROXYAUTH-SECRET-2".equals(secret)) {
                assertThat("proxy login is absent even before redaction is enabled", before, not(containsString(secret)));
            } else {
                assertThat("baseline log should show origin credential " + secret, before, containsString(secret));
            }
        }

        Response configured = send(proxy.getLocalPort(), "PUT", "/mockserver/configuration",
            "{\"redactSecretsInLog\": true, \"fixtureBodyRedactFields\": \"password\", \"redactSecretsInRecordedExpectations\": true}");
        assertThat(configured.body, configured.statusCode, is(200));
        proxiedRequest();

        assertNoSecrets("LOGS", retrieve("LOGS", null));
        assertNoSecrets("LOGS LOG_ENTRIES", retrieve("LOGS", "LOG_ENTRIES"));
        for (String format : Arrays.asList("JSON", "HAR", "CURL")) {
            assertNoSecrets("REQUESTS " + format, retrieve("REQUESTS", format));
        }
        for (String format : Arrays.asList("JSON", "HAR")) {
            assertNoSecrets("REQUEST_RESPONSES " + format, retrieve("REQUEST_RESPONSES", format));
        }
        // redactSecretsInRecordedExpectations masks credentials in headers, cookies and the query string only
        String recorded = retrieve("RECORDED_EXPECTATIONS", "JSON");
        for (String secret : Arrays.asList("AUTHZ-SECRET-1", "PROXYAUTH-SECRET-2", "APIKEY-SECRET-3", "COOKIE-SECRET-4", "QUERY-SECRET-5", "SETCOOKIE-SECRET-7")) {
            assertThat("RECORDED_EXPECTATIONS leaked " + secret + ":\n" + recorded, recorded, not(containsString(secret)));
        }
    }

    private static void assertNoSecrets(String surface, String output) {
        for (String secret : SECRETS) {
            assertThat(surface + " leaked " + secret + ":\n" + output, output, not(containsString(secret)));
        }
    }

    private String retrieve(String type, String format) throws IOException {
        Response response = send(proxy.getLocalPort(), "PUT",
            "/mockserver/retrieve?type=" + type + (format != null ? "&format=" + format : ""),
            "{\"path\": \"" + PATH + "\"}");
        assertThat(type + " " + format + " status", response.statusCode, is(200));
        assertThat(type + " " + format + " should include the exchange:\n" + response.body, response.body, containsString(PATH));
        return response.body;
    }

    /**
     * Sends the request to the proxy in absolute form, as an HTTP client configured with a proxy does, over a
     * raw socket so every header (Proxy-Authorization included) reaches MockServer exactly as written.
     */
    private String proxiedRequest() throws IOException {
        String upstreamHost = "127.0.0.1:" + upstream.getLocalPort();
        byte[] body = "{\"user\":\"bob\",\"password\":\"BODY-SECRET-6\"}".getBytes(StandardCharsets.UTF_8);
        String head = "POST http://" + upstreamHost + PATH + "?key=QUERY-SECRET-5&model=gpt HTTP/1.1\r\n"
            + "Host: " + upstreamHost + "\r\n"
            + "Authorization: Bearer AUTHZ-SECRET-1\r\n"
            + "Proxy-Authorization: Basic PROXYAUTH-SECRET-2\r\n"
            + "x-api-key: APIKEY-SECRET-3\r\n"
            + "Cookie: session=COOKIE-SECRET-4\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Connection: close\r\n"
            + "\r\n";
        try (Socket socket = new Socket("127.0.0.1", proxy.getLocalPort())) {
            socket.setSoTimeout(20_000);
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
            String response = new String(drain(socket.getInputStream()), StandardCharsets.UTF_8);
            assertThat("the proxied request should be served by the upstream:\n" + response, response, containsString(" 200 "));
            return response;
        }
    }

    private static Response send(int port, String method, String path, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 0) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int statusCode = connection.getResponseCode();
            InputStream stream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(statusCode, stream == null ? "" : new String(drain(stream), StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] drain(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static final class Response {
        final int statusCode;
        final String body;

        Response(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }
}
