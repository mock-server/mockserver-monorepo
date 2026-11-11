package org.mockserver.proxyservlet;

import com.google.common.collect.ImmutableSet;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.MediaType;
import org.mockserver.model.Protocol;
import org.mockserver.model.RetrieveType;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ExpectationSerializer;
import org.mockserver.serialization.HttpRequestSerializer;
import org.mockserver.serialization.PortBindingSerializer;
import org.mockserver.servlet.responsewriter.ServletResponseWriter;
import org.mockserver.time.EpochService;
import org.slf4j.event.Level;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LOG_DATE_FORMAT;
import static org.mockserver.log.model.LogEntry.LogMessageType.*;
import static org.mockserver.log.model.LogEntryMessages.RECEIVED_REQUEST_MESSAGE_FORMAT;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.PortBinding.portBinding;

/**
 * @author jamesdbloom
 */
public class ProxyServletTest {

    private final HttpRequestSerializer httpRequestSerializer = new HttpRequestSerializer(new MockServerLogger());
    private final ExpectationSerializer expectationSerializer = new ExpectationSerializer(new MockServerLogger());
    private final ExpectationSerializer expectationSerializerWithDefaultFields = new ExpectationSerializer(new MockServerLogger(), true);
    private final PortBindingSerializer portBindingSerializer = new PortBindingSerializer(new MockServerLogger());

    private HttpState httpStateHandler;
    private HttpState servletHttpState;
    private HttpActionHandler mockActionHandler;

    @InjectMocks
    private ProxyServlet proxyServlet;

    private MockHttpServletResponse response;

    @BeforeClass
    public static void fixTime() {
        EpochService.fixedTimeGlobally(true);
    }

    @Before
    public void setupFixture() {
        mockActionHandler = mock(HttpActionHandler.class);
        Scheduler scheduler = mock(Scheduler.class);

        httpStateHandler = spy(new HttpState(configuration(), new MockServerLogger(), scheduler));
        response = new MockHttpServletResponse();
        proxyServlet = new ProxyServlet();
        // the servlet's own state, which openMocks replaces with httpStateHandler
        servletHttpState = (HttpState) ReflectionTestUtils.getField(proxyServlet, "httpStateHandler");


        openMocks(this);
    }

    @After
    public void destroyServlet() {
        // shuts the servlet's scheduler and event loops, and stops the state it holds: httpStateHandler
        proxyServlet.destroy();
        servletHttpState.stop();
    }

    private MockHttpServletRequest buildHttpServletRequest(String method, String requestURI, String body) {
        MockHttpServletRequest expectationRetrieveRequestsRequest = new MockHttpServletRequest(method, requestURI);
        expectationRetrieveRequestsRequest.setContent(body.getBytes());
        return expectationRetrieveRequestsRequest;
    }

    private void assertResponse(MockHttpServletResponse response, int responseStatusCode, String responseBody) {
        assertThat(response.getStatus(), is(responseStatusCode));
        assertThat(new String(response.getContentAsByteArray(), UTF_8), is(responseBody));
    }

    @Test
    public void shouldRetrieveRequests() {
        // given
        httpStateHandler.log(
            new LogEntry()
                .setHttpRequest(request("request_one"))
                .setType(RECEIVED_REQUEST)
        );
        // when
        proxyServlet
            .service(
                buildHttpServletRequest(
                    "PUT",
                    "/mockserver/retrieve",
                    httpRequestSerializer.serialize(request("request_one"))
                ),
                response
            );

        // then
        assertResponse(response, 200, httpRequestSerializer.serialize(Collections.singletonList(
            request("request_one")
        )));
    }

    @Test
    public void shouldClear() {
        // given
        httpStateHandler.add(new Expectation(request("request_one")).thenRespond(response("response_one")));
        httpStateHandler.log(
            new LogEntry()
                .setHttpRequest(request("request_one"))
                .setType(EXPECTATION_MATCHED)
        );
        MockHttpServletRequest clearRequest = buildHttpServletRequest(
            "PUT",
            "/mockserver/clear",
            httpRequestSerializer.serialize(request("request_one"))
        );

        // when
        proxyServlet.service(clearRequest, response);

        // then
        assertResponse(response, 200, "");
        assertThat(httpStateHandler.firstMatchingExpectation(request("request_one")), is(nullValue()));
        assertThat(httpStateHandler.retrieve(request("/retrieve")
            .withMethod("PUT")
            .withBody(
                httpRequestSerializer.serialize(request("request_one"))
            )), is(response().withBody("[]", MediaType.JSON_UTF_8).withStatusCode(200)));
    }

