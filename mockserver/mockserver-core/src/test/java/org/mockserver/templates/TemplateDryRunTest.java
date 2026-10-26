package org.mockserver.templates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.templates.engine.ScenarioStateTemplateTestLock;
import org.mockserver.templates.engine.javascript.JavaScriptTemplateEngine;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * {@code PUT /mockserver/template/test}: renders a template against a sample request and reports the result,
 * the failure with its template position, or what the template did that is probably a mistake.
 */
public class TemplateDryRunTest {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();
    // two nested loops over the largest range, so it never finishes on its own
    private static final String VELOCITY_LOOPS_FOREVER = "#foreach($i in [1..2147483647])#foreach($j in [1..2147483647])#end#end{ \"statusCode\": 200 }";

    static JsonNode body(HttpResponse response) throws Exception {
        return OBJECT_MAPPER.readTree(response.getBodyAsString());
    }

    static ObjectNode input(String templateType, String template) {
        ObjectNode input = OBJECT_MAPPER.createObjectNode().put("templateType", templateType).put("template", template);
        input.putObject("request").put("method", "POST").put("path", "/orders/42").put("body", "{\"name\":\"tea\"}");
        return input;
    }

    static HttpResponse dryRun(TemplateDryRun dryRun, JsonNode input) throws Exception {
        return dryRun.test(request().withMethod("PUT").withPath("/mockserver/template/test").withBody(input.toString()))
            .get(30, TimeUnit.SECONDS);
    }

    @Test
    public void rendersAVelocityResponseTemplateAgainstTheSampleRequest() throws Exception {
        HttpResponse response = dryRun(new TemplateDryRun(configuration()),
            input("VELOCITY", "{ \"statusCode\": 201, \"body\": \"$request.method $request.path\" }"));

        assertThat(response.getStatusCode(), is(200));
        JsonNode report = body(response);
        assertThat(report.path("valid").asBoolean(), is(true));
        assertThat(report.path("templateType").asText(), is("VELOCITY"));
        assertThat(report.path("actionType").asText(), is("RESPONSE_TEMPLATE"));
        assertThat(report.path("renderedResponse").path("statusCode").asInt(), is(201));
        assertThat(report.path("renderedResponse").path("body").asText(), is("POST /orders/42"));
        assertThat(report.has("warnings"), is(false));
    }

    @Test
    public void rendersAMustacheForwardTemplateAsARequest() throws Exception {
        ObjectNode input = input("MUSTACHE", "{ \"method\": \"GET\", \"path\": \"/upstream{{ request.path }}\" }").put("actionType", "FORWARD_TEMPLATE");

        JsonNode report = body(dryRun(new TemplateDryRun(configuration()), input));

        assertThat(report.path("valid").asBoolean(), is(true));
        assertThat(report.path("actionType").asText(), is("FORWARD_TEMPLATE"));
        assertThat(report.path("renderedRequest").path("path").asText(), is("/upstream/orders/42"));
    }

    @Test
    public void rendersAJavaScriptResponseTemplate() throws Exception {
        requireGraalJs();
        JsonNode report = body(dryRun(new TemplateDryRun(configuration()),
            input("JAVASCRIPT", "return { statusCode: 200, body: JSON.parse(request.body).name };")));

        assertThat(report.path("valid").asBoolean(), is(true));
        assertThat(report.path("renderedResponse").path("body").asText(), is("tea"));
    }

    @Test
    public void reportsAVelocityParseErrorWithItsLineAndColumn() throws Exception {
        JsonNode report = body(dryRun(new TemplateDryRun(configuration()),
            input("VELOCITY", "{\n  \"statusCode\": 200,\n  #if {\n}")));

        assertThat(report.path("valid").asBoolean(), is(false));
        assertThat(report.path("error").path("line").asInt(), is(3));
        assertThat(report.path("error").path("column").asInt(), is(7));
        assertThat(report.path("error").path("message").asText(), containsString("Encountered \"{\""));
    }

    @Test
    public void reportsAVelocityReferenceThatDidNotResolve() throws Exception {
        JsonNode report = body(dryRun(new TemplateDryRun(configuration()),
            input("VELOCITY", "{\n  \"statusCode\": 200,\n  \"body\": \"$request.heders\"\n}")));

        assertThat(report.path("valid").asBoolean(), is(true));
        JsonNode warning = report.path("warnings").get(0);
        assertThat(warning.path("message").asText(), startsWith("$request.heders did not resolve"));
        assertThat(warning.path("line").asInt(), is(3));
        assertThat(warning.path("column").asInt(), is(12));
    }

