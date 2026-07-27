package org.mockserver.templates.engine.javascript;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matcher;
import org.junit.*;
import org.junit.rules.ExpectedException;
import org.mockserver.time.FixedTime;
import org.mockito.Mock;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.HttpRequestDTO;
import org.mockserver.serialization.model.HttpResponseDTO;
import org.mockserver.time.TimeService;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.TEMPLATE_GENERATED;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.model.Parameter.param;
import static org.mockserver.model.ParameterBody.params;
import static org.mockserver.model.XmlBody.xml;
import static org.slf4j.event.Level.INFO;

/**
 * @author jamesdbloom
 */
public class JavaScriptTemplateEngineTest {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();
    // Disable the JavaScript template execution timeout (0 = no watchdog, see PolyglotRunner) for the
    // shared-Configuration tests. These tests exercise invalid-JS handling and normal template execution,
    // NOT the timeout feature, so they must not depend on wall-clock time: under parallel CI load GraalJS
    // runs interpreter-only (no JIT) and a first parse/execute can exceed the 5000ms production default,
    // making them flaky. The production default is unchanged. The three timeout-specific tests
    // (shouldAbortLongRunningJavaScriptTemplateWhenExecutionTimeoutExceeded,
    // shouldEvaluateNormalJavaScriptTemplateWithinExecutionTimeout,
    // shouldNotAbortSlowTemplateWhenExecutionTimeoutDisabled) build their own Configuration and are unaffected.
    private static final Configuration configuration = configuration().javascriptTemplateExecutionTimeout(0L);

    @ClassRule
    public static final FixedTime fixedTime = new FixedTime();

    @Rule
    public final ExpectedException exception = ExpectedException.none();

    @Mock
    private MockServerLogger mockServerLogger;

    @Before
    public void setupTestFixture() {
        openMocks(this);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
    }

    public static void graalJsAvailable() {
        assumeThat("GraalVM Polyglot API available", JavaScriptTemplateEngine.isPolyglotAvailable(), is(true));
    }