    @Test
    public void shouldReturnStatus() {
        // given
        MockHttpServletRequest statusRequest = buildHttpServletRequest(
            "PUT",
            "/status",
            ""
        );

        // when
        proxyServlet.service(statusRequest, response);

        // then
        assertResponse(response, 200, portBindingSerializer.serialize(
            portBinding(80)
        ));
    }

    @Test
    public void shouldReturnStatusOnCustomPath() {
        String originalStatusPath = ConfigurationProperties.livenessHttpGetPath();
        try {
            // given
            ConfigurationProperties.livenessHttpGetPath("/livenessProbe");
            MockHttpServletRequest statusRequest = buildHttpServletRequest(
                "GET",
                "/livenessProbe",
                ""
            );

            // when
            proxyServlet.service(statusRequest, response);

            // then
            assertResponse(response, 200, portBindingSerializer.serialize(
                portBinding(80)
            ));
        } finally {
            ConfigurationProperties.livenessHttpGetPath(originalStatusPath);
        }
    }

    @Test
    public void shouldBindNewPorts() {
        // given
        MockHttpServletRequest statusRequest = buildHttpServletRequest(
            "PUT",
            "/bind", portBindingSerializer.serialize(
                portBinding(1090, 1090)
            ));

        // when
        proxyServlet.service(statusRequest, response);

        // then
        assertResponse(response, 501, "");
    }

    @Test
    public void shouldStop() {
        // given
        MockHttpServletRequest statusRequest = buildHttpServletRequest(
            "PUT",
            "/stop",
            ""
        );

        // when
        proxyServlet.service(statusRequest, response);

        // then
        assertResponse(response, 501, "");
    }

    @Test
    public void shouldRetrieveRecordedExpectations() {
        // given
        httpStateHandler.log(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("request_one"))
                .setHttpResponse(response("response_one"))
                .setExpectation(new Expectation(request("request_one"), Times.once(), TimeToLive.unlimited(), 0).withId("key_one").thenRespond(response("response_one")))
        );
        // when
        MockHttpServletRequest expectationRetrieveExpectationsRequest = buildHttpServletRequest(
            "PUT",
            "/mockserver/retrieve",
            httpRequestSerializer.serialize(request("request_one"))
        );
        expectationRetrieveExpectationsRequest.setQueryString("type=" + RetrieveType.RECORDED_EXPECTATIONS.name());
        proxyServlet.service(expectationRetrieveExpectationsRequest, response);