    @Test
    public void reportsEachMustacheVariableThatDidNotResolveWithItsLine() throws Exception {
        JsonNode report = body(dryRun(new TemplateDryRun(configuration()),
            input("MUSTACHE", "{\n  \"statusCode\": 200,\n  \"reasonPhrase\": \"{{reqest.method}}\",\n  \"body\": \"{{request.pth}}{{request.path}}{{missing}}\"\n}")));

        assertThat(report.path("valid").asBoolean(), is(true));
        assertThat(report.path("renderedResponse").path("body").asText(), is("/orders/42"));
        JsonNode warnings = report.path("warnings");
        assertThat(warnings.size(), is(3));
        assertThat(warnings.get(0).path("message").asText(), is("{{reqest.method}} did not resolve against this request, so it rendered as empty text"));
        assertThat(warnings.get(0).path("line").asInt(), is(3));
        assertThat(warnings.get(0).has("column"), is(false));
        assertThat(warnings.get(1).path("message").asText(), startsWith("{{request.pth}} did not resolve"));
        assertThat(warnings.get(1).path("line").asInt(), is(4));
        assertThat(warnings.get(2).path("message").asText(), startsWith("{{missing}} did not resolve"));
        assertThat(warnings.get(2).path("line").asInt(), is(4));
    }

    @Test
    public void reportsAMustacheVariableThatDidNotResolveInAForwardTemplate() throws Exception {
        ObjectNode input = input("MUSTACHE", "{ \"method\": \"GET\", \"path\": \"/upstream{{ request.pth }}\" }").put("actionType", "FORWARD_TEMPLATE");

        JsonNode report = body(dryRun(new TemplateDryRun(configuration()), input));

        assertThat(report.path("valid").asBoolean(), is(true));
        assertThat(report.path("renderedRequest").path("path").asText(), is("/upstream"));
        assertThat(report.path("warnings").size(), is(1));
        assertThat(report.path("warnings").get(0).path("message").asText(), startsWith("{{request.pth}} did not resolve"));
        assertThat(report.path("warnings").get(0).path("line").asInt(), is(1));
    }

    @Test
    public void doesNotWarnAboutMustacheVariablesThatResolveToAValueOrToNothing() throws Exception {
        JsonNode report = body(dryRun(new TemplateDryRun(configuration()),
            input("MUSTACHE", "{\n  \"statusCode\": 200,\n  \"body\": \"{{request.method}} {{ request.path }}{{request.secure}}{{#absent}}{{alsoAbsent}}{{/absent}}{{^absent}} none{{/absent}} {{#jsonPath}}$.name{{/jsonPath}}{{jsonPathResult}}\"\n}")));

        assertThat(report.path("valid").asBoolean(), is(true));
        assertThat(report.path("renderedResponse").path("body").asText(), is("POST /orders/42 none tea"));
        assertThat(report.has("warnings"), is(false));
    }

    @Test
    public void reportsAtMostAFixedNumberOfMustacheVariablesThatDidNotResolve() throws Exception {
        StringBuilder template = new StringBuilder("{ \"statusCode\": 200, \"body\": \"");
        for (int i = 0; i < 30; i++) {
            template.append("{{missing").append(i).append("}}");
        }
        JsonNode report = body(dryRun(new TemplateDryRun(configuration()), input("MUSTACHE", template.append("\" }").toString())));

        assertThat(report.path("valid").asBoolean(), is(true));
        assertThat(report.path("warnings").size(), is(20));
        assertThat(report.path("warnings").get(0).path("message").asText(), startsWith("{{missing0}} did not resolve"));
    }

    @Test
    public void reportsAJavaScriptErrorInTemplateCoordinatesWithAHint() throws Exception {
        requireGraalJs();
        JsonNode report = body(dryRun(new TemplateDryRun(configuration()),
            input("JAVASCRIPT", "var status = 200;\nreturn { statusCode: status, body: requestBody };")));

        assertThat(report.path("valid").asBoolean(), is(false));
        assertThat(report.path("error").path("line").asInt(), is(2));
        assertThat(report.path("error").path("column").asInt(), is(36));
        assertThat(report.path("error").path("hint").asText(), startsWith("requestBody is not defined"));
    }

