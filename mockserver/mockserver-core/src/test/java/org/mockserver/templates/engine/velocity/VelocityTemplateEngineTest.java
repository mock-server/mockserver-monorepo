package org.mockserver.templates.engine.velocity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matcher;
import org.junit.*;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.mockserver.time.FixedTime;
import org.mockito.Mock;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.HttpRequestDTO;
import org.mockserver.serialization.model.HttpResponseDTO;
import org.mockserver.time.TimeService;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThrows;
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
import static org.slf4j.event.Level.INFO;

/**
 * @author jamesdbloom
 */
public class VelocityTemplateEngineTest {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();
    private static final Configuration configuration = configuration();

    @ClassRule
    public static final FixedTime fixedTime = new FixedTime();

    @Rule
    public final ExpectedException exception = ExpectedException.none();

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Mock
    private MockServerLogger mockServerLogger;

    @Before
    public void setupTestFixture() {
        openMocks(this);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
    }

    @Test
    public void shouldRenderLoadIterationContextVariable() {
        // given a load-generation iteration context injected under "iteration"
        HttpRequest request = request().withPath("/item");
        org.mockserver.load.IterationContext iteration =
            new org.mockserver.load.IterationContext(7, 2, 3, 1234, 42);

        // when
        String rendered = new VelocityTemplateEngine(mockServerLogger, configuration)
            .renderTemplate("/item/$iteration.index/vu/$iteration.vuId/count/$iteration.count", request, iteration);

        // then the iteration bean getters resolve
        assertThat(rendered, org.hamcrest.Matchers.is("/item/7/vu/2/count/42"));
    }

    @Test
    public void shouldRenderWithoutIterationContextWhenNull() {
        // when iteration is null the render is identical to the no-iteration overload
        HttpRequest request = request().withPath("/item").withMethod("GET");
        String rendered = new VelocityTemplateEngine(mockServerLogger, configuration)
            .renderTemplate("method=$request.method", request, null);
        assertThat(rendered, org.hamcrest.Matchers.is("method=GET"));
    }

    @Test
    public void shouldHandleHttpRequestsWithVelocityResponseTemplateWithMethodPathAndHeader() throws JsonProcessingException {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'method': '$request.method', 'path': '$request.path', 'headers': '$request.headers.host[0]'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

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
                .setArguments(OBJECT_MAPPER.readTree("" +
                        "{" + NEW_LINE +
                        "    'statusCode': 200," + NEW_LINE +
                        "    'body': \"{'method': 'POST', 'path': '/somePath', 'headers': 'mock-server.com'}\"" + NEW_LINE +
                        "    }" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithVelocityResponseTemplateWithParametersCookiesAndBody() throws JsonProcessingException {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'queryStringParameters': '$request.queryStringParameters.nameOne[0],$request.queryStringParameters.nameTwo[0],$request.queryStringParameters.nameTwo[1]'," +
            " 'pathParameters': '$request.pathParameters.nameOne[0],$request.pathParameters.nameTwo[0],$request.pathParameters.nameTwo[1]'," +
            " 'cookies': '$request.cookies.session'," +
            " 'body': '$request.body'}\"" + NEW_LINE +
            "}";
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
        HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'queryStringParameters': 'queryValueOne,queryValueTwoOne,queryValueTwoTwo', 'pathParameters': 'pathValueOne,pathValueTwoOne,pathValueTwoTwo', 'cookies': 'some_session_id', 'body': 'some_body'}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(OBJECT_MAPPER.readTree("" +
                        "{" + NEW_LINE +
                        "    'statusCode': 200," + NEW_LINE +
                        "    'body': \"{'queryStringParameters': 'queryValueOne,queryValueTwoOne,queryValueTwoTwo', 'pathParameters': 'pathValueOne,pathValueTwoOne,pathValueTwoTwo', 'cookies': 'some_session_id', 'body': 'some_body'}\"" + NEW_LINE +
                        "}" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithVelocityResponseTemplateReferencingPathGroups() {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'whole': '$request.pathGroups[0]', 'first': '$request.pathGroups[1]', 'second': '$request.pathGroups[2]', 'named': '$request.namedPathGroups.userId'}\"" + NEW_LINE +
            "}";
        // path groups are populated post-match by the matcher; set them directly here to drive the template
        HttpRequest request = request()
            .withPath("/users/42/orders/abc")
            .withMethod("GET")
            .withPathGroups(java.util.Arrays.asList("/users/42/orders/abc", "42", "abc"))
            .withNamedPathGroups(java.util.Collections.singletonMap("userId", "42"));

        // when
        HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'whole': '/users/42/orders/abc', 'first': '42', 'second': 'abc', 'named': '42'}")
        ));
    }

    @Test
    public void shouldHandleHttpRequestsWithVelocityResponseTemplateWithDynamicValuesDateAndUUID() throws JsonProcessingException {
        boolean originalFixedUUID = UUIDService.fixedUUID();
        boolean originalFixedTime = TimeService.fixedTime();
        try {
            // given
            UUIDService.fixedUUID(true);
            TimeService.fixedTime(true);
            String template = "{" + NEW_LINE +
                "    'statusCode': 200," + NEW_LINE +
                "    'body': \"{'date': '$now', 'date_epoch': '$now_epoch', 'date_iso_8601': '$now_iso_8601', 'date_rfc_1123': '$now_rfc_1123', 'uuids': ['$uuid', '$uuid'] }\"" + NEW_LINE +
                "}";
            HttpRequest request = request()
                .withPath("/somePath")
                .withQueryStringParameter("nameOne", "valueOne")
                .withQueryStringParameter("nameTwo", "valueTwoOne", "valueTwoTwo")
                .withMethod("POST")
                .withCookie("session", "some_session_id")
                .withBody("some_body");

            // when
            HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

            // then
            assertThat(actualHttpResponse, is(
                response()
                    .withStatusCode(200)
                    .withBody("{'date': '" + TimeService.now() + "', 'date_epoch': '" + TimeService.now().getEpochSecond() + "', 'date_iso_8601': '" + DateTimeFormatter.ISO_INSTANT.format(TimeService.now()) + "', 'date_rfc_1123': '" + DateTimeFormatter.RFC_1123_DATE_TIME.format(TimeService.offsetNow()) + "', 'uuids': ['" + UUIDService.getUUID() + "', '" + UUIDService.getUUID() + "'] }")
            ));
            verify(mockServerLogger).logEvent(
                new LogEntry()
                    .setType(TEMPLATE_GENERATED)
                    .setLogLevel(INFO)
                    .setHttpRequest(request)
                    .setMessageFormat("generated output:{}from template:{}for request:{}")
                    .setArguments(OBJECT_MAPPER.readTree("" +
                            "{" + NEW_LINE +
                            "    'statusCode': 200," + NEW_LINE +
                            "    'body': \"{'date': '" + TimeService.now() + "', 'date_epoch': '" + TimeService.now().getEpochSecond() + "', 'date_iso_8601': '" + DateTimeFormatter.ISO_INSTANT.format(TimeService.now()) + "', 'date_rfc_1123': '" + DateTimeFormatter.RFC_1123_DATE_TIME.format(TimeService.offsetNow()) + "', 'uuids': ['" + UUIDService.getUUID() + "', '" + UUIDService.getUUID() + "'] }\"" + NEW_LINE +
                            "}" + NEW_LINE),
                        template,
                        request
                    )
            );

        } finally {
            UUIDService.fixedUUID(originalFixedUUID);
            TimeService.fixedTime(originalFixedTime);
        }
    }

    @Test
    public void shouldHandleHttpRequestsWithVelocityResponseTemplateWithDynamicValuesRandom() {
        shouldPopulateRandomValue("$rand_int", equalTo(1));
        shouldPopulateRandomValue("$rand_int_10", allOf(greaterThan(0), lessThan(3)));
        shouldPopulateRandomValue("$rand_int_100", allOf(greaterThan(0), lessThan(4)));
        shouldPopulateRandomValue("$rand_bytes", allOf(greaterThan(20), lessThan(50)));
        shouldPopulateRandomValue("$rand_bytes_16", allOf(greaterThan(20), lessThan(50)));
        shouldPopulateRandomValue("$rand_bytes_32", allOf(greaterThan(40), lessThan(60)));
        shouldPopulateRandomValue("$rand_bytes_64", allOf(greaterThan(80), lessThan(120)));
        shouldPopulateRandomValue("$rand_bytes_128", allOf(greaterThan(160), lessThan(300)));
    }

    private void shouldPopulateRandomValue(String function, Matcher<Integer> matcher) {
        // given
        String template = "{ 'body': '" + function + "' }";
        HttpRequest request = request()
            .withPath("/somePath")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse.getBodyAsString(), not(equalTo("")));
        assertThat(actualHttpResponse.getBodyAsString().length(), matcher);
    }

