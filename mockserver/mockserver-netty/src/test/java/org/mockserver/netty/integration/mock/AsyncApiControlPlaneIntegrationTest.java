package org.mockserver.netty.integration.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Over-the-wire integration test for the AsyncAPI control-plane HTTP endpoints exposed by a real,
 * running MockServer:
 * <ul>
 *   <li>{@code PUT /mockserver/asyncapi} — load an AsyncAPI spec;</li>
 *   <li>{@code GET /mockserver/asyncapi} — report loaded status (channels, publisher/subscriber counts);</li>
 *   <li>{@code PUT /mockserver/asyncapi/verify} — assert against recorded messages and return a verdict.</li>
 * </ul>
 *
 * <p>This drives the full Netty → {@link org.mockserver.mock.HttpState} → AsyncAPI control-plane
 * ({@code AsyncApiControlPlaneRegistry} → {@code AsyncApiControlPlaneImpl}) path over a real socket,
 * which the module-level control-plane tests (which call the orchestrator/impl directly) do not.
 *
 * <p><strong>No live broker is required.</strong> With no {@code brokerConfig} in the request body the
 * control-plane parses the spec and reports {@code loaded:true} with zero publishers and zero
 * subscribers — no broker connection is attempted. Because there are no subscribers, the reachable
 * broker-less verify verdict is a <em>failure</em> (the default {@code atLeast:1} constraint cannot be
 * met with zero recorded messages), returned as {@code 406 Not Acceptable} with the failure detail in
 * the body. Verifying the passing (202) verdict would require recorded messages from a live broker and
 * is therefore covered by the live-broker integration suite, not here.
 */
public class AsyncApiControlPlaneIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ASYNC_API_SPEC = "{\n" +
        "  \"asyncapi\": \"2.6.0\",\n" +
        "  \"info\": { \"title\": \"Orders API\", \"version\": \"1.0.0\" },\n" +
        "  \"channels\": {\n" +
        "    \"orders/created\": {\n" +
        "      \"publish\": {\n" +
        "        \"message\": {\n" +
        "          \"payload\": {\n" +
        "            \"type\": \"object\",\n" +
        "            \"properties\": {\n" +
        "              \"orderId\": { \"type\": \"integer\" },\n" +
        "              \"status\": { \"type\": \"string\" }\n" +
        "            },\n" +
        "            \"required\": [\"orderId\"]\n" +
        "          }\n" +
        "        }\n" +
        "      }\n" +
        "    },\n" +
        "    \"orders/shipped\": {\n" +
        "      \"subscribe\": {\n" +
        "        \"message\": {\n" +
        "          \"payload\": { \"type\": \"string\" }\n" +
        "        }\n" +
        "      }\n" +
        "    }\n" +
        "  }\n" +
        "}";

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startServer() {
        mockServer = new MockServer();
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
    }

    @Before
    public void resetServer() {
        // resets AsyncAPI control-plane state (HttpState.reset -> AsyncApiControlPlaneRegistry.reset)
        mockServerClient.reset();
    }

    @Test
    public void shouldLoadSpecOverTheWire() throws Exception {
        // when - PUT the spec to the running server over a raw socket
        HttpResult result = send("PUT", "/mockserver/asyncapi", ASYNC_API_SPEC);

        // then - 201 Created with a status body describing the loaded, broker-less mock
        assertThat("PUT /mockserver/asyncapi should return 201 Created", result.statusCode, is(201));
        JsonNode body = MAPPER.readTree(result.body);
        assertThat(body.get("loaded").asBoolean(), is(true));
        assertThat(body.get("specTitle").asText(), is("Orders API"));
        assertThat(body.get("channelCount").asInt(), is(2));
        // no brokerConfig => no broker connections attempted
        assertThat(body.get("publishers").asInt(), is(0));
        assertThat(body.get("subscribers").asInt(), is(0));
    }

    @Test
    public void shouldReportStatusOverTheWireAfterLoad() throws Exception {
        // given - a spec loaded over the wire
        HttpResult put = send("PUT", "/mockserver/asyncapi", ASYNC_API_SPEC);
        assertThat(put.statusCode, is(201));

        // when - GET the status over the wire
        HttpResult status = send("GET", "/mockserver/asyncapi", null);

        // then - 200 OK reflecting the loaded spec's channels and publisher/subscriber counts
        assertThat("GET /mockserver/asyncapi should return 200 OK", status.statusCode, is(200));
        JsonNode body = MAPPER.readTree(status.body);
        assertThat(body.get("loaded").asBoolean(), is(true));
        assertThat(body.get("specTitle").asText(), is("Orders API"));
        assertThat(body.get("channels").size(), is(2));
        assertThat(body.get("publishers").asInt(), is(0));
        assertThat(body.get("subscribers").asInt(), is(0));

        boolean sawCreated = false;
        boolean sawShipped = false;
        for (JsonNode channel : body.get("channels")) {
            if ("orders/created".equals(channel.get("name").asText())) {
                sawCreated = true;
            }
            if ("orders/shipped".equals(channel.get("name").asText())) {
                sawShipped = true;
            }
        }
        assertThat("status should list the orders/created channel", sawCreated, is(true));
        assertThat("status should list the orders/shipped channel", sawShipped, is(true));
    }

    @Test
    public void shouldReportEmptyStatusOverTheWireWhenNoSpecLoaded() throws Exception {
        // when - GET the status with no spec loaded
        HttpResult status = send("GET", "/mockserver/asyncapi", null);

        // then - 200 OK reporting an unloaded control-plane
        assertThat(status.statusCode, is(200));
        JsonNode body = MAPPER.readTree(status.body);
        assertThat(body.get("loaded").asBoolean(), is(false));
        assertThat(body.get("channels").size(), is(0));
        assertThat(body.get("publishers").asInt(), is(0));
        assertThat(body.get("subscribers").asInt(), is(0));
    }

    @Test
    public void shouldReturnVerifyVerdictOverTheWire() throws Exception {
        // given - a spec loaded over the wire (broker-less => no subscribers, no recorded messages)
        HttpResult put = send("PUT", "/mockserver/asyncapi", ASYNC_API_SPEC);
        assertThat(put.statusCode, is(201));

        // when - verify at-least-one message on a channel that has recorded nothing
        String verification = "{ \"channel\": \"orders/shipped\" }";
        HttpResult verify = send("PUT", "/mockserver/asyncapi/verify", verification);

        // then - the verdict is a failure: default atLeast:1 cannot be met with zero recorded messages,
        // returned as 406 Not Acceptable with the failure detail in the body
        assertThat("verify with no matching messages should return 406 Not Acceptable",
            verify.statusCode, is(406));
        assertThat(verify.body, containsString("channel 'orders/shipped'"));
        assertThat(verify.body, containsString("at least 1"));
        assertThat(verify.body, containsString("found 0"));
    }

    @Test
    public void shouldRejectBlankVerifyBodyOverTheWire() throws Exception {
        // when - verify with an empty body
        HttpResult verify = send("PUT", "/mockserver/asyncapi/verify", "");

        // then - 400 Bad Request from the control-plane endpoint
        assertThat(verify.statusCode, is(400));
        assertThat(verify.body, containsString("must not be empty"));
    }

    @Test
    public void shouldAnswerAnUnparseableSpecWithAJsonErrorThatKeepsTheParserMessage() throws Exception {
        // when - a YAML document the parser rejects with a multi-line message
        HttpResult put = send("PUT", "/mockserver/asyncapi", "garbage: [");

        // then - the 400 body is valid JSON (the message's newlines are escaped, not raw)
        assertThat(put.statusCode, is(400));
        String error = MAPPER.readTree(put.body).get("error").asText();
        assertThat(error, startsWith("failed to load AsyncAPI spec: "));
        assertThat(error, containsString("garbage: ["));
    }

    @Test
    public void shouldRejectAVerifyRequestWithACountFieldOutsideCount() throws Exception {
        // given
        assertThat(send("PUT", "/mockserver/asyncapi", ASYNC_API_SPEC).statusCode, is(201));

        // when - "atMost" at the top level, where it used to be silently ignored
        HttpResult verify = send("PUT", "/mockserver/asyncapi/verify", "{ \"channel\": \"orders/shipped\", \"atMost\": 0 }");

        // then - refused with a JSON error naming the field and where it belongs
        assertThat(verify.statusCode, is(400));
        String error = MAPPER.readTree(verify.body).get("error").asText();
        assertThat(error, containsString("'atMost'"));
        assertThat(error, containsString("\"count\""));
    }

    @Test
    public void shouldRefuseAKafkaJaasConfigurationOverTheWire() throws Exception {
        // a JAAS configuration chooses the login module class the Kafka client loads, so it is startup-only
        String body = "{ \"spec\": " + MAPPER.writeValueAsString(ASYNC_API_SPEC) + ", \"brokerConfig\": {"
            + " \"kafkaBootstrapServers\": \"localhost:1\", \"kafkaSecurity\": {"
            + " \"securityProtocol\": \"SASL_PLAINTEXT\", \"saslMechanism\": \"PLAIN\","
            + " \"saslJaasConfig\": \"com.sun.security.auth.module.JndiLoginModule required;\" } } }";

        HttpResult put = send("PUT", "/mockserver/asyncapi", body);

        assertThat(put.statusCode, is(400));
        String error = MAPPER.readTree(put.body).get("error").asText();
        assertThat(error, containsString("'kafkaSecurity.saslJaasConfig' cannot be set over the control plane"));
        assertThat(error, containsString("mockserver.asyncKafkaSaslJaasConfig"));
        assertThat(MAPPER.readTree(send("GET", "/mockserver/asyncapi", null).body).get("loaded").asBoolean(), is(false));
    }

    // --- raw HTTP helper ---

    private static final class HttpResult {
        final int statusCode;
        final String body;

        HttpResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    /**
     * Issue a raw HTTP/1.1 request against the running MockServer control port and parse the status
     * code and body from the response. Uses {@code Connection: close} so the whole response can be read
     * to EOF without needing to interpret {@code Content-Length} / chunked framing.
     */
    private HttpResult send(String method, String path, String body) {
        try (Socket socket = new Socket("localhost", mockServer.getLocalPort())) {
            socket.setSoTimeout(10000);
            StringBuilder request = new StringBuilder();
            request.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            request.append("Host: localhost:").append(mockServer.getLocalPort()).append("\r\n");
            request.append("Connection: close\r\n");
            byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            if (body != null) {
                request.append("Content-Type: application/json\r\n");
                request.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
            }
            request.append("\r\n");

            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
            if (bodyBytes.length > 0) {
                socket.getOutputStream().write(bodyBytes);
            }
            socket.getOutputStream().flush();

            byte[] raw = readFully(socket.getInputStream());
            String response = new String(raw, StandardCharsets.UTF_8);

            int firstLineEnd = response.indexOf("\r\n");
            String statusLine = firstLineEnd >= 0 ? response.substring(0, firstLineEnd) : response;
            // "HTTP/1.1 201 Created" -> 201
            String[] statusParts = statusLine.split(" ");
            int statusCode = Integer.parseInt(statusParts[1]);

            int headerEnd = response.indexOf("\r\n\r\n");
            String responseBody = headerEnd >= 0 ? response.substring(headerEnd + 4) : "";

            return new HttpResult(statusCode, responseBody);
        } catch (Exception e) {
            throw new RuntimeException("failed to send " + method + " " + path, e);
        }
    }

    private static byte[] readFully(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
