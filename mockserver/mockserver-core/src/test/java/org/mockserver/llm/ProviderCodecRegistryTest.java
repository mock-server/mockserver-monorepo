package org.mockserver.llm;

import org.junit.Test;
import org.mockserver.model.Provider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;

public class ProviderCodecRegistryTest {

    @Test
    public void shouldReturnEmptyForUnregisteredProvider() {
        // given
        ProviderCodecRegistry registry = new ProviderCodecRegistry();

        // then
        assertThat(registry.lookup(Provider.ANTHROPIC).isPresent(), is(false));
        assertThat(registry.lookup(Provider.OPENAI).isPresent(), is(false));
    }

    @Test
    public void shouldReturnCodecAfterRegistration() {
        // given
        ProviderCodecRegistry registry = new ProviderCodecRegistry();
        ProviderCodec codec = new StubCodec(Provider.ANTHROPIC, "2024-10-22");

        // when
        registry.register(codec);

        // then
        Optional<ProviderCodec> result = registry.lookup(Provider.ANTHROPIC);
        assertThat(result.isPresent(), is(true));
        assertThat(result.get().provider(), is(Provider.ANTHROPIC));
        assertThat(result.get().apiVersion(), is("2024-10-22"));
    }

    @Test
    public void shouldOverwriteOnReRegister() {
        // given
        ProviderCodecRegistry registry = new ProviderCodecRegistry();
        ProviderCodec codec1 = new StubCodec(Provider.OPENAI, "v1");
        ProviderCodec codec2 = new StubCodec(Provider.OPENAI, "v2");

        // when
        registry.register(codec1);
        registry.register(codec2);

        // then
        Optional<ProviderCodec> result = registry.lookup(Provider.OPENAI);
        assertThat(result.isPresent(), is(true));
        assertThat(result.get().apiVersion(), is("v2"));
    }

    @Test
    public void shouldNotAffectOtherProviders() {
        // given
        ProviderCodecRegistry registry = new ProviderCodecRegistry();
        ProviderCodec codec = new StubCodec(Provider.GEMINI, "v1beta");

        // when
        registry.register(codec);

        // then
        assertThat(registry.lookup(Provider.GEMINI).isPresent(), is(true));
        assertThat(registry.lookup(Provider.ANTHROPIC).isPresent(), is(false));
        assertThat(registry.lookup(Provider.OPENAI).isPresent(), is(false));
    }

    @Test
    public void shouldHandleConcurrentRegistration() throws InterruptedException {
        // given
        ProviderCodecRegistry registry = new ProviderCodecRegistry();
        // one concurrent registration task per provider, so every provider is registered
        // exactly once (the final assertion checks all providers are present).
        Provider[] providers = Provider.values();
        int threadCount = providers.length;
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(threadCount, 10));
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Exception> exceptions = new ArrayList<>();

        // when - register different providers concurrently
        for (int i = 0; i < threadCount; i++) {
            Provider provider = providers[i];
            String version = "v" + i;
            executor.submit(() -> {
                try {
                    registry.register(new StubCodec(provider, version));
                } catch (Exception e) {
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // then - no exceptions thrown
        assertThat(latch.await(5, TimeUnit.SECONDS), is(true));
        assertThat(exceptions.isEmpty(), is(true));

        // and all registered providers are present
        for (Provider provider : providers) {
            assertThat(registry.lookup(provider).isPresent(), is(true));
        }

        executor.shutdown();
    }

    @Test
    public void shouldHaveAllProvidersRegisteredInSingletonInstance() {
        // given
        ProviderCodecRegistry registry = ProviderCodecRegistry.getInstance();

        // when
        List<String> names = registry.supportedProviderNames();

        // then — all 15 providers should be registered (7 chat + 2 rerank-only + 6
        // OpenAI-chat-compatible aliases)
        assertThat(names, hasSize(15));
        assertThat(names, containsInAnyOrder(
            "ANTHROPIC",
            "OPENAI",
            "OPENAI_RESPONSES",
            "GEMINI",
            "BEDROCK",
            "AZURE_OPENAI",
            "OLLAMA",
            "COHERE",
            "VOYAGE",
            "MISTRAL",
            "XAI",
            "DEEPSEEK",
            "GROQ",
            "OPENROUTER",
            "ORCAROUTER"
        ));
    }

    @Test
    public void shouldLookUpAllRegisteredProviders() {
        // given
        ProviderCodecRegistry registry = ProviderCodecRegistry.getInstance();

        // then — each provider should be present
        for (Provider provider : Provider.values()) {
            assertThat("codec should be registered for " + provider,
                registry.lookup(provider).isPresent(), is(true));
        }
    }

    private static class StubCodec implements ProviderCodec {
        private final Provider provider;
        private final String apiVersion;

        StubCodec(Provider provider, String apiVersion) {
            this.provider = provider;
            this.apiVersion = apiVersion;
        }

        @Override
        public Provider provider() {
            return provider;
        }

        @Override
        public String apiVersion() {
            return apiVersion;
        }
    }
}
