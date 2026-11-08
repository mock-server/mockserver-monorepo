package org.mockserver.netty.integration.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.dashboard.model.DashboardLogEntryDTO;
import org.mockserver.dashboard.serializers.DashboardLogEntryDTOGroupSerializer;
import org.mockserver.dashboard.serializers.DashboardLogEntryDTOSerializer;
import org.mockserver.dashboard.serializers.DescriptionSerializer;
import org.mockserver.dashboard.serializers.ThrowableSerializer;
import org.mockserver.httpclient.ForwardCredentialHeadersTestEnvironment;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.Format;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpTemplate;
import org.mockserver.model.Protocol;
import org.mockserver.model.SocketAddress;
import org.mockserver.netty.MockServer;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.socket.PortFactory;
import org.mockserver.socket.tls.ForwardProxyTLSX509CertificatesTrustManager;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpOverrideForwardedRequest.forwardOverriddenRequest;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpTemplate.template;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * {@code forwardCredentialHeadersByHost} end to end: a forwarding MockServer, started with the property and with
 * {@code redactSecretsInLog} off, sends the credential to the upstream it names, over HTTP/1.1, over HTTP/2 and
 * on the unmatched proxy path, while nothing it logs, records, renders or returns ever contains the value.
 */
public class ForwardCredentialHeadersIntegrationTest {

    private static final String SEPARATE_CA = "org/mockserver/netty/integration/tls/separateca/";
    private static final String TOKEN = "Bearer fch-token-" + UUID.randomUUID();
    private static final String KEY = "fch-key-" + UUID.randomUUID();
    private static final Map<String, String> ENVIRONMENT = Map.of("FCH_TEST_TOKEN", TOKEN, "FCH_TEST_KEY", KEY);
    private static final ObjectMapper DASHBOARD_MAPPER = ObjectMapperFactory.createObjectMapper(
        new DashboardLogEntryDTOSerializer(),
        new DashboardLogEntryDTOGroupSerializer(),
        new DescriptionSerializer(),
        new ThrowableSerializer()
    );

    private static EventLoopGroup clientEventLoopGroup;
    private static MockServer upstream;
    private static MockServerClient upstreamClient;
    private static MockServer otherUpstream;
    private static MockServerClient otherUpstreamClient;
    private static ForwardServer forwardServer;
    private static MockServerClient forwardClient;

    private static class ForwardServer extends MockServer {
        ForwardServer(Configuration configuration) {
            super(configuration);
        }

        HttpState state() {
            return httpState;
        }
    }