        // then
        assertResponse(response, 200, expectationSerializerWithDefaultFields.serialize(Collections.singletonList(
            new Expectation(request("request_one"), Times.once(), TimeToLive.unlimited(), 0).withId("key_one").thenRespond(response("response_one"))
        )));
    }

    @Test
    public void shouldRetrieveLogMessages() {
        Level originalLevel = ConfigurationProperties.logLevel();
        try {
            // given
            ConfigurationProperties.logLevel("INFO");
            httpStateHandler.log(
                new LogEntry()
                    .setType(RECEIVED_REQUEST)
                    .setLogLevel(Level.INFO)
                    .setHttpRequest(request("request_one"))
                    .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                    .setArguments(request("request_one"))
            );

            // when
            MockHttpServletRequest retrieveLogRequest = buildHttpServletRequest(
                "PUT",
                "/mockserver/retrieve",
                httpRequestSerializer.serialize(request("request_one"))
            );
            retrieveLogRequest.setQueryString("type=" + RetrieveType.LOGS.name());
            proxyServlet.service(retrieveLogRequest, response);

            // then
            assertThat(response.getStatus(), is(200));
            assertThat(
                new String(response.getContentAsByteArray(), UTF_8),
                is(endsWith(LOG_DATE_FORMAT.format(new Date(EpochService.currentTimeMillis())) + " - received request:" + NEW_LINE +
                    NEW_LINE +
                    "  {" + NEW_LINE +
                    "    \"path\" : \"request_one\"" + NEW_LINE +
                    "  }" + NEW_LINE +
                    NEW_LINE))
            );
        } finally {
            ConfigurationProperties.logLevel(originalLevel.name());
        }
    }

    @Test
    public void shouldNotProxyRequestsForItselfOnDefaultPort() {
        // given
        HttpRequest request = request("request_one").withHeader("Host", "localhost").withMethod("GET");
        MockHttpServletRequest httpServletRequest = buildHttpServletRequest("GET", "request_one", "");
        httpServletRequest.addHeader("Host", "localhost");
        httpServletRequest.setLocalAddr("local_address");
        httpServletRequest.setLocalPort(80);

        // when
        proxyServlet.service(httpServletRequest, response);

        // then
        verify(mockActionHandler).processAction(
            eq(
                request
                    .withSecure(false)
                    .withLocalAddress("local_address:80")
                    .withRemoteAddress("localhost:80")
                    .withKeepAlive(true)
                    .withProtocol(Protocol.HTTP_1_1)
            ),
            any(ServletResponseWriter.class),
            isNull(),
            eq(ImmutableSet.of(
                "local_address",
                "localhost",
                "127.0.0.1"
            )),
            eq(false),
            eq(true));
    }

    @Test
    public void shouldProxyRequestsOnNonDefaultPort() {
        // given
        HttpRequest request = request("request_one").withHeader("Host", "localhost").withMethod("GET");
        MockHttpServletRequest httpServletRequest = buildHttpServletRequest("GET", "request_one", "");
        httpServletRequest.addHeader("Host", "localhost");
        httpServletRequest.setLocalAddr("local_address");
        httpServletRequest.setLocalPort(666);

        // when
        proxyServlet.service(httpServletRequest, response);

        // then
        verify(mockActionHandler).processAction(
            eq(
                request
                    .withSecure(false)
                    .withLocalAddress("local_address:666")
                    .withRemoteAddress("localhost:80")
                    .withKeepAlive(true)
                    .withProtocol(Protocol.HTTP_1_1)
            ),
            any(ServletResponseWriter.class),
            isNull(),
            eq(ImmutableSet.of(
                "local_address:666",
                "localhost:666",
                "127.0.0.1:666"
            )),
            eq(true),
            eq(true));
    }

    @Test
    public void shouldNotProxySecureRequestsForItselfOnDefaultPort() {
        // given
        HttpRequest request = request("request_one").withHeader("Host", "localhost").withMethod("GET");
        MockHttpServletRequest httpServletRequest = buildHttpServletRequest("GET", "request_one", "");
        httpServletRequest.addHeader("Host", "localhost");
        httpServletRequest.setSecure(true);
        httpServletRequest.setLocalAddr("local_address");
        httpServletRequest.setLocalPort(443);

        // when
        proxyServlet.service(httpServletRequest, response);

        // then
        verify(mockActionHandler).processAction(
            eq(
                request
                    .withSecure(true)
                    .withLocalAddress("local_address:443")
                    .withRemoteAddress("localhost:80")
                    .withKeepAlive(true)
                    .withProtocol(Protocol.HTTP_1_1)
            ),
            any(ServletResponseWriter.class),
            isNull(),
            eq(ImmutableSet.of(
                "local_address",
                "localhost",
                "127.0.0.1"
            )),
            eq(false),
            eq(true));
    }

    @Test
    public void shouldProxySecureRequestsOnNonDefaultPort() {
        // given
        HttpRequest request = request("request_one").withHeader("Host", "localhost").withMethod("GET");
        MockHttpServletRequest httpServletRequest = buildHttpServletRequest("GET", "request_one", "");
        httpServletRequest.addHeader("Host", "localhost");
        httpServletRequest.setSecure(true);
        httpServletRequest.setLocalAddr("local_address");
        httpServletRequest.setLocalPort(666);

        // when
        proxyServlet.service(httpServletRequest, response);

        // then
        verify(mockActionHandler).processAction(
            eq(
                request
                    .withSecure(true)
                    .withLocalAddress("local_address:666")
                    .withRemoteAddress("localhost:80")
                    .withKeepAlive(true)
                    .withProtocol(Protocol.HTTP_1_1)
            ),
            any(ServletResponseWriter.class),
            isNull(),
            eq(ImmutableSet.of(
                "local_address:666",
                "localhost:666",
                "127.0.0.1:666"
            )),
            eq(true),
            eq(true));
    }

}