    @Test
    public void reportsOutputThatIsNotAValidResponseWithTheValidationErrorsAndTheOutput() throws Exception {
        JsonNode report = body(dryRun(new TemplateDryRun(configuration()),
            input("VELOCITY", "{ \"statusCode\": \"two hundred\" }")));

        assertThat(report.path("valid").asBoolean(), is(false));
        assertThat(report.path("error").path("message").asText(), containsString("serving it would answer with a 500"));
        assertThat(report.path("error").path("validationErrors").asText(), containsString("statusCode"));
        assertThat(report.path("renderedOutput").asText(), containsString("two hundred"));
    }

    @Test
    public void saysOutputThatIsNotValidWouldAnswerWithNotFoundOrBadGatewayWhenTemplateFailureIsNotAServerError() throws Exception {
        TemplateDryRun dryRun = new TemplateDryRun(configuration().templateFailureAsServerError(false));

        JsonNode responseReport = body(dryRun(dryRun, input("VELOCITY", "{ \"statusCode\": \"two hundred\" }")));
        JsonNode forwardReport = body(dryRun(dryRun, input("VELOCITY", "this is not a request").put("actionType", "FORWARD_TEMPLATE")));

        assertThat(responseReport.path("error").path("message").asText(), is("the template rendered output that is not a valid response, so serving it would answer with a 404"));
        assertThat(forwardReport.path("error").path("message").asText(), is("the template rendered output that is not a valid request, so serving it would answer with a 502"));
    }

    @Test
    public void reportsAForwardTemplateWhoseOutputIsNotARequest() throws Exception {
        ObjectNode input = input("VELOCITY", "this is not a request").put("actionType", "FORWARD_TEMPLATE");

        JsonNode report = body(dryRun(new TemplateDryRun(configuration()), input));

        assertThat(report.path("valid").asBoolean(), is(false));
        assertThat(report.path("error").path("message").asText(), containsString("serving it would answer with a 500"));
        assertThat(report.path("renderedOutput").asText(), is("this is not a request"));
    }

