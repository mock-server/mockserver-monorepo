package org.mockserver.llm;

import org.junit.Test;
import org.mockserver.model.Completion;
import org.mockserver.model.CompletionGeneration;
import org.mockserver.model.Delay;
import org.mockserver.model.LogNormal;
import org.mockserver.model.StreamingPhysics;
import org.mockserver.model.Usage;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.CompletionGeneration.generation;
import static org.mockserver.model.LogNormal.logNormal;
import static org.mockserver.model.Ttft.ttft;

public class LlmCompletionGeneratorTest {

    @Test
    public void shouldGenerateTextOfExactlySampledTokenCount() {
        for (int target : new int[]{1, 2, 3, 5, 16, 37, 64, 100, 250, 512, 1000, 2047, 4096}) {
            String text = LlmCompletionGenerator.fillerText(target, new Random(target));
            assertThat("token count for target " + target,
                TokenCounter.estimateTokens(text), is(target));
        }
    }

    @Test
    public void shouldReturnEmptyTextForZeroTokens() {
        assertThat(LlmCompletionGenerator.fillerText(0, new Random(1)), is(""));
    }

    @Test
    public void shouldSampleDeterministicallyFromSameSeed() {
        LogNormal outputTokens = logNormal().withMedian(250.0).withSigma(0.8);
        int first = LlmCompletionGenerator.sampleOutputTokens(outputTokens, new Random(42));
        for (int i = 0; i < 10; i++) {
            assertThat(LlmCompletionGenerator.sampleOutputTokens(outputTokens, new Random(42)), is(first));
        }
    }

    @Test
    public void shouldSampleAcrossDistribution() {
        LogNormal outputTokens = logNormal().withMedian(256.0).withSigma(0.8);
        Random random = new Random(7);
        Set<Integer> samples = new HashSet<>();
        int belowMedian = 0;
        for (int i = 0; i < 500; i++) {
            int sample = LlmCompletionGenerator.sampleOutputTokens(outputTokens, random);
            samples.add(sample);
            if (sample < 256) {
                belowMedian++;
            }
        }
        assertThat("expected spread of samples", samples.size(), greaterThan(50));
        assertThat("expected samples below median", belowMedian, greaterThan(100));
    }

    @Test
    public void shouldDegenerateToMedianWhenSigmaZero() {
        assertThat(LlmCompletionGenerator.sampleOutputTokens(
            logNormal().withMedian(123.0).withSigma(0.0), new Random(1)), is(123));
    }

    @Test
    public void shouldClampOutputTokensToAtLeastOne() {
        assertThat(LlmCompletionGenerator.sampleOutputTokens(
            logNormal().withMedian(4.0).withSigma(5.0), new Random(2)), greaterThanOrEqualTo(1));
    }

    @Test
    public void shouldGenerateCompletionWithConsistentUsage() {
        Completion template = completion()
            .withStopReason("end_turn")
            .withUsage(Usage.usage().withInputTokens(999).withOutputTokens(999));
        CompletionGeneration generation = generation()
            .withOutputTokens(logNormal().withMedian(50.0).withSigma(0.0));
        Completion generated = LlmCompletionGenerator.generate(
            template, generation, 320, 1234L, false);

        assertThat(TokenCounter.estimateTokens(generated.getText()), is(50));
        assertThat(generated.getUsage().getOutputTokens(), is(50));
        assertThat(generated.getUsage().getInputTokens(), is(320));
        assertThat(generated.getStopReason(), is("end_turn"));
        // template must not be mutated
        assertThat(template.getUsage().getOutputTokens(), is(999));
        assertThat(template.getText(), nullValue());
    }

    @Test
    public void shouldScaleTtftWithInputTokens() {
        CompletionGeneration generation = generation()
            .withTtft(ttft().withBaseMs(100.0).withMsPerKiloInputTokens(50.0));
        StreamingPhysics small = LlmCompletionGenerator.sampledPhysics(
            generation, 60, 1_000, new Random(1), null);
        StreamingPhysics large = LlmCompletionGenerator.sampledPhysics(
            generation, 60, 9_000, new Random(1), null);

        long smallTtft = small.getTimeToFirstToken().getTimeUnit().toMillis(small.getTimeToFirstToken().getValue());
        long largeTtft = large.getTimeToFirstToken().getTimeUnit().toMillis(large.getTimeToFirstToken().getValue());
        assertThat(smallTtft, is(150L));
        assertThat(largeTtft, is(550L));
    }

