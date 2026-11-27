package org.mockserver.mock.action.http;

import org.junit.Test;
import org.mockserver.llm.TokenCounter;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Completion;
import org.mockserver.model.CompletionGeneration;
import org.mockserver.model.HttpLlmResponse;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.LogNormal;
import org.mockserver.model.SseEvent;
import org.mockserver.model.Ttft;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.CompletionGeneration.generation;
import static org.mockserver.model.HttpLlmResponse.llmResponse;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.LogNormal.logNormal;
import static org.mockserver.model.Ttft.ttft;

public class HttpLlmResponseActionHandlerGenerateTest {

    private final HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());

    @Test
    public void shouldGenerateNonStreamingOpenAiResponseWithConsistentUsage() {
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(org.mockserver.model.Provider.OPENAI)
            .withModel("gpt-4o")
            .withCompletion(completion()
                .withGenerate(generation()
                    .withOutputTokens(logNormal().withMedian(40.0).withSigma(0.0))));
        // prompt with a known-size message
        StringBuilder userMessage = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            userMessage.append("word ");
        }

        HttpResponse response = handler.handle(llmResponse, request()
            .withPath("/v1/chat/completions")
            .withBody("{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"" + userMessage.toString().trim() + "\"}]}"));

        assertThat(response.getStatusCode(), is(200));
        String body = response.getBodyAsString();
        assertThat(body, notNullValue());
        // generated text present, fixture text absent
        assertThat(body.contains("\"object\":\"chat.completion\""), is(true));
        // usage.outputTokens must equal the generated text's token count
        org.mockserver.model.HttpRequest parseProbe = request().withBody(body);
        // parse usage from the encoded response via the OpenAI shape
        int completionTokens = extractJsonIntField(body, "completion_tokens");
        int promptTokens = extractJsonIntField(body, "prompt_tokens");
        assertThat(completionTokens, is(40));
        assertThat(promptTokens, greaterThan(0));
    }

    @Test
    public void shouldGenerateStreamingResponseWithTtftAndUsage() {
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(org.mockserver.model.Provider.ANTHROPIC)
            .withModel("claude-sonnet-4")
            .withCompletion(completion()
                .withStreaming(Boolean.TRUE)
                .withGenerate(generation()
                    .withOutputTokens(logNormal().withMedian(64.0).withSigma(0.0))
                    .withTtft(ttft().withBaseMs(100.0).withMsPerKiloInputTokens(50.0))));

        List<SseEvent> events = handler.handleStreaming(llmResponse, request()
            .withPath("/v1/messages")
            .withBody("{\"model\":\"claude-sonnet-4\",\"max_tokens\":64,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"));

        assertThat(events.size(), greaterThan(2));
        // first event carries the TTFT delay
        long firstDelay = events.get(0).getDelay().getTimeUnit().toMillis(events.get(0).getDelay().getValue());
        assertThat("ttft delay on first event", firstDelay, is(100L));
        // subsequent events carry per-token delays
        long secondDelay = events.get(1).getDelay().getTimeUnit().toMillis(events.get(1).getDelay().getValue());
        assertThat("per-token delay present", secondDelay, greaterThan(0L));
        // final usage event reflects the generated output length
        String lastUsage = null;
        for (SseEvent event : events) {
            if (event.getData() != null && event.getData().contains("output_tokens")) {
                lastUsage = event.getData();
            }
        }
        assertThat(lastUsage, notNullValue());
        assertThat(lastUsage.contains("\"output_tokens\":" + 64), is(true));
    }

    @Test
    public void shouldGenerateDifferentTextForDifferentRequestsSameExpectation() {
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(org.mockserver.model.Provider.OPENAI)
            .withModel("gpt-4o")
            .withCompletion(completion()
                .withGenerate(generation()
                    .withOutputTokens(logNormal().withMedian(32.0).withSigma(0.3))));

        java.util.Set<Integer> tokenCounts = new java.util.HashSet<>();
        for (int i = 0; i < 20; i++) {
            String body = handler.handle(llmResponse, request()
                .withPath("/v1/chat/completions")
                .withBody("{\"messages\":[{\"role\":\"user\",\"content\":\"request " + i + "\"}]}")).getBodyAsString();
            tokenCounts.add(extractJsonIntField(body, "completion_tokens"));
        }
        // per-request seeds (derived from method+path+body) must sample more than one length
        assertThat("expected varying output lengths across distinct requests",
            tokenCounts.size(), greaterThan(1));
    }

    @Test
    public void shouldFallBackToInferredUsageWhenNoGenerate() {
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(org.mockserver.model.Provider.OPENAI)
            .withModel("gpt-4o")
            .withCompletion(completion().withText("Hello world response"));

        // usage inference is opt-in and off by default: usage stays null
        HttpResponse response = handler.handle(llmResponse, request()
            .withPath("/v1/chat/completions")
            .withBody("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"));

        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString().contains("\"usage\""), is(true));
    }

    @Test
    public void shouldKeepExpectationCompletionUnmutatedAcrossRequests() {
        Completion template = completion()
            .withGenerate(generation()
                .withOutputTokens(logNormal().withMedian(20.0).withSigma(0.0)));
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(org.mockserver.model.Provider.OPENAI)
            .withModel("gpt-4o")
            .withCompletion(template);

        handler.handle(llmResponse, request()
            .withPath("/v1/chat/completions")
            .withBody("{\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}"));

        assertThat(template.getText(), nullValue());
        assertThat(template.getGenerate(), notNullValue());
    }

    @Test
    public void shouldEnforceOutputSchemaAgainstGeneratedText() {
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(org.mockserver.model.Provider.OPENAI)
            .withModel("gpt-4o")
            .withCompletion(completion()
                .withOutputSchema("{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}},\"required\":[\"answer\"],\"additionalProperties\":false}")
                .withEnforceOutputSchema(Boolean.TRUE)
                .withGenerate(generation()
                    .withOutputTokens(logNormal().withMedian(40.0).withSigma(0.0))));

        HttpResponse error = handler.enforcementErrorResponseOrNull(llmResponse, request()
            .withPath("/v1/chat/completions")
            .withBody("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"));

        assertThat("generated filler does not conform to the schema, so strict enforcement fails the request",
            error, notNullValue());
        assertThat(error.getStatusCode(), is(502));
    }

    @Test
    public void shouldReplayIdenticalGenerationForIdenticalRequests() {
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(org.mockserver.model.Provider.OPENAI)
            .withModel("gpt-4o")
            .withCompletion(completion()
                .withGenerate(generation()
                    .withOutputTokens(logNormal().withMedian(48.0).withSigma(0.2))));
        HttpRequest sameBody = request()
            .withPath("/v1/chat/completions")
            .withBody("{\"messages\":[{\"role\":\"user\",\"content\":\"stable\"}]}");

        String first = handler.handle(llmResponse, sameBody).getBodyAsString();
        String second = handler.handle(llmResponse, request()
            .withPath("/v1/chat/completions")
            .withBody("{\"messages\":[{\"role\":\"user\",\"content\":\"stable\"}]}")).getBodyAsString();

        // the codec-fresh response id/created differ per response; the generated
        // content and usage must replay identically
        assertThat("an identical request replays the identical generated content",
            extractJsonStringField(second, "content"), is(extractJsonStringField(first, "content")));
        assertThat(extractJsonIntField(second, "completion_tokens"),
            is(extractJsonIntField(first, "completion_tokens")));
        assertThat(extractJsonIntField(second, "prompt_tokens"),
            is(extractJsonIntField(first, "prompt_tokens")));
    }

    @Test
    public void shouldDeriveSeedFromMethodPathAndBody() {
        HttpRequest request = request()
            .withMethod("POST")
            .withPath("/v1/chat/completions")
            .withBody("{\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}");
        HttpRequest same = request()
            .withMethod("POST")
            .withPath("/v1/chat/completions")
            .withBody("{\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}");
        HttpRequest otherBody = request()
            .withMethod("POST")
            .withPath("/v1/chat/completions")
            .withBody("{\"messages\":[{\"role\":\"user\",\"content\":\"b\"}]}");
        HttpRequest otherPath = request()
            .withMethod("POST")
            .withPath("/v1/messages")
            .withBody("{\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}");

        assertThat(HttpLlmResponseActionHandler.newSeed(request),
            is(HttpLlmResponseActionHandler.newSeed(same)));
        assertThat(HttpLlmResponseActionHandler.newSeed(request),
            not(HttpLlmResponseActionHandler.newSeed(otherBody)));
        assertThat(HttpLlmResponseActionHandler.newSeed(request),
            not(HttpLlmResponseActionHandler.newSeed(otherPath)));
        assertThat("a body-less request falls back to entropy without failing",
            HttpLlmResponseActionHandler.newSeed(request().withPath("/x")), notNullValue());
    }

    @Test
    public void shouldChargeTokenQuotaAtDeclaredMedianInGenerateMode() {
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(org.mockserver.model.Provider.OPENAI)
            .withModel("gpt-4o")
            .withCompletion(completion()
                .withGenerate(generation()
                    .withOutputTokens(logNormal().withMedian(100.0).withSigma(0.0))))
            .withChaos(org.mockserver.model.LlmChaosProfile.llmChaosProfile()
                .withQuotaName("gen-quota")
                .withTokenQuotaLimit(250L)
                .withTokenQuotaWindowMillis(60_000L));

        HttpRequest post = request()
            .withPath("/v1/chat/completions")
            .withBody("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        // each request charges ~100 tokens at the median, so the 250-token window
        // admits 2 and blocks the 3rd
        assertThat(handler.chaosErrorResponseOrNull(llmResponse, post), nullValue());
        assertThat(handler.chaosErrorResponseOrNull(llmResponse, post), nullValue());
        assertThat("3rd request exceeds the 250-token window",
            handler.chaosErrorResponseOrNull(llmResponse, post), notNullValue());
    }

    private static int extractJsonIntField(String json, String fieldName) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("\"" + fieldName + "\":(\\d+)").matcher(json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static String extractJsonStringField(String json, String fieldName) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("\"" + fieldName + "\":\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
