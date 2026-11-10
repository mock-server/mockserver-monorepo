package org.mockserver.testing.integration.proxy;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.apache.commons.lang3.RandomUtils;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.mockserver.client.MockServerClient;
import org.mockserver.echo.http.EchoServer;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.Times;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpStatusCode;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.tls.KeyStoreFactory;
import org.mockserver.streams.IOStreamUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.StringStartsWith.startsWith;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpStatusCode.OK_200;
import static org.mockserver.model.JsonSchemaBody.jsonSchema;
import static org.mockserver.model.StringBody.exact;
import static org.mockserver.test.Assert.assertContains;
import static org.mockserver.validator.jsonschema.JsonSchemaValidator.OPEN_API_SPECIFICATION_URL;
import static org.mockserver.verify.Verification.verification;
import static org.mockserver.verify.VerificationSequence.verificationSequence;
import static org.mockserver.verify.VerificationTimes.atLeast;
import static org.mockserver.verify.VerificationTimes.exactly;

/**
 * @author jamesdbloom
 */
public abstract class AbstractProxyIntegrationTest {

    @Rule
    public Timeout defaultTimeout = Timeout.seconds(600);

    protected static String servletContext = "";
    private static EventLoopGroup clientEventLoopGroup;

    @BeforeClass
    public static void createClientAndEventLoopGroup() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(AbstractProxyIntegrationTest.class.getSimpleName() + "-eventLoop"));
    }

    @AfterClass
    public static void stopEventLoopGroup() {
        clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
    }

    protected HttpClient createHttpClient() {
        return HttpClients
            .custom()
            .setSSLSocketFactory(new SSLConnectionSocketFactory(new KeyStoreFactory(configuration(), new MockServerLogger()).sslContext(), NoopHostnameVerifier.INSTANCE))
            .setRoutePlanner(new DefaultProxyRoutePlanner(
                new HttpHost(
                    System.getProperty("http.proxyHost", "127.0.0.1"),
                    Integer.parseInt(System.getProperty("http.proxyPort", String.valueOf(getProxyPort())))
                )
            )).build();
    }

    public abstract int getProxyPort();

    public abstract MockServerClient getMockServerClient();

    public abstract int getServerPort();

    public abstract EchoServer getEchoServer();

    protected String calculatePath(String path) {
        return (!path.startsWith("/") ? "/" : "") + path;
    }

    protected String addContextToPath(String path) {
        String cleanedPath = path;
        if (isNotBlank(servletContext)) {
            cleanedPath =
                (!servletContext.startsWith("/") ? "/" : "") +
                    servletContext +
                    (!servletContext.endsWith("/") ? "/" : "") +
                    (cleanedPath.startsWith("/") ? cleanedPath.substring(1) : cleanedPath);
        }
        return (!cleanedPath.startsWith("/") ? "/" : "") + cleanedPath;
    }

    @Before
    public void resetServer() {
        getMockServerClient().reset();
    }

    @Test
    public void shouldForwardRequestsUsingSocketDirectly() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", getProxyPort())) {
            // given
            OutputStream output = socket.getOutputStream();

            // when
            // - send GET request for headers only
            output.write(("" +
                "GET " + addContextToPath("test_headers_only") + " HTTP/1.1\r" + NEW_LINE +
                "Host: 127.0.0.1:" + getServerPort() + "\r" + NEW_LINE +
                "x-test: test_headers_only\r" + NEW_LINE +
                "Content-Length: 0\r" + NEW_LINE +
                "Connection: keep-alive\r" + NEW_LINE +
                "\r" + NEW_LINE
            ).getBytes(StandardCharsets.UTF_8));
            output.flush();

            // then
            assertContains(IOStreamUtils.readHttpInputStreamToString(socket), "x-test: test_headers_only");

            // and
            getMockServerClient().verify(
                request()
                    .withMethod("GET")
                    .withPath("/test_headers_only"),
                exactly(1)
            );

            // - send GET request for headers and body
            output.write(("" +
                "GET " + addContextToPath("test_headers_and_body") + " HTTP/1.1\r" + NEW_LINE +
                "Host: 127.0.0.1:" + getServerPort() + "\r" + NEW_LINE +
                "Content-Length: " + "an_example_body".getBytes(StandardCharsets.UTF_8).length + "\r" + NEW_LINE +
                "x-test: test_headers_and_body\r" + NEW_LINE +
                "\r" + NEW_LINE +
                "an_example_body"
            ).getBytes(StandardCharsets.UTF_8));
            output.flush();

            // then
            String response = IOStreamUtils.readHttpInputStreamToString(socket);
            assertContains(response, "x-test: test_headers_and_body");
            assertContains(response, "an_example_body");
        }

        // and
        getMockServerClient().verify(
            request()
                .withMethod("GET")
                .withPath("/test_headers_and_body")
                .withBody("an_example_body"),
            exactly(1)
        );
    }

    @Test
    public void shouldForwardRequestsUsingHttpClient() throws Exception {
        // given
        HttpClient httpClient = createHttpClient();

        // when
        HttpPost request = new HttpPost(
            new URIBuilder()
                .setScheme("http")
                .setHost("127.0.0.1")
                .setPort(getServerPort())
                .setPath(addContextToPath("test_headers_and_body"))
                .build()
        );
        request.setEntity(new StringEntity("an_example_body"));
        HttpResponse response = httpClient.execute(request);

        // then
        assertEquals(HttpStatusCode.OK_200.code(), response.getStatusLine().getStatusCode());
        assertEquals("an_example_body", new String(EntityUtils.toByteArray(response.getEntity()), UTF_8));

        // and
        getMockServerClient().verify(
            request()
                .withMethod("POST")
                .withPath("/test_headers_and_body")
                .withBody("an_example_body"),
            exactly(1)
        );
    }

    @Test
    public void shouldForwardRequestsWithComplexCookies() throws Exception {
        // given
        HttpClient httpClient = createHttpClient();
        Header setCookieOneHeader = new BasicHeader("Set-Cookie", "personalization_59996b985e24ce008d3df3bd07e27c1b=\"\"; Expires=Thu, 01-Jan-1970 00:00:10 GMT; Path=/");
        Header setCookieTwoHeader = new BasicHeader("Set-Cookie", "anonymous_59996b985e24ce008d3df3bd07e27c1b=acgzEaAKOVR=mAY9yJhP7IrC9Am; Version=1; Comment=\"Anonymous cookie for site\"; Max-Age=15552000; Expires=Sat, 19-Mar-2016 18:43:26 GMT; Path=/");
        Header cookieOneHeader = new BasicHeader("Cookie", "personalization_59996b985e24ce008d3df3bd07e27c1b=\"\"");
        Header cookieTwoHeader = new BasicHeader("Cookie", "anonymous_59996b985e24ce008d3df3bd07e27c1b=\"acgzEaAKOVR=mAY9yJhP7IrC9Am\"");

        // when
        HttpPost request = new HttpPost(
            new URIBuilder()
                .setScheme("http")
                .setHost("127.0.0.1")
                .setPort(getServerPort())
                .setPath(addContextToPath("test_headers_and_body"))
                .build()
        );
        request.addHeader(setCookieOneHeader);
        request.addHeader(setCookieTwoHeader);
        request.addHeader(cookieOneHeader);
        request.addHeader(cookieTwoHeader);
        request.setEntity(new StringEntity("an_example_body"));
        HttpResponse response = httpClient.execute(request);

        // then
        assertEquals(HttpStatusCode.OK_200.code(), response.getStatusLine().getStatusCode());
        assertThat(response.getHeaders("Set-Cookie").length, is(2));
        assertThat(response.getHeaders("Set-Cookie")[0].getValue(), is(setCookieOneHeader.getValue()));
        assertThat(response.getHeaders("Set-Cookie")[1].getValue(), is(setCookieTwoHeader.getValue()));
        assertEquals("an_example_body", new String(EntityUtils.toByteArray(response.getEntity()), UTF_8));

        // and
        getMockServerClient().verify(
            request()
                .withMethod("POST")
                .withPath("/test_headers_and_body")
                .withHeader(setCookieOneHeader.getName(), setCookieOneHeader.getValue())
                .withHeader(setCookieTwoHeader.getName(), setCookieTwoHeader.getValue())
                .withCookie("personalization_59996b985e24ce008d3df3bd07e27c1b", "")
                .withCookie("anonymous_59996b985e24ce008d3df3bd07e27c1b", "acgzEaAKOVR=mAY9yJhP7IrC9Am")
                .withBody("an_example_body"),
            exactly(1)
        );
    }

    @Test
    public void shouldForwardRequestsWithCharset() throws Exception {
        // given
        HttpClient httpClient = createHttpClient();
        String body = "" +
            "{" + NEW_LINE +
            "\"field\" : \"" + Base64.getEncoder().encodeToString(RandomUtils.nextBytes(50)) + "\"" + NEW_LINE +
            "}";

        // when
        HttpPost request = new HttpPost(
            new URIBuilder()
                .setScheme("http")
                .setHost("127.0.0.1")
                .setPort(getServerPort())
                .setPath(addContextToPath("test_headers_and_body"))
                .build()
        );
        request.setEntity(new StringEntity(body));
        request.setHeader("Content-Type", "application/encrypted;charset=utf-8");
        HttpResponse response = httpClient.execute(request);

        // then
        assertEquals(HttpStatusCode.OK_200.code(), response.getStatusLine().getStatusCode());
        assertEquals(body, new String(EntityUtils.toByteArray(response.getEntity()), UTF_8));

        // and
        getMockServerClient().verify(
            request()
                .withMethod("POST")
                .withPath("/test_headers_and_body")
                .withBody(body),
            exactly(1)
        );
    }

    @Test
    public void shouldForwardRequestsToUnknownPath() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", getProxyPort())) {
            // given
            OutputStream output = socket.getOutputStream();

            // when
            // - send GET request
            output.write(("" +
                "GET " + addContextToPath("not_found") + " HTTP/1.1\r" + NEW_LINE +
                "Host: 127.0.0.1:" + getServerPort() + "\r" + NEW_LINE +
                "Connection: close\r" + NEW_LINE +
                "\r" + NEW_LINE
            ).getBytes(StandardCharsets.UTF_8));
            output.flush();

            // then
            assertContains(IOStreamUtils.readHttpInputStreamToString(socket), "HTTP/1.1 404");
        }

        // and
        getMockServerClient().verify(
            request()
                .withMethod("GET")
                .withPath("/not_found"),
            exactly(1)
        );

    }

    @Test
    public void shouldForwardRequestsWithBlankQueryParameter() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", getProxyPort())) {
            // given
            getEchoServer().clear();
            OutputStream output = socket.getOutputStream();

            // when
            // - send GET request
            output.write(("" +
                "GET " + addContextToPath("/some/path?parameter") + " HTTP/1.1\r" + NEW_LINE +
                "Host: 127.0.0.1:" + getServerPort() + "\r" + NEW_LINE +
                "Connection: close\r" + NEW_LINE +
                "\r" + NEW_LINE
            ).getBytes(StandardCharsets.UTF_8));
            output.flush();

            // then
            assertContains(IOStreamUtils.readHttpInputStreamToString(socket), "HTTP/1.1 200");
            HttpRequest proxiedRequest = getEchoServer().getLastRequest();
            assertThat(proxiedRequest.getQueryStringParameters().getRawParameterString(), is("parameter"));
        }

        // and
        getMockServerClient().verify(
            request()
                .withMethod("GET")
                .withPath("/some/path"),
            exactly(1)
        );
    }

    @Test
    public void shouldForwardRequestsWithQueryParameterStartingWithExclamationMark() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", getProxyPort())) {
            // given
            getEchoServer().clear();
            OutputStream output = socket.getOutputStream();

            // when
            // - send GET request
            output.write(("" +
                "GET " + addContextToPath("/some/path?q=!in:chats%20is:unread") + " HTTP/1.1\r" + NEW_LINE +
                "Host: 127.0.0.1:" + getServerPort() + "\r" + NEW_LINE +
                "Connection: close\r" + NEW_LINE +
                "\r" + NEW_LINE
            ).getBytes(StandardCharsets.UTF_8));
            output.flush();

            // then
            assertContains(IOStreamUtils.readHttpInputStreamToString(socket), "HTTP/1.1 200");
            HttpRequest proxiedRequest = getEchoServer().getLastRequest();
            assertThat(proxiedRequest.getQueryStringParameters().getRawParameterString(), is("q=!in:chats%20is:unread"));
        }

        // and
        getMockServerClient().verify(
            request()
                .withMethod("GET")
                .withPath("/some/path"),
            exactly(1)
        );
    }

    @Test
    public void shouldReturnResponseByMatchingPathExactTimes() throws URISyntaxException, IOException {
        // when
        getMockServerClient()
            .when(
                request()
                    .withPath(calculatePath("some_path")), Times.exactly(1)
            )
            .respond(
                response()
                    .withBody("some_body")
            );

        // then
        HttpClient httpClient = createHttpClient();
        HttpGet request = new HttpGet(
            new URIBuilder()
                .setScheme("http")
                .setHost("127.0.0.1")
                .setPort(getServerPort())
                .setPath(addContextToPath("some_path"))
                .build()
        );
        HttpResponse response = httpClient.execute(request);
        assertThat(new String(EntityUtils.toByteArray(response.getEntity()), UTF_8), is("some_body"));
        assertThat(response.getStatusLine().getStatusCode(), is(OK_200.code()));

        httpClient = createHttpClient();
        request = new HttpGet(
            new URIBuilder()
                .setScheme("http")
                .setHost("127.0.0.1")
                .setPort(getServerPort())
                .setPath(addContextToPath("some_path"))
                .build()
        );
        response = httpClient.execute(request);
        assertThat(new String(EntityUtils.toByteArray(response.getEntity()), UTF_8), is(""));
    }

    @Test
    public void shouldReturnResponseByMatchingStringBody() throws IOException, URISyntaxException {
        // when
        getMockServerClient()
            .when(
                request()
                    .withBody(
                        exact("some_random_body")
                    ),
                Times.exactly(2)
            )
            .respond(
                response()
                    .withBody("some_string_body_response")
            );

        // then
        HttpClient httpClient = createHttpClient();
        HttpPost request = new HttpPost(
            new URIBuilder()
                .setScheme("http")
                .setHost("127.0.0.1")
                .setPort(getServerPort())
                .setPath(addContextToPath("some_path"))
                .build()
        );
        request.setEntity(new StringEntity("some_random_body"));
        HttpResponse response = httpClient.execute(request);
        assertThat(new String(EntityUtils.toByteArray(response.getEntity()), UTF_8), is("some_string_body_response"));
        assertThat(response.getStatusLine().getStatusCode(), is(OK_200.code()));
    }

    @Test
    public void shouldReturnResponseByMatchingBodyWithJsonSchema() throws URISyntaxException, IOException {
        // when
        getMockServerClient()
            .when(request().withBody(jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"description\": \"A product from Acme's catalog\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"description\": \"The unique identifier for a product\"," + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"description\": \"Name of the product\"," + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 1," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}")), Times.exactly(2)).respond(response().withBody("some_body"));

        // then
        HttpClient httpClient = createHttpClient();
        HttpPost request = new HttpPost(
            new URIBuilder()
                .setScheme("http")
                .setHost("127.0.0.1")
                .setPort(getServerPort())
                .setPath(addContextToPath("some_path"))
                .build()
        );
        request.setEntity(new StringEntity("{" + NEW_LINE +
            "    \"id\": 1," + NEW_LINE +
            "    \"name\": \"A green door\"," + NEW_LINE +
            "    \"price\": 12.50," + NEW_LINE +
            "    \"tags\": [\"home\", \"green\"]" + NEW_LINE +
            "}"));
        HttpResponse response = httpClient.execute(request);
        assertThat(new String(EntityUtils.toByteArray(response.getEntity()), UTF_8), is("some_body"));
        assertThat(response.getStatusLine().getStatusCode(), is(OK_200.code()));
    }

    @Test
    public void shouldVerifyRequests() throws Exception {
        // given
        HttpClient httpClient = createHttpClient();

        // when
        httpClient.execute(
            new HttpGet(
                new URIBuilder()
                    .setScheme("http")
                    .setHost("127.0.0.1")
                    .setPort(getServerPort())
                    .setPath(addContextToPath("test_headers_and_body"))
                    .build()
            )
        );
        httpClient.execute(
            new HttpGet(
                new URIBuilder()
                    .setScheme("http")
                    .setHost("127.0.0.1")
                    .setPort(getServerPort())
                    .setPath(addContextToPath("test_headers_only"))
                    .build()
            )
        );

        // then
        getMockServerClient()
            .verify(
                request()
                    .withMethod("GET")
                    .withPath("/test_headers_and_body")
            );
        getMockServerClient()
            .verify(
                request()
                    .withMethod("GET")
                    .withPath("/test_headers_and_body"),
                exactly(1)
            );
        getMockServerClient()
            .verify(
                request()
                    .withPath("/test_headers_.*"),
                atLeast(1)
            );
        getMockServerClient()
            .verify(
                request()
                    .withPath("/test_headers_.*"),
                exactly(2)
            );
        getMockServerClient()
            .verify(
                request()
                    .withPath("/other_path"),
                exactly(0)
            );
    }

    @Test
    public void shouldVerifyRequestsSequence() throws Exception {
        // given
        HttpClient httpClient = createHttpClient();

        // when
        httpClient.execute(
            new HttpGet(
                new URIBuilder()
                    .setScheme("http")
                    .setHost("127.0.0.1")
                    .setPort(getServerPort())
                    .setPath(addContextToPath("test_headers_and_body"))
                    .build()
            )
        );
        httpClient.execute(
            new HttpGet(
                new URIBuilder()
                    .setScheme("http")
                    .setHost("127.0.0.1")
                    .setPort(getServerPort())
                    .setPath(addContextToPath("test_headers_only"))
                    .build()
            )
        );

        // then
        getMockServerClient()
            .verify(
                request()
                    .withMethod("GET")
                    .withPath("/test_headers_and_body"),
                request()
                    .withMethod("GET")
                    .withPath("/test_headers_only")
            );
    }

    @Test
    public void shouldVerifyRequestAndResponsePair() throws Exception {
        // given
        getMockServerClient()
            .when(
                request()
                    .withPath(calculatePath("verify_response_test")),
                Times.exactly(1)
            )
            .respond(
                response()
                    .withStatusCode(OK_200.code())
                    .withBody("response_body")
            );

        // when
        HttpClient httpClient = createHttpClient();
        HttpGet request = new HttpGet(
            new URIBuilder()
                .setScheme("http")
                .setHost("127.0.0.1")
                .setPort(getServerPort())
                .setPath(addContextToPath("verify_response_test"))
                .build()
        );
        HttpResponse response = httpClient.execute(request);

        // then - verify the response was 200
        assertEquals(HttpStatusCode.OK_200.code(), response.getStatusLine().getStatusCode());

        // and - verify request-response pair with matching status code passes
        getMockServerClient()
            .verify(
                request()
                    .withPath("/verify_response_test"),
                response()
                    .withStatusCode(OK_200.code()),
                atLeast(1)
            );

        // and - verify request-response pair with wrong status code fails
        try {
            getMockServerClient()
                .verify(
                    request()
                        .withPath("/verify_response_test"),
                    response()
                        .withStatusCode(500),
                    atLeast(1)
                );
            fail("expected exception to be thrown");
        } catch (AssertionError ae) {
            assertThat(ae.getMessage(), startsWith("Response not found"));
        }
    }

    @Test
    public void shouldVerifyRequestAndResponsePairWithBodyMatcher() throws Exception {
        // given
        getMockServerClient()
            .when(
                request()
                    .withPath(calculatePath("verify_body_test")),
                Times.exactly(1)
            )
            .respond(
                response()
                    .withStatusCode(OK_200.code())
                    .withBody("expected_body_content")
            );

        // when
        HttpClient httpClient = createHttpClient();
        HttpGet request = new HttpGet(
            new URIBuilder()
                .setScheme("http")
                .setHost("127.0.0.1")
                .setPort(getServerPort())
                .setPath(addContextToPath("verify_body_test"))
                .build()
        );
        HttpResponse response = httpClient.execute(request);

        // then - verify the response was 200
        assertEquals(HttpStatusCode.OK_200.code(), response.getStatusLine().getStatusCode());

        // and - verify request-response pair with matching body passes
        getMockServerClient()
            .verify(
                request()
                    .withPath("/verify_body_test"),
                response()
                    .withBody("expected_body_content"),
                atLeast(1)
            );

        // and - verify request-response pair with non-matching body fails
        try {
            getMockServerClient()
                .verify(
                    request()
                        .withPath("/verify_body_test"),
                    response()
                        .withBody("wrong_body"),
                    atLeast(1)
                );
            fail("expected exception to be thrown");
        } catch (AssertionError ae) {
            assertThat(ae.getMessage(), startsWith("Response not found"));
        }
    }

    @Test
    public void shouldVerifyResponseOnly() throws Exception {
        // given
        getMockServerClient()
            .when(
                request()
                    .withPath(calculatePath("verify_response_only")),
                Times.exactly(1)
            )
            .respond(
                response()
                    .withStatusCode(OK_200.code())
                    .withBody("some_response_body")
            );

        // when
        HttpClient httpClient = createHttpClient();
        httpClient.execute(
            new HttpGet(
                new URIBuilder()
                    .setScheme("http")
                    .setHost("127.0.0.1")
                    .setPort(getServerPort())
                    .setPath(addContextToPath("verify_response_only"))
                    .build()
            )
        );

        // then - verify response-only with matching status code passes
        getMockServerClient()
            .verify(
                response()
                    .withStatusCode(OK_200.code()),
                atLeast(1)
            );

        // and - verify response-only with non-matching status code fails
        try {
            getMockServerClient()
                .verify(
                    response()
                        .withStatusCode(500),
                    atLeast(1)
                );
            fail("expected exception to be thrown");
        } catch (AssertionError ae) {
            assertThat(ae.getMessage(), startsWith("Response not found"));
        }
    }

    @Test
    public void shouldVerifyWithVerificationObject() throws Exception {
        // given
        getMockServerClient()
            .when(
                request()
                    .withPath(calculatePath("verify_object_test")),
                Times.exactly(1)
            )
            .respond(
                response()
                    .withStatusCode(OK_200.code())
                    .withBody("verification_body")
            );

        // when
        HttpClient httpClient = createHttpClient();
        HttpGet request = new HttpGet(
            new URIBuilder()
                .setScheme("http")
                .setHost("127.0.0.1")
                .setPort(getServerPort())
                .setPath(addContextToPath("verify_object_test"))
                .build()
        );
        HttpResponse response = httpClient.execute(request);

        // then - verify the response was 200
        assertEquals(HttpStatusCode.OK_200.code(), response.getStatusLine().getStatusCode());

        // and - verify using a pre-built Verification object with request and response
        getMockServerClient()
            .verify(
                verification()
                    .withRequest(request()
                        .withPath("/verify_object_test"))
                    .withResponse(response()
                        .withStatusCode(OK_200.code()))
                    .withTimes(atLeast(1))
            );
    }

    @Test
    public void shouldVerifySequenceWithResponses() throws Exception {
        // given
        getMockServerClient()
            .when(
                request()
                    .withPath(calculatePath("verify_seq_first")),
                Times.exactly(1)
            )
            .respond(
                response()
                    .withStatusCode(OK_200.code())
                    .withBody("first_response")
            );
        getMockServerClient()
            .when(
                request()
                    .withPath(calculatePath("verify_seq_second")),
                Times.exactly(1)
            )
            .respond(
                response()
                    .withStatusCode(201)
                    .withBody("second_response")
            );

        // when
        HttpClient httpClient = createHttpClient();
        httpClient.execute(
            new HttpGet(
                new URIBuilder()
                    .setScheme("http")
                    .setHost("127.0.0.1")
                    .setPort(getServerPort())
                    .setPath(addContextToPath("verify_seq_first"))
                    .build()
            )
        );
        httpClient.execute(
            new HttpGet(
                new URIBuilder()
                    .setScheme("http")
                    .setHost("127.0.0.1")
                    .setPort(getServerPort())
                    .setPath(addContextToPath("verify_seq_second"))
                    .build()
            )
        );

        // then - verify sequence with index-aligned responses passes
        getMockServerClient()
            .verify(
                verificationSequence()
                    .withRequests(
                        request().withPath("/verify_seq_first"),
                        request().withPath("/verify_seq_second")
                    )
                    .withResponses(
                        response().withStatusCode(OK_200.code()),
                        response().withStatusCode(201)
                    )
            );

        // and - verify sequence with wrong response fails
        try {
            getMockServerClient()
                .verify(
                    verificationSequence()
                        .withRequests(
                            request().withPath("/verify_seq_first"),
                            request().withPath("/verify_seq_second")
                        )
                        .withResponses(
                            response().withStatusCode(OK_200.code()),
                            response().withStatusCode(500)
                        )
                );
            fail("expected exception to be thrown");
        } catch (AssertionError ae) {
            assertThat(ae.getMessage(), startsWith("Response sequence not found"));
        }
    }

    @Test
    public void shouldVerifyHopByHopHeadersWithoutProxyCredentials() throws Exception {
        // given
        HttpClient httpClient = createHttpClient();

        // when
        HttpGet httpGet = new HttpGet(
            new URIBuilder()
                .setScheme("http")
                .setHost("127.0.0.1")
                .setPort(getServerPort())
                .setPath(addContextToPath("test_headers_only"))
                .build()
        );
        httpGet.addHeader("Proxy-Authorization", "some-random_value");
        httpGet.addHeader("keep-alive", "false");
        httpGet.addHeader("Authorization", "Bearer origin-header-value");
        httpGet.addHeader("Cookie", "session=origin-cookie-value");
        httpClient.execute(httpGet);

        // then
        getMockServerClient()
            .verify(
                request()
                    .withMethod("GET")
                    .withPath("/test_headers_only")
                    .withHeader("keep-alive", "false")
                    .withHeader("Authorization", "Bearer origin-header-value")
                    .withCookie("session", "origin-cookie-value")
            );
        getMockServerClient()
            .verify(
                request()
                    .withMethod("GET")
                    .withPath("/test_headers_only")
                    .withHeader("keep-alive"),
                exactly(1)
            );
        getMockServerClient()
            .verify(
                request()
                    .withMethod("GET")
                    .withPath("/test_headers_only")
                    .withHeader("Proxy-Authorization"),
                exactly(0)
            );
    }

    @Test
    public void shouldVerifyZeroRequests() throws Exception {
        // given
        HttpClient httpClient = createHttpClient();

        // when
        httpClient.execute(
            new HttpGet(
                new URIBuilder()
                    .setScheme("http")
                    .setHost("127.0.0.1")
                    .setPort(getServerPort())
                    .setPath(addContextToPath("test_headers_and_body"))
                    .build()
            )
        );

        // then
        try {
            getMockServerClient()
                .verify(
                    request()
                        .withPath("/test_headers_and_body"), exactly(0)
                );
            fail("expected exception to be thrown");
        } catch (AssertionError ae) {
            assertThat(ae.getMessage(), startsWith("Request found 1 time but should have been found exactly 0 times, expected:<{" + NEW_LINE +
                "  \"path\" : \"" + "/test_headers_and_body" + "\"" + NEW_LINE +
                "}> but was:<{"));
        }
    }

    @Test
    public void shouldVerifyNoRequestsExactly() throws Exception {
        // given
        HttpClient httpClient = createHttpClient();

        // when
        httpClient.execute(
            new HttpGet(
                new URIBuilder()
                    .setScheme("http")
                    .setHost("127.0.0.1")
                    .setPort(getServerPort())
                    .setPath(addContextToPath("test_headers_and_body"))
                    .build()
            )
        );

        // then
        try {
            getMockServerClient()
                .verify(
                    request()
                        .withPath("/other_path"),
                    exactly(1)
                );
            fail("expected exception to be thrown");
        } catch (AssertionError ae) {
            assertThat(ae.getMessage(), startsWith("Request not found exactly once, expected:<{" + NEW_LINE +
                "  \"path\" : \"" + "/other_path" + "\"" + NEW_LINE +
                "}> but was:<{"));
        }
    }

    @Test
    public void shouldVerifyNoRequestsTimesNotSpecified() throws Exception {
        // given
        HttpClient httpClient = createHttpClient();

        // when
        httpClient.execute(
            new HttpGet(
                new URIBuilder()
                    .setScheme("http")
                    .setHost("127.0.0.1")
                    .setPort(getServerPort())
                    .setPath(addContextToPath("test_headers_and_body"))
                    .build()
            )
        );

        // then
        try {
            getMockServerClient()
                .verify(
                    request()
                        .withPath("/other_path")
                );
            fail("expected exception to be thrown");
        } catch (AssertionError ae) {
            assertThat(ae.getMessage(), startsWith("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"" + "/other_path" + "\"" + NEW_LINE +
                "} ]> but was:<{"));
        }
    }

    @Test
    public void shouldVerifyNotEnoughRequests() throws Exception {
        // given
        HttpClient httpClient = createHttpClient();

        // when
        httpClient.execute(
            new HttpGet(
                new URIBuilder()
                    .setScheme("http")
                    .setHost("127.0.0.1")
                    .setPort(getServerPort())
                    .setPath(addContextToPath("test_headers_and_body"))
                    .build()
            )
        );
        httpClient.execute(
            new HttpGet(
                new URIBuilder()
                    .setScheme("http")
                    .setHost("127.0.0.1")
                    .setPort(getServerPort())
                    .setPath(addContextToPath("test_headers_and_body"))
                    .build()
            )
        );

        // then
        try {
            getMockServerClient()
                .verify(
                    request()
                        .withPath("/test_headers_and_body"),
                    atLeast(3)
                );
            fail("expected exception to be thrown");
        } catch (AssertionError ae) {
            assertThat(ae.getMessage(), startsWith("Request not found at least 3 times, expected:<{" + NEW_LINE +
                "  \"path\" : \"" + "/test_headers_and_body" + "\"" + NEW_LINE +
                "}> but was:<[ {"));
        }
    }

    @Test
    public void shouldVerifyRequestsSequenceNotFound() throws Exception {
        // given
        HttpClient httpClient = createHttpClient();

        // when
        httpClient.execute(
            new HttpGet(
                new URIBuilder()
                    .setScheme("http")
                    .setHost("127.0.0.1")
                    .setPort(getServerPort())
                    .setPath(addContextToPath("test_headers_and_body"))
                    .build()
            )
        );
        httpClient.execute(
            new HttpGet(
                new URIBuilder()
                    .setScheme("http")
                    .setHost("127.0.0.1")
                    .setPort(getServerPort())
                    .setPath(addContextToPath("test_headers_only"))
                    .build()
            )
        );

        // then
        try {
            getMockServerClient()
                .verify(
                    request()
                        .withMethod("GET")
                        .withPath("/test_headers_only"),
                    request()
                        .withMethod("GET")
                        .withPath("/test_headers_and_body")
                );
            fail("expected exception to be thrown");
        } catch (AssertionError ae) {
            assertThat(ae.getMessage(), startsWith("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"method\" : \"GET\"," + NEW_LINE +
                "  \"path\" : \"/test_headers_only\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"method\" : \"GET\"," + NEW_LINE +
                "  \"path\" : \"/test_headers_and_body\"" + NEW_LINE +
                "} ]> but was:<[ {"));
        }
    }

    @Test
    public void shouldClearRequests() throws Exception {
        // given
        HttpClient httpClient = createHttpClient();

        // when
        httpClient.execute(
            new HttpGet(
                new URIBuilder()
                    .setScheme("http")
                    .setHost("127.0.0.1")
                    .setPort(getServerPort())
                    .setPath(addContextToPath("test_headers_and_body"))
                    .build()
            )
        );
        httpClient.execute(
            new HttpGet(
                new URIBuilder()
                    .setScheme("http")
                    .setHost("127.0.0.1")
                    .setPort(getServerPort())
                    .setPath(addContextToPath("test_headers_only"))
                    .build()
            )
        );
        getMockServerClient()
            .clear(
                request()
                    .withMethod("GET")
                    .withPath("/test_headers_and_body")
            );

        // then
        getMockServerClient()
            .verify(
                request()
                    .withMethod("GET")
                    .withPath("/test_headers_and_body"),
                exactly(0)
            );
        getMockServerClient()
            .verify(
                request()
                    .withPath("/test_headers_.*"),
                exactly(1)
            );
    }

    @Test
    public void shouldResetRequests() throws Exception {
        // given
        HttpClient httpClient = createHttpClient();

        // when
        httpClient.execute(
            new HttpGet(
                new URIBuilder()
                    .setScheme("http")
                    .setHost("127.0.0.1")
                    .setPort(getServerPort())
                    .setPath(addContextToPath("test_headers_and_body"))
                    .build()
            )
        );
        httpClient.execute(
            new HttpGet(
                new URIBuilder()
                    .setScheme("http")
                    .setHost("127.0.0.1")
                    .setPort(getServerPort())
                    .setPath(addContextToPath("test_headers_only"))
                    .build()
            )
        );
        getMockServerClient().reset();

        // then
        getMockServerClient()
            .verify(
                request()
                    .withMethod("GET")
                    .withPath("/test_headers_and_body"),
                exactly(0)
            );
        getMockServerClient()
            .verify(
                request()
                    .withPath("/test_headers_.*"),
                atLeast(0)
            );
        getMockServerClient()
            .verify(
                request()
                    .withPath("/test_headers_.*"),
                exactly(0)
            );
    }

    @Test
    public void shouldReturnErrorForInvalidRequestToClear() throws Exception {
        // when
        org.mockserver.model.HttpResponse httpResponse = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false).sendRequest(
            request()
                .withMethod("PUT")
                .withHeader(HOST.toString(), "127.0.0.1:" + getProxyPort())
                .withPath(addContextToPath("mockserver/clear"))
                .withBody("{" + NEW_LINE +
                    "    \"path\" : 500," + NEW_LINE +
                    "    \"method\" : true," + NEW_LINE +
                    "    \"keepAlive\" : \"false\"" + NEW_LINE +
                    "  }")
        ).get(10, TimeUnit.SECONDS);

        // then
        assertThat(httpResponse.getStatusCode(), is(400));
        assertThat(httpResponse.getBodyAsString(), is("incorrect request matcher json format for:" + NEW_LINE +
            "" + NEW_LINE +
            "  {" + NEW_LINE +
            "      \"path\" : 500," + NEW_LINE +
            "      \"method\" : true," + NEW_LINE +
            "      \"keepAlive\" : \"false\"" + NEW_LINE +
            "    }" + NEW_LINE +
            "" + NEW_LINE +
            " schema validation errors:" + NEW_LINE +
            "" + NEW_LINE +
            "  10 errors:\n" +
            "   - $.anyOf: is missing but it is required\n" +
            "   - $.binaryData: is missing but it is required\n" +
            "   - $.dnsName: is missing but it is required\n" +
            "   - $.if: is missing but it is required\n" +
            "   - $.keepAlive: string found, boolean expected\n" +
            "   - $.method: boolean found, string expected\n" +
            "   - $.method: should be valid to one and only one schema, but 0 are valid\n" +
            "   - $.path: integer found, string expected\n" +
            "   - $.path: should be valid to one and only one schema, but 0 are valid\n" +
            "   - $.specUrlOrPayload: is missing, but is required, if specifying OpenAPI request matcher" + NEW_LINE +
            "  " + NEW_LINE +
            "  " + OPEN_API_SPECIFICATION_URL.replaceAll(NEW_LINE, NEW_LINE + "  ")));
    }

    @Test
    public void shouldReturnErrorForInvalidRequestToVerify() throws Exception {
        // when
        org.mockserver.model.HttpResponse httpResponse = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false).sendRequest(
            request()
                .withMethod("PUT")
                .withHeader(HOST.toString(), "127.0.0.1:" + getProxyPort())
                .withPath(addContextToPath("mockserver/verify"))
                .withBody("{" + NEW_LINE +
                    "    \"httpRequest\": {" + NEW_LINE +
                    "        \"path\": \"/simple\"" + NEW_LINE +
                    "    }, " + NEW_LINE +
                    "    \"times\": 1" + NEW_LINE +
                    "}")
        ).get(10, TimeUnit.SECONDS);

        // then
        assertThat(httpResponse.getStatusCode(), is(400));
        assertThat(httpResponse.getBodyAsString(), is("incorrect verification json format for:" + NEW_LINE +
            "" + NEW_LINE +
            "  {" + NEW_LINE +
            "      \"httpRequest\": {" + NEW_LINE +
            "          \"path\": \"/simple\"" + NEW_LINE +
            "      }, " + NEW_LINE +
            "      \"times\": 1" + NEW_LINE +
            "  }" + NEW_LINE +
            "" + NEW_LINE +
            " schema validation errors:" + NEW_LINE +
            "" + NEW_LINE +
            "  1 error:\n" +
            "   - $.times: integer found, object expected" + NEW_LINE +
            "  " + NEW_LINE +
            "  " + OPEN_API_SPECIFICATION_URL.replaceAll(NEW_LINE, NEW_LINE + "  ")));
    }

    @Test
    public void shouldReturnErrorForInvalidRequestToVerifySequence() throws Exception {
        // when
        org.mockserver.model.HttpResponse httpResponse = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false).sendRequest(
            request()
                .withMethod("PUT")
                .withHeader(HOST.toString(), "127.0.0.1:" + getProxyPort())
                .withPath(addContextToPath("mockserver/verifySequence"))
                .withBody("{" + NEW_LINE +
                    "    \"httpRequest\": {" + NEW_LINE +
                    "        \"path\": false" + NEW_LINE +
                    "    }," + NEW_LINE +
                    "    \"httpRequest\": {" + NEW_LINE +
                    "        \"path\": 10" + NEW_LINE +
                    "    }" + NEW_LINE +
                    "}")
        ).get(10, TimeUnit.SECONDS);

        // then
        assertThat(httpResponse.getStatusCode(), is(400));
        assertThat(httpResponse.getBodyAsString(), is("incorrect verification sequence json format for:" + NEW_LINE +
            "" + NEW_LINE +
            "  {" + NEW_LINE +
            "      \"httpRequest\": {" + NEW_LINE +
            "          \"path\": false" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"httpRequest\": {" + NEW_LINE +
            "          \"path\": 10" + NEW_LINE +
            "      }" + NEW_LINE +
            "  }" + NEW_LINE +
            "" + NEW_LINE +
            " schema validation errors:" + NEW_LINE +
            "" + NEW_LINE +
            "  6 errors:\n" +
            "   - $.expectationIds: is missing but it is required\n" +
            "   - $.httpRequest: is not defined in the schema and the schema does not allow additional properties\n" +
            "   - $.httpRequests: is missing, but is required, if specifying action of type Requests\n" +
            "   - $.httpResponses: is missing, but is required, if specifying action of type Responses\n" +
            "   - $: should be valid to one and only one schema, but 0 are valid\n" +
            "   - oneOf of the following must be specified [httpError, httpForward, httpForwardClassCallback, httpForwardObjectCallback, httpForwardTemplate, httpForwardValidateAction, httpForwardWithFallback, httpOverrideForwardedRequest, httpResponse, httpResponseClassCallback, httpResponseObjectCallback, httpResponseTemplate]" + NEW_LINE +
            "  " + NEW_LINE +
            "  " + OPEN_API_SPECIFICATION_URL.replaceAll(NEW_LINE, NEW_LINE + "  ")));
    }
}
