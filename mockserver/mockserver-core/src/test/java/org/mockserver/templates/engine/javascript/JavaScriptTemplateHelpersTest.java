package org.mockserver.templates.engine.javascript;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.serialization.model.HttpResponseDTO;
import org.mockserver.templates.engine.TemplateFunctions;
import org.mockserver.templates.engine.helpers.TemplateFaker;
import org.slf4j.event.Level;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

public class JavaScriptTemplateHelpersTest {

    private static final Configuration CONFIGURATION = configuration().javascriptTemplateExecutionTimeout(0L);
    private static final HttpRequest REQUEST = request()
        .withPath("/colours")
        .withBody("<order><item>a</item><item>b</item></order>");

    @Mock
    private MockServerLogger mockServerLogger;

    @Before
    public void setupTestFixture() {
        assumeTrue("GraalVM Polyglot API available", JavaScriptTemplateEngine.isPolyglotAvailable());
        openMocks(this);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
    }

    private String body(String expression) {
        return body(new JavaScriptTemplateEngine(mockServerLogger, CONFIGURATION), expression, null);
    }

    private String body(JavaScriptTemplateEngine engine, String expression, Map<String, Object> parameters) {
        return engine
            .executeTemplate("return { 'statusCode': 200, 'body': String(" + expression + ") };", parameters, REQUEST, null, HttpResponseDTO.class)
            .getBodyAsString();
    }

    @Test
    public void shouldPickRandomlyFromArgumentsOrAnArray() {
        // 200 draws, as the Velocity and Mustache tests use: a colour is missed with probability 3 * (2/3)^200, about 2e-35
        Set<String> picked = new HashSet<>(Arrays.asList(body("(function() { var picks = []; for (var i = 0; i < 200; i++) { picks.push(lists.pickRandom('red', 'green', 'blue')); } return picks.join(','); })()").split(",")));
        assertThat(picked, containsInAnyOrder("red", "green", "blue"));
        assertThat(body("lists.pickRandom(['only'])"), is("only"));
    }

    @Test
    public void shouldPickTheSameSequenceWhenTheFakerSeedIsSet() {
        Configuration seeded = configuration().javascriptTemplateExecutionTimeout(0L).templateFakerSeed(42L);
        String picks = "[1,2,3,4,5,6,7,8,9,10].map(function() { return lists.pickRandom('a','b','c','d','e','f','g','h'); }).join('')";
        assertThat(body(new JavaScriptTemplateEngine(mockServerLogger, seeded), picks, null),
            is(body(new JavaScriptTemplateEngine(mockServerLogger, seeded), picks, null)));
    }

    @Test
    public void shouldBuildJoinAndIterateLists() {
        assertThat(body("lists.arrayJoin(', ', lists.array('a', 'b', 'c'))"), is("a, b, c"));
        assertThat(body("lists.arrayJoin('-', ['x', 'y'])"), is("x-y"));
        assertThat(body("lists.arrayJoin(',', lists.range(1, 3))"), is("1,2,3"));
        assertThat(body("lists.range(3, 1).size()"), is("3"));
    }

    @Test
    public void shouldFailForARangeThatIsTooLarge() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> body("lists.range(1, 1000000).size()"));
        assertThat(String.valueOf(failure.getMessage()) + failure.getCause(), containsString("lists.range: a range has at most 10000 values"));
    }

    @Test
    public void shouldFormatTheRequestXml() {
        assertThat(body("xpath.formatXml(request.body)"), is("<order>\n  <item>a</item>\n  <item>b</item>\n</order>"));
    }

    @Test
    public void shouldRenderTheHostname() {
        assertThat(body("hostname"), is(TemplateFunctions.hostname()));
    }

    @Test
    public void shouldBindTheNewHelpersOnlyForATemplateThatNamesThem() {
        assertThat(body("typeof globalThis['li' + 'sts'] + ' ' + typeof globalThis['host' + 'name']"), is("undefined undefined"));
        assertThat(body("typeof lists + ' ' + typeof hostname"), is("object string"));
        assertThat(body("typeof lists + ' ' + typeof globalThis['host' + 'name']"), is("object undefined"));
        assertThat(body("typeof hostname + ' ' + typeof globalThis['li' + 'sts']"), is("string undefined"));
    }

    @Test
    public void shouldGenerateFakeDataForALocale() {
        Configuration seeded = configuration().javascriptTemplateExecutionTimeout(0L).templateFakerSeed(7L);
        String expected = new TemplateFaker(7L).locale("de-DE").name().firstName();
        assertThat(body(new JavaScriptTemplateEngine(mockServerLogger, seeded), "faker.locale('de-DE').name().firstName()", null), is(expected));
        String unsupported = new TemplateFaker(7L).name().firstName();
        assertThat(body(new JavaScriptTemplateEngine(mockServerLogger, seeded), "faker.locale('xx').name().firstName()", null), is(unsupported));
    }

    @Test
    public void shouldExposeTemplateParametersAsAJavaScriptObject() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("greeting", "hello");
        parameters.put("names", List.of("ann", "bob"));
        parameters.put("limits", Map.of("max", 5));
        parameters.put("empty", "");
        JavaScriptTemplateEngine engine = new JavaScriptTemplateEngine(mockServerLogger, CONFIGURATION);
        assertThat(body(engine, "parameters.greeting + ' ' + parameters.names.join(' and ') + ' max ' + parameters.limits.max", parameters), is("hello ann and bob max 5"));
        assertThat(body(engine, "JSON.stringify(parameters)", parameters), is("{\"greeting\":\"hello\",\"names\":[\"ann\",\"bob\"],\"limits\":{\"max\":5},\"empty\":\"\"}"));
        assertThat(body(engine, "typeof parameters", null), is("undefined"));
    }
}