    @Test
    public void shouldHandleHttpRequestsWithVelocityResponseTemplateWithLoopOverValues() throws JsonProcessingException {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'headers': [#foreach( $value in $request.headers.values() )'$value[0]'#if( $foreach.hasNext ), #end#end]}\"" + NEW_LINE +
            "}";


        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withHeader(CONTENT_TYPE.toString(), "plain/text")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

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
                .setArguments(OBJECT_MAPPER.readTree("" +
                        "{" + NEW_LINE +
                        "    'statusCode': 200," + NEW_LINE +
                        "    'body': \"{'headers': ['mock-server.com', 'plain/text']}\"" + NEW_LINE +
                        "}" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithVelocityResponseTemplateWithIfElse() throws JsonProcessingException {
        // given
        String template = "#if ( $request.method == 'POST' && $request.path == '/somePath' )" + NEW_LINE +
            "    {" + NEW_LINE +
            "        'statusCode': 200," + NEW_LINE +
            "        'body': \"{'name': 'value'}\"" + NEW_LINE +
            "    }" + NEW_LINE +
            "#else" + NEW_LINE +
            "    {" + NEW_LINE +
            "        'statusCode': 406," + NEW_LINE +
            "        'body': \"$!request.body\"" + NEW_LINE +
            "    }" + NEW_LINE +
            "#end";
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'name': 'value'}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(OBJECT_MAPPER.readTree("" +
                        "{" + NEW_LINE +
                        "    'statusCode': 200," + NEW_LINE +
                        "    'body': \"{'name': 'value'}\"" + NEW_LINE +
                        "}" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithVelocityResponseTemplateWithXPath() throws JsonProcessingException {
        // given
        String template = "#set($xmlBody = $xml.parse($!request.body))" + NEW_LINE +
            "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'key': '$xml.find('/element/key/text()')', 'value': '$xml.find('/element/value/text()')'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withBody("<element><key>some_key</key><value>some_value</value></element>");

        // when
        HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'key': 'some_key', 'value': 'some_value'}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(OBJECT_MAPPER.readTree("" +
                        "{" + NEW_LINE +
                        "    'statusCode': 200," + NEW_LINE +
                        "    'body': \"{'key': 'some_key', 'value': 'some_value'}\"" + NEW_LINE +
                        "}" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithVelocityResponseTemplateWithJsonParsing() throws JsonProcessingException {
        // given
        String template = "#set($jsonBody = $json.parse($!request.body))" + NEW_LINE +
            "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'titles': [#foreach( $book in $jsonBody.store.book )'$book.title'#if( $foreach.hasNext ), #end#end], 'bikeColor': '$jsonBody.store.bicycle.color'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withBody(json("{" + NEW_LINE +
                "    \"store\": {" + NEW_LINE +
                "        \"book\": [" + NEW_LINE +
                "            {" + NEW_LINE +
                "                \"category\": \"reference\"," + NEW_LINE +
                "                \"author\": \"Nigel Rees\"," + NEW_LINE +
                "                \"title\": \"Sayings of the Century\"," + NEW_LINE +
                "                \"price\": 18.95" + NEW_LINE +
                "            }," + NEW_LINE +
                "            {" + NEW_LINE +
                "                \"category\": \"fiction\"," + NEW_LINE +
                "                \"author\": \"Herman Melville\"," + NEW_LINE +
                "                \"title\": \"Moby Dick\"," + NEW_LINE +
                "                \"isbn\": \"0-553-21311-3\"," + NEW_LINE +
                "                \"price\": 8.99" + NEW_LINE +
                "            }" + NEW_LINE +
                "        ]," + NEW_LINE +
                "        \"bicycle\": {" + NEW_LINE +
                "            \"color\": \"red\"," + NEW_LINE +
                "            \"price\": 19.95" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"expensive\": 10" + NEW_LINE +
                "}"));

        // when
        HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'titles': ['Sayings of the Century', 'Moby Dick'], 'bikeColor': 'red'}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(OBJECT_MAPPER.readTree("" +
                        "{" + NEW_LINE +
                        "    'statusCode': 200," + NEW_LINE +
                        "    'body': \"{'titles': ['Sayings of the Century', 'Moby Dick'], 'bikeColor': 'red'}\"" + NEW_LINE +
                        "}" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithVelocityForwardTemplateWithPathBodyParametersAndCookies() throws JsonProcessingException {
        // given
        String template = "{" + NEW_LINE +
            "    'path': '$request.path'," + NEW_LINE +
            "    'body': \"{'queryStringParameters': '$request.queryStringParameters.nameOne[0],$request.queryStringParameters.nameTwo[0],$request.queryStringParameters.nameTwo[1]'," +
            " 'pathParameters': '$request.pathParameters.nameOne[0],$request.pathParameters.nameTwo[0],$request.pathParameters.nameTwo[1]'," +
            " 'cookies': '$request.cookies.session'," +
            " 'body': '$request.body'}\"" + NEW_LINE +
            "}";
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
        HttpRequest actualHttpRequest = new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpRequestDTO.class);

        // then
        assertThat(actualHttpRequest, is(
            request()
                .withPath("/somePath")
                .withBody("{'queryStringParameters': 'queryValueOne,queryValueTwoOne,queryValueTwoTwo', 'pathParameters': 'pathValueOne,pathValueTwoOne,pathValueTwoTwo', 'cookies': 'some_session_id', 'body': 'some_body'}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(OBJECT_MAPPER.readTree("" +
                        "{" + NEW_LINE +
                        "    'path' : \"/somePath\"," + NEW_LINE +
                        "    'body': \"{'queryStringParameters': 'queryValueOne,queryValueTwoOne,queryValueTwoTwo', 'pathParameters': 'pathValueOne,pathValueTwoOne,pathValueTwoTwo', 'cookies': 'some_session_id', 'body': 'some_body'}\"" + NEW_LINE +
                        "}" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldRefuseClassReachThroughFromBoundHelpersByDefault() {
        // The sandbox must gate EVERY object reachable from a template, not just $request: the built-in
        // helpers ($faker, $strings, $crypto, ...) and the Velocity tools are host objects too, and a walk
        // from any of them to a classloader would reach java.lang.Runtime just as well.
        // given - the out-of-the-box configuration
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withBody("some_body".getBytes(StandardCharsets.UTF_8));

        for (String reference : new String[]{"$faker", "$strings", "$json", "$request"}) {
            String template = "{" + NEW_LINE +
                "    'statusCode': 200," + NEW_LINE +
                "    'body': \"$!" + reference.substring(1) + ".class.classLoader.loadClass('java.lang.Runtime').getName()\"" + NEW_LINE +
                "}";

            // when
            HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

            // then - nothing resolved, so java.lang.Runtime was never reached through this object
            assertThat("class reach-through via " + reference, actualHttpResponse, is(
                response()
                    .withStatusCode(200)
                    .withBody("")
            ));
        }
    }

    @Test
    public void shouldDisallowClassLoadingInVelocityTemplateByDefault() {
        // Velocity ships in the default distribution (unlike the GraalJS engine), so its class-loading
        // reach-through is the most exposed form of the template RCE described by GHSA-7pwj-xvc2-hfpc: an
        // attacker who can register an expectation submits a template that loads java.lang.Runtime and
        // executes an OS command. Out of the box that must now be blocked, and only an operator who
        // explicitly sets velocityDisallowClassLoading=false gets the old reach-through back.
        Boolean originalVelocityDenyClasses = configuration.velocityDisallowClassLoading();

        try {
            // given
            String template = "{" + NEW_LINE +
                "    'statusCode': 200," + NEW_LINE +
                "    'body': \"$!request.class.classLoader.loadClass('java.lang.Runtime').getRuntime().exec(\"does_not_exist.sh\")\"" + NEW_LINE +
                "}";
            HttpRequest request = request()
                .withPath("/somePath")
                .withMethod("POST")
                .withHeader(HOST.toString(), "mock-server.com")
                .withBody("some_body".getBytes(StandardCharsets.UTF_8));

            // then - with the out-of-the-box configuration the class is never loaded, so exec never runs
            HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);
            assertThat(actualHttpResponse, is(
                response()
                    .withStatusCode(200)
                    .withBody("")
            ));

            // when - an operator explicitly opts out of the sandbox
            configuration.velocityDisallowClassLoading(false);

            // then - class loading reaches Runtime.exec again (it fails only because the program does not
            // exist), which is exactly what the default now suppresses
            Exception exception = assertThrows(RuntimeException.class, () -> new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class));
            assertThat(exception.getMessage(), containsString("Cannot run program \"does_not_exist.sh\""));
        } finally {
            configuration.velocityDisallowClassLoading(originalVelocityDenyClasses);
        }
    }

    @Test
    public void shouldApplyDisallowClassLoadingWhenToggledOnLiveEngine() {
        // GAP #47: the sandbox setting must take effect when flipped on an ALREADY-CONSTRUCTED engine
        // (system property / Configuration setter / PUT /mockserver/configuration at runtime), not only
        // when a brand-new engine is built. VelocityTemplateEngine.currentEngineHolder() rebuilds the
        // underlying VelocityEngine (installing the SecureUberspector) when the live
        // velocityDisallowClassLoading differs from the flag the current engine was built with, so the
        // SAME engine instance must start blocking class loading after the toggle. The existing
        // shouldHandleHttpRequestsWithVelocityTemplateWithDisallowClassLoading test instead builds a
        // fresh engine after the flip, so this live rebuild-on-same-engine path was never covered.

        // given - a dedicated configuration with class loading allowed, and ONE engine built from it
        Configuration liveConfiguration = configuration().velocityDisallowClassLoading(false);
        VelocityTemplateEngine engine = new VelocityTemplateEngine(mockServerLogger, liveConfiguration);

        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"$!request.class.classLoader.loadClass('java.lang.Runtime').getRuntime().exec(\"does_not_exist.sh\")\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withBody("some_body".getBytes(StandardCharsets.UTF_8));

        // then - with class loading allowed the SAME engine EXECUTES the class-loading line (it reaches
        // Runtime.exec, which fails only because the script does not exist) — proving class loading is
        // genuinely live before the toggle
        Exception executed = assertThrows(RuntimeException.class, () -> engine.executeTemplate(template, request, HttpResponseDTO.class));
        assertThat(executed.getMessage(), containsString("Cannot run program \"does_not_exist.sh\""));

        // when - the sandbox is toggled ON on the SAME configuration the SAME engine holds
        liveConfiguration.velocityDisallowClassLoading(true);

        // then - the SAME engine now BLOCKS the class-loading line: currentEngineHolder() rebuilds the
        // VelocityEngine with the SecureUberspector, so the loadClass reference is inert and the body
        // renders empty rather than executing (and no exception is thrown)
        HttpResponse blocked = engine.executeTemplate(template, request, HttpResponseDTO.class);
        assertThat(blocked, is(
            response()
                .withStatusCode(200)
                .withBody("")
        ));
    }

    @Test
    public void shouldHandleHttpRequestsWithVelocityTemplateWithDisallowedText() {
        String originalVelocityDisallowedText = configuration.velocityDisallowedText();

        try {
            // given
            String template = "{" + NEW_LINE +
                "    'statusCode': 200," + NEW_LINE +
                "    'body': \"$!request.class.classLoader.loadClass('java.lang.Runtime').getRuntime().exec(\"does_not_exist.sh\")\"" + NEW_LINE +
                "}";
            HttpRequest request = request()
                .withPath("/somePath")
                .withMethod("POST")
                .withHeader(HOST.toString(), "mock-server.com")
                .withBody("some_body".getBytes(StandardCharsets.UTF_8));

            // then - with no disallowed text the template still renders (the class-loading reference is
            // inert under the default sandbox, so the body is empty rather than the output of exec)
            HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);
            assertThat(actualHttpResponse, is(
                response()
                    .withStatusCode(200)
                    .withBody("")
            ));

            // when
            configuration.velocityDisallowedText("request.class");

            // then - the template is now rejected before it renders at all
            Exception exception = assertThrows(RuntimeException.class, () -> new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class));
            assertThat(exception.getMessage(), containsString("Found disallowed string \"request.class\" in template:"));
        } finally {
            configuration.velocityDisallowedText(originalVelocityDisallowedText);
        }
    }

    @Test
    public void shouldHandleHttpRequestsWithVelocityTemplateWithDisallowedTextList() {
        String originalVelocityDisallowedText = configuration.velocityDisallowedText();

        try {
            // given
            String template = "{" + NEW_LINE +
                "    'statusCode': 200," + NEW_LINE +
                "    'body': \"$!request.class.classLoader.loadClass('java.lang.Runtime').getRuntime().exec(\"does_not_exist.sh\")\"" + NEW_LINE +
                "}";
            HttpRequest request = request()
                .withPath("/somePath")
                .withMethod("POST")
                .withHeader(HOST.toString(), "mock-server.com")
                .withBody("some_body".getBytes(StandardCharsets.UTF_8));
            configuration.velocityDisallowedText("request.class,classLoader.loadClass");

            // then
            Exception exception = assertThrows(RuntimeException.class, () -> new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class));
            assertThat(exception.getMessage(), containsString("Found disallowed string \"request.class\" in template:"));

            // when
            configuration.velocityDisallowedText("classLoader.loadClass,request.class");

            // then
            exception = assertThrows(RuntimeException.class, () -> new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class));
            assertThat(exception.getMessage(), containsString("Found disallowed string \"classLoader.loadClass\" in template:"));
        } finally {
            configuration.velocityDisallowedText(originalVelocityDisallowedText);
        }
    }

    @Test
    public void shouldNotExposeImportToolForFileReadOrSsrf() throws Exception {
        // SECURITY: the ImportTool ($import.read(...)) lets a template read an arbitrary local file or
        // fetch a remote URL (SSRF / local-file-read), so it is deliberately NOT registered. A template
        // referencing $import must therefore NOT return the file content — the reference is undefined and
        // is emitted as inert literal text (non-strict references) rather than reading the file.
        File testInputFile = new File(temporaryFolder.getRoot(), "testInputFile.txt");
        String testInputFileContent = "This file content must NOT leak into the response body";
        Files.write(Paths.get(testInputFile.getAbsolutePath()), testInputFileContent.getBytes());

        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"prefix-$import.read('" + testInputFile.getAbsolutePath().replace("\\", "\\\\") + "')\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withBody("some_body".getBytes(StandardCharsets.UTF_8));

        // when
        HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then — the file content must never appear in the rendered response
        assertThat(actualHttpResponse.getStatusCode(), is(200));
        assertThat(actualHttpResponse.getBodyAsString(), not(containsString(testInputFileContent)));
    }

    @Test
    public void shouldHandleHttpRequestsWithVelocityTemplateWithMathTool() {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"$math.sub('5', '3')\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withBody("some_body".getBytes(StandardCharsets.UTF_8));

        // when
        HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("2")
        ));
    }

    @Test
    public void shouldHandleHttpRequestsWithVelocityTemplateWithDateTool() {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"$date.day $date.month $date.year\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withBody("some_body".getBytes(StandardCharsets.UTF_8));
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTime(new java.util.Date());
        int year = cal.get(java.util.Calendar.YEAR);
        int month = cal.get(java.util.Calendar.MONTH);
        int day = cal.get(java.util.Calendar.DAY_OF_MONTH);

        // when
        HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody(day + " " + month + " " + year)
        ));
    }

    @Test
    public void shouldHandleHttpRequestsWithVelocityResponseTemplateInvalidFields() throws JsonProcessingException {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'method': '$!request.method.invalid', 'path': '$request.invalid', 'headers': '$!request.headers.host[0]'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withBody("some_body".getBytes(StandardCharsets.UTF_8));

        // when
        HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'method': '', 'path': '$request.invalid', 'headers': 'mock-server.com'}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(OBJECT_MAPPER.readTree("" +
                        "{" + NEW_LINE +
                        "    'statusCode': 200," + NEW_LINE +
                        "    'body': \"{'method': '', 'path': '$request.invalid', 'headers': 'mock-server.com'}\"" + NEW_LINE +
                        "}" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldHandleInvalidVelocityTemplate() {
        // given
        String template = "#if {" + NEW_LINE +
            "    'path' : \"/somePath\"," + NEW_LINE +
            "    'queryStringParameters' : [ {" + NEW_LINE +
            "        'name' : \"queryParameter\"," + NEW_LINE +
            "        'values' : [ \"$!request.queryStringParameters['queryParameter'][0]\" ]" + NEW_LINE +
            "    } ]," + NEW_LINE +
            "    'headers' : [ {" + NEW_LINE +
            "        'name' : \"Host\"," + NEW_LINE +
            "        'values' : [ \"localhost:1090\" ]" + NEW_LINE +
            "    } ]," + NEW_LINE +
            "    'body': \"{'name': 'value'}\"" + NEW_LINE +
            "}";

        // when
        RuntimeException runtimeException = assertThrows(RuntimeException.class, () -> new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                .withPath("/someOtherPath")
                .withQueryStringParameter("queryParameter", "someValue")
                .withBody("some_body"),
            HttpRequestDTO.class
        ));

        // then
        assertThat(runtimeException.getMessage(), is("Exception:" + NEW_LINE +
            "" + NEW_LINE +
            "  Encountered \"{\" at VelocityResponseTemplate[line 1, column 5]" + NEW_LINE +
            "  Was expecting one of:" + NEW_LINE +
            "      \"(\" ..." + NEW_LINE +
            "      <WHITESPACE> ..." + NEW_LINE +
            "      <NEWLINE> ..." + NEW_LINE +
            "      " + NEW_LINE +
            "" + NEW_LINE +
            " transforming template:" + NEW_LINE +
            "" + NEW_LINE +
            "  #if {" + NEW_LINE +
            "      'path' : \"/somePath\"," + NEW_LINE +
            "      'queryStringParameters' : [ {" + NEW_LINE +
            "          'name' : \"queryParameter\"," + NEW_LINE +
            "          'values' : [ \"$!request.queryStringParameters['queryParameter'][0]\" ]" + NEW_LINE +
            "      } ]," + NEW_LINE +
            "      'headers' : [ {" + NEW_LINE +
            "          'name' : \"Host\"," + NEW_LINE +
            "          'values' : [ \"localhost:1090\" ]" + NEW_LINE +
            "      } ]," + NEW_LINE +
            "      'body': \"{'name': 'value'}\"" + NEW_LINE +
            "  }" + NEW_LINE +
            "" + NEW_LINE +
            " for request:" + NEW_LINE +
            "" + NEW_LINE +
            "  {" + NEW_LINE +
            "    \"path\" : \"/someOtherPath\"," + NEW_LINE +
            "    \"queryStringParameters\" : {" + NEW_LINE +
            "      \"queryParameter\" : [ \"someValue\" ]" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"body\" : \"some_body\"" + NEW_LINE +
            "  }" + NEW_LINE));
    }

    @Test
    public void shouldHandleMultipleHttpRequestsWithVelocityResponseTemplateInParallel()
        throws InterruptedException, ExecutionException {
        // given
        String template = "#if ( $request.method == 'POST' && $request.path == '/somePath' )" + NEW_LINE +
            "    {" + NEW_LINE +
            "        'statusCode': 200," + NEW_LINE +
            "        'body': \"{'name': 'value'}\"" + NEW_LINE +
            "    }" + NEW_LINE +
            "#else" + NEW_LINE +
            "    {" + NEW_LINE +
            "        'statusCode': 406," + NEW_LINE +
            "        'body': \"$!request.body\"" + NEW_LINE +
            "    }" + NEW_LINE +
            "#end";

        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withBody("some_body");

        // when
        VelocityTemplateEngine velocityTemplateEngine = new VelocityTemplateEngine(mockServerLogger, configuration);

        ExecutorService newFixedThreadPool = Executors.newFixedThreadPool(30);

        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            futures.add(newFixedThreadPool.submit(() -> {
                assertThat(velocityTemplateEngine.executeTemplate(template, request, HttpResponseDTO.class), is(
                    response()
                        .withStatusCode(200)
                        .withBody("{'name': 'value'}")
                ));
                return true;
            }));

        }

        for (Future<Boolean> future : futures) {
            future.get();
        }
        newFixedThreadPool.shutdown();
    }

    /**
     * JsonTool ($json) is a request scoped tool, so it should be recreated for each request. Otherwise, failures
     * @throws JsonProcessingException
     */
    @Test
    public void shouldHandleResponseTemplateWithVelocity() {
        String template = "{" + NEW_LINE +
            "    'statusCode': $response.statusCode," + NEW_LINE +
            "    'body': \"path=$request.path,originalBody=$response.body\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/testPath")
            .withMethod("GET");
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody("hello");

        HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, httpResponse, HttpResponseDTO.class);

        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("path=/testPath,originalBody=hello")
        ));
    }

    @Test
    public void shouldExposeFaker() {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'firstName': '$faker.name().firstName()', 'email': '$faker.internet().emailAddress()'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse.getBodyAsString(), not(emptyString()));
        assertThat(actualHttpResponse.getBodyAsString(), not(containsString("$faker")));
    }

    @Test
    public void shouldProduceDeterministicFakerOutputWhenTemplateFakerSeedSet() {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'firstName': '$faker.name().firstName()', 'email': '$faker.internet().emailAddress()'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request().withPath("/somePath");

        // when — two independent engines with the SAME seed, and one with a DIFFERENT seed
        String bodyA = new VelocityTemplateEngine(mockServerLogger, configuration().templateFakerSeed(4242L)).executeTemplate(template, request, HttpResponseDTO.class).getBodyAsString();
        String bodyB = new VelocityTemplateEngine(mockServerLogger, configuration().templateFakerSeed(4242L)).executeTemplate(template, request, HttpResponseDTO.class).getBodyAsString();
        String bodyC = new VelocityTemplateEngine(mockServerLogger, configuration().templateFakerSeed(9999L)).executeTemplate(template, request, HttpResponseDTO.class).getBodyAsString();

        // then — same seed reproduces byte-identical faker output; a different seed differs
        assertThat("same seed must reproduce identical faker output", bodyA, is(bodyB));
        assertThat("faker must have produced a value", bodyA, not(containsString("'firstName': ''")));
        assertThat("different seed should produce different faker output", bodyC, not(is(bodyA)));
    }

    @Test
    public void shouldUseRequestScopeToolsInThreadSafeWay() throws JsonProcessingException, ExecutionException, InterruptedException {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': '$json.parse($!request.body).name'"+ NEW_LINE +
            "}";

        // when
        VelocityTemplateEngine velocityTemplateEngine = new VelocityTemplateEngine(mockServerLogger, configuration);
        ExecutorService newFixedThreadPool = Executors.newFixedThreadPool(30);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 100;i++) {
            final int requestNumber = i;
            futures.add(newFixedThreadPool.submit(() -> {
                HttpRequest request = request()
                    .withPath("/somePath")
                    .withMethod("POST")
                    .withHeader(HOST.toString(), "mock-server.com")
                    .withBody(String.format("{\"name\": \"value%s\"}",requestNumber));


                assertThat(velocityTemplateEngine.executeTemplate(template, request, HttpResponseDTO.class), is(
                    response()
                        .withStatusCode(200)
                        .withBody(String.format("value%s", requestNumber))
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
    public void shouldShareStatelessFunctionsAndHelpersAcrossConcurrentRendersWithoutCrossContamination() throws ExecutionException, InterruptedException {
        // The built-in functions ($strings, $uuid) and helpers are hoisted into a single shared map that
        // every render references (not copies) via Velocity context chaining. This exercises that shared
        // path under heavy concurrency: each render mixes a per-request value (request.body, via the
        // shared $strings helper) with the per-render-varying $uuid generator, and asserts (a) every
        // thread sees ONLY its own request value back (no cross-thread contamination of the shared
        // bindings) and (b) the $uuid generator still produces a distinct value per render. A regression
        // that mutated the shared map would also surface here as an UnsupportedOperationException, since
        // the shared map is wrapped unmodifiable.
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': '$strings.uppercase($!request.body)-$uuid'" + NEW_LINE +
            "}";

        // when
        VelocityTemplateEngine velocityTemplateEngine = new VelocityTemplateEngine(mockServerLogger, configuration);
        ExecutorService newFixedThreadPool = Executors.newFixedThreadPool(30);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            final int requestNumber = i;
            futures.add(newFixedThreadPool.submit(() -> {
                HttpRequest request = request()
                    .withPath("/somePath")
                    .withMethod("POST")
                    .withHeader(HOST.toString(), "mock-server.com")
                    .withBody(String.format("value%s", requestNumber));

                HttpResponse response = velocityTemplateEngine.executeTemplate(template, request, HttpResponseDTO.class);
                String body = response.getBodyAsString();
                // each render must see its OWN request body back, upper-cased by the shared $strings helper
                assertThat(body, startsWith(String.format("VALUE%s-", requestNumber)));
                // and the shared $uuid generator must still have produced a value for this render
                return body.substring(body.indexOf('-') + 1);
            }));
        }

        java.util.Set<String> uuids = new java.util.HashSet<>();
        for (Future<String> future : futures) {
            uuids.add(future.get());
        }
        newFixedThreadPool.shutdown();
        // every render produced a distinct uuid — the hoisted generator is still invoked per render
        assertThat(uuids, hasSize(200));
    }

    @Test
    public void shouldHandleVelocityResponseTemplateWithJsonPath() {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'title': '$jsonPath.find(\"$.store.book[0].title\")', 'bikeColor': '$jsonPath.find(\"$.store.bicycle.color\")'}\"" + NEW_LINE +
            "}";
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
        HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'title': 'Sayings of the Century', 'bikeColor': 'red'}")
        ));
    }

    @Test
    public void shouldHandleVelocityResponseTemplateWithXPath() {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'key': '$xPath.find(\"/element/key\")', 'value': '$xPath.find(\"/element/value\")'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withBody("<element><key>some_key</key><value>some_value</value></element>");

        // when
        HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'key': 'some_key', 'value': 'some_value'}")
        ));
    }

    @Test
    public void shouldHandleVelocityResponseTemplateWithJsonPathForMissingPath() {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'missing': '$jsonPath.find(\"$.store.does.not.exist\")'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withBody(json("{ \"store\": { \"bicycle\": { \"color\": \"red\" } } }"));

        // when
        HttpResponse actualHttpResponse = new VelocityTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then - missing path mirrors Mustache: empty value, no exception
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'missing': ''}")
        ));
    }

    // ----- parsed-template cache regression tests -----
    // These exercise the parse-once cache (render via Velocity's own Template.merge) on both the
    // cold-cache (first render) and warm-cache (subsequent render) paths, proving the cached path
    // matches the re-parsing path for directives that depend on Velocity's native render — most
    // importantly #stop (StopCommand) and #macro — and that bounding/eviction stays correct.

    @Test
    public void shouldRenderStopDirectiveAsCleanPartialOutputOnColdAndWarmCache() {
        // given - #stop ends rendering mid-template, so only the text before it should appear; this
        // only works if rendering goes through Velocity's own merge (which catches StopCommand)
        String template = "before#stop after";
        HttpRequest request = request().withPath("/somePath");
        VelocityTemplateEngine velocityTemplateEngine = new VelocityTemplateEngine(mockServerLogger, configuration);

        // when - cold cache (first render parses) and warm cache (second render reuses parsed AST)
        String cold = velocityTemplateEngine.renderTemplate(template, request);
        String warm = velocityTemplateEngine.renderTemplate(template, request);

        // then - both produce the clean partial output
        assertThat(cold, is("before"));
        assertThat(warm, is("before"));
    }

    @Test
    public void shouldRenderStopDirectiveInExecuteTemplateOnColdAndWarmCache() {
        // given
        String template = "{'statusCode': 200, 'body': 'kept'}#stop {'this': 'dropped'}";
        HttpRequest request = request().withPath("/somePath");
        VelocityTemplateEngine velocityTemplateEngine = new VelocityTemplateEngine(mockServerLogger, configuration);

        // when - run twice to cover cold then warm cache
        HttpResponse cold = velocityTemplateEngine.executeTemplate(template, request, HttpResponseDTO.class);
        HttpResponse warm = velocityTemplateEngine.executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(cold, is(response().withStatusCode(200).withBody("kept")));
        assertThat(warm, is(response().withStatusCode(200).withBody("kept")));
    }

    @Test
    public void shouldRenderMacroDefineAndInvokeStablyAcrossRepeatedRenders() {
        // given - a macro defined and invoked twice; macros are resolved during Velocity's own render,
        // so a hand-rolled render path would mishandle them - this proves merge() is used
        String template = "#macro(greet $name)Hi $name!#end#greet(\"a\") #greet(\"b\")";
        HttpRequest request = request().withPath("/somePath");
        VelocityTemplateEngine velocityTemplateEngine = new VelocityTemplateEngine(mockServerLogger, configuration);

        // when - single render then a repeated render against the warm cache
        String first = velocityTemplateEngine.renderTemplate(template, request);
        String second = velocityTemplateEngine.renderTemplate(template, request);

        // then - output stable across renders
        assertThat(first, is("Hi a! Hi b!"));
        assertThat(second, is(first));
    }

    @Test
    public void shouldRemainCorrectAfterCacheOverflowEviction() {
        // given - render more than PARSED_TEMPLATE_CACHE_MAX distinct templates so the bounded cache
        // evicts entries, then re-render an early (now-evicted) template and confirm it still renders
        VelocityTemplateEngine velocityTemplateEngine = new VelocityTemplateEngine(mockServerLogger, configuration);
        HttpRequest request = request().withPath("/somePath");

        String firstTemplate = "tmpl-0=$request.path";
        String firstExpected = "tmpl-0=/somePath";
        assertThat(velocityTemplateEngine.renderTemplate(firstTemplate, request), is(firstExpected));

        // when - overflow the cache with distinct templates (forces eviction of the first template)
        int overflow = VelocityTemplateEngine.PARSED_TEMPLATE_CACHE_MAX + 50;
        for (int i = 1; i <= overflow; i++) {
            String distinct = "tmpl-" + i + "=$request.path";
            assertThat(velocityTemplateEngine.renderTemplate(distinct, request), is("tmpl-" + i + "=/somePath"));
        }

        // then - the evicted first template re-parses cleanly and renders the same output (no exception)
        assertThat(velocityTemplateEngine.renderTemplate(firstTemplate, request), is(firstExpected));
        // and the most recent template still renders correctly
        assertThat(velocityTemplateEngine.renderTemplate("tmpl-" + overflow + "=$request.path", request), is("tmpl-" + overflow + "=/somePath"));
    }

    @Test
    public void shouldRenderSharedCachedTemplateCorrectlyUnderConcurrencyWithPerIterationState()
        throws InterruptedException, ExecutionException {
        // given - a single template rendered concurrently with a per-iteration $uuid and $iteration.*,
        // proving the shared cached/parsed Template is rendered thread-safely with distinct per-call state
        String template = "id=$uuid,index=$iteration.index,count=$iteration.count";
        VelocityTemplateEngine velocityTemplateEngine = new VelocityTemplateEngine(mockServerLogger, configuration);
        ExecutorService newFixedThreadPool = Executors.newFixedThreadPool(30);

        // when
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            final int index = i;
            futures.add(newFixedThreadPool.submit(() -> {
                HttpRequest request = request().withPath("/somePath");
                org.mockserver.load.IterationContext iteration =
                    new org.mockserver.load.IterationContext(index, index, index, 0, 200);
                String rendered = velocityTemplateEngine.renderTemplate(template, request, iteration);
                // per-call state must be the caller's own index/count even though the parsed AST is shared
                assertThat(rendered, startsWith("id="));
                assertThat(rendered, containsString(",index=" + index + ","));
                assertThat(rendered, endsWith(",count=200"));
                // return the rendered uuid portion so the caller can assert distinctness
                return rendered.substring("id=".length(), rendered.indexOf(",index="));
            }));
        }

        // then - per-call uuids did not bleed across threads: either all distinct (default random UUIDs)
        // or all identical (when another test in this phase pinned UUIDService.fixedUUID(true)); never a
        // partial mix, which would indicate cross-thread state corruption of the shared cached template
        java.util.Set<String> uuids = new java.util.HashSet<>();
        for (Future<String> future : futures) {
            uuids.add(future.get());
        }
        newFixedThreadPool.shutdown();
        assertThat(uuids.size(), anyOf(is(200), is(1)));
    }

    // ---- JSON-RPC id echoing ----

    /**
     * Every MCP and A2A mock builder (across all eight client languages) emits
     * {@code "id": $!{request.jsonRpcRawId}}. A JSON-RPC notification carries no id, so before the
     * fix that rendered to nothing and produced {@code {"jsonrpc":"2.0","result":{},"id": }} --
     * not parseable JSON. The id must render as the JSON {@code null} literal instead.
     */
    @Test
    public void shouldRenderJsonRpcRawIdAsNullLiteralForNotification() throws JsonProcessingException {
        // given a JSON-RPC notification (no "id" member)
        HttpRequest request = request()
            .withMethod("POST")
            .withPath("/mcp")
            .withBody("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");

        // when the builders' response template is rendered
        String rendered = new VelocityTemplateEngine(mockServerLogger, configuration)
            .renderTemplate("{\"jsonrpc\": \"2.0\", \"result\": {}, \"id\": $!{request.jsonRpcRawId}}", request, null);

        // then the result is parseable JSON carrying an explicit null id
        assertThat(rendered, containsString("\"id\": null"));
        assertThat(OBJECT_MAPPER.readTree(rendered).path("id").isNull(), is(true));
    }

    @Test
    public void shouldEchoJsonRpcRawIdUnchangedForNumericAndStringIds() {
        VelocityTemplateEngine engine = new VelocityTemplateEngine(mockServerLogger, configuration);
        String template = "id=$!{request.jsonRpcRawId}";

        HttpRequest numericId = request().withMethod("POST").withPath("/mcp")
            .withBody("{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tools/list\"}");
        assertThat(engine.renderTemplate(template, numericId, null), is("id=42"));

        HttpRequest stringId = request().withMethod("POST").withPath("/mcp")
            .withBody("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"tools/list\"}");
        assertThat(engine.renderTemplate(template, stringId, null), is("id=\"abc\""));

        // an explicit null id is already a valid JSON-RPC id and must stay null
        HttpRequest nullId = request().withMethod("POST").withPath("/mcp")
            .withBody("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"tools/list\"}");
        assertThat(engine.renderTemplate(template, nullId, null), is("id=null"));
    }

    /**
     * A non-JSON-RPC request must be left alone -- {@code jsonRpcRawId} stays unset so a template
     * referencing it renders empty exactly as before.
     */
    @Test
    public void shouldNotSetJsonRpcRawIdForNonJsonRpcRequest() {
        HttpRequest request = request().withMethod("POST").withPath("/other")
            .withBody("{\"some\":\"payload\"}");

        String rendered = new VelocityTemplateEngine(mockServerLogger, configuration)
            .renderTemplate("id=$!{request.jsonRpcRawId}", request, null);

        assertThat(rendered, is("id="));
    }

}