    @Test
    public void shouldApplyTtftJitter() {
        CompletionGeneration generation = generation()
            .withTtft(ttft().withBaseMs(1000.0).withJitter(0.5));
        Set<Long> delays = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            StreamingPhysics physics = LlmCompletionGenerator.sampledPhysics(
                generation, null, 0, new Random(i), null);
            Delay delay = physics.getTimeToFirstToken();
            delays.add(delay.getTimeUnit().toMillis(delay.getValue()));
        }
        assertThat("expected jittered delays to vary", delays.size(), greaterThan(3));
        for (long delay : delays) {
            assertThat("jittered delay within [500, 1500]", delay, greaterThanOrEqualTo(500L));
            assertThat(delay, lessThan(1500L));
        }
    }

    @Test
    public void shouldReturnNullPhysicsWhenNothingSampled() {
        assertThat(LlmCompletionGenerator.sampledPhysics(
            generation(), null, 100, new Random(1), null), nullValue());
    }

    @Test
    public void shouldSampleTokensPerSecondWithinBounds() {
        LogNormal tokensPerSecond = logNormal().withMedian(60.0).withSigma(0.15);
        Random random = new Random(11);
        for (int i = 0; i < 100; i++) {
            Integer rate = LlmCompletionGenerator.sampleTokensPerSecond(tokensPerSecond, random);
            assertThat(rate, notNullValue());
            assertThat(rate, greaterThanOrEqualTo(1));
            assertThat(rate, lessThan(10001));
        }
    }

    @Test
    public void shouldLeaveTokensPerSecondUnsetWhenNotConfigured() {
        assertThat(LlmCompletionGenerator.sampleTokensPerSecond(null, new Random(1)), nullValue());
    }

    @Test
    public void shouldForceStreamingFlagOnStreamingPath() {
        Completion template = completion();
        CompletionGeneration generation = generation()
            .withOutputTokens(logNormal().withMedian(10.0).withSigma(0.0));
        Completion generated = LlmCompletionGenerator.generate(template, generation, 100, 5L, true);
        assertThat(generated.getStreaming(), is(Boolean.TRUE));
    }

    @Test
    public void shouldSamplePhysicsOnStreamingPathWhenConfigured() {
        Completion template = completion();
        CompletionGeneration generation = generation()
            .withOutputTokens(logNormal().withMedian(10.0).withSigma(0.0))
            .withTokensPerSecond(logNormal().withMedian(60.0).withSigma(0.0))
            .withTtft(ttft().withBaseMs(120.0));
        Completion generated = LlmCompletionGenerator.generate(template, generation, 100, 5L, true);
        assertThat(generated.getStreaming(), is(Boolean.TRUE));
        assertThat(generated.getStreamingPhysics(), notNullValue());
        assertThat(generated.getStreamingPhysics().getTokensPerSecond(), is(60));
        Delay ttft = generated.getStreamingPhysics().getTimeToFirstToken();
        assertThat(ttft.getTimeUnit().toMillis(ttft.getValue()), is(120L));
    }

    @Test
    public void shouldKeepStreamingFalseOnNonStreamingPath() {
        Completion template = completion().withStreaming(Boolean.TRUE);
        CompletionGeneration generation = generation()
            .withOutputTokens(logNormal().withMedian(10.0).withSigma(0.0));
        Completion generated = LlmCompletionGenerator.generate(template, generation, 100, 5L, false);
        assertThat(generated.getStreaming(), is(Boolean.FALSE));
    }

    @Test
    public void shouldPreserveExplicitSeedOverDerived() {
        assertThat(LlmCompletionGenerator.resolveSeed(
            generation().withSeed(777L), 42L), is(777L));
        assertThat(LlmCompletionGenerator.resolveSeed(
            generation(), 42L), is(42L));
    }

    @Test
    public void shouldReproduceIdenticalGenerationFromSameSeed() {
        CompletionGeneration generation = generation()
            .withOutputTokens(logNormal().withMedian(64.0).withSigma(0.4))
            .withTokensPerSecond(logNormal().withMedian(60.0).withSigma(0.1))
            .withTtft(ttft().withBaseMs(100.0).withMsPerKiloInputTokens(50.0).withJitter(0.2));
        Completion a = LlmCompletionGenerator.generate(completion(), generation, 500, 999L, true);
        Completion b = LlmCompletionGenerator.generate(completion(), generation, 500, 999L, true);
        assertThat(b.getText(), is(a.getText()));
        assertThat(b.getUsage(), is(a.getUsage()));
        assertThat(b.getStreamingPhysics().getTokensPerSecond(), is(a.getStreamingPhysics().getTokensPerSecond()));
        assertThat(b.getStreamingPhysics().getTimeToFirstToken().getValue(), is(a.getStreamingPhysics().getTimeToFirstToken().getValue()));
    }

    @Test
    public void shouldVaryGenerationAcrossSeeds() {
        CompletionGeneration generation = generation()
            .withOutputTokens(logNormal().withMedian(64.0).withSigma(0.4));
        Set<String> texts = new HashSet<>();
        for (long seed = 0; seed < 20; seed++) {
            texts.add(LlmCompletionGenerator.generate(completion(), generation, 10, seed, false).getText());
        }
        assertThat("different seeds should usually give different text", texts.size(), greaterThan(10));
    }

    @Test
    public void shouldPreserveDeclaredSubCountsInUsage() {
        Completion template = completion().withUsage(
            Usage.usage()
                .withCachedInputTokens(10)
                .withCacheCreationTokens(20)
                .withReasoningTokens(30));
        CompletionGeneration generation = generation()
            .withOutputTokens(logNormal().withMedian(40.0).withSigma(0.0));
        Completion generated = LlmCompletionGenerator.generate(template, generation, 100, 1L, false);
        assertThat(generated.getUsage().getCachedInputTokens(), is(10));
        assertThat(generated.getUsage().getCacheCreationTokens(), is(20));
        assertThat(generated.getUsage().getReasoningTokens(), is(30));
        assertThat(generated.getUsage().getInputTokens(), is(100));
        assertThat(generated.getUsage().getOutputTokens(), is(40));
    }

    @Test
    public void shouldClampHugeSampledLengthsToMaxOutputTokens() {
        LogNormal outputTokens = logNormal().withMedian(20000.0).withSigma(0.8);
        Random random = new Random(3);
        for (int i = 0; i < 50; i++) {
            int sampled = LlmCompletionGenerator.sampleOutputTokens(outputTokens, random);
            assertThat(sampled, lessThan(100_001));
            assertThat(TokenCounter.estimateTokens(LlmCompletionGenerator.fillerText(sampled, random)), is(sampled));
        }
    }

    @Test
    public void shouldBoundFillerCacheUnderManyDistinctLengths() {
        Random random = new Random(5);
        java.util.Set<Integer> lengths = new java.util.HashSet<>();
        for (int i = 1; i <= 600; i++) {
            int length = 3 * i + 1; // all odd — previously the unbounded case
            lengths.add(length);
            LlmCompletionGenerator.fillerText(length, random);
        }
        assertThat("filler cache must stay bounded",
            LlmCompletionGenerator.cacheSizeForTesting(), lessThan(200));
        // a recently used length is still served correctly after eviction churn
        assertThat(TokenCounter.estimateTokens(LlmCompletionGenerator.fillerText(600 * 3 + 1, random)), is(600 * 3 + 1));
    }

    @Test
    public void shouldTreatEmptyGenerateAsOneTokenGeneration() {
        Completion generated = LlmCompletionGenerator.generate(completion(), generation(), 10, 1L, false);
        assertThat("empty generate degenerates to a 1-token sample",
            TokenCounter.estimateTokens(generated.getText()), is(1));
    }

    @Test
    public void shouldOverlaySampledPhysicsOntoDeclaredPhysics() {
        StreamingPhysics declared = StreamingPhysics.streamingPhysics()
            .withJitter(0.3)
            .withSubwordStreaming(Boolean.FALSE);
        Completion template = completion().withStreamingPhysics(declared);
        CompletionGeneration generation = generation()
            .withTtft(ttft().withBaseMs(200.0));
        Completion generated = LlmCompletionGenerator.generate(template, generation, 1_000, 9L, true);
        // sampled ttft applied; declared jitter and subword mode survive the overlay
        assertThat(generated.getStreamingPhysics().getTimeToFirstToken().getValue(), is(200L));
        assertThat(generated.getStreamingPhysics().getJitter(), is(0.3));
        assertThat(generated.getStreamingPhysics().getSubwordStreaming(), is(Boolean.FALSE));
        // the expectation's own physics object must not be mutated
        assertThat(declared.getTimeToFirstToken(), nullValue());
    }

    @Test
    public void shouldNotMutateSharedDeclaredPhysicsAcrossRequests() {
        StreamingPhysics declared = StreamingPhysics.streamingPhysics().withJitter(0.5);
        Completion template = completion()
            .withStreamingPhysics(declared)
            .withGenerate(generation()
                .withOutputTokens(logNormal().withMedian(16.0).withSigma(0.2))
                .withTtft(ttft().withBaseMs(50.0)));
        for (int i = 0; i < 5; i++) {
            LlmCompletionGenerator.generate(template, template.getGenerate(), 100, i, true);
        }
        assertThat("declared physics must keep its original ttft",
            declared.getTimeToFirstToken(), nullValue());
        assertThat(declared.getJitter(), is(0.5));
    }
}
