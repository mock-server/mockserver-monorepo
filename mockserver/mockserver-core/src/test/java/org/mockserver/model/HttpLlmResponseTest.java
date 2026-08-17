package org.mockserver.model;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.EmbeddingResponse.embedding;
import static org.mockserver.model.HttpLlmResponse.llmResponse;

import static org.hamcrest.core.IsSame.sameInstance;
public class HttpLlmResponseTest {

    @Test
    public void shouldAlwaysCreateNewObject() {
        assertThat(llmResponse(), is(llmResponse()));
        assertThat(llmResponse(), not(sameInstance(llmResponse())));
    }

    @Test
    public void shouldReturnType() {
        assertThat(llmResponse().getType(), is(Action.Type.LLM_RESPONSE));
    }

    @Test
    public void shouldBuildWithAllFields() {
        // given
        Completion c = completion().withText("Hello");

        // when
        HttpLlmResponse response = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(c);

        // then
        assertThat(response.getProvider(), is(Provider.ANTHROPIC));
        assertThat(response.getModel(), is("claude-sonnet-4-20250514"));
        assertThat(response.getCompletion(), is(c));
        assertThat(response.getEmbedding(), is(nullValue()));
    }

    @Test
    public void shouldBuildWithEmbedding() {
        // given
        EmbeddingResponse e = embedding().withDimensions(1536);

        // when
        HttpLlmResponse response = llmResponse()
            .withProvider(Provider.OPENAI)
            .withModel("text-embedding-3-small")
            .withEmbedding(e);

        // then
        assertThat(response.getProvider(), is(Provider.OPENAI));
        assertThat(response.getModel(), is("text-embedding-3-small"));
        assertThat(response.getEmbedding(), is(e));
        assertThat(response.getCompletion(), is(nullValue()));
    }

    @Test
    public void shouldHaveNullDefaultValues() {
        // when
        HttpLlmResponse response = llmResponse();

        // then
        assertThat(response.getProvider(), is(nullValue()));
        assertThat(response.getModel(), is(nullValue()));
        assertThat(response.getCompletion(), is(nullValue()));
        assertThat(response.getEmbedding(), is(nullValue()));
    }

    @Test
    public void shouldBeEqualWhenSameValues() {
        // given
        Completion c = completion().withText("Hello");
        HttpLlmResponse r1 = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(c);
        HttpLlmResponse r2 = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(c);

        // then
        assertThat(r1, is(r2));
    }

    @Test
    public void shouldHaveSameHashCodeWhenEqual() {
        // given
        Completion c = completion().withText("Hello");
        HttpLlmResponse r1 = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(c);
        HttpLlmResponse r2 = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(c);

        // then
        assertThat(r1.hashCode(), is(r2.hashCode()));
    }

    @Test
    public void shouldNotBeEqualWhenDifferentProvider() {
        assertThat(
            llmResponse().withProvider(Provider.ANTHROPIC),
            is(not(llmResponse().withProvider(Provider.OPENAI)))
        );
    }

    @Test
    public void shouldNotBeEqualWhenDifferentModel() {
        assertThat(
            llmResponse().withModel("model-a"),
            is(not(llmResponse().withModel("model-b")))
        );
    }

    @Test
    public void shouldNotBeEqualWhenDifferentCompletion() {
        assertThat(
            llmResponse().withCompletion(completion().withText("A")),
            is(not(llmResponse().withCompletion(completion().withText("B"))))
        );
    }

    @Test
    public void shouldNotBeEqualToNull() {
        assertThat(llmResponse().withProvider(Provider.ANTHROPIC).equals(null), is(false));
    }

    @Test
    public void shouldNotBeEqualToDifferentType() {
        assertThat(llmResponse().withProvider(Provider.ANTHROPIC).equals("response"), is(false));
    }

    @Test
    public void shouldBeEqualToItself() {
        // given
        HttpLlmResponse response = llmResponse().withProvider(Provider.ANTHROPIC);

        // then
        assertThat(response, is(response));
    }

    @Test
    public void shouldSupportDelay() {
        // when
        HttpLlmResponse response = llmResponse()
            .withDelay(TimeUnit.SECONDS, 3);

        // then
        assertThat(response.getDelay(), is(new Delay(TimeUnit.SECONDS, 3)));
    }

    @Test
    public void shouldSupportDelayObject() {
        // when
        HttpLlmResponse response = llmResponse()
            .withDelay(Delay.milliseconds(500));

        // then
        assertThat(response.getDelay(), is(new Delay(TimeUnit.MILLISECONDS, 500)));
    }

    @Test
    public void shouldCoverAllProviderEnumValues() {
        // verify all provider enum values exist
        assertThat(Provider.values().length, is(15));
        assertThat(Provider.valueOf("ANTHROPIC"), is(Provider.ANTHROPIC));
        assertThat(Provider.valueOf("OPENAI"), is(Provider.OPENAI));
        assertThat(Provider.valueOf("OPENAI_RESPONSES"), is(Provider.OPENAI_RESPONSES));
        assertThat(Provider.valueOf("GEMINI"), is(Provider.GEMINI));
        assertThat(Provider.valueOf("BEDROCK"), is(Provider.BEDROCK));
        assertThat(Provider.valueOf("AZURE_OPENAI"), is(Provider.AZURE_OPENAI));
        assertThat(Provider.valueOf("OLLAMA"), is(Provider.OLLAMA));
        assertThat(Provider.valueOf("COHERE"), is(Provider.COHERE));
        assertThat(Provider.valueOf("VOYAGE"), is(Provider.VOYAGE));
        assertThat(Provider.valueOf("MISTRAL"), is(Provider.MISTRAL));
        assertThat(Provider.valueOf("XAI"), is(Provider.XAI));
        assertThat(Provider.valueOf("DEEPSEEK"), is(Provider.DEEPSEEK));
        assertThat(Provider.valueOf("GROQ"), is(Provider.GROQ));
        assertThat(Provider.valueOf("OPENROUTER"), is(Provider.OPENROUTER));
        assertThat(Provider.valueOf("ORCAROUTER"), is(Provider.ORCAROUTER));
    }
}