    @Test
    public void cutsOffAJavaScriptBusyLoopAtTheConfiguredTimeout() throws Exception {
        requireGraalJs();
        TemplateDryRun dryRun = new TemplateDryRun(configuration().javascriptTemplateExecutionTimeout(300L), 300L, 20_000L);
        long start = System.nanoTime();

        JsonNode report = body(dryRun(dryRun, input("JAVASCRIPT", "while (true) {}")));

        assertThat(report.path("valid").asBoolean(), is(false));
        assertThat(report.path("timedOut").asBoolean(), is(true));
        assertThat(report.path("error").path("message").asText(), containsString("exceeded the configured timeout of 300ms"));
        assertThat(report.path("error").path("message").asText(), not(containsString("set it to 0")));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), lessThan(20_000L));
        // the thread was released: the next dry run renders
        assertThat(body(dryRun(dryRun, input("JAVASCRIPT", "return { statusCode: 204 };"))).path("valid").asBoolean(), is(true));
    }

    @Test
    public void cutsOffAJavaScriptBusyLoopEvenWhenTheTimeoutIsDisabled() throws Exception {
        requireGraalJs();
        TemplateDryRun dryRun = new TemplateDryRun(configuration().javascriptTemplateExecutionTimeout(-1L), 300L, 20_000L);

        JsonNode report = body(dryRun(dryRun, input("JAVASCRIPT", "while (true) {}")));

        assertThat(report.path("timedOut").asBoolean(), is(true));
        assertThat(report.path("error").path("message").asText(), containsString("javascriptTemplateExecutionTimeout is 0 or less"));
        assertThat(report.path("error").path("message").asText(), containsString("a dry run always stops a JavaScript template after 300ms"));
        assertThat(report.path("error").path("message").asText(), not(containsString("set it to 0")));
    }

    @Test
    public void answersAtTheDeadlineWhileATemplateIsStillRunning() throws Exception {
        requireGraalJs();
        // a negative grace puts the response deadline before the template's own timeout
        TemplateDryRun dryRun = new TemplateDryRun(configuration().javascriptTemplateExecutionTimeout(3_000L), 3_000L, -2_700L);
        long start = System.nanoTime();

        JsonNode report = body(dryRun(dryRun, input("JAVASCRIPT", "while (true) {}")));

        assertThat(report.path("timedOut").asBoolean(), is(true));
        assertThat(report.path("error").path("message").asText(), startsWith("the template did not finish within 300ms"));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), lessThan(2_500L));
    }

    @Test
    public void stopsAVelocityLoopAtTheVelocityTimeout() throws Exception {
        TemplateDryRun dryRun = new TemplateDryRun(configuration().velocityTemplateExecutionTimeout(300L).javascriptTemplateExecutionTimeout(20_000L), 5_000L, 20_000L);
        long start = System.nanoTime();

        JsonNode report = body(dryRun(dryRun, input("VELOCITY", VELOCITY_LOOPS_FOREVER)));

        assertThat(report.path("valid").asBoolean(), is(false));
        assertThat(report.path("timedOut").asBoolean(), is(true));
        assertThat(report.path("error").path("message").asText(), containsString("exceeded the configured timeout of 300ms"));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), lessThan(20_000L));
        // the thread was released: the next dry run renders
        assertThat(body(dryRun(dryRun, input("VELOCITY", "{ \"statusCode\": 204 }"))).path("valid").asBoolean(), is(true));
    }

    @Test
    public void stopsAVelocityLoopEvenWhenTheVelocityTimeoutIsDisabled() throws Exception {
        TemplateDryRun dryRun = new TemplateDryRun(configuration().velocityTemplateExecutionTimeout(0L), 300L, 2_000L);

        JsonNode report = body(dryRun(dryRun, input("VELOCITY", VELOCITY_LOOPS_FOREVER)));

        assertThat(report.path("timedOut").asBoolean(), is(true));
        assertThat(report.path("error").path("message").asText(), containsString("a dry run always stops a Velocity template after 300ms"));
        assertThat(report.path("error").path("message").asText(), not(containsString("set it to 0")));
        assertThat(body(dryRun(dryRun, input("VELOCITY", "{ \"statusCode\": 204 }"))).path("valid").asBoolean(), is(true));
    }

    @Test
    public void answersAtTheVelocityDeadlineWhileAVelocityTemplateIsStillRunning() throws Exception {
        // a negative grace puts the response deadline before the template's own timeout
        TemplateDryRun dryRun = new TemplateDryRun(configuration().velocityTemplateExecutionTimeout(3_000L).javascriptTemplateExecutionTimeout(20_000L), 3_000L, -2_700L);

        JsonNode report = body(dryRun(dryRun, input("VELOCITY", VELOCITY_LOOPS_FOREVER)));

        assertThat(report.path("timedOut").asBoolean(), is(true));
        assertThat(report.path("error").path("message").asText(), startsWith("the template did not finish within 300ms"));
        assertThat(report.path("error").path("message").asText(), containsString("long Java call"));
        assertThat(report.path("error").path("message").asText(), not(containsString("cannot be cancelled")));
    }

    @Test
    public void capsAnEngineMessageSoTheReportStaysSmall() throws Exception {
        requireGraalJs();
        HttpResponse response = dryRun(new TemplateDryRun(configuration()),
            input("JAVASCRIPT", "throw new Error('x'.repeat(3000000));"));

        JsonNode report = body(response);
        assertThat(report.path("valid").asBoolean(), is(false));
        assertThat(report.path("error").path("message").asText().length(), is(TemplateDryRun.MAX_REPORTED_MESSAGE_CHARS));
        assertThat(report.path("error").path("messageTruncated").asBoolean(), is(true));
        assertThat(response.getBodyAsString().length(), lessThan(4 * TemplateDryRun.MAX_REPORTED_MESSAGE_CHARS));
    }

    @Test
    public void capsWarningsCopiedFromTheTemplate() throws Exception {
        ScenarioStateTemplateTestLock.LOCK.lock();
        try {
            StringBuilder template = new StringBuilder();
            for (int i = 0; i < 60; i++) {
                template.append("$scenario.set('s").append(i).append("', '").append("y".repeat(100_000)).append("')");
            }
            template.append("{ \"statusCode\": 200 }");

            HttpResponse response = dryRun(new TemplateDryRun(configuration()), input("VELOCITY", template.toString()));

            JsonNode warnings = body(response).path("warnings");
            assertThat(warnings.size(), is(TemplateDryRun.MAX_WARNINGS));
            assertThat(warnings.get(0).path("message").asText().length(), is(TemplateDryRun.MAX_REPORTED_WARNING_CHARS));
            assertThat(warnings.get(0).path("messageTruncated").asBoolean(), is(true));
            assertThat(response.getBodyAsString().length(), lessThan(2 * TemplateDryRun.MAX_WARNINGS * TemplateDryRun.MAX_REPORTED_WARNING_CHARS));
        } finally {
            ScenarioStateTemplateTestLock.LOCK.unlock();
        }
    }

    @Test
    public void keepsTheServingSandbox() throws Exception {
        requireGraalJs();
        TemplateDryRun dryRun = new TemplateDryRun(configuration());

        JsonNode javaScript = body(dryRun(dryRun, input("JAVASCRIPT",
            "var runtime = Java.type('java.lang.Runtime'); return { statusCode: 200, body: '' + runtime.getRuntime() };")));
        assertThat(javaScript.path("valid").asBoolean(), is(false));
        assertThat(javaScript.path("error").path("message").asText(), containsString("java.lang.Runtime"));

        JsonNode velocity = body(dryRun(dryRun, input("VELOCITY",
            "{ \"statusCode\": 200, \"body\": \"$request.getClass().forName('java.lang.Runtime')\" }")));
        assertThat(velocity.path("valid").asBoolean(), is(true));
        assertThat(velocity.path("renderedResponse").path("body").asText(), is("$request.getClass().forName('java.lang.Runtime')"));
    }

    @Test
    public void refusesADryRunWhileTheMaximumAreRunning() throws Exception {
        requireGraalJs();
        TemplateDryRun dryRun = new TemplateDryRun(configuration().javascriptTemplateExecutionTimeout(2_000L));
        ObjectNode busyLoop = input("JAVASCRIPT", "while (true) {}");
        CompletableFuture<?>[] running = new CompletableFuture<?>[TemplateDryRun.MAX_CONCURRENT_DRY_RUNS];
        for (int i = 0; i < running.length; i++) {
            running[i] = dryRun.test(request().withMethod("PUT").withPath("/mockserver/template/test").withBody(busyLoop.toString()));
        }

        HttpResponse refused = dryRun(dryRun, input("VELOCITY", "{ \"statusCode\": 200 }"));

        assertThat(refused.getStatusCode(), is(429));
        assertThat(body(refused).path("error").asText(), containsString("already running"));
        assertThat(body(refused).path("error").asText(), containsString("a Velocity template inside one long Java call keeps it until that call returns"));
        CompletableFuture.allOf(running).get(30, TimeUnit.SECONDS);
        assertThat(dryRun(dryRun, input("VELOCITY", "{ \"statusCode\": 200 }")).getStatusCode(), is(200));
    }

    @Test
    public void rejectsInvalidInputWithABadRequest() throws Exception {
        TemplateDryRun dryRun = new TemplateDryRun(configuration());

        assertBadRequest(dryRun, "", "a body is required");
        assertBadRequest(dryRun, "[]", "must be a JSON object");
        assertBadRequest(dryRun, "{\"templateType\":\"FREEMARKER\",\"template\":\"x\"}", "templateType must be");
        assertBadRequest(dryRun, "{\"templateType\":\"VELOCITY\"}", "template is required");
        assertBadRequest(dryRun, "{\"templateType\":\"VELOCITY\",\"template\":\"x\",\"actionType\":\"RESPONSE\"}", "actionType must be");
        assertBadRequest(dryRun, "{\"templateType\":\"VELOCITY\",\"template\":\"x\",\"request\":{\"method\":7}}", "request is not a valid request");
    }

    private static void assertBadRequest(TemplateDryRun dryRun, String body, String error) throws Exception {
        HttpResponse response = dryRun.test(request().withMethod("PUT").withPath("/mockserver/template/test").withBody(body)).get(30, TimeUnit.SECONDS);
        assertThat(response.getStatusCode(), is(400));
        assertThat(body(response).path("error").asText(), containsString(error));
    }

    private static void requireGraalJs() {
        assertThat("GraalJS is on mockserver-core's test classpath", JavaScriptTemplateEngine.isPolyglotAvailable(), is(true));
    }
}
