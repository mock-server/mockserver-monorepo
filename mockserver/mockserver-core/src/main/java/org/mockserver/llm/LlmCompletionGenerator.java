package org.mockserver.llm;

import org.mockserver.model.Completion;
import org.mockserver.model.CompletionGeneration;
import org.mockserver.model.Delay;
import org.mockserver.model.LogNormal;
import org.mockserver.model.StreamingPhysics;
import org.mockserver.model.Ttft;
import org.mockserver.model.Usage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Generates a {@link Completion} per request from a {@link CompletionGeneration}
 * declaration, so a mock can behave like a probabilistic model instead of returning
 * a fixed fixture.
 *
 * <p>The generated completion is <strong>self-consistent</strong>:
 * <ul>
 *   <li>the output text is filler containing <em>exactly</em> the sampled number of
 *       tokens (measured with {@link TokenCounter}), so the reported
 *       {@code usage.outputTokens} equals the real text length by construction;</li>
 *   <li>{@code usage.inputTokens} is estimated from the decoded request's
 *       conversation, so multi-turn history growth is reflected;</li>
 *   <li>the streaming physics' time-to-first-token scales with that input estimate,
 *       modelling prefill; tokens-per-second is itself sampled.</li>
 * </ul>
 *
 * <p>Sampling is seeded and therefore reproducible: the same seed produces the same
 * output length, the same text and the same timings. Output lengths are clamped to
 * at most {@link #MAX_OUTPUT_TOKENS} tokens ({@value #MAX_OUTPUT_TOKENS}). The
 * per-length filler cache is bounded at {@link #FILLER_CACHE_MAX} lengths (LRU).
 */
public final class LlmCompletionGenerator {

    /**
     * Common English words used as filler. Every word kept is lowercase letters of
     * five characters or fewer, which {@link TokenCounter} scores as exactly one
     * token (short lowercase words stay whole and a plain space is absorbed), so a
     * text of N words measures exactly N tokens.
     */
    private static final String[] WORDS = initWords();

    private static String[] initWords() {
        String raw = "the of and to in is that for it as with was on be at by this had not are but "
            + "from or have an they which one you were all we her she there would their will when who "
            + "him been has more if no out do so can what up said than its now find long down day did "
            + "get come made may part over new even want any right take mean well back much before "
            + "move same too also form just help put small large great still such both other many "
            + "these those then them only into first last next each about after above below under "
            + "again once here where why how some your mine ours water heat wood food glass paper "
            + "metal stone grass light sound house road door hand head foot plant tree seed leaf "
            + "root stem skin bone cell wave force power speed scale range level point line area "
            + "place case group study model stage step unit layer field";
        List<String> kept = new ArrayList<>();
        for (String word : raw.split("\\s+")) {
            if (word.matches("[a-z]{1,5}")) {
                kept.add(word);
            }
        }
        return kept.toArray(new String[0]);
    }

    /**
     * Bounded LRU cache of exact-length filler texts, keyed by token count. Bounded
     * so a workload sampling many distinct lengths cannot grow it without limit: at
     * {@code MAX_OUTPUT_TOKENS} each entry holds ~4 candidate strings of ~600 KB
     * each, so the cache is capped at {@link #FILLER_CACHE_MAX} lengths.
     */
    private static final int FILLER_CACHE_MAX = 128;
    private static final Map<Integer, List<String>> FILLER_CACHE =
        Collections.synchronizedMap(new LinkedHashMap<Integer, List<String>>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, List<String>> eldest) {
                return size() > FILLER_CACHE_MAX;
            }
        });

    /** Cache candidates per length so a sampled length can reuse filler without rebuilding. */
    private static final int FILLER_CANDIDATES_PER_LENGTH = 4;

    private static final int MAX_OUTPUT_TOKENS = 100_000;

    /**
     * Lengths above this are built per request and never cached: near the
     * {@link #MAX_OUTPUT_TOKENS} clamp one filler string is already hundreds of KB,
     * so holding four candidates per length would retain megabytes per entry.
     */
    private static final int FILLER_CACHE_SINGLE_CANDIDATE_ABOVE = 8192;

    private LlmCompletionGenerator() {
        // utility class
    }

    /**
     * Generate a per-request completion from {@code generation}, or return
     * {@code completion} unchanged when it has no {@code generate} config. The
     * returned completion always carries computed {@link Usage}: input tokens from
     * {@code inputTokens} (the decoded request's prompt estimate), output tokens
     * equal to the generated text's real token count.
     *
     * @param completion  the expectation's completion (never mutated)
     * @param generation  its {@code generate} config (non-null)
     * @param inputTokens the decoded request's prompt-token estimate
     * @param seed        the resolved per-request seed
     * @param streaming   whether the response will stream (selects the streaming path)
     * @return a per-request completion with generated text and computed usage
     */
    public static Completion generate(Completion completion,
                                      CompletionGeneration generation,
                                      int inputTokens,
                                      long seed,
                                      boolean streaming) {
        Random random = new Random(seed);

        Integer outputTokens = sampleOutputTokens(generation.getOutputTokens(), random);
        Integer tokensPerSecond = sampleTokensPerSecond(generation.getTokensPerSecond(), random);

        String text = fillerText(outputTokens, random);

        Completion generated = copyOf(completion)
            .withText(text)
            .withUsage(usageFor(completion, inputTokens, outputTokens));

        StreamingPhysics sampled = sampledPhysics(generation, tokensPerSecond, inputTokens, random,
            snapshotOf(completion.getStreamingPhysics()));
        if (sampled != null) {
            // snapshotOf already detached the declared physics, so `sampled` is safe to hand out
            generated.withStreamingPhysics(sampled);
        }
        if (streaming && generated.getStreaming() == null) {
            generated.withStreaming(Boolean.TRUE);
        }
        if (!streaming) {
            generated.withStreaming(Boolean.FALSE);
        }
        return generated;
    }

    /**
     * Resolve the seed for a request: an explicit {@code generation.seed} wins;
     * otherwise the caller-supplied per-request/session seed is used.
     */
    public static long resolveSeed(CompletionGeneration generation, long defaultSeed) {
        return generation.getSeed() != null ? generation.getSeed() : defaultSeed;
    }

    /** Sample the output-token count; a null distribution degenerates to a single token. */
    static int sampleOutputTokens(LogNormal outputTokens, Random random) {
        int sampled = sampleInt(outputTokens, random, 1);
        return Math.min(Math.max(sampled, 1), MAX_OUTPUT_TOKENS);
    }

    /** Sample the streaming rate; null means leave the physics rate unset (expander default applies). */
    static Integer sampleTokensPerSecond(LogNormal tokensPerSecond, Random random) {
        if (tokensPerSecond == null || tokensPerSecond.getMedian() == null) {
            return null;
        }
        int sampled = sampleInt(tokensPerSecond, random, 1);
        if (sampled < 1) {
            sampled = 1;
        }
        return Math.min(sampled, 10000);
    }

    /**
     * Build per-request streaming physics: the sampled tokens-per-second plus a
     * time-to-first-token of {@code baseMs + msPerKiloInputTokens * inputTokens / 1000}
     * with the configured jitter. Starts from the completion's declared physics and
     * overlays the sampled fields, so declared jitter/seed/subword settings survive a
     * partial generate declaration; returns {@code null} when the generation declares
     * neither an output rate nor a TTFT (leaving the declared physics untouched).
     */
    static StreamingPhysics sampledPhysics(CompletionGeneration generation,
                                          Integer tokensPerSecond,
                                          int inputTokens,
                                          Random random,
                                          StreamingPhysics declared) {
        Ttft ttft = generation.getTtft();
        if (tokensPerSecond == null && ttft == null) {
            return null;
        }
        StreamingPhysics physics = declared != null ? declared : StreamingPhysics.streamingPhysics();
        if (tokensPerSecond != null) {
            physics.withTokensPerSecond(tokensPerSecond);
        }
        if (ttft != null) {
            double base = ttft.getBaseMs() != null ? ttft.getBaseMs() : 0.0;
            double perKilo = ttft.getMsPerKiloInputTokens() != null ? ttft.getMsPerKiloInputTokens() : 0.0;
            double ttftMs = base + perKilo * (inputTokens / 1000.0);
            double jitter = ttft.getJitter() != null ? ttft.getJitter() : 0.0;
            if (jitter > 0.0) {
                ttftMs *= 1.0 + (random.nextDouble() * 2 * jitter - jitter);
            }
            physics.withTimeToFirstToken(new Delay(TimeUnit.MILLISECONDS, Math.max(0, Math.round(ttftMs))));
        }
        return physics;
    }

    /** Computed usage: output from the generated text, input from the decoded request. */
    private static Usage usageFor(Completion completion, int inputTokens, int outputTokens) {
        Usage existing = completion.getUsage();
        Usage usage = existing != null ? copyUsage(existing) : Usage.usage();
        usage.withInputTokens(Math.max(0, inputTokens));
        usage.withOutputTokens(outputTokens);
        return usage;
    }

    /** Test seam: current filler-cache size, so the bound is verifiable without touching production behaviour. */
    static int cacheSizeForTesting() {
        synchronized (FILLER_CACHE) {
            return FILLER_CACHE.size();
        }
    }

    /**
     * Filler text of exactly {@code tokenCount} tokens. Served from a bounded LRU
     * cache keyed by length (see {@link #FILLER_CACHE_MAX}); a miss builds N words
     * from the one-token-per-word bank (see {@link #WORDS}), so the measured count
     * equals {@code tokenCount} exactly. Lengths beyond {@link #FILLER_CACHE_SINGLE_CANDIDATE_ABOVE}
     * are never cached — near the {@link #MAX_OUTPUT_TOKENS} clamp a single candidate
     * string is already hundreds of KB, so four candidates would hold megabytes.
     */
    static String fillerText(int tokenCount, Random random) {
        if (tokenCount <= 0) {
            return "";
        }
        if (tokenCount > FILLER_CACHE_SINGLE_CANDIDATE_ABOVE) {
            return buildFiller(tokenCount, random);
        }
        List<String> candidates;
        synchronized (FILLER_CACHE) {
            candidates = FILLER_CACHE.get(tokenCount);
            if (candidates == null) {
                candidates = new ArrayList<>(FILLER_CANDIDATES_PER_LENGTH);
                for (int i = 0; i < FILLER_CANDIDATES_PER_LENGTH; i++) {
                    candidates.add(buildFiller(tokenCount, new Random(tokenCount)));
                }
                FILLER_CACHE.put(tokenCount, candidates);
            }
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    /**
     * Build filler of approximately {@code targetTokens} tokens. The bank contains
     * only lowercase words of at most five letters, each exactly one token, so the
     * count is just the number of words joined with single spaces.
     */
    private static String buildFiller(int targetTokens, Random random) {
        StringBuilder sb = new StringBuilder(targetTokens * 6);
        for (int i = 0; i < targetTokens; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(WORDS[random.nextInt(WORDS.length)]);
        }
        return sb.toString();
    }

    /** Sample a rounded value from a log-normal spec, clamped to at least its {@code min} (default 1). */
    private static int sampleInt(LogNormal spec, Random random, int defaultMin) {
        if (spec == null || spec.getMedian() == null) {
            return defaultMin;
        }
        double sigma = spec.getSigma() != null ? spec.getSigma() : 0.0;
        double min = spec.getMin() != null ? spec.getMin() : 1.0;
        double sample = spec.getMedian() * Math.exp(sigma * random.nextGaussian());
        return (int) Math.max(Math.round(min), Math.round(sample));
    }

    /** Per-request copy of the expectation's completion, so the shared model is never mutated. */
    private static Completion copyOf(Completion source) {
        return source.mutableCopy();
    }

    /**
     * Defensive copy of a declared physics object: {@link StreamingPhysics} builders mutate
     * and return {@code this}, so overlaying sampled fields onto the expectation's own
     * instance would leak per-request timing into the shared expectation model. Always
     * returns a detached copy carrying the declared values (or {@code null}).
     */
    static StreamingPhysics snapshotOf(StreamingPhysics declared) {
        if (declared == null) {
            return null;
        }
        return StreamingPhysics.streamingPhysics()
            .withTimeToFirstToken(declared.getTimeToFirstToken())
            .withTokensPerSecond(declared.getTokensPerSecond())
            .withJitter(declared.getJitter())
            .withSeed(declared.getSeed())
            .withSubwordStreaming(declared.getSubwordStreaming());
    }

    private static Usage copyUsage(Usage source) {
        return Usage.usage()
            .withInputTokens(source.getInputTokens())
            .withOutputTokens(source.getOutputTokens())
            .withCachedInputTokens(source.getCachedInputTokens())
            .withCacheCreationTokens(source.getCacheCreationTokens())
            .withReasoningTokens(source.getReasoningTokens());
    }
}