    @Test
    public void shouldBindLoadIterationContextVariable() {
        // given a load-generation iteration context exposed to the JS scope as "iteration"
        graalJsAvailable();
        String template = "return {" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': 'idx=' + iteration.getIndex() + ',vu=' + iteration.getVuId()" + NEW_LINE +
            "};";
        HttpRequest request = request().withPath("/somePath").withMethod("GET");
        org.mockserver.load.IterationContext iteration =
            new org.mockserver.load.IterationContext(7, 2, 3, 1234, 42);

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration)
            .executeTemplate(template, request, iteration, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(response().withStatusCode(200).withBody("idx=7,vu=2")));
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptResponseTemplateReferencingPathGroups() {
        // given
        graalJsAvailable();
        String template = "return {" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': 'first=' + request.pathGroups[1] + ',second=' + request.pathGroups[2] + ',named=' + request.namedPathGroups.userId" + NEW_LINE +
            "};";
        // path groups are populated post-match by the matcher; set them directly here to drive the template
        HttpRequest request = request()
            .withPath("/users/42/orders/abc")
            .withMethod("GET")
            .withPathGroups(java.util.Arrays.asList("/users/42/orders/abc", "42", "abc"))
            .withNamedPathGroups(java.util.Collections.singletonMap("userId", "42"));

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("first=42,second=abc,named=42")
        ));
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptResponseTemplateWithECMA6() throws JsonProcessingException {
        // given
        graalJsAvailable();
        String template = "var customer = { name: \"Foo\" }" + NEW_LINE +
            "var card = { amount: 7, product: \"Bar\", unitprice: 42 }" + NEW_LINE +
            "return {" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': `Hello ${customer.name}, want to buy ${card.amount} ${card.product} for a total of ${card.amount * card.unitprice} bucks?`" + NEW_LINE +
            "};";
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("Hello Foo, want to buy 7 Bar for a total of 294 bucks?")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"Hello Foo, want to buy 7 Bar for a total of 294 bucks?\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    JavaScriptTemplateEngine.wrapTemplate(template),
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptResponseTemplateWithMethodPathAndHeader() throws JsonProcessingException {
        // given
        graalJsAvailable();
        String template = "return {" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': '{\\'method\\': \\'' + request.method + '\\', \\'path\\': \\'' + request.path + '\\', \\'headers\\': \\'' + request.headers.host[0] + '\\'}'" + NEW_LINE +
            "};";
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'method': 'POST', 'path': '/somePath', 'headers': 'mock-server.com'}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"{'method': 'POST', 'path': '/somePath', 'headers': 'mock-server.com'}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    JavaScriptTemplateEngine.wrapTemplate(template),
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptResponseTemplateWithParametersCookiesAndBody() throws JsonProcessingException {
        // given
        graalJsAvailable();
        String template = "return {" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': '{\\'queryStringParameters\\': \\'' + request.queryStringParameters.nameOne[0] + ',' + request.queryStringParameters.nameTwo[0] + ',' + request.queryStringParameters.nameTwo[1] + '\\'," +
            " \\'pathParameters\\': \\'' + request.pathParameters.nameOne[0] + ',' + request.pathParameters.nameTwo[0] + ',' + request.pathParameters.nameTwo[1] + '\\'," +
            " \\'cookies\\': \\'' + request.cookies.session + '\\'," +
            " \\'body\\': \\'' + request.body + '\\'}'" + NEW_LINE +
            "};";
        HttpRequest request = request()
            .withPath("/somePath")
            .withQueryStringParameter("nameOne", "queryValueOne")
            .withQueryStringParameter("nameTwo", "queryValueTwoOne", "queryValueTwoTwo")
            .withPathParameter("nameOne", "pathValueOne")
            .withPathParameter("nameTwo", "pathValueTwoOne", "pathValueTwoTwo")
            .withMethod("POST")
            .withCookie("session", "some_session_id")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody(
                    "{'queryStringParameters': 'queryValueOne,queryValueTwoOne,queryValueTwoTwo', 'pathParameters': 'pathValueOne,pathValueTwoOne,pathValueTwoTwo', 'cookies': 'some_session_id', 'body': 'some_body'}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"{'queryStringParameters': 'queryValueOne,queryValueTwoOne,queryValueTwoTwo', 'pathParameters': 'pathValueOne,pathValueTwoOne,pathValueTwoTwo', 'cookies': 'some_session_id', 'body': 'some_body'}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    JavaScriptTemplateEngine.wrapTemplate(template),
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptResponseTemplateWithDynamicValuesDateAndUUID() throws JsonProcessingException {
        boolean originalFixedUUID = UUIDService.fixedUUID();
        boolean originalFixedTime = TimeService.fixedTime();
        try {
            // given
        graalJsAvailable();
            UUIDService.fixedUUID(true);
            TimeService.fixedTime(true);
            String template = "return {" + NEW_LINE +
                "    'statusCode': 200," + NEW_LINE +
                "    'body': '{\\'date\\': \\'' + now + '\\', \\'date_epoch\\': \\'' + now_epoch + '\\', \\'date_iso_8601\\': \\'' + now_iso_8601 + '\\', \\'date_rfc_1123\\': \\'' + now_rfc_1123 + '\\', \\'uuids\\': [\\'' + uuid + '\\', \\'' + uuid + '\\'] }'" + NEW_LINE +
                "};";
            HttpRequest request = request()
                .withPath("/somePath")
                .withQueryStringParameter("nameOne", "valueOne")
                .withQueryStringParameter("nameTwo", "valueTwoOne", "valueTwoTwo")
                .withMethod("POST")
                .withCookie("session", "some_session_id")
                .withBody("some_body");

            // when
            HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

            // then
            assertThat(actualHttpResponse, is(
                response()
                    .withStatusCode(200)
                    .withBody("{'date': '" + TimeService.now() + "', 'date_epoch': '" + TimeService
                        .now()
                        .getEpochSecond() + "', 'date_iso_8601': '" + DateTimeFormatter.ISO_INSTANT.format(TimeService.now()) + "', 'date_rfc_1123': '" + DateTimeFormatter.RFC_1123_DATE_TIME.format(TimeService.offsetNow()) + "', 'uuids': ['" + UUIDService.getUUID() + "', '" + UUIDService.getUUID() + "'] }")
            ));
            verify(mockServerLogger).logEvent(
                new LogEntry()
                    .setType(TEMPLATE_GENERATED)
                    .setLogLevel(INFO)
                    .setHttpRequest(request)
                    .setMessageFormat("generated output:{}from template:{}for request:{}")
                    .setArguments(
                        OBJECT_MAPPER.readTree("" +
                                                   "{" + NEW_LINE +
                                                   "    'statusCode': 200," + NEW_LINE +
                                                   "    'body': \"{'date': '" + TimeService.now() + "', 'date_epoch': '" + TimeService
                            .now()
                            .getEpochSecond() + "', 'date_iso_8601': '" + DateTimeFormatter.ISO_INSTANT.format(TimeService.now()) + "', 'date_rfc_1123': '" + DateTimeFormatter.RFC_1123_DATE_TIME.format(TimeService.offsetNow()) + "', 'uuids': ['" + UUIDService.getUUID() + "', '" + UUIDService.getUUID() + "'] }\"" + NEW_LINE +
                                                   "}" + NEW_LINE),
                        JavaScriptTemplateEngine.wrapTemplate(template),
                        request
                    )
            );

        } finally {
            UUIDService.fixedUUID(originalFixedUUID);
            TimeService.fixedTime(originalFixedTime);
        }
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptResponseTemplateWithDynamicValuesRandom() {
        shouldPopulateRandomValue("rand_int", equalTo(1));
        shouldPopulateRandomValue("rand_int_10", allOf(greaterThan(0), lessThan(3)));
        shouldPopulateRandomValue("rand_int_100", allOf(greaterThan(0), lessThan(4)));
        shouldPopulateRandomValue("rand_bytes", allOf(greaterThan(20), lessThan(50)));
        shouldPopulateRandomValue("rand_bytes_16", allOf(greaterThan(20), lessThan(50)));
        shouldPopulateRandomValue("rand_bytes_32", allOf(greaterThan(40), lessThan(60)));
        shouldPopulateRandomValue("rand_bytes_64", allOf(greaterThan(80), lessThan(120)));
        shouldPopulateRandomValue("rand_bytes_128", allOf(greaterThan(160), lessThan(300)));
    }

    private void shouldPopulateRandomValue(String function, Matcher<Integer> matcher) {
        // given
        graalJsAvailable();
        String template = "return { 'body': " + function + " };";
        HttpRequest request = request()
            .withPath("/somePath")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse.getBodyAsString(), not(equalTo("")));
        assertThat(actualHttpResponse.getBodyAsString().length(), matcher);
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptResponseTemplateWithLoopOverValuesUsingThis() throws JsonProcessingException {
        // given
        graalJsAvailable();
        String template = "var headers = '';" + NEW_LINE +
            "for (header in request.headers) {" + NEW_LINE +
            "  headers += '\\'' + request.headers[header] + '\\', ';" + NEW_LINE +
            "}" + NEW_LINE +
            "return {" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': '{\\'headers\\': [' + headers.slice(0, -2) + ']}'" + NEW_LINE +
            "};";

        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withHeader(CONTENT_TYPE.toString(), "plain/text")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'headers': ['mock-server.com', 'plain/text']}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"{'headers': ['mock-server.com', 'plain/text']}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    JavaScriptTemplateEngine.wrapTemplate(template),
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptResponseTemplateWithIfElse() throws JsonProcessingException {
        // given
        graalJsAvailable();
        String template = "" +
            "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
            "    return {" + NEW_LINE +
            "        'statusCode': 200," + NEW_LINE +
            "        'body': JSON.stringify({name: 'value'})" + NEW_LINE +
            "    };" + NEW_LINE +
            "} else {" + NEW_LINE +
            "    return {" + NEW_LINE +
            "        'statusCode': 406," + NEW_LINE +
            "        'body': request.body" + NEW_LINE +
            "    };" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{\"name\":\"value\"}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"{\\\"name\\\":\\\"value\\\"}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    JavaScriptTemplateEngine.wrapTemplate(template),
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptForwardTemplateWithPathBodyParametersAndCookies() throws JsonProcessingException {
        // given
        graalJsAvailable();
        String template = "return {" + NEW_LINE +
            "    'path': request.path," + NEW_LINE +
            "    'body': '{\\'queryStringParameters\\': \\'' + request.queryStringParameters.nameOne[0] + ',' + request.queryStringParameters.nameTwo[0] + ',' + request.queryStringParameters.nameTwo[1] + '\\'," +
            " \\'pathParameters\\': \\'' + request.pathParameters.nameOne[0] + ',' + request.pathParameters.nameTwo[0] + ',' + request.pathParameters.nameTwo[1] + '\\'," +
            " \\'cookies\\': \\'' + request.cookies.session + '\\'," +
            " \\'body\\': \\'' + request.body + '\\'}'" + NEW_LINE +
            "};";
        HttpRequest request = request()
            .withPath("/somePath")
            .withQueryStringParameter("nameOne", "queryValueOne")
            .withQueryStringParameter("nameTwo", "queryValueTwoOne", "queryValueTwoTwo")
            .withPathParameter("nameOne", "pathValueOne")
            .withPathParameter("nameTwo", "pathValueTwoOne", "pathValueTwoTwo")
            .withMethod("POST")
            .withCookie("session", "some_session_id")
            .withBody("some_body");

        // when
        HttpRequest actualHttpRequest = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpRequestDTO.class);

        // then
        assertThat(actualHttpRequest, is(
            request()
                .withPath("/somePath")
                .withBody(
                    "{'queryStringParameters': 'queryValueOne,queryValueTwoOne,queryValueTwoTwo', 'pathParameters': 'pathValueOne,pathValueTwoOne,pathValueTwoTwo', 'cookies': 'some_session_id', 'body': 'some_body'}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'path' : \"/somePath\"," + NEW_LINE +
                                               "    'body': \"{'queryStringParameters': 'queryValueOne,queryValueTwoOne,queryValueTwoTwo', 'pathParameters': 'pathValueOne,pathValueTwoOne,pathValueTwoTwo', 'cookies': 'some_session_id', 'body': 'some_body'}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    JavaScriptTemplateEngine.wrapTemplate(template),
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptTemplateFirstExample() {
        // given
        graalJsAvailable();
        String template = "" +
            "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
            "    return {" + NEW_LINE +
            "        'statusCode': 200," + NEW_LINE +
            "        'body': JSON.stringify({name: 'value'})" + NEW_LINE +
            "    };" + NEW_LINE +
            "} else {" + NEW_LINE +
            "    return {" + NEW_LINE +
            "        'statusCode': 406," + NEW_LINE +
            "        'body': request.body" + NEW_LINE +
            "    };" + NEW_LINE +
            "}";

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                            .withPath("/somePath")
                                                                                                                            .withMethod("POST")
                                                                                                                            .withBody("some_body"),
                                                                                                                        HttpResponseDTO.class
        );

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{\"name\":\"value\"}")
        ));
    }

    @Test
    public void shouldRefuseJavaClassesWithoutDeniedClassWithoutDeniedText() {
        // The advisory case (GHSA-7pwj-xvc2-hfpc): an attacker who can register an expectation submits a
        // JavaScript template that reaches java.lang.Runtime and executes an OS command in the MockServer
        // process. With no restrictions configured — the out-of-the-box state — host classes must NOT
        // resolve, so getRuntime()/exec() never runs. Before the fix this same template reached exec() and
        // failed only because the program did not exist ("Cannot run program"), which is asserted against
        // below so a regression to the unrestricted default fails this test loudly.
        String originalJavaScriptAllowedClass = configuration.javascriptAllowedClasses();
        String originalJavaScriptRestrictedClass = configuration.javascriptDisallowedClasses();
        String originalJavaScriptRestrictedText = configuration.javascriptDisallowedText();

        try {
            // given
        graalJsAvailable();
            configuration.javascriptAllowedClasses(null);
            configuration.javascriptDisallowedClasses(null);
            configuration.javascriptDisallowedText(null);

            String template = "" +
                "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 200," + NEW_LINE +
                "        'body': java.lang.Runtime.getRuntime().exec(\"does_not_exist.sh\")" + NEW_LINE +
                "    };" + NEW_LINE +
                "} else {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 406," + NEW_LINE +
                "        'body': request.body" + NEW_LINE +
                "    };" + NEW_LINE +
                "}";

            // then
            Exception exception = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                                                               .withPath("/somePath")
                                                                                                                                                               .withMethod("POST")
                                                                                                                                                               .withBody("some_body"),
                                                                                                                                                           HttpResponseDTO.class
            ));
            assertThat(exception.getMessage(), containsString("Runtime.getRuntime is not a function"));
            assertThat(exception.getMessage(), not(containsString("Cannot run program")));

            // and - the explicit Java.type(...) host-class lookup form is refused too
            Exception javaTypeException = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(
                "return { 'statusCode': 200, 'body': Java.type('java.lang.Runtime').getRuntime().exec('does_not_exist.sh') };",
                request().withPath("/somePath").withMethod("POST").withBody("some_body"),
                HttpResponseDTO.class
            ));
            assertThat(javaTypeException.getMessage(), containsString("Access to host class java.lang.Runtime is not allowed or does not exist"));
            assertThat(javaTypeException.getMessage(), not(containsString("Cannot run program")));

            // and - so is the ProcessBuilder reach-through a deny-list could never enumerate
            Exception processBuilderException = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(
                "return { 'statusCode': 200, 'body': new java.lang.ProcessBuilder('does_not_exist.sh').start().toString() };",
                request().withPath("/somePath").withMethod("POST").withBody("some_body"),
                HttpResponseDTO.class
            ));
            assertThat(processBuilderException.getMessage(), containsString("Access to host class java.lang.ProcessBuilder is not allowed"));
            assertThat(processBuilderException.getMessage(), not(containsString("Cannot run program")));

            // and - a template that does not touch host classes still renders exactly as before
            HttpResponse ordinaryResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(
                "return { 'statusCode': 200, 'body': 'path is ' + request.path };",
                request().withPath("/somePath").withMethod("POST").withBody("some_body"),
                HttpResponseDTO.class
            );
            assertThat(ordinaryResponse, is(response().withStatusCode(200).withBody("path is /somePath")));

        } finally {
            configuration.javascriptAllowedClasses(originalJavaScriptAllowedClass);
            configuration.javascriptDisallowedClasses(originalJavaScriptRestrictedClass);
            configuration.javascriptDisallowedText(originalJavaScriptRestrictedText);
        }
    }

    @Test
    public void shouldAllowAllJavaClassesWhenJavaScriptAllowedClassesIsWildcard() {
        // The documented escape hatch for users who deliberately want the pre-7.4.1 unrestricted behaviour
        // and whose templates all come from a trusted source. Proving it actually restores host-class access
        // matters because it is the supported migration path for templates broken by the safe default.
        String originalJavaScriptAllowedClass = configuration.javascriptAllowedClasses();
        String originalJavaScriptRestrictedClass = configuration.javascriptDisallowedClasses();
        String originalJavaScriptRestrictedText = configuration.javascriptDisallowedText();

        try {
            // given
            graalJsAvailable();
            configuration.javascriptAllowedClasses("*");
            configuration.javascriptDisallowedClasses(null);
            configuration.javascriptDisallowedText(null);

            // then an arbitrary host class resolves again
            HttpResponse response = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(
                "return { 'statusCode': java.lang.Integer.parseInt('418'), 'body': 'unrestricted' };",
                request().withPath("/somePath").withMethod("POST").withBody("some_body"),
                HttpResponseDTO.class
            );
            assertThat(response, is(response().withStatusCode(418).withBody("unrestricted")));

            // and Runtime.exec is reached again (it fails only because the program does not exist), which is
            // exactly the behaviour the safe default suppresses
            Exception exception = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(
                "return { 'statusCode': 200, 'body': java.lang.Runtime.getRuntime().exec('does_not_exist.sh') };",
                request().withPath("/somePath").withMethod("POST").withBody("some_body"),
                HttpResponseDTO.class
            ));
            assertThat(exception.getMessage(), containsString("Cannot run program \"does_not_exist.sh\""));

        } finally {
            configuration.javascriptAllowedClasses(originalJavaScriptAllowedClass);
            configuration.javascriptDisallowedClasses(originalJavaScriptRestrictedClass);
            configuration.javascriptDisallowedText(originalJavaScriptRestrictedText);
        }
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptTemplateWithJavaStringsWithDeniedClass() {
        String originalJavaScriptRestrictedClass = configuration.javascriptDisallowedClasses();
        String originalJavaScriptRestrictedText = configuration.javascriptDisallowedText();

        try {
            // given
        graalJsAvailable();
            configuration.javascriptDisallowedClasses("java.lang.Runtime");
            configuration.javascriptDisallowedText(null);

            String template = "" +
                "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 200," + NEW_LINE +
                "        'body': java.lang.Runtime.getRuntime().exec(\"does_not_exist.sh\")" + NEW_LINE +
                "    };" + NEW_LINE +
                "} else {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 406," + NEW_LINE +
                "        'body': request.body" + NEW_LINE +
                "    };" + NEW_LINE +
                "}";

            // then
            Exception exception = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                                                               .withPath("/somePath")
                                                                                                                                                               .withMethod("POST")
                                                                                                                                                               .withBody("some_body"),
                                                                                                                                                           HttpResponseDTO.class
            ));
            // GraalJS 25.x reports denied class lookup via dot notation as a TypeError rather than ClassNotFoundException
            assertThat(exception.getMessage(), containsString("Runtime.getRuntime is not a function"));

        } finally {
            configuration.javascriptDisallowedClasses(originalJavaScriptRestrictedClass);
            configuration.javascriptDisallowedText(originalJavaScriptRestrictedText);
        }
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptTemplateWithJavaStringsWithDeniedClassList() {
        String originalJavaScriptRestrictedClass = configuration.javascriptDisallowedClasses();
        String originalJavaScriptRestrictedText = configuration.javascriptDisallowedText();

        try {
            // given
        graalJsAvailable();
            configuration.javascriptDisallowedClasses("java.lang.Runtime,java.lang.String");
            configuration.javascriptDisallowedText(null);

            String templateOne = "" +
                "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 200," + NEW_LINE +
                "        'body': java.lang.Runtime.getRuntime().exec(new java.lang.String(\"does_not_exist.sh\"))" + NEW_LINE +
                "    };" + NEW_LINE +
                "} else {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 406," + NEW_LINE +
                "        'body': request.body" + NEW_LINE +
                "    };" + NEW_LINE +
                "}";

            // then
            Exception exception = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(templateOne, request()
                                                                                                                                                               .withPath("/somePath")
                                                                                                                                                               .withMethod("POST")
                                                                                                                                                               .withBody("some_body"),
                                                                                                                                                           HttpResponseDTO.class
            ));
            // GraalJS 25.x reports denied class lookup via dot notation as a TypeError rather than ClassNotFoundException
            assertThat(exception.getMessage(), containsString("Runtime.getRuntime is not a function"));

            // given
        graalJsAvailable();
            configuration.javascriptDisallowedClasses("java.lang.String,java.lang.Runtime");
            configuration.javascriptDisallowedText(null);

            String templateTwo = "" +
                "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': java.lang.Integer.parseInt(new java.lang.String(\"200\"))," + NEW_LINE +
                "        'body': java.lang.Runtime.getRuntime().exec(new java.lang.String(\"does_not_exist.sh\"))" + NEW_LINE +
                "    };" + NEW_LINE +
                "} else {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 406," + NEW_LINE +
                "        'body': request.body" + NEW_LINE +
                "    };" + NEW_LINE +
                "}";

            // then
            exception = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(templateTwo, request()
                                                                                                                                                     .withPath("/somePath")
                                                                                                                                                     .withMethod("POST")
                                                                                                                                                     .withBody("some_body"),
                                                                                                                                                 HttpResponseDTO.class
            ));
            // GraalJS 25.x reports denied class via 'new' as: "Access to host class java.lang.String is not allowed or does not exist."
            assertThat(exception.getMessage(), containsString("Access to host class java.lang.String is not allowed or does not exist"));

        } finally {
            configuration.javascriptDisallowedClasses(originalJavaScriptRestrictedClass);
            configuration.javascriptDisallowedText(originalJavaScriptRestrictedText);
        }
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptTemplateWithJavaStringsWithDeniedText() {
        String originalJavaScriptRestrictedClass = configuration.javascriptDisallowedClasses();
        String originalJavaScriptRestrictedText = configuration.javascriptDisallowedText();

        try {
            // given
        graalJsAvailable();
            configuration.javascriptDisallowedClasses(null);
            configuration.javascriptDisallowedText("getRuntime().exec");

            String template = "" +
                "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 200," + NEW_LINE +
                "        'body': java.lang.Runtime.getRuntime().exec(\"does_not_exist.sh\")" + NEW_LINE +
                "    };" + NEW_LINE +
                "} else {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 406," + NEW_LINE +
                "        'body': request.body" + NEW_LINE +
                "    };" + NEW_LINE +
                "}";

            // then
            Exception exception = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                                                               .withPath("/somePath")
                                                                                                                                                               .withMethod("POST")
                                                                                                                                                               .withBody("some_body"),
                                                                                                                                                           HttpResponseDTO.class
            ));
            assertThat(exception.getMessage(), containsString("Found disallowed string \"getRuntime().exec\" in template:"));

        } finally {
            configuration.javascriptDisallowedClasses(originalJavaScriptRestrictedClass);
            configuration.javascriptDisallowedText(originalJavaScriptRestrictedText);
        }
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptTemplateWithJavaStringsWithDeniedTextList() {
        String originalJavaScriptRestrictedClass = configuration.javascriptDisallowedClasses();
        String originalJavaScriptRestrictedText = configuration.javascriptDisallowedText();

        try {
            // given
        graalJsAvailable();
            configuration.javascriptDisallowedClasses(null);
            configuration.javascriptDisallowedText("getRuntime().exec,does_not_exist.sh");

            String template = "" +
                "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 200," + NEW_LINE +
                "        'body': java.lang.Runtime.getRuntime().exec(\"does_not_exist.sh\")" + NEW_LINE +
                "    };" + NEW_LINE +
                "} else {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 406," + NEW_LINE +
                "        'body': request.body" + NEW_LINE +
                "    };" + NEW_LINE +
                "}";

            // then
            Exception exception = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                                                               .withPath("/somePath")
                                                                                                                                                               .withMethod("POST")
                                                                                                                                                               .withBody("some_body"),
                                                                                                                                                           HttpResponseDTO.class
            ));
            assertThat(exception.getMessage(), containsString("Found disallowed string \"getRuntime().exec\" in template:"));

            // given
        graalJsAvailable();
            configuration.javascriptDisallowedClasses(null);
            configuration.javascriptDisallowedText("does_not_exist.sh,getRuntime().exec");

            // then
            exception = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                                                     .withPath("/somePath")
                                                                                                                                                     .withMethod("POST")
                                                                                                                                                     .withBody("some_body"),
                                                                                                                                                 HttpResponseDTO.class
            ));
            assertThat(exception.getMessage(), containsString("Found disallowed string \"does_not_exist.sh\" in template:"));

        } finally {
            configuration.javascriptDisallowedClasses(originalJavaScriptRestrictedClass);
            configuration.javascriptDisallowedText(originalJavaScriptRestrictedText);
        }
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptTemplateWithJavaStringsWithDeniedClassAndDeniedText() {
        String originalJavaScriptRestrictedClass = configuration.javascriptDisallowedClasses();
        String originalJavaScriptRestrictedText = configuration.javascriptDisallowedText();

        try {
            // given
        graalJsAvailable();
            configuration.javascriptDisallowedClasses("java.lang.Runtime");
            configuration.javascriptDisallowedText("getRuntime().exec");

            String template = "" +
                "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 200," + NEW_LINE +
                "        'body': java.lang.Runtime.getRuntime().exec(\"does_not_exist.sh\")" + NEW_LINE +
                "    };" + NEW_LINE +
                "} else {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 406," + NEW_LINE +
                "        'body': request.body" + NEW_LINE +
                "    };" + NEW_LINE +
                "}";

            // then
            Exception exception = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                                                               .withPath("/somePath")
                                                                                                                                                               .withMethod("POST")
                                                                                                                                                               .withBody("some_body"),
                                                                                                                                                           HttpResponseDTO.class
            ));
            assertThat(exception.getMessage(), containsString("Found disallowed string \"getRuntime().exec\" in template:"));

        } finally {
            configuration.javascriptDisallowedClasses(originalJavaScriptRestrictedClass);
            configuration.javascriptDisallowedText(originalJavaScriptRestrictedText);
        }
    }

    @Test
    public void shouldAllowOnlyListedClassWhenJavaScriptAllowedClassesIsSet() {
        // The allow-list (javascriptAllowedClasses) is the recommended, safe-by-construction restriction
        // documented at JavaScriptTemplateEngine:79 as the real protection (a deny-list is bypassable via
        // ProcessBuilder / Class.forName reach-through). This verifies that when an allow-list is set, ONLY
        // listed classes resolve via host-class lookup and every other host class is refused at render time.
        String originalJavaScriptAllowedClass = configuration.javascriptAllowedClasses();
        String originalJavaScriptRestrictedClass = configuration.javascriptDisallowedClasses();
        String originalJavaScriptRestrictedText = configuration.javascriptDisallowedText();

        try {
            // given only java.lang.Integer is permitted
            graalJsAvailable();
            configuration.javascriptAllowedClasses("java.lang.Integer");
            configuration.javascriptDisallowedClasses(null);
            configuration.javascriptDisallowedText(null);

            // then the allowed class resolves and is invoked
            HttpResponse allowedResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(
                "return { 'statusCode': java.lang.Integer.parseInt('418'), 'body': 'allowed' };",
                request().withPath("/somePath").withMethod("POST").withBody("some_body"),
                HttpResponseDTO.class
            );
            assertThat(allowedResponse, is(response().withStatusCode(418).withBody("allowed")));

            // then java.lang.Runtime is NOT on the allow-list, so it is refused (resolves to undefined; the
            // dangerous getRuntime()/exec() never runs). Under a working allow-list this is "not a function";
            // if host-class lookup were unrestricted the message would instead be "Cannot run program".
            Exception runtimeException = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(
                "return { 'statusCode': 200, 'body': java.lang.Runtime.getRuntime().exec('does_not_exist.sh') };",
                request().withPath("/somePath").withMethod("POST").withBody("some_body"),
                HttpResponseDTO.class
            ));
            assertThat(runtimeException.getMessage(), containsString("Runtime.getRuntime is not a function"));
            assertThat(runtimeException.getMessage(), not(containsString("Cannot run program")));

            // then java.lang.ProcessBuilder (the deny-list reach-through the warning calls out) is also refused
            Exception processBuilderException = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(
                "return { 'statusCode': 200, 'body': new java.lang.ProcessBuilder('does_not_exist.sh').start().toString() };",
                request().withPath("/somePath").withMethod("POST").withBody("some_body"),
                HttpResponseDTO.class
            ));
            assertThat(processBuilderException.getMessage(), containsString("Access to host class java.lang.ProcessBuilder is not allowed"));
            assertThat(processBuilderException.getMessage(), not(containsString("Cannot run program")));

            // then java.lang.Class (the Class.forName reach-through the warning calls out) is also refused
            Exception classForNameException = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(
                "return { 'statusCode': 200, 'body': java.lang.Class.forName('java.lang.Runtime').getName() };",
                request().withPath("/somePath").withMethod("POST").withBody("some_body"),
                HttpResponseDTO.class
            ));
            assertThat(classForNameException.getMessage(), containsString("Class.forName is not a function"));

            // then the same denial holds for the explicit Java.type(...) host-class lookup form
            Exception javaTypeException = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(
                "return { 'statusCode': 200, 'body': Java.type('java.lang.Runtime').getRuntime().exec('does_not_exist.sh') };",
                request().withPath("/somePath").withMethod("POST").withBody("some_body"),
                HttpResponseDTO.class
            ));
            assertThat(javaTypeException.getMessage(), containsString("Access to host class java.lang.Runtime is not allowed or does not exist"));
            assertThat(javaTypeException.getMessage(), not(containsString("Cannot run program")));

        } finally {
            configuration.javascriptAllowedClasses(originalJavaScriptAllowedClass);
            configuration.javascriptDisallowedClasses(originalJavaScriptRestrictedClass);
            configuration.javascriptDisallowedText(originalJavaScriptRestrictedText);
        }
    }

    @Test
    public void shouldPreferJavaScriptAllowedClassesOverDisallowedClassesWhenBothAreSet() {
        // JavaScriptTemplateEngine:110-120 consults javascriptAllowedClasses first and returns its verdict
        // immediately, so when both restrictions are set the deny-list is never reached. That precedence is
        // advertised to users in jekyll-www.mock-server.com/mock_server/_includes/template_restriction_configuration.html
        // and is what makes the allow-list a usable hardening step on an instance that already sets a deny-list.
        String originalJavaScriptAllowedClass = configuration.javascriptAllowedClasses();
        String originalJavaScriptRestrictedClass = configuration.javascriptDisallowedClasses();
        String originalJavaScriptRestrictedText = configuration.javascriptDisallowedText();

        try {
            // given the same class is both allowed and denied
            graalJsAvailable();
            configuration.javascriptAllowedClasses("java.lang.Integer");
            configuration.javascriptDisallowedClasses("java.lang.Integer");
            configuration.javascriptDisallowedText(null);

            // then the allow-list wins and the class still resolves
            HttpResponse allowedResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(
                "return { 'statusCode': java.lang.Integer.parseInt('418'), 'body': 'allowed' };",
                request().withPath("/somePath").withMethod("POST").withBody("some_body"),
                HttpResponseDTO.class
            );
            assertThat(allowedResponse, is(response().withStatusCode(418).withBody("allowed")));

            // then the allow-list remains exclusive - a class on NEITHER list is still refused, which proves the
            // deny-list was not the operative gate (were it, java.lang.ProcessBuilder would have resolved)
            Exception processBuilderException = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(
                "return { 'statusCode': 200, 'body': new java.lang.ProcessBuilder('does_not_exist.sh').start().toString() };",
                request().withPath("/somePath").withMethod("POST").withBody("some_body"),
                HttpResponseDTO.class
            ));
            assertThat(processBuilderException.getMessage(), containsString("Access to host class java.lang.ProcessBuilder is not allowed"));
            assertThat(processBuilderException.getMessage(), not(containsString("Cannot run program")));

        } finally {
            configuration.javascriptAllowedClasses(originalJavaScriptAllowedClass);
            configuration.javascriptDisallowedClasses(originalJavaScriptRestrictedClass);
            configuration.javascriptDisallowedText(originalJavaScriptRestrictedText);
        }
    }

    @Test
    public void shouldAllowPackagePrefixWhenJavaScriptAllowedClassesEndsWithWildcardOrDot() {
        // JavaScriptTemplateEngine:126-141 matches an entry ending in ".*" (or a bare trailing ".") as a
        // package prefix rather than an exact class name. Both forms are documented for users, and a
        // prefix bug in an ALLOW-list is over-permissive, so both are covered here.
        String originalJavaScriptAllowedClass = configuration.javascriptAllowedClasses();
        String originalJavaScriptRestrictedClass = configuration.javascriptDisallowedClasses();
        String originalJavaScriptRestrictedText = configuration.javascriptDisallowedText();

        try {
            // given a "java.lang.*" package prefix
            graalJsAvailable();
            configuration.javascriptDisallowedClasses(null);
            configuration.javascriptDisallowedText(null);
            configuration.javascriptAllowedClasses("java.lang.*");

            // then any class in the permitted package resolves, without being listed by name
            HttpResponse wildcardResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(
                "return { 'statusCode': java.lang.Integer.parseInt('418'), 'body': 'allowed' };",
                request().withPath("/somePath").withMethod("POST").withBody("some_body"),
                HttpResponseDTO.class
            );
            assertThat(wildcardResponse, is(response().withStatusCode(418).withBody("allowed")));

            // then a class outside the permitted package is still refused
            Exception wildcardRandomException = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(
                "return { 'statusCode': 200, 'body': Java.type('java.util.Random').toString() };",
                request().withPath("/somePath").withMethod("POST").withBody("some_body"),
                HttpResponseDTO.class
            ));
            assertThat(wildcardRandomException.getMessage(), containsString("Access to host class java.util.Random is not allowed or does not exist"));

            // given the bare trailing-dot form of the same prefix
            configuration.javascriptAllowedClasses("java.lang.");

            // then it behaves identically
            HttpResponse trailingDotResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(
                "return { 'statusCode': java.lang.Integer.parseInt('418'), 'body': 'allowed' };",
                request().withPath("/somePath").withMethod("POST").withBody("some_body"),
                HttpResponseDTO.class
            );
            assertThat(trailingDotResponse, is(response().withStatusCode(418).withBody("allowed")));

            Exception trailingDotRandomException = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(
                "return { 'statusCode': 200, 'body': Java.type('java.util.Random').toString() };",
                request().withPath("/somePath").withMethod("POST").withBody("some_body"),
                HttpResponseDTO.class
            ));
            assertThat(trailingDotRandomException.getMessage(), containsString("Access to host class java.util.Random is not allowed or does not exist"));

        } finally {
            configuration.javascriptAllowedClasses(originalJavaScriptAllowedClass);
            configuration.javascriptDisallowedClasses(originalJavaScriptRestrictedClass);
            configuration.javascriptDisallowedText(originalJavaScriptRestrictedText);
        }
    }

    @Test
    public void shouldNotMatchBeyondTrailingDotWhenJavaScriptAllowedClassesUsesPackagePrefix() {
        // The prefix must stop at the trailing dot: "a.b.c.*" permits package a.b.c but NOT the sibling
        // package a.b.c2 whose name merely starts with the same characters. Were the "*" and the "." both
        // stripped, an allow-list would silently widen to every package sharing the prefix - an
        // over-permissive failure, which is the only kind that matters for an allow-list. Netty's
        // io.netty.handler.codec.http / io.netty.handler.codec.http2 pair gives a real classpath example.
        String originalJavaScriptAllowedClass = configuration.javascriptAllowedClasses();
        String originalJavaScriptRestrictedClass = configuration.javascriptDisallowedClasses();
        String originalJavaScriptRestrictedText = configuration.javascriptDisallowedText();

        try {
            // given
            graalJsAvailable();
            configuration.javascriptAllowedClasses("io.netty.handler.codec.http.*");
            configuration.javascriptDisallowedClasses(null);
            configuration.javascriptDisallowedText(null);

            // then a class in the permitted package resolves
            HttpResponse allowedResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(
                "return { 'statusCode': 200, 'body': Java.type('io.netty.handler.codec.http.HttpMethod').GET.name() };",
                request().withPath("/somePath").withMethod("POST").withBody("some_body"),
                HttpResponseDTO.class
            );
            assertThat(allowedResponse, is(response().withStatusCode(200).withBody("GET")));

            // then the sibling package sharing the leading characters is NOT covered by the prefix
            Exception http2Exception = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(
                "return { 'statusCode': 200, 'body': Java.type('io.netty.handler.codec.http2.Http2Error').toString() };",
                request().withPath("/somePath").withMethod("POST").withBody("some_body"),
                HttpResponseDTO.class
            ));
            assertThat(http2Exception.getMessage(), containsString("Access to host class io.netty.handler.codec.http2.Http2Error is not allowed or does not exist"));

        } finally {
            configuration.javascriptAllowedClasses(originalJavaScriptAllowedClass);
            configuration.javascriptDisallowedClasses(originalJavaScriptRestrictedClass);
            configuration.javascriptDisallowedText(originalJavaScriptRestrictedText);
        }
    }

    @Test
    public void shouldHandleHttpRequestsWithSlowJavaScriptTemplate() {
        // given
        graalJsAvailable();
        String template = "" +
            "for (var i = 0; i < 1000000; i++) {" + NEW_LINE +
            "  i * i;" + NEW_LINE +
            "}" + NEW_LINE +
            "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
            "    return {" + NEW_LINE +
            "        'statusCode': 200," + NEW_LINE +
            "        'body': JSON.stringify({name: 'value'})" + NEW_LINE +
            "    };" + NEW_LINE +
            "} else {" + NEW_LINE +
            "    return {" + NEW_LINE +
            "        'statusCode': 406," + NEW_LINE +
            "        'body': request.body" + NEW_LINE +
            "    };" + NEW_LINE +
            "}";

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                            .withPath("/somePath")
                                                                                                                            .withMethod("POST")
                                                                                                                            .withBody("some_body"),
                                                                                                                        HttpResponseDTO.class
        );

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{\"name\":\"value\"}")
        ));
    }

    @Test
    public void shouldHandleMultipleHttpRequestsInParallel() throws InterruptedException {
        // given
        graalJsAvailable();
        final String template = "" +
            "for (var i = 0; i < 1000000; i++) {" + NEW_LINE +
            "  i * i;" + NEW_LINE +
            "}" + NEW_LINE +
            "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
            "    return {" + NEW_LINE +
            "        'statusCode': 200," + NEW_LINE +
            "        'body': JSON.stringify({name: 'value'})" + NEW_LINE +
            "    };" + NEW_LINE +
            "} else {" + NEW_LINE +
            "    return {" + NEW_LINE +
            "        'statusCode': 406," + NEW_LINE +
            "        'body': request.body" + NEW_LINE +
            "    };" + NEW_LINE +
            "}";

        // when
        final JavaScriptTemplateEngine javascriptTemplateEngine = new JavaScriptTemplateEngine(mockServerLogger, configuration);

        // then
        final HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withBody("some_body");
        Thread[] threads = new Thread[3];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Scheduler.SchedulerThreadFactory("MockServer Test " + this.getClass().getSimpleName()).newThread(() -> assertThat(javascriptTemplateEngine.executeTemplate(template, request,
                                                                                                                                                                                        HttpResponseDTO.class
            ), is(
                response()
                    .withStatusCode(200)
                    .withBody("{\"name\":\"value\"}")
            )));
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
    }

    @Test
    public void shouldAbortLongRunningJavaScriptTemplateWhenExecutionTimeoutExceeded() {
        // given a deliberately long-running (effectively unbounded) template and a short timeout
        graalJsAvailable();
        // per-instance Configuration (NOT global ConfigurationProperties) so this test does not
        // mutate global state and can run in the parallel Surefire phase
        Configuration shortTimeoutConfiguration = configuration().javascriptTemplateExecutionTimeout(250L);
        String template = "" +
            "while (true) {" + NEW_LINE +
            "  Math.sqrt(Math.random());" + NEW_LINE +
            "}" + NEW_LINE +
            "return { 'statusCode': 200, 'body': 'never reached' };";
        HttpRequest request = request().withPath("/somePath").withMethod("POST").withBody("some_body");

        // when
        long startMillis = System.currentTimeMillis();
        JavaScriptTemplateTimeoutException thrown = assertThrows(
            JavaScriptTemplateTimeoutException.class,
            () -> new JavaScriptTemplateEngine(mockServerLogger, shortTimeoutConfiguration)
                .executeTemplate(template, request, HttpResponseDTO.class)
        );
        long elapsedMillis = System.currentTimeMillis() - startMillis;

        // then the evaluation is aborted promptly (not hanging) with a clear timeout error
        assertThat(thrown.getMessage(), containsString("exceeded the configured timeout of 250ms"));
        // generous upper bound: the 250ms cap plus cancellation overhead, must NOT hang
        assertThat("aborted within a bounded time (was " + elapsedMillis + "ms)", elapsedMillis < 10_000L, is(true));
    }

    @Test
    public void shouldEvaluateNormalJavaScriptTemplateWithinExecutionTimeout() {
        // given a normal template and a generous timeout
        graalJsAvailable();
        Configuration timeoutConfiguration = configuration().javascriptTemplateExecutionTimeout(5_000L);
        String template = "" +
            "return {" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': JSON.stringify({name: 'value'})" + NEW_LINE +
            "};";

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, timeoutConfiguration)
            .executeTemplate(template, request().withPath("/somePath").withMethod("POST"), HttpResponseDTO.class);

        // then a normal template still evaluates fine under a normal timeout
        assertThat(actualHttpResponse, is(
            response().withStatusCode(200).withBody("{\"name\":\"value\"}")
        ));
    }

    @Test
    public void shouldNotAbortSlowTemplateWhenExecutionTimeoutDisabled() {
        // given the sentinel (0) disables the timeout, restoring unbounded behaviour
        graalJsAvailable();
        Configuration disabledConfiguration = configuration().javascriptTemplateExecutionTimeout(0L);
        // a finite but non-trivial loop that completes well within test time; with the timeout
        // disabled it must NOT be cancelled even though no cap is enforced
        String template = "" +
            "for (var i = 0; i < 5000000; i++) {" + NEW_LINE +
            "  i * i;" + NEW_LINE +
            "}" + NEW_LINE +
            "return { 'statusCode': 200, 'body': JSON.stringify({name: 'value'}) };";

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, disabledConfiguration)
            .executeTemplate(template, request().withPath("/somePath").withMethod("POST"), HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response().withStatusCode(200).withBody("{\"name\":\"value\"}")
        ));
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptTemplateSecondExample() {
        // given
        graalJsAvailable();
        String template = "" +
            "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
            "    return {" + NEW_LINE +
            "        'statusCode': 200," + NEW_LINE +
            "        'body': JSON.stringify({name: 'value'})" + NEW_LINE +
            "    };" + NEW_LINE +
            "} else {" + NEW_LINE +
            "    return {" + NEW_LINE +
            "        'statusCode': 406," + NEW_LINE +
            "        'body': request.body" + NEW_LINE +
            "    };" + NEW_LINE +
            "}";

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                            .withPath("/someOtherPath")
                                                                                                                            .withBody("some_body"),
                                                                                                                        HttpResponseDTO.class
        );

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(406)
                .withBody("some_body")
        ));
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptForwardTemplateWithMethodPathAndHeader() {
        // given
        graalJsAvailable();
        String template = "return {" + NEW_LINE +
            "    'path': '/somePath'," + NEW_LINE +
            "    'body': '{\\'method\\': \\'' + request.method + '\\', \\'path\\': \\'' + request.path + '\\', \\'headers\\': \\'' + request.headers.host[0] + '\\'}'" + NEW_LINE +
            "};";
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withBody("some_body");

        // when
        HttpRequest actualHttpRequest = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpRequestDTO.class);

        // then
        assertThat(actualHttpRequest, is(
            request()
                .withPath("/somePath")
                .withBody("{'method': 'POST', 'path': '/somePath', 'headers': 'mock-server.com'}")
        ));
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptUsingBodyAsStringForRequestWithStringBody() {
        // given
        graalJsAvailable();
        String template = "" +
            "return { statusCode: 200, headers: { Date: [ \"Fri Jan 28 2022 22:02:46 GMT+0000 (GMT)\" ] }, body: JSON.stringify({is_active: JSON.parse(request.body).is_active, id: \"1234\", name: \"taras\"}) };";


        // when
        HttpResponse actualHttpRequest = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                           .withPath("/someOtherPath")
                                                                                                                           .withBody("{\"is_active\":\"active_value\",\"id\":\"1234\",\"name\":\"taras\"}"),
                                                                                                                       HttpResponseDTO.class
        );

        // then
        assertThat(actualHttpRequest, is(
            response()
                .withStatusCode(200)
                .withHeader("Date", "Fri Jan 28 2022 22:02:46 GMT+0000 (GMT)")
                .withBody("{\"is_active\":\"active_value\",\"id\":\"1234\",\"name\":\"taras\"}")
        ));
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptUsingBodyAsStringForRequestWithJsonBody() {
        // given
        graalJsAvailable();
        String template = "" +
            "return { statusCode: 200, headers: { Date: [ \"Fri Jan 28 2022 22:02:46 GMT+0000 (GMT)\" ] }, body: JSON.stringify({is_active: JSON.parse(request.body).is_active, id: \"1234\", name: \"taras\"}) };";


        // when
        HttpResponse actualHttpRequest = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                           .withPath("/someOtherPath")
                                                                                                                           .withBody(json("{\"is_active\":\"active_value\",\"id\":\"1234\",\"name\":\"taras\"}")),
                                                                                                                       HttpResponseDTO.class
        );

        // then
        assertThat(actualHttpRequest, is(
            response()
                .withStatusCode(200)
                .withHeader("Date", "Fri Jan 28 2022 22:02:46 GMT+0000 (GMT)")
                .withBody("{\"is_active\":\"active_value\",\"id\":\"1234\",\"name\":\"taras\"}")
        ));
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptUsingBodyAsStringForRequestWithXmlBody() {
        // given
        graalJsAvailable();
        String template = "" +
            "return { statusCode: 200, headers: { Date: [ \"Fri Jan 28 2022 22:02:46 GMT+0000 (GMT)\" ] }, body: JSON.stringify({is_active: request.body, id: \"1234\", name: \"taras\"}) };";


        // when
        HttpResponse actualHttpRequest = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                           .withPath("/someOtherPath")
                                                                                                                           .withBody(xml("<root><is_active>active_value</is_active></root>")),
                                                                                                                       HttpResponseDTO.class
        );

        // then
        assertThat(actualHttpRequest, is(
            response()
                .withStatusCode(200)
                .withHeader("Date", "Fri Jan 28 2022 22:02:46 GMT+0000 (GMT)")
                .withBody("{\"is_active\":\"<root><is_active>active_value</is_active></root>\",\"id\":\"1234\",\"name\":\"taras\"}")
        ));
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptUsingBodyAsStringForRequestWithParameterBody() {
        // given
        graalJsAvailable();
        String template = "" +
            "return { statusCode: 200, headers: { Date: [ \"Fri Jan 28 2022 22:02:46 GMT+0000 (GMT)\" ] }, body: JSON.stringify({is_active: JSON.parse(request.body), id: \"1234\", name: \"taras\"}) };";


        // when
        HttpResponse actualHttpRequest = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                           .withPath("/someOtherPath")
                                                                                                                           .withBody(params(param("one", "valueOne"), param("two", "valueTwoOne", "valueTwoTwo"))),
                                                                                                                       HttpResponseDTO.class
        );

        // then
        assertThat(actualHttpRequest, is(
            response()
                .withStatusCode(200)
                .withHeader("Date", "Fri Jan 28 2022 22:02:46 GMT+0000 (GMT)")
                .withBody("{\"is_active\":{\"one\":[\"valueOne\"],\"two\":[\"valueTwoOne\",\"valueTwoTwo\"]},\"id\":\"1234\",\"name\":\"taras\"}")
        ));
    }

    @Test
    public void shouldHandleInvalidJavaScript() {
        // given
        graalJsAvailable();
        String template = "{" + NEW_LINE +
            "    'path' : \"/somePath\"," + NEW_LINE +
            "    'queryStringParameters' : [ {" + NEW_LINE +
            "        'name' : \"queryParameter\"," + NEW_LINE +
            "        'values' : request.queryStringParameters['queryParameter']" + NEW_LINE +
            "    } ]," + NEW_LINE +
            "    'headers' : [ {" + NEW_LINE +
            "        'name' : \"Host\"," + NEW_LINE +
            "        'values' : [ \"localhost:1090\" ]" + NEW_LINE +
            "    } ]," + NEW_LINE +
            "    'body': \"{'name': 'value'}\"" + NEW_LINE +
            "};";
        // when
        RuntimeException runtimeException = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                                                                         .withPath("/someOtherPath")
                                                                                                                                                                         .withQueryStringParameter("queryParameter", "someValue")
                                                                                                                                                                         .withBody("some_body"),
                                                                                                                                                                     HttpRequestDTO.class
        ));

        // then - GraalJS error message differs from Nashorn but still reports a syntax error
        assertThat(runtimeException.getMessage(), allOf(
            containsString("Exception:"),
            containsString("transforming template:"),
            containsString("for request:")
        ));
    }

    @Test
    public void shouldHandleResponseTemplateWithJavaScript() {
        graalJsAvailable();
        String template = "return {" + NEW_LINE +
            "    'statusCode': response.statusCode," + NEW_LINE +
            "    'body': 'path=' + request.path + ',originalBody=' + response.body" + NEW_LINE +
            "};";
        HttpRequest request = request()
            .withPath("/testPath")
            .withMethod("GET");
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody("hello");

        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, httpResponse, HttpResponseDTO.class);

        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("path=/testPath,originalBody=hello")
        ));
    }

    @Test
    public void shouldRefuseClassReachThroughFromBoundHostObjects() {
        // The class filter gates Java.type(...) / the java.* globals, but the guest context is built with
        // HostAccess.ALL and REAL host objects are bound into it ($faker and the other built-in helpers).
        // If a template could walk from one of those objects to java.lang.Class — faker.getClass().forName(...)
        // or .getClassLoader().loadClass(...) — it would reach Runtime without ever asking the class filter,
        // and the default-deny sandbox would be worthless. This pins that hole shut.
        String originalJavaScriptAllowedClass = configuration.javascriptAllowedClasses();
        String originalJavaScriptRestrictedClass = configuration.javascriptDisallowedClasses();
        String originalJavaScriptRestrictedText = configuration.javascriptDisallowedText();

        try {
            // given - the out-of-the-box configuration
            graalJsAvailable();
            configuration.javascriptAllowedClasses(null);
            configuration.javascriptDisallowedClasses(null);
            configuration.javascriptDisallowedText(null);

            // Every case walks from a BOUND HOST OBJECT. $faker and $strings are real host objects; note
            // `request` is deliberately NOT used here because inside the template it is a JSON.parse'd plain
            // JavaScript object, so a walk from it would pass vacuously and prove nothing.
            String[] reachThroughTemplates = {
                "return { 'statusCode': 200, 'body': '' + faker.getClass().forName('java.lang.Runtime') };",
                "return { 'statusCode': 200, 'body': '' + faker.getClass().getClassLoader().loadClass('java.lang.Runtime') };",
                "return { 'statusCode': 200, 'body': '' + faker.class.classLoader.loadClass('java.lang.Runtime') };",
                "return { 'statusCode': 200, 'body': '' + strings.getClass().getClassLoader().loadClass('java.lang.Runtime') };",
            };

            for (String template : reachThroughTemplates) {
                Throwable failure = null;
                String body = null;
                try {
                    body = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(
                        template,
                        request().withPath("/somePath").withMethod("POST").withBody("some_body"),
                        HttpResponseDTO.class
                    ).getBodyAsString();
                } catch (Throwable throwable) {
                    failure = throwable;
                }

                // then - however it fails, it must NOT have produced a handle on java.lang.Runtime
                if (failure != null) {
                    assertThat("reach-through must not execute a command: " + template,
                        failure.getMessage(), not(containsString("Cannot run program")));
                } else {
                    assertThat("reach-through resolved java.lang.Runtime: " + template,
                        body, not(containsString("java.lang.Runtime")));
                }
            }

        } finally {
            configuration.javascriptAllowedClasses(originalJavaScriptAllowedClass);
            configuration.javascriptDisallowedClasses(originalJavaScriptRestrictedClass);
            configuration.javascriptDisallowedText(originalJavaScriptRestrictedText);
        }
    }

    @Test
    public void shouldExposeFaker() {
        // given
        graalJsAvailable();
        String template = "return {" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': '{\"firstName\": \"' + faker.name().firstName() + '\", \"email\": \"' + faker.internet().emailAddress() + '\"}'" + NEW_LINE +
            "};";
        HttpRequest request = request()
            .withPath("/somePath")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse.getBodyAsString(), not(emptyString()));
        assertThat(actualHttpResponse.getBodyAsString(), not(containsString("faker")));
    }

    @Test
    public void shouldProduceDeterministicFakerOutputWhenTemplateFakerSeedSet() {
        // given
        graalJsAvailable();
        String template = "return {" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': '{\"firstName\": \"' + faker.name().firstName() + '\", \"email\": \"' + faker.internet().emailAddress() + '\"}'" + NEW_LINE +
            "};";
        HttpRequest request = request().withPath("/somePath");

        // when — two engines with the SAME seed, one with a DIFFERENT seed
        String bodyA = new JavaScriptTemplateEngine(mockServerLogger, configuration().javascriptTemplateExecutionTimeout(0L).templateFakerSeed(4242L)).executeTemplate(template, request, HttpResponseDTO.class).getBodyAsString();
        String bodyB = new JavaScriptTemplateEngine(mockServerLogger, configuration().javascriptTemplateExecutionTimeout(0L).templateFakerSeed(4242L)).executeTemplate(template, request, HttpResponseDTO.class).getBodyAsString();
        String bodyC = new JavaScriptTemplateEngine(mockServerLogger, configuration().javascriptTemplateExecutionTimeout(0L).templateFakerSeed(9999L)).executeTemplate(template, request, HttpResponseDTO.class).getBodyAsString();

        // then — same seed reproduces identical faker output; a different seed differs
        assertThat("same seed must reproduce identical faker output", bodyA, is(bodyB));
        assertThat("faker must have produced a value", bodyA, not(containsString("\"firstName\": \"\"")));
        assertThat("different seed should produce different faker output", bodyC, not(is(bodyA)));
    }

    @Test
    public void shouldRestrictGlobalContextMultipleHttpRequestsInParallel() throws InterruptedException, ExecutionException {
        // given
        graalJsAvailable();
        final String template = ""
            + "var resbody = \"ok\"; " + NEW_LINE
            + "if (request.path.match(\".*1$\")) { " + NEW_LINE
            + "    resbody = \"nok\"; " + NEW_LINE
            + "}; " + NEW_LINE
            + "resp = { " + NEW_LINE
            + "    'statusCode': 200, "
            + "    'body': resbody" + NEW_LINE
            + "}; " + NEW_LINE
            + "return resp;";

        // when
        final JavaScriptTemplateEngine javascriptTemplateEngine = new JavaScriptTemplateEngine(mockServerLogger, configuration);

        // then
        final HttpRequest ok = request()
            .withPath("/somePath/0")
            .withMethod("POST")
            .withBody("some_body");

        final HttpRequest nok = request()
            .withPath("/somePath/1")
            .withMethod("POST")
            .withBody("another_body");

        ExecutorService newFixedThreadPool = Executors.newFixedThreadPool(30);

        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            futures.add(newFixedThreadPool.submit(() -> {
                assertThat(javascriptTemplateEngine.executeTemplate(template, ok,
                                                                    HttpResponseDTO.class
                ), is(
                    response()
                        .withStatusCode(200)
                        .withBody("ok")
                ));
                return true;
            }));

            futures.add(newFixedThreadPool.submit(() -> {
                assertThat(javascriptTemplateEngine.executeTemplate(template, nok,
                                                                    HttpResponseDTO.class
                ), is(
                    response()
                        .withStatusCode(200)
                        .withBody("nok")
                ));
                return true;
            }));

        }

        for (Future<Boolean> future : futures) {
            future.get();
        }
        newFixedThreadPool.shutdown();
    }

    @Test
    public void shouldHandleJavaScriptResponseTemplateWithJsonPath() {
        // given
        graalJsAvailable();
        String template = "return {" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': '{\\'title\\': \\'' + jsonPath('$.store.book[0].title') + '\\', \\'bikeColor\\': \\'' + jsonPath('$.store.bicycle.color') + '\\'}'" + NEW_LINE +
            "};";
        HttpRequest request = request()
            .withPath("/somePath")
            .withBody(json("{" + NEW_LINE +
                "    \"store\": {" + NEW_LINE +
                "        \"book\": [" + NEW_LINE +
                "            { \"title\": \"Sayings of the Century\", \"price\": 18.95 }" + NEW_LINE +
                "        ]," + NEW_LINE +
                "        \"bicycle\": { \"color\": \"red\", \"price\": 19.95 }" + NEW_LINE +
                "    }" + NEW_LINE +
                "}"));

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'title': 'Sayings of the Century', 'bikeColor': 'red'}")
        ));
    }

    @Test
    public void shouldHandleJavaScriptResponseTemplateWithXPath() {
        // given
        graalJsAvailable();
        String template = "return {" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': '{\\'key\\': \\'' + xPath('/element/key') + '\\', \\'value\\': \\'' + xPath('/element/value') + '\\'}'" + NEW_LINE +
            "};";
        HttpRequest request = request()
            .withPath("/somePath")
            .withBody("<element><key>some_key</key><value>some_value</value></element>");

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'key': 'some_key', 'value': 'some_value'}")
        ));
    }

    @Test
    public void shouldHandleJavaScriptResponseTemplateWithJsonPathForMissingPath() {
        // given
        graalJsAvailable();
        String template = "return {" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': '{\\'missing\\': \\'' + jsonPath('$.store.does.not.exist') + '\\'}'" + NEW_LINE +
            "};";
        HttpRequest request = request()
            .withPath("/somePath")
            .withBody(json("{ \"store\": { \"bicycle\": { \"color\": \"red\" } } }"));

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then - missing path mirrors Mustache: empty value, no exception
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'missing': ''}")
        ));
    }

    @Test
    public void shouldFailWithClearErrorWhenJavaScriptTemplateUsedButGraalJsUnavailable() {
        // given - GraalJS forced unavailable via the test-visible constructor. This simulates the shipped
        // netty jar-with-dependencies / Docker image, where the optional org.graalvm.polyglot dependency is
        // absent (POLYGLOT_AVAILABLE=false), even though GraalJS may be on this test classpath.
        String template = "return { 'statusCode': 200, 'body': 'hello' };";
        HttpRequest request = request().withPath("/somePath").withMethod("GET");
        JavaScriptTemplateEngine engine = new JavaScriptTemplateEngine(mockServerLogger, configuration, false);

        // when
        RuntimeException exception = assertThrows(RuntimeException.class, () ->
            engine.executeTemplate(template, request, HttpResponseDTO.class));

        // then - fails loudly with an actionable message instead of silently returning null (degraded response)
        assertThat(exception.getMessage(), allOf(
            containsString("JavaScript response templates require the GraalJS engine"),
            containsString("not on the classpath"),
            containsString("org.graalvm.polyglot:js"),
            containsString("Velocity or Mustache")
        ));
        // and the failure is logged at ERROR level
        verify(mockServerLogger).logEvent(new LogEntry()
            .setLogLevel(Level.ERROR)
            .setHttpRequest(request)
            .setMessageFormat("JavaScript response templates require the GraalJS engine, which is not on the classpath. Add the org.graalvm.polyglot:js (or js-community) dependency, or use the Velocity or Mustache template engine."));
    }

    @Test
    public void shouldRenderNormallyWhenGraalJsAvailable() {
        // given - GraalJS present: behaviour is unchanged (control for the fail-loud test above)
        graalJsAvailable();
        String template = "return { 'statusCode': 200, 'body': 'hello' };";
        HttpRequest request = request().withPath("/somePath").withMethod("GET");

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration, true)
            .executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(response().withStatusCode(200).withBody("hello")));
    }

}
