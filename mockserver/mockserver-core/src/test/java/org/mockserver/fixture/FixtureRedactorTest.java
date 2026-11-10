package org.mockserver.fixture;

import org.junit.Test;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.model.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.fixture.FixtureRedactor.REDACTED_PLACEHOLDER;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.model.HttpSseResponse.sseResponse;
import static org.mockserver.model.SseEvent.sseEvent;

public class FixtureRedactorTest {

    private final FixtureRedactor redactor = new FixtureRedactor();

    private static HttpRequest requestOf(Expectation expectation) {
        return (HttpRequest) expectation.getHttpRequest();
    }

    // --- Request header redaction ---

    @Test
    public void shouldRedactAuthorizationHeader() {
        // given
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/api")
                .withHeader("Authorization", "Bearer sk-secret-key-123")
                .withHeader("Content-Type", "application/json")
        ).thenRespond(response().withStatusCode(200));

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        // then
        assertThat(requestOf(redacted[0]).getFirstHeader("Authorization"), is(REDACTED_PLACEHOLDER));
        assertThat(requestOf(redacted[0]).getFirstHeader("Content-Type"), is("application/json"));
    }

    @Test
    public void shouldRedactXApiKeyHeader() {
        // given
        Expectation expectation = Expectation.when(
            request().withHeader("x-api-key", "my-secret-api-key")
        ).thenRespond(response().withStatusCode(200));

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        // then
        assertThat(requestOf(redacted[0]).getFirstHeader("x-api-key"), is(REDACTED_PLACEHOLDER));
    }

    @Test
    public void shouldRedactApiKeyHeader() {
        // given
        Expectation expectation = Expectation.when(
            request().withHeader("api-key", "secret")
        ).thenRespond(response().withStatusCode(200));

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        // then
        assertThat(requestOf(redacted[0]).getFirstHeader("api-key"), is(REDACTED_PLACEHOLDER));
    }

    @Test
    public void shouldRedactCookieHeader() {
        // given
        Expectation expectation = Expectation.when(
            request().withHeader("Cookie", "session=abc123")
        ).thenRespond(response().withStatusCode(200));

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        // then
        assertThat(requestOf(redacted[0]).getFirstHeader("Cookie"), is(REDACTED_PLACEHOLDER));
    }

    @Test
    public void shouldRedactProxyAuthorizationHeader() {
        // given
        Expectation expectation = Expectation.when(
            request().withHeader("Proxy-Authorization", "Basic dXNlcjpwYXNz")
        ).thenRespond(response().withStatusCode(200));

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        // then
        assertThat(requestOf(redacted[0]).getFirstHeader("Proxy-Authorization"), is(REDACTED_PLACEHOLDER));
    }

    // --- Response header redaction ---

    @Test
    public void shouldRedactSetCookieInResponse() {
        // given
        Expectation expectation = Expectation.when(
            request().withMethod("GET").withPath("/login")
        ).thenRespond(
            response().withStatusCode(200)
                .withHeader("Set-Cookie", "session=xyz789")
                .withHeader("Content-Type", "text/html")
        );

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        // then
        assertThat(redacted[0].getHttpResponse().getFirstHeader("Set-Cookie"), is(REDACTED_PLACEHOLDER));
        assertThat(redacted[0].getHttpResponse().getFirstHeader("Content-Type"), is("text/html"));
    }

    // --- Non-sensitive headers preserved ---

    @Test
    public void shouldPreserveNonSensitiveHeaders() {
        // given
        Expectation expectation = Expectation.when(
            request().withMethod("GET").withPath("/data")
                .withHeader("Accept", "application/json")
                .withHeader("User-Agent", "TestClient/1.0")
        ).thenRespond(
            response().withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withHeader("X-Request-Id", "abc-123")
        );

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        // then
        assertThat(requestOf(redacted[0]).getFirstHeader("Accept"), is("application/json"));
        assertThat(requestOf(redacted[0]).getFirstHeader("User-Agent"), is("TestClient/1.0"));
        assertThat(redacted[0].getHttpResponse().getFirstHeader("Content-Type"), is("application/json"));
        assertThat(redacted[0].getHttpResponse().getFirstHeader("X-Request-Id"), is("abc-123"));
    }

    // --- Case-insensitive matching ---

    @Test
    public void shouldRedactHeadersCaseInsensitively() {
        // given
        Expectation expectation = Expectation.when(
            request().withHeader("AUTHORIZATION", "Bearer secret")
                .withHeader("X-API-KEY", "secret")
        ).thenRespond(response().withStatusCode(200));

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        // then
        assertThat(requestOf(redacted[0]).getFirstHeader("AUTHORIZATION"), is(REDACTED_PLACEHOLDER));
        assertThat(requestOf(redacted[0]).getFirstHeader("X-API-KEY"), is(REDACTED_PLACEHOLDER));
    }

    // --- Custom redaction list ---

    @Test
    public void shouldRedactCustomHeaders() {
        // given
        FixtureRedactor customRedactor = new FixtureRedactor(Arrays.asList("X-Custom-Secret", "X-Internal-Token"));
        Expectation expectation = Expectation.when(
            request()
                .withHeader("X-Custom-Secret", "secret-value")
                .withHeader("X-Internal-Token", "token-value")
                .withHeader("Authorization", "Bearer should-not-redact") // not in custom list
        ).thenRespond(response().withStatusCode(200));

        // when
        Expectation[] redacted = customRedactor.redact(new Expectation[]{expectation});

        // then
        assertThat(requestOf(redacted[0]).getFirstHeader("X-Custom-Secret"), is(REDACTED_PLACEHOLDER));
        assertThat(requestOf(redacted[0]).getFirstHeader("X-Internal-Token"), is(REDACTED_PLACEHOLDER));
        // Authorization is NOT in the custom list, so it should be preserved
        assertThat(requestOf(redacted[0]).getFirstHeader("Authorization"), is("Bearer should-not-redact"));
    }

    // --- Copies, not live entries ---

    @Test
    public void shouldNotMutateOriginalExpectation() {
        // given
        Expectation original = Expectation.when(
            request().withHeader("Authorization", "Bearer secret-key")
        ).thenRespond(response().withStatusCode(200).withHeader("Set-Cookie", "session=abc"));

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{original});

        // then -- originals unchanged
        assertThat(requestOf(original).getFirstHeader("Authorization"), is("Bearer secret-key"));
        assertThat(original.getHttpResponse().getFirstHeader("Set-Cookie"), is("session=abc"));

        // redacted copies changed
        assertThat(requestOf(redacted[0]).getFirstHeader("Authorization"), is(REDACTED_PLACEHOLDER));
        assertThat(redacted[0].getHttpResponse().getFirstHeader("Set-Cookie"), is(REDACTED_PLACEHOLDER));
    }

    // --- Multiple expectations ---

    @Test
    public void shouldRedactMultipleExpectations() {
        // given
        Expectation[] expectations = {
            Expectation.when(
                request().withHeader("Authorization", "Bearer key1")
            ).thenRespond(response().withStatusCode(200)),
            Expectation.when(
                request().withHeader("x-api-key", "key2")
            ).thenRespond(response().withStatusCode(201))
        };

        // when
        Expectation[] redacted = redactor.redact(expectations);

        // then
        assertThat(redacted.length, is(2));
        assertThat(requestOf(redacted[0]).getFirstHeader("Authorization"), is(REDACTED_PLACEHOLDER));
        assertThat(requestOf(redacted[1]).getFirstHeader("x-api-key"), is(REDACTED_PLACEHOLDER));
    }

    // --- Null/empty handling ---

    @Test
    public void shouldHandleNullInput() {
        // when
        Expectation[] redacted = redactor.redact(null);

        // then
        assertThat(redacted.length, is(0));
    }

    @Test
    public void shouldHandleEmptyArray() {
        // when
        Expectation[] redacted = redactor.redact(new Expectation[0]);

        // then
        assertThat(redacted.length, is(0));
    }

    // --- SSE response header redaction ---

    @Test
    public void shouldRedactHeadersInSseResponse() {
        // given
        Expectation expectation = new Expectation(
            request().withMethod("GET").withPath("/stream")
                .withHeader("Authorization", "Bearer secret"),
            Times.unlimited(),
            TimeToLive.unlimited(),
            0
        ).thenRespondWithSse(
            sseResponse()
                .withStatusCode(200)
                .withHeader("Set-Cookie", "session=xyz")
                .withHeader("X-Custom", "visible")
                .withEvent(sseEvent().withData("test"))
        );

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        // then -- request header redacted
        assertThat(requestOf(redacted[0]).getFirstHeader("Authorization"), is(REDACTED_PLACEHOLDER));
        // SSE response headers redacted
        HttpSseResponse sseResp = redacted[0].getHttpSseResponse();
        boolean foundSetCookie = false;
        boolean foundCustom = false;
        for (Header header : sseResp.getHeaders().getEntries()) {
            if (header.getName().getValue().equalsIgnoreCase("Set-Cookie")) {
                assertThat(header.getValues().get(0).getValue(), is(REDACTED_PLACEHOLDER));
                foundSetCookie = true;
            }
            if (header.getName().getValue().equalsIgnoreCase("X-Custom")) {
                assertThat(header.getValues().get(0).getValue(), is("visible"));
                foundCustom = true;
            }
        }
        assertThat("Set-Cookie header should be found and redacted", foundSetCookie, is(true));
        assertThat("X-Custom header should be found and not redacted", foundCustom, is(true));
    }

    // --- Expectation without headers ---

    @Test
    public void shouldHandleExpectationWithNoHeaders() {
        // given
        Expectation expectation = Expectation.when(
            request().withMethod("GET").withPath("/no-headers")
        ).thenRespond(response().withStatusCode(200).withBody("ok"));

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        // then
        assertThat(redacted[0].getHttpResponse().getBodyAsString(), is("ok"));
    }

    // --- Body field redaction ---

    private final FixtureRedactor bodyRedactor = new FixtureRedactor(
        Arrays.asList("Authorization"), Arrays.asList("api_key", "password"));

    @Test
    public void shouldRedactConfiguredJsonBodyFieldsInRequest() {
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/api")
                .withBody("{\"api_key\":\"sk-secret\",\"prompt\":\"hello\"}")
        ).thenRespond(response().withStatusCode(200));

        Expectation[] redacted = bodyRedactor.redact(new Expectation[]{expectation});

        String body = requestOf(redacted[0]).getBodyAsString();
        assertThat(body.contains("sk-secret"), is(false));
        assertThat(body.contains(REDACTED_PLACEHOLDER), is(true));
        assertThat(body.contains("hello"), is(true));
    }

    @Test
    public void shouldRedactNestedJsonBodyFields() {
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/api")
                .withBody("{\"outer\":{\"password\":\"p@ss\",\"keep\":1}}")
        ).thenRespond(response().withStatusCode(200));

        Expectation[] redacted = bodyRedactor.redact(new Expectation[]{expectation});

        String body = requestOf(redacted[0]).getBodyAsString();
        assertThat(body.contains("p@ss"), is(false));
        assertThat(body.contains(REDACTED_PLACEHOLDER), is(true));
        assertThat(body.contains("keep"), is(true));
    }

    @Test
    public void shouldRedactConfiguredJsonBodyFieldsInResponse() {
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/api")
        ).thenRespond(response().withStatusCode(200).withBody("{\"password\":\"secret\",\"ok\":true}"));

        Expectation[] redacted = bodyRedactor.redact(new Expectation[]{expectation});

        assertThat(redacted[0].getHttpResponse().getBodyAsString().contains("secret"), is(false));
    }

    @Test
    public void shouldFailClosedOnUnparseableBodyWhenBodyRedactionConfigured() {
        // a body that is neither a JSON document nor an SSE stream, but for which
        // field redaction is configured, is replaced wholesale (fail closed) so a
        // credential hidden in an unparseable payload cannot leak into a fixture
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/api").withBody("not json api_key=sk-secret")
        ).thenRespond(response().withStatusCode(200));

        Expectation[] redacted = bodyRedactor.redact(new Expectation[]{expectation});

        String body = requestOf(redacted[0]).getBodyAsString();
        assertThat(body.contains("sk-secret"), is(false));
        assertThat(body, is(FixtureRedactor.UNPARSEABLE_BODY_PLACEHOLDER));
    }

    @Test
    public void shouldLeaveUnstructuredBodyWithoutSecretFieldUnchanged() {
        // an ordinary non-JSON body (plain text / HTML / decoded binary) that does
        // not mention any configured field name is preserved — there is nothing to
        // redact, so it must NOT be destroyed by the fail-closed path
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/api")
        ).thenRespond(response().withStatusCode(200).withBody("Hello, World!"));

        Expectation[] redacted = bodyRedactor.redact(new Expectation[]{expectation});

        assertThat(redacted[0].getHttpResponse().getBodyAsString(), is("Hello, World!"));
    }

    @Test
    public void shouldLeaveScalarJsonBodyUnchangedWhenBodyRedactionConfigured() {
        // a scalar JSON value parses cleanly and cannot carry a named field — it is
        // left intact rather than fail-closed (it is structurally inspectable)
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/api").withBody("\"just a string\"")
        ).thenRespond(response().withStatusCode(200));

        Expectation[] redacted = bodyRedactor.redact(new Expectation[]{expectation});

        assertThat(requestOf(redacted[0]).getBodyAsString(), is("\"just a string\""));
    }

    @Test
    public void shouldRedactConfiguredFieldsInsideSseStreamBody() {
        // a streamed (SSE) body has its configured fields redacted per data: payload,
        // preserving the event structure and non-JSON markers such as [DONE]
        String sse = "event: message\n"
            + "data: {\"api_key\":\"sk-secret\",\"text\":\"hi\"}\n"
            + "\n"
            + "data: [DONE]\n";
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/api")
        ).thenRespond(response().withStatusCode(200).withBody(sse));

        Expectation[] redacted = bodyRedactor.redact(new Expectation[]{expectation});

        String body = redacted[0].getHttpResponse().getBodyAsString();
        assertThat(body.contains("sk-secret"), is(false));
        assertThat(body.contains(REDACTED_PLACEHOLDER), is(true));
        assertThat(body.contains("hi"), is(true));
        assertThat(body.contains("[DONE]"), is(true));
        assertThat(body.contains("event: message"), is(true));
    }

    @Test
    public void shouldFailClosedOnUnparseableSseDataPayloadMentioningSecretField() {
        // a data: payload that mentions a configured field but is not parseable as a
        // standalone JSON object/array (e.g. a truncated chunk) is failed closed,
        // while a plain marker like [DONE] is left intact
        String sse = "data: {\"api_key\":\"sk-secret\",\"truncated\n"
            + "\n"
            + "data: [DONE]\n";
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/api")
        ).thenRespond(response().withStatusCode(200).withBody(sse));

        Expectation[] redacted = bodyRedactor.redact(new Expectation[]{expectation});

        String body = redacted[0].getHttpResponse().getBodyAsString();
        assertThat(body.contains("sk-secret"), is(false));
        assertThat(body.contains(FixtureRedactor.UNPARSEABLE_BODY_PLACEHOLDER), is(true));
        assertThat(body.contains("[DONE]"), is(true));
    }

    @Test
    public void shouldRedactConfiguredFieldsInSseResponseEventData() {
        SseEvent secretEvent = sseEvent()
            .withEvent("message")
            .withData("{\"api_key\":\"sk-secret\",\"text\":\"hi\"}")
            .withId("1")
            .withRetry(1000)
            .withComment("kept")
            .withDelay(TimeUnit.MILLISECONDS, 50);
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/api")
        ).thenRespondWithSse(
            sseResponse()
                .withStatusCode(200)
                .withTemplateType(HttpTemplate.TemplateType.VELOCITY)
                .withEvent(secretEvent)
                .withEvent(sseEvent().withData("plain text, not json"))
                .withEvent(sseEvent().withData("{\"api_key\":\"sk-secret\",\"truncated"))
                .withEvent(sseEvent().withData("[DONE]"))
        );

        Expectation[] redacted = bodyRedactor.redact(new Expectation[]{expectation});

        HttpSseResponse sseResp = redacted[0].getHttpSseResponse();
        List<SseEvent> events = sseResp.getEvents();
        assertThat(events.size(), is(4));
        assertThat(events.get(0).getData(), is("{\"api_key\":\"" + REDACTED_PLACEHOLDER + "\",\"text\":\"hi\"}"));
        assertThat(events.get(0).getEvent(), is("message"));
        assertThat(events.get(0).getId(), is("1"));
        assertThat(events.get(0).getRetry(), is(1000));
        assertThat(events.get(0).getComment(), is("kept"));
        assertThat(events.get(0).getDelay(), is(new Delay(TimeUnit.MILLISECONDS, 50)));
        assertThat(events.get(1).getData(), is("plain text, not json"));
        assertThat(events.get(2).getData(), is(FixtureRedactor.UNPARSEABLE_BODY_PLACEHOLDER));
        assertThat(events.get(3).getData(), is("[DONE]"));
        assertThat(sseResp.getTemplateType(), is(HttpTemplate.TemplateType.VELOCITY));
        // the source event is never mutated
        assertThat(secretEvent.getData(), is("{\"api_key\":\"sk-secret\",\"text\":\"hi\"}"));
    }

    @Test
    public void shouldRedactAnSseCommentThatNamesAConfiguredField() {
        Expectation expectation = Expectation.when(request().withPath("/sse")).thenRespondWithSse(
            sseResponse()
                .withEvent(sseEvent().withComment("api_key=sk-secret").withDelay(TimeUnit.MILLISECONDS, 5))
                .withEvent(sseEvent().withComment("heartbeat"))
                .withEvent(sseEvent().withData("plain").withComment("token api_key sk-secret"))
        );

        List<SseEvent> events = bodyRedactor.redact(new Expectation[]{expectation})[0].getHttpSseResponse().getEvents();

        assertThat(events.get(0).getComment(), is(FixtureRedactor.UNPARSEABLE_BODY_PLACEHOLDER));
        assertThat(events.get(0).getDelay(), is(new Delay(TimeUnit.MILLISECONDS, 5)));
        assertThat(events.get(1).getComment(), is("heartbeat"));
        assertThat(events.get(2).getComment(), is(FixtureRedactor.UNPARSEABLE_BODY_PLACEHOLDER));
        assertThat(events.get(2).getData(), is("plain"));
    }

    @Test
    public void shouldLeaveSseResponseEventsUnchangedWhenNoBodyFieldsConfigured() {
        List<SseEvent> events = Arrays.asList(
            sseEvent().withEvent("message").withData("{\"api_key\":\"sk-secret\"}").withId("1"),
            sseEvent().withData("[DONE]")
        );
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/api")
        ).thenRespondWithSse(sseResponse().withStatusCode(200).withEvents(events));

        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        assertThat(redacted[0].getHttpSseResponse().getEvents(), is(events));
    }

    @Test
    public void defaultRedactorDoesNotTouchBodies() {
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/api").withBody("{\"api_key\":\"sk-secret\"}")
        ).thenRespond(response().withStatusCode(200));

        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        assertThat(requestOf(redacted[0]).getBodyAsString().contains("sk-secret"), is(true));
    }

    // --- Query-string redaction ---

    @Test
    public void shouldRedactSensitiveQueryParameterValuesByDefault() {
        // Gemini-style ?key=<API_KEY> and other credential-bearing query params are
        // redacted by the default redactor, while ordinary params are preserved
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/v1beta/models/x:generateContent")
                .withQueryStringParameter("key", "AIza-super-secret")
                .withQueryStringParameter("alt", "sse")
        ).thenRespond(response().withStatusCode(200));

        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        HttpRequest req = requestOf(redacted[0]);
        assertThat(req.getFirstQueryStringParameter("key"), is(REDACTED_PLACEHOLDER));
        assertThat(req.getFirstQueryStringParameter("alt"), is("sse"));
    }

    @Test
    public void shouldRedactAwsSigV4QueryCredentials() {
        Expectation expectation = Expectation.when(
            request().withMethod("GET").withPath("/object")
                .withQueryStringParameter("X-Amz-Signature", "deadbeef")
                .withQueryStringParameter("X-Amz-Security-Token", "FwoG-token")
                .withQueryStringParameter("X-Amz-Date", "20260629T000000Z")
        ).thenRespond(response().withStatusCode(200));

        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        HttpRequest req = requestOf(redacted[0]);
        assertThat(req.getFirstQueryStringParameter("X-Amz-Signature"), is(REDACTED_PLACEHOLDER));
        assertThat(req.getFirstQueryStringParameter("X-Amz-Security-Token"), is(REDACTED_PLACEHOLDER));
        assertThat(req.getFirstQueryStringParameter("X-Amz-Date"), is("20260629T000000Z"));
    }

    @Test
    public void shouldRedactAndPreserveMultiResponseSequentialList() {
        // given - a consolidated expectation with a SEQUENTIAL two-response list, each
        // carrying a secret header (the single-response redactor would have dropped the list)
        Expectation expectation = new Expectation(request().withMethod("GET").withPath("/token"))
            .withResponseMode(org.mockserver.mock.ResponseMode.SEQUENTIAL)
            .thenRespond(Arrays.asList(
                response().withStatusCode(200).withHeader("Set-Cookie", "session=first-secret"),
                response().withStatusCode(200).withHeader("Set-Cookie", "session=second-secret")
            ));

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        // then - the list is preserved (both responses), SEQUENTIAL mode kept, secrets masked
        assertThat(redacted[0].getHttpResponses().size(), is(2));
        assertThat(redacted[0].getResponseMode(), is(org.mockserver.mock.ResponseMode.SEQUENTIAL));
        assertThat(redacted[0].getHttpResponses().get(0).getFirstHeader("Set-Cookie"), is(REDACTED_PLACEHOLDER));
        assertThat(redacted[0].getHttpResponses().get(1).getFirstHeader("Set-Cookie"), is(REDACTED_PLACEHOLDER));
    }

    // --- Parsed cookie lists ---

    // The cookie list is serialized apart from the Cookie / Set-Cookie headers (JSON cookies, HAR) and the
    // cURL form rebuilds a Cookie header from it, so masking only the headers left every value readable.
    @Test
    public void shouldRedactParsedRequestAndResponseCookies() {
        HttpRequest request = (HttpRequest) redactor.redactRequestDefinition(
            request("/api").withHeader("Cookie", "session=cookie-secret").withCookie("session", "cookie-secret"));
        HttpResponse response = redactor.redactResponseObject(
            response().withHeader("Set-Cookie", "sid=set-cookie-secret").withCookie("sid", "set-cookie-secret"));

        assertThat(request.getCookieList().get(0).getName().getValue(), is("session"));
        assertThat(request.getCookieList().get(0).getValue().getValue(), is(REDACTED_PLACEHOLDER));
        assertThat(request.toString().contains("cookie-secret"), is(false));
        assertThat(response.getCookieList().get(0).getValue().getValue(), is(REDACTED_PLACEHOLDER));
        assertThat(response.toString().contains("set-cookie-secret"), is(false));
    }

    @Test
    public void shouldLeaveCookiesWhenCookieIsNotASensitiveHeader() {
        HttpRequest request = (HttpRequest) new FixtureRedactor(Arrays.asList("Authorization"))
            .redactRequestDefinition(request("/api").withCookie("session", "not-a-secret-here"));

        assertThat(request.getCookieList().get(0).getValue().getValue(), is("not-a-secret-here"));
    }

    // --- Free-text scrubbing ---

    @Test
    public void shouldCollectCredentialValuesBeforeBodyFieldValues() {
        HttpRequest request = request("/api")
            .withHeader("Authorization", "Bearer token-value")
            .withHeader("Cookie", "session=cookie-value; theme=dark")
            .withHeader("Accept", "not-sensitive")
            .withQueryStringParameter("key", "query-value")
            .withBody(json("{\"password\":\"body-value\",\"remember\":true,\"pin\":\"12\"}"));
        HttpResponse response = response().withHeader("Set-Cookie", "sid=set-cookie-value; Path=/");

        List<String> values = new FixtureRedactor(FixtureRedactor.defaultSensitiveHeaders(), Arrays.asList("password", "remember", "pin"))
            .sensitiveValues(new RequestDefinition[]{request}, response);

        assertThat(values, hasItems("Bearer token-value", "token-value", "session=cookie-value; theme=dark", "session=cookie-value",
            "theme=dark", "cookie-value", "query-value", "body-value", "sid=set-cookie-value; Path=/", "sid=set-cookie-value", "set-cookie-value"));
        // too short to search for in free text (a bare cookie value needs MIN_SCRUBBED_COOKIE_VALUE_LENGTH),
        // a common word, or not a sensitive place
        assertThat(values, not(hasItems("12")));
        assertThat(values, not(hasItems("dark")));
        assertThat(values, not(hasItems("true")));
        assertThat(values, not(hasItems("not-sensitive")));
        // the matcher keeps values in this order while they fit its bounds
        for (String credential : Arrays.asList("Bearer token-value", "token-value", "cookie-value", "query-value", "set-cookie-value")) {
            assertThat(credential, values.indexOf(credential) < values.indexOf("body-value"), is(true));
        }
    }

    @Test
    public void shouldRedactSensitiveFieldInNonUtf8BodyWithoutContentType() {
        // a Latin-1 body with no Content-Type is kept as binary; redaction reads it as text, not base64
        FixtureRedactor passwordRedactor = new FixtureRedactor(FixtureRedactor.defaultSensitiveHeaders(), Collections.singletonList("password"));
        HttpRequest request = request("/login").withBody(latin1WithoutContentType("{\"user\":\"José\",\"password\":\"hunter2-secret\"}"));
        HttpResponse response = response().withBody(latin1WithoutContentType("{\"user\":\"José\",\"password\":\"hunter2-response\"}"));

        String redactedRequestBody = ((HttpRequest) passwordRedactor.redactRequestDefinition(request)).getBodyAsText();
        String redactedResponseBody = passwordRedactor.redactResponseObject(response).getBodyAsText();
        List<String> values = passwordRedactor.sensitiveValues(new RequestDefinition[]{request}, response);

        assertThat(redactedRequestBody.contains(REDACTED_PLACEHOLDER), is(true));
        assertThat(redactedRequestBody.contains("hunter2-secret"), is(false));
        assertThat(redactedResponseBody.contains(REDACTED_PLACEHOLDER), is(true));
        assertThat(redactedResponseBody.contains("hunter2-response"), is(false));
        assertThat(values, hasItems("hunter2-secret", "hunter2-response"));
    }

    @Test
    public void shouldFailClosedOnUnparseableNonUtf8BodyWithoutContentType() {
        FixtureRedactor passwordRedactor = new FixtureRedactor(FixtureRedactor.defaultSensitiveHeaders(), Collections.singletonList("password"));
        HttpRequest request = request("/login").withBody(latin1WithoutContentType("{\"user\":\"José\",\"password\":\"hunter2-secret\""));

        String redactedBody = ((HttpRequest) passwordRedactor.redactRequestDefinition(request)).getBodyAsText();

        assertThat(redactedBody, is(FixtureRedactor.UNPARSEABLE_BODY_PLACEHOLDER));
    }

    private static BodyWithContentType<?> latin1WithoutContentType(String text) {
        BodyWithContentType<?> body = new org.mockserver.codec.BodyDecoderEncoder().bytesToBody(text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), null);
        assertThat(body.getType(), is(Body.Type.BINARY));
        return body;
    }

    @Test
    public void shouldCollectEveryRequestAndResponseCredentialBeforeAnyBodyFieldValue() {
        HttpRequest request = request("/api").withBody(json("{\"password\":\"request-body-value\"}"));
        HttpResponse first = response().withBody(json("{\"password\":\"response-body-value\"}"));
        HttpResponse second = response().withHeader("Set-Cookie", "sid=late-set-cookie-value; Path=/");

        List<String> values = new FixtureRedactor(FixtureRedactor.defaultSensitiveHeaders(), Collections.singletonList("password"))
            .sensitiveValues(Collections.<RequestDefinition>singletonList(request), Arrays.asList(first, second));

        for (String body : Arrays.asList("request-body-value", "response-body-value")) {
            assertThat(body, values.indexOf("late-set-cookie-value") < values.indexOf(body), is(true));
        }
    }

    @Test
    public void shouldCollectTheTextOfANestedSensitiveObjectOnceNotAtEveryLevel() {
        StringBuilder body = new StringBuilder("{\"password\":");
        int depth = 200;
        for (int i = 0; i < depth; i++) {
            body.append("{\"a\":");
        }
        body.append("\"leaf-secret-value-");
        for (int i = 0; i < 1_000; i++) {
            body.append((char) ('a' + i % 26));
        }
        body.append("\"");
        for (int i = 0; i < depth; i++) {
            body.append('}');
        }
        body.append('}');

        List<String> values = new FixtureRedactor(FixtureRedactor.defaultSensitiveHeaders(), Collections.singletonList("password"))
            .sensitiveValues(new RequestDefinition[]{request("/").withBody(json(body.toString()))}, null);

        long total = values.stream().mapToLong(String::length).sum();
        // the leaf and the whole object once, each with its JSON-escaped form: the text of each of the 200 levels would
        // be about 200 x the body
        assertThat("collected " + total + " characters from a " + body.length() + " character body", total <= 4L * body.length(), is(true));
        assertThat(values.stream().anyMatch(value -> value.startsWith("leaf-secret-value-")), is(true));
    }

    @Test
    public void shouldNotSearchFreeTextForValuesBeyondItsBounds() {
        StringBuilder huge = new StringBuilder();
        while (huge.length() <= SensitiveValueMatcher.MAX_VALUE_LENGTH) {
            huge.append("0123456789");
        }
        List<String> values = new ArrayList<>();
        values.add("first-credential");
        values.add(huge.toString());
        Random random = new Random(11L);
        values.addAll(distinctValues(random, SensitiveValueMatcher.MAX_VALUES + 5_000));

        SensitiveValueMatcher matcher = SensitiveValueMatcher.of(values);

        assertThat(matcher.valueCount() <= SensitiveValueMatcher.MAX_VALUES, is(true));
        assertThat(matcher.totalLength() <= SensitiveValueMatcher.MAX_TOTAL_LENGTH, is(true));
        assertThat(SensitiveValueMatcher.TRUNCATION_WARNED.get(), is(true));
        // the values given first are the ones kept; a value longer than MAX_VALUE_LENGTH is never searched for
        assertThat(matcher.scrub("a first-credential here"), is("a " + REDACTED_PLACEHOLDER + " here"));
        assertThat(matcher.scrub(huge.toString()), is(huge.toString()));
        // the automaton it builds is bounded by the values kept
        SensitiveValueMatcher.Automaton automaton = matcher.automaton();
        assertThat(automaton.nodeCount() <= SensitiveValueMatcher.MAX_TOTAL_LENGTH + 1, is(true));
    }

    @Test
    public void shouldNotCollectCommonWordsFromAnySensitivePlace() {
        HttpRequest request = request("/test/true")
            .withHeader("x-api-key", "TRUE")
            .withHeader("Cookie", "flag=false; consent=Null")
            .withCookie("flag", "false")
            .withQueryStringParameter("token", "null");

        List<String> values = redactor.sensitiveValues(new RequestDefinition[]{request}, null);

        assertThat(values, not(hasItems("TRUE")));
        assertThat(values, not(hasItems("false")));
        assertThat(values, not(hasItems("null")));
        assertThat(values, not(hasItems("Null")));
        assertThat(values, hasItems("flag=false", "consent=Null"));
    }

    @Test
    public void shouldScrubOnlyWhatMatches() {
        SensitiveValueMatcher matcher = SensitiveValueMatcher.of(Arrays.asList("Bearer token-value", "token-value"));

        assertThat(matcher.scrub("found: Bearer token-value and token-value"),
            is("found: " + REDACTED_PLACEHOLDER + " and " + REDACTED_PLACEHOLDER));
        String untouched = "nothing to see";
        assertThat(matcher.scrub(untouched) == untouched, is(true));
        assertThat(matcher.scrub(null), is(nullValue()));
        assertThat(matcher.containsAny("a token-value here"), is(true));
        assertThat(matcher.containsAny("a token-valu here"), is(false));
        assertThat(SensitiveValueMatcher.of(Collections.emptyList()).isEmpty(), is(true));
        assertThat(SensitiveValueMatcher.of(Arrays.asList(null, "")).scrub(untouched) == untouched, is(true));
    }

    @Test
    public void shouldMaskEverythingOverlappingOccurrencesCover() {
        SensitiveValueMatcher.Automaton automaton = SensitiveValueMatcher.Automaton.of(Arrays.asList("abcd", "cdef", "cd", "xyzw"));

        // overlapping and nested occurrences become one placeholder; separate ones, even adjacent, stay separate
        assertThat(automaton.scrub("<abcdef>"), is("<" + REDACTED_PLACEHOLDER + ">"));
        assertThat(automaton.scrub("<cd>"), is("<" + REDACTED_PLACEHOLDER + ">"));
        assertThat(automaton.scrub("xyzwxyzw"), is(REDACTED_PLACEHOLDER + REDACTED_PLACEHOLDER));
        assertThat(SensitiveValueMatcher.Automaton.of(Collections.singletonList("aa")).scrub("aaa"), is(REDACTED_PLACEHOLDER));
    }

    /**
     * Differential: wherever occurrences do not overlap, the one-pass matcher masks exactly what replacing each value
     * in turn, longest first, did. (Where they overlap the old replace could leave part of a value; see the next test.)
     */
    @Test
    public void shouldScrubExactlyAsReplacingEachValueLongestFirstWhereOccurrencesDoNotOverlap() {
        Random random = new Random(20260929L);
        for (int round = 0; round < 300; round++) {
            List<String> values = distinctValues(random, 1 + random.nextInt(60));
            StringBuilder text = new StringBuilder();
            int pieces = random.nextInt(80);
            for (int i = 0; i < pieces; i++) {
                text.append(filler(random, random.nextInt(12)));
                if (random.nextBoolean()) {
                    text.append(values.get(random.nextInt(values.size())));
                }
            }

            String expected = replacingEachValueLongestFirst(text.toString(), values);
            assertThat("round " + round, SensitiveValueMatcher.Automaton.of(values).scrub(text.toString()), is(expected));
            assertThat("round " + round, SensitiveValueMatcher.Automaton.of(values).containsAny(text.toString()), is(!expected.equals(text.toString())));
            assertThat("round " + round, SensitiveValueMatcher.of(values).scrub(text.toString()), is(expected));
        }
    }

    /**
     * Differential over any text, including overlapping occurrences, non-ASCII and surrogate pairs: both the automaton
     * and the value-by-value path used for short texts mask exactly the union of every value's occurrences.
     */
    @Test
    public void shouldMaskTheUnionOfAllOccurrencesOnEitherPath() {
        String[] alphabets = {"ab", "abc", "aA\u007f\u0080\u00ff", "x\ud83d\ude00\ud83d\ude01\ud800\udc00", "\u0000\u4e00\u4e01\uffff\u007f", "abcdefghij0123\"\\ ={}:,"};
        Random random = new Random(20260930L);
        for (int round = 0; round < 5_000; round++) {
            String alphabet = alphabets[random.nextInt(alphabets.length)];
            List<String> values = new ArrayList<>();
            int count = 1 + random.nextInt(random.nextInt(10) == 0 ? 60 : 8);
            for (int i = 0; i < count; i++) {
                values.add(random(random, alphabet, random.nextInt(20) == 0 ? 50 + random.nextInt(300) : 1 + random.nextInt(random.nextBoolean() ? 3 : 9)));
            }
            if (random.nextInt(5) == 0 && values.get(0).length() > 1) {
                values.add(values.get(0).substring(1));
            }
            StringBuilder text = new StringBuilder(random(random, alphabet, random.nextInt(random.nextInt(8) == 0 ? 5_000 : 80)));
            for (int plant = random.nextInt(4); plant > 0; plant--) {
                text.insert(random.nextInt(text.length() + 1), values.get(random.nextInt(values.size())));
            }
            String expected = unionOfOccurrences(text.toString(), values);
            List<String> longestFirst = new ArrayList<>(values);
            longestFirst.sort(Comparator.comparingInt(String::length).reversed());

            assertThat("round " + round, SensitiveValueMatcher.Automaton.of(values).scrub(text.toString()), is(expected));
            assertThat("round " + round, SensitiveValueMatcher.scrubValueByValue(text.toString(), longestFirst), is(expected));
            SensitiveValueMatcher matcher = SensitiveValueMatcher.of(values);
            for (int repeat = 0; repeat < 3; repeat++) {
                assertThat("round " + round, matcher.scrub(text.toString()), is(expected));
                assertThat("round " + round, matcher.containsAny(text.toString()), is(!expected.equals(text.toString())));
            }
        }
    }

    @Test(timeout = 10_000)
    public void shouldScrubInTimeLinearInTheTextUpToTheBoundOnValues() {
        Random random = new Random(7L);
        List<String> values = distinctValues(random, SensitiveValueMatcher.MAX_VALUES);
        StringBuilder text = new StringBuilder();
        while (text.length() < 2_000_000) {
            text.append(filler(random, 20)).append(values.get(random.nextInt(values.size())));
        }
        SensitiveValueMatcher matcher = SensitiveValueMatcher.of(values);
        matcher.scrub(text.toString());

        long start = System.nanoTime();
        String scrubbed = matcher.scrub(text.toString());
        long millis = (System.nanoTime() - start) / 1_000_000;

        // checking each of 10,000 values against 2 MB of text is about 2e10 character comparisons
        assertThat("scrubbed 2 MB against 10,000 values in " + millis + "ms", millis < 2_000, is(true));
        assertThat(scrubbed.contains(values.get(0)), is(false));
    }

    /**
     * Each character of the text costs at most a binary search over one node's children, even where a node has
     * thousands of non-ASCII children: at the root (values with distinct CJK first characters) and deeper (values
     * sharing an ASCII prefix, then distinct CJK characters), against a CJK text that never matches.
     */
    @Test(timeout = 20_000)
    public void shouldScrubNonAsciiTextInLinearTimeHoweverManyChildrenANodeHas() {
        List<String> distinctFirst = new ArrayList<>();
        List<String> distinctAfterPrefix = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            distinctFirst.add((char) (0x4e00 + i) + "xyz");
            distinctAfterPrefix.add("tok_" + (char) (0x4e00 + i));
        }
        StringBuilder cjk = new StringBuilder();
        StringBuilder prefixed = new StringBuilder();
        while (cjk.length() < 1_000_000) {
            cjk.append('\u9fa0');
            prefixed.append("tok_\u9fa0");
        }
        for (List<String> values : Arrays.asList(distinctFirst, distinctAfterPrefix)) {
            SensitiveValueMatcher.Automaton automaton = SensitiveValueMatcher.Automaton.of(values);
            for (String text : Arrays.asList(cjk.toString(), prefixed.toString())) {
                automaton.scrub(text);
                long start = System.nanoTime();
                String scrubbed = automaton.scrub(text);
                long millis = (System.nanoTime() - start) / 1_000_000;

                // with each node's children in a list, each character would scan up to 20,000 of them
                assertThat("scrubbed 1M non-ASCII characters against " + values.get(0) + "... in " + millis + "ms", millis < 1_000, is(true));
                assertThat(scrubbed == text, is(true));
            }
        }
    }

    // the scrubbing this replaced: each value in turn, longest first
    private static String replacingEachValueLongestFirst(String text, List<String> values) {
        List<String> longestFirst = new ArrayList<>(values);
        longestFirst.sort(Comparator.comparingInt(String::length).reversed());
        String scrubbed = text;
        for (String value : longestFirst) {
            scrubbed = scrubbed.replace(value, REDACTED_PLACEHOLDER);
        }
        return scrubbed;
    }

    // every occurrence of every value, overlapping ones merged, each merged stretch replaced once
    private static String unionOfOccurrences(String text, List<String> values) {
        List<int[]> occurrences = new ArrayList<>();
        for (String value : values) {
            for (int index = text.indexOf(value); index >= 0; index = text.indexOf(value, index + 1)) {
                occurrences.add(new int[]{index, index + value.length()});
            }
        }
        occurrences.sort((left, right) -> left[0] != right[0] ? Integer.compare(left[0], right[0]) : Integer.compare(right[1], left[1]));
        StringBuilder scrubbed = new StringBuilder();
        int copied = 0;
        int end = -1;
        for (int[] occurrence : occurrences) {
            if (occurrence[0] < end) {
                end = Math.max(end, occurrence[1]);
            } else {
                if (end >= 0) {
                    scrubbed.append(REDACTED_PLACEHOLDER);
                    copied = end;
                }
                scrubbed.append(text, copied, occurrence[0]);
                end = occurrence[1];
            }
        }
        if (end >= 0) {
            scrubbed.append(REDACTED_PLACEHOLDER);
            copied = end;
        }
        return scrubbed.append(text, copied, text.length()).toString();
    }

    private static String random(Random random, String alphabet, int length) {
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            text.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return text.toString();
    }

    // lowercase letters only, none a substring of another, so the filler (no lowercase) and the placeholder cannot
    // create or split an occurrence
    private static List<String> distinctValues(Random random, int count) {
        List<String> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (values.size() < count) {
            StringBuilder value = new StringBuilder();
            int length = 6 + random.nextInt(15);
            for (int i = 0; i < length; i++) {
                value.append((char) ('a' + random.nextInt(26)));
            }
            String candidate = value.toString();
            if (seen.add(candidate) && (count > 1_000 || values.stream().noneMatch(existing -> existing.contains(candidate) || candidate.contains(existing)))) {
                values.add(candidate);
            }
        }
        return values;
    }

    private static String filler(Random random, int length) {
        String alphabet = " ,.:{}[]\"0123456789-=";
        StringBuilder filler = new StringBuilder();
        for (int i = 0; i < length; i++) {
            filler.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return filler.toString();
    }
}