    @BeforeClass
    public static void startServers() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(ForwardCredentialHeadersIntegrationTest.class.getSimpleName() + "-eventLoop"));
        // the upstreams' certificates are signed by a CA that is not the forwarding server's, which credential
        // connections never trust
        upstream = new MockServer(separateCaConfiguration());
        upstreamClient = new MockServerClient("localhost", upstream.getLocalPort());
        // the values are read once, as the server starts, so the lookup is only needed while it is built
        otherUpstream = new MockServer(separateCaConfiguration());
        otherUpstreamClient = new MockServerClient("localhost", otherUpstream.getLocalPort());
        ForwardCredentialHeadersTestEnvironment.use(ENVIRONMENT::get);
        try {
            // TLS to 127.0.0.1 on any port, and plain HTTP only to the first upstream's port; TLS entries apply only
            // while the forward client verifies the upstream certificate and host name, here against the upstreams' CA
            forwardServer = new ForwardServer(configuration()
                .redactSecretsInLog(false)
                .forwardProxyHttp2Upgrade(true)
                .forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.CUSTOM)
                .forwardProxyTLSCustomTrustX509Certificates(SEPARATE_CA + "ca.pem")
                .forwardProxyTLSHostnameVerificationEnabled(true)
                .forwardCredentialHeadersByHost("127.0.0.1=Authorization:FCH_TEST_TOKEN;X-Upstream-Key:FCH_TEST_KEY,"
                    + "http://127.0.0.1:" + upstream.getLocalPort() + "=Authorization:FCH_TEST_TOKEN;X-Upstream-Key:FCH_TEST_KEY"));
        } finally {
            ForwardCredentialHeadersTestEnvironment.restore();
        }
        forwardClient = new MockServerClient("localhost", forwardServer.getLocalPort());
    }

    private static Configuration separateCaConfiguration() {
        return configuration()
            .certificateAuthorityCertificate(SEPARATE_CA + "ca.pem")
            .certificateAuthorityPrivateKey(SEPARATE_CA + "ca-key-pkcs8.pem");
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(forwardClient);
        stopQuietly(forwardServer);
        stopQuietly(upstreamClient);
        stopQuietly(upstream);
        stopQuietly(otherUpstreamClient);
        stopQuietly(otherUpstream);
        if (clientEventLoopGroup != null) {
            clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
        }
    }

    @Before
    public void reset() {
        forwardClient.reset();
        for (MockServerClient client : List.of(upstreamClient, otherUpstreamClient)) {
            client.reset();
            client
                .when(request().withPath("/credential/.*").withProtocol(Protocol.HTTP_1_1))
                .respond(response().withStatusCode(200).withBody("upstream_saw_http1"));
            client
                .when(request().withPath("/credential/.*").withProtocol(Protocol.HTTP_2))
                .respond(response().withStatusCode(201).withBody("upstream_saw_http2"));
        }
    }

    @Test(timeout = 60000)
    public void shouldSendTheCredentialUpstreamWithoutLoggingOrRecordingIt() throws Exception {
        // given
        forwardTo("/credential/forward", "127.0.0.1", upstream.getLocalPort(), HttpForward.Scheme.HTTP);

        // when - the client sends its own Authorization header, which the credential replaces upstream
        HttpResponse response = send(request("/credential/forward").withHeader("Authorization", "Bearer client-token"), false);

        // then
        assertThat(response.getStatusCode(), is(200));
        HttpRequest received = receivedUpstream("/credential/forward");
        assertThat(received.getHeader("Authorization"), contains(TOKEN));
        assertThat(received.getHeader("X-Upstream-Key"), contains(KEY));
        HttpRequest recorded = forwardClient.retrieveRecordedRequests(request("/credential/forward"))[0];
        assertThat(recorded.getHeader("Authorization"), contains("Bearer client-token"));
        assertThat(recorded.containsHeader("X-Upstream-Key"), is(false));
        assertNoCredentialAnywhere();
    }

    @Test(timeout = 60000)
    public void shouldSendTheCredentialOverHttp2() throws Exception {
        // given
        forwardTo("/credential/h2", "127.0.0.1", upstream.getLocalPort(), HttpForward.Scheme.HTTPS);

        // when
        HttpResponse response = send(request("/credential/h2").withSecure(true), true);

        // then - forwardProxyHttp2Upgrade made the upstream leg HTTP/2
        assertThat(response.getStatusCode(), is(201));
        HttpRequest received = receivedUpstream("/credential/h2");
        assertThat(received.getProtocol(), is(Protocol.HTTP_2));
        assertThat(received.getHeader("Authorization"), contains(TOKEN));
        assertThat(received.getHeader("X-Upstream-Key"), contains(KEY));
        assertNoCredentialAnywhere();
    }

    @Test(timeout = 60000)
    public void shouldSendEachCredentialOnANewConnectionOverHttp1AndHttp2() throws Exception {
        // given - the forward client pools connections by default; the upstream answers with the client address it saw
        // and whether the credential arrived
        upstreamClient
            .when(request().withPath("/connection/.*"))
            .respond(template(HttpTemplate.TemplateType.MUSTACHE, "{ \"statusCode\": 200, \"body\": \"{{ request.remoteAddress }} {{ request.headers.X-Upstream-Key.0 }}\" }"));
        forwardTo("/connection/h1", "127.0.0.1", upstream.getLocalPort(), HttpForward.Scheme.HTTPS);
        forwardTo("/connection/h2", "127.0.0.1", upstream.getLocalPort(), HttpForward.Scheme.HTTPS);

        // when
        List<String> http1 = new ArrayList<>();
        List<String> http2 = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            http1.add(send(request("/connection/h1"), false).getBodyAsString());
            http2.add(send(request("/connection/h2").withSecure(true), true).getBodyAsString());
        }

        // then - each arrived with the credential from another client port
        for (List<String> seen : List.of(http1, http2)) {
            assertThat(seen.toString(), seen.get(0), endsWith(" " + KEY));
            assertThat(seen.toString(), seen.get(1), endsWith(" " + KEY));
            assertThat(seen.toString(), seen.get(0).split(" ")[0], matchesPattern("127\\.0\\.0\\.1:[0-9]+"));
            assertThat(seen.toString(), seen.get(0).split(" ")[0], not(seen.get(1).split(" ")[0]));
        }
        for (HttpRequest received : upstreamClient.retrieveRecordedRequests(request("/connection/h2"))) {
            assertThat(received.getProtocol(), is(Protocol.HTTP_2));
        }
    }

    @Test(timeout = 60000)
    public void shouldSendTheCredentialOnTheUnmatchedProxyPath() throws Exception {
        // when - no expectation matches, so the request is proxied to its Host
        HttpResponse response = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false)
            .sendRequest(
                request("/credential/proxied").withHeader(HOST.toString(), "127.0.0.1:" + upstream.getLocalPort()),
                new InetSocketAddress("127.0.0.1", forwardServer.getLocalPort())
            ).get(15, SECONDS);

        // then
        assertThat(response.getStatusCode(), is(200));
        HttpRequest received = receivedUpstream("/credential/proxied");
        assertThat(received.getHeader("Authorization"), contains(TOKEN));
        assertNoCredentialAnywhere();
    }

    @Test(timeout = 60000)
    public void shouldNotLogTheCredentialWhenTheUpstreamCannotBeReached() throws Exception {
        // given - the configured host over TLS, on a port nothing listens on
        forwardTo("/credential/unreachable", "127.0.0.1", PortFactory.findFreePort(), HttpForward.Scheme.HTTPS);

        // when
        HttpResponse response = send(request("/credential/unreachable"), false);

        // then - a refused connection is answered 502
        assertThat(response.getStatusCode(), is(502));
        assertThat(forwardClient.retrieveLogMessages(request()), containsString("/credential/unreachable"));
        assertNoCredentialAnywhere();
    }

    @Test(timeout = 60000)
    public void shouldSendTheCredentialOverTlsToAnyPortButOverPlainHttpOnlyToThePortOptedIn() throws Exception {
        // given - the second upstream's port is configured for TLS (any port) but not for plain HTTP
        forwardTo("/credential/tls-other-port", "127.0.0.1", otherUpstream.getLocalPort(), HttpForward.Scheme.HTTPS);
        forwardTo("/credential/plain-other-port", "127.0.0.1", otherUpstream.getLocalPort(), HttpForward.Scheme.HTTP);

        // when
        send(request("/credential/tls-other-port"), false);
        send(request("/credential/plain-other-port"), false);

        // then
        assertThat(receivedBy(otherUpstreamClient, "/credential/tls-other-port").getHeader("Authorization"), contains(TOKEN));
        assertThat(receivedBy(otherUpstreamClient, "/credential/plain-other-port").containsHeader("Authorization"), is(false));
    }

    @Test(timeout = 60000)
    public void shouldNotLogTheCredentialWhenTheRequestCannotBeEncoded() throws Exception {
        // given - an override adds a header value with a line break, which the HTTP encoder refuses, so encoding the
        // credentialed copy fails
        forwardClient
            .when(request().withPath("/credential/unencodable"))
            .forward(forwardOverriddenRequest(request()
                .withSecure(true)
                .withSocketAddress("127.0.0.1", upstream.getLocalPort(), SocketAddress.Scheme.HTTPS)
                .withHeader("Host", "127.0.0.1:" + upstream.getLocalPort())
                .withHeader("X-Unencodable", "first\r\nsecond")));

        // when
        send(request("/credential/unencodable"), false);

        // then
        assertThat(forwardClient.retrieveLogMessages(request()), containsString("exception encoding request"));
        assertNoCredentialAnywhere();
    }

    @Test(timeout = 60000)
    public void shouldNotSendTheCredentialToAnotherHost() throws Exception {
        // given - the same upstream, reached as localhost, which is not configured
        forwardTo("/credential/other-host", "localhost", upstream.getLocalPort(), HttpForward.Scheme.HTTP);

        // when
        send(request("/credential/other-host"), false);

        // then
        HttpRequest received = receivedUpstream("/credential/other-host");
        assertThat(received.containsHeader("Authorization"), is(false));
        assertThat(received.containsHeader("X-Upstream-Key"), is(false));
    }

    @Test(timeout = 60000)
    public void shouldIgnoreTheConfigurationEndpoint() throws Exception {
        // when - a PUT tries to point the credential at another host
        HttpResponse put = send(request("/mockserver/configuration")
            .withMethod("PUT")
            .withBody("{\"forwardCredentialHeadersByHost\":\"localhost=Authorization:FCH_TEST_TOKEN\"}"), false);
        forwardTo("/credential/after-put", "localhost", upstream.getLocalPort(), HttpForward.Scheme.HTTP);
        send(request("/credential/after-put"), false);

        // then
        assertThat(put.getStatusCode(), is(200));
        assertThat(receivedUpstream("/credential/after-put").containsHeader("Authorization"), is(false));
        String configurationJson = send(request("/mockserver/configuration").withMethod("GET"), false).getBodyAsString();
        assertThat(configurationJson, not(containsString("forwardCredentialHeadersByHost")));
        assertThat(configurationJson, not(containsString("FCH_TEST_TOKEN")));
    }

    private void forwardTo(String path, String host, int port, HttpForward.Scheme scheme) {
        forwardClient
            .when(request().withPath(path))
            .forward(forward().withHost(host).withPort(port).withScheme(scheme));
    }

    private HttpResponse send(HttpRequest request, boolean http2) throws Exception {
        if (http2) {
            request.withProtocol(Protocol.HTTP_2);
        }
        return new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false)
            .sendRequest(request.withHeader(HOST.toString(), "127.0.0.1:" + forwardServer.getLocalPort()))
            .get(15, SECONDS);
    }

    private static HttpRequest receivedUpstream(String path) {
        return receivedBy(upstreamClient, path);
    }

    private static HttpRequest receivedBy(MockServerClient client, String path) {
        HttpRequest[] received = client.retrieveRecordedRequests(request(path));
        assertThat(received.length, is(1));
        return received[0];
    }

    private void assertNoCredentialAnywhere() throws Exception {
        List<String> rendered = new ArrayList<>();
        rendered.add(forwardClient.retrieveLogMessages(request()));
        rendered.add(retrieve("LOGS", "LOG_ENTRIES"));
        rendered.add(forwardClient.retrieveRecordedRequests(request(), Format.JSON));
        rendered.add(forwardClient.retrieveRecordedRequestsAndResponses(request(), Format.JSON));
        rendered.add(forwardClient.retrieveRecordedRequestsAndResponses(request(), Format.HAR));
        rendered.add(forwardClient.retrieveRecordedExpectations(request(), Format.JSON));
        rendered.add(forwardClient.retrieveRecordedExpectations(request(), Format.JAVA));
        rendered.add(dashboardJson());
        for (String text : rendered) {
            assertThat(text, not(containsString(TOKEN)));
            assertThat(text, not(containsString(TOKEN.substring("Bearer ".length()))));
            assertThat(text, not(containsString(KEY)));
        }
    }

    private String retrieve(String type, String format) throws Exception {
        return send(request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", type)
            .withQueryStringParameter("format", format), false).getBodyAsString();
    }

    private String dashboardJson() throws Exception {
        CompletableFuture<List<DashboardLogEntryDTO>> entries = new CompletableFuture<>();
        Configuration configuration = forwardServer.state().getConfiguration();
        forwardServer.state().getMockServerLog().retrieveLogEntriesInReverseForUI(
            null,
            logEntry -> true,
            logEntry -> new DashboardLogEntryDTO(logEntry, configuration),
            stream -> entries.complete(stream.collect(Collectors.toList()))
        );
        List<DashboardLogEntryDTO> dtos = entries.get(15, SECONDS);
        assertThat(dtos, not(empty()));
        return DASHBOARD_MAPPER.writeValueAsString(dtos);
    }
}
