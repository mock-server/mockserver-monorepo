package org.mockserver.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.model.Provider;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Pins the {@code httpLlmResponse.provider} enum in the expectation JSON Schema to
 * {@link Provider} — the schema is what rejects an incoming expectation, so a provider missing from it
 * is unusable no matter how completely the server implements it.
 *
 * <p>This drifted silently: {@code MISTRAL}, {@code XAI}, {@code DEEPSEEK}, {@code GROQ}, and
 * {@code OPENROUTER} were added to {@link Provider} with working, registered response codecs, but never
 * to the schema, so {@code "provider": "MISTRAL"} was answered with
 * {@code 400 incorrect expectation json format}. Nothing connected the two lists, and the schema is
 * also the source the editor-extension schemas are generated from, so the gap propagated outwards.</p>
 *
 * <p>Both directions are asserted. A provider the schema omits is a supported feature users cannot
 * reach; a provider only the schema knows about accepts an expectation the server cannot then serve.</p>
 */
public class ProviderSchemaEnumParityTest {

    private static final String SCHEMA_RESOURCE = "org/mockserver/model/schema/httpLlmResponse.json";

    private static Set<String> schemaProviderEnum() throws Exception {
        try (InputStream schemaStream = ProviderSchemaEnumParityTest.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
            assertThat("schema resource " + SCHEMA_RESOURCE + " must be on the classpath", schemaStream, notNullValue());
            JsonNode enumNode = new ObjectMapper().readTree(schemaStream).get("properties").get("provider").get("enum");
            assertThat("httpLlmResponse.provider must declare an enum", enumNode, notNullValue());
            Set<String> values = new LinkedHashSet<>();
            enumNode.forEach(value -> values.add(value.asText()));
            return values;
        }
    }

    @Test
    public void everyProviderTheServerSupportsIsAcceptedByTheSchema() throws Exception {
        // given
        Set<String> schemaProviders = schemaProviderEnum();

        // when
        List<String> missingFromSchema = Arrays.stream(Provider.values())
            .map(Enum::name)
            .filter(name -> !schemaProviders.contains(name))
            .collect(Collectors.toList());

        // then - a provider the schema omits cannot be used in an expectation at all
        assertThat(
            "Provider values missing from the httpLlmResponse schema enum (expectations naming them are "
                + "rejected with a 400): " + missingFromSchema,
            missingFromSchema, is(empty())
        );
    }

    @Test
    public void theSchemaAcceptsNoProviderTheServerDoesNotSupport() throws Exception {
        // given
        Set<String> providerNames = Arrays.stream(Provider.values()).map(Enum::name).collect(Collectors.toSet());

        // when
        List<String> unknownToTheServer = new ArrayList<>(schemaProviderEnum());
        unknownToTheServer.removeIf(providerNames::contains);

        // then - accepting one would let an expectation be created that cannot be served
        assertThat(
            "httpLlmResponse schema enum values with no matching Provider constant: " + unknownToTheServer,
            unknownToTheServer, is(empty())
        );
    }

    @Test
    public void everyProviderTheSchemaAcceptsHasARegisteredResponseCodec() {
        // given - the schema is the gate on creating an LLM expectation; a provider that passes it but
        // has no codec would be accepted at create time and then fail when a request had to be answered
        List<String> withoutCodec = Arrays.stream(Provider.values())
            .filter(provider -> !ProviderCodecRegistry.getInstance().lookup(provider).isPresent())
            .map(Enum::name)
            .collect(Collectors.toList());

        // then
        assertThat("Provider values with no registered response codec: " + withoutCodec, withoutCodec, is(empty()));
    }
}
