package org.mockserver.llm.cost;

import org.mockserver.model.Provider;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Estimated cost calculation for LLM API usage.
 *
 * <p>A pure lookup over a pricing table keyed on (provider, model) with
 * input/output rates in USD per million tokens. Returns {@code null} for an
 * unknown model so callers can distinguish "free/known-zero" from "unpriceable".
 *
 * <p><strong>Companion dashboard pricing table</strong>
 * {@code mockserver-ui/src/lib/llmPricing.ts} maintains a parallel table with
 * the same prefix-walk semantics, and is kept in sync with this table. A
 * UI-side drift guard ({@code mockserver-ui/src/__tests__/llmPricing.test.ts})
 * snapshots the expected prefixes and rates and fails if the dashboard table
 * drifts from them, so when refreshing rates update both tables together and
 * update that test's canonical snapshot to match.
 *
 * <p><strong>Pricing source / freshness:</strong> rates are public provider
 * list prices captured 2026-06 (newer families) / 2025-Q4 (original entries).
 * They WILL drift — treat any total as an estimate, not an invoice. Sources:
 * Anthropic (anthropic.com/pricing), OpenAI (openai.com/api/pricing),
 * Gemini (ai.google.dev/pricing).
 */
public final class LlmPricing {

    /**
     * The cheapest model in a provider's pricing table — its prefix label and
     * blended (input+output per-million) rate. Returned by {@link #cheapestModel(Provider)}
     * so a caller can suggest a smaller model for trivial work.
     */
    public static final class ModelOption {
        private final String label;
        private final double blendedPerMillion;

        public ModelOption(String label, double blendedPerMillion) {
            this.label = label;
            this.blendedPerMillion = blendedPerMillion;
        }

        public String getLabel() {
            return label;
        }

        public double getBlendedPerMillion() {
            return blendedPerMillion;
        }
    }

    /** Input/output list price in USD per million tokens. */
    public static final class PricingEntry {
        private final double inputPerMillion;
        private final double outputPerMillion;
        private final boolean approximate;

        public PricingEntry(double inputPerMillion, double outputPerMillion) {
            this(inputPerMillion, outputPerMillion, false);
        }

        public PricingEntry(double inputPerMillion, double outputPerMillion, boolean approximate) {
            this.inputPerMillion = inputPerMillion;
            this.outputPerMillion = outputPerMillion;
            this.approximate = approximate;
        }

        public double getInputPerMillion() {
            return inputPerMillion;
        }

        public double getOutputPerMillion() {
            return outputPerMillion;
        }

        /**
         * True when these rates are an admitted placeholder / approximation (e.g. gpt-5*
         * mapped to the nearest known tier) rather than a verified provider list price. Any
         * cost derived from an approximate rate must be presented as an estimate, not fact.
         */
        public boolean isApproximate() {
            return approximate;
        }
    }

    // Prefix-keyed tables. The lookup walks each list in order and uses the
    // first entry whose key is a prefix of the model id (case-insensitive), so
    // MORE-SPECIFIC PREFIXES MUST COME FIRST (e.g. gpt-4o-mini before gpt-4o,
    // otherwise every gpt-4o-mini model would silently bill at gpt-4o's rate).
    private static final List<Map.Entry<String, PricingEntry>> ANTHROPIC_PRICING = Arrays.asList(
        // Current Claude families (public list prices, anthropic.com/pricing, captured
        // 2026-06). More-specific dotted prefixes (claude-opus-4-8) come before the
        // generic claude-opus-4 so they win the prefix walk.
        entry("claude-fable-5", new PricingEntry(10.0, 50.0)),
        entry("claude-opus-4-8", new PricingEntry(5.0, 25.0)),
        entry("claude-opus-4-7", new PricingEntry(5.0, 25.0)),
        entry("claude-opus-4-6", new PricingEntry(5.0, 25.0)),
        entry("claude-opus-4-5", new PricingEntry(5.0, 25.0)),
        entry("claude-sonnet-4-6", new PricingEntry(3.0, 15.0)),
        entry("claude-sonnet-4-5", new PricingEntry(3.0, 15.0)),
        entry("claude-haiku-4-5", new PricingEntry(1.0, 5.0)),
        // Original Claude 4.0/4.1 families (claude-opus-4-0/4-1, claude-sonnet-4-0).
        entry("claude-opus-4", new PricingEntry(15.0, 75.0)),
        entry("claude-sonnet-4", new PricingEntry(3.0, 15.0)),
        entry("claude-haiku-4", new PricingEntry(0.8, 4.0))
    );

    private static final List<Map.Entry<String, PricingEntry>> OPENAI_PRICING = Arrays.asList(
        // gpt-4o-mini before gpt-4o (prefix walk). gpt-4.1 family: list prices
        // captured 2026-06 (openai.com/api/pricing).
        entry("gpt-4o-mini", new PricingEntry(0.15, 0.6)),
        entry("gpt-4o", new PricingEntry(2.5, 10.0)),
        entry("gpt-4.1-mini", new PricingEntry(0.4, 1.6)),
        entry("gpt-4.1-nano", new PricingEntry(0.1, 0.4)),
        entry("gpt-4.1", new PricingEntry(2.0, 8.0)),
        // o-series reasoning models. o4-mini / o3-mini before o3 in the walk.
        entry("o4-mini", new PricingEntry(1.1, 4.4)),
        entry("o3-mini", new PricingEntry(1.1, 4.4)),
        entry("o3", new PricingEntry(15.0, 60.0)),
        // APPROXIMATE: gpt-5* prices are NOT verified here — mapped to the nearest
        // known tier (gpt-4o flagship / gpt-4o-mini cheap tier) as a placeholder so a
        // recognised model resolves to SOMETHING rather than null. Confirm against the
        // provider price list before relying on these figures.
        entry("gpt-5-mini", new PricingEntry(0.15, 0.6, true)),   // ~gpt-4o-mini tier (approx, TBC)
        entry("gpt-5-nano", new PricingEntry(0.15, 0.6, true)),   // ~gpt-4o-mini tier (approx, TBC)
        entry("gpt-5", new PricingEntry(2.5, 10.0, true))         // ~gpt-4o flagship tier (approx, TBC)
    );

    private static final List<Map.Entry<String, PricingEntry>> GEMINI_PRICING = Arrays.asList(
        // gemini-2.5 families: list prices captured 2026-06 (ai.google.dev/pricing).
        // More-specific 2.5-flash-lite before 2.5-flash before 2.5-pro in the walk.
        entry("gemini-2.5-flash-lite", new PricingEntry(0.1, 0.4)),
        entry("gemini-2.5-flash", new PricingEntry(0.3, 2.5)),
        entry("gemini-2.5-pro", new PricingEntry(1.25, 10.0)),
        entry("gemini-2.0-flash-lite", new PricingEntry(0.075, 0.3)),
        entry("gemini-2.0-flash", new PricingEntry(0.1, 0.4))
    );

    // Ollama is always free (local models).
    private static final PricingEntry OLLAMA_PRICING = new PricingEntry(0.0, 0.0);

    // --- OpenAI-chat-compatible providers ---
    // These are public list prices for OpenAI-compatible providers, captured 2026-06.
    // They are marked APPROXIMATE (isApproximate() == true) because these providers
    // revise prices frequently and MockServer does not verify them per-release — treat
    // any derived cost as an estimate, not an invoice. More-specific prefixes come
    // first so the prefix walk resolves the right tier.
    private static final List<Map.Entry<String, PricingEntry>> MISTRAL_PRICING = Arrays.asList(
        // mistral.ai/pricing (approx). mistral-large ~ flagship, mistral-small ~ cheap tier.
        entry("mistral-large", new PricingEntry(2.0, 6.0, true)),
        entry("mistral-medium", new PricingEntry(0.4, 2.0, true)),
        entry("mistral-small", new PricingEntry(0.1, 0.3, true)),
        entry("codestral", new PricingEntry(0.3, 0.9, true)),
        entry("ministral-8b", new PricingEntry(0.1, 0.1, true)),
        entry("ministral-3b", new PricingEntry(0.04, 0.04, true)),
        entry("open-mistral-nemo", new PricingEntry(0.15, 0.15, true))
    );

    private static final List<Map.Entry<String, PricingEntry>> XAI_PRICING = Arrays.asList(
        // x.ai/api (approx). grok-3-mini before grok-3 in the prefix walk.
        entry("grok-4", new PricingEntry(3.0, 15.0, true)),
        entry("grok-3-mini", new PricingEntry(0.3, 0.5, true)),
        entry("grok-3", new PricingEntry(3.0, 15.0, true)),
        entry("grok-2", new PricingEntry(2.0, 10.0, true)),
        entry("grok-beta", new PricingEntry(5.0, 15.0, true))
    );

    private static final List<Map.Entry<String, PricingEntry>> DEEPSEEK_PRICING = Arrays.asList(
        // deepseek.com/pricing (approx). deepseek-reasoner (R1) vs deepseek-chat (V3).
        entry("deepseek-reasoner", new PricingEntry(0.55, 2.19, true)),
        entry("deepseek-chat", new PricingEntry(0.27, 1.1, true))
    );

    private static final List<Map.Entry<String, PricingEntry>> GROQ_PRICING = Arrays.asList(
        // groq.com/pricing (approx) — Groq serves open-weight models at its own rates.
        // llama-3.3-70b / llama-3.1-8b before the generic llama fallbacks.
        entry("llama-3.3-70b", new PricingEntry(0.59, 0.79, true)),
        entry("llama-3.1-8b", new PricingEntry(0.05, 0.08, true)),
        entry("llama-3.1-70b", new PricingEntry(0.59, 0.79, true)),
        entry("llama-3.1-405b", new PricingEntry(1.79, 1.79, true)),
        entry("mixtral-8x7b", new PricingEntry(0.24, 0.24, true)),
        entry("gemma2-9b", new PricingEntry(0.2, 0.2, true))
    );

    private LlmPricing() {
    }

    private static Map.Entry<String, PricingEntry> entry(String prefix, PricingEntry pricing) {
        return new SimpleImmutableEntry<>(prefix, pricing);
    }

    private static PricingEntry findPricing(List<Map.Entry<String, PricingEntry>> table, String model) {
        if (model == null) {
            return null;
        }
        String lower = model.toLowerCase();
        for (Map.Entry<String, PricingEntry> e : table) {
            if (lower.startsWith(e.getKey().toLowerCase())) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Look up pricing for a provider and model, or {@code null} if unrecognised.
     */
    public static PricingEntry getPricing(Provider provider, String model) {
        if (provider == null) {
            return null;
        }
        switch (provider) {
            case ANTHROPIC:
                return findPricing(ANTHROPIC_PRICING, model);
            case BEDROCK:
                // Bedrock Anthropic model ids look like "anthropic.claude-sonnet-4-…".
                return findPricing(ANTHROPIC_PRICING, model == null ? null : model.replaceFirst("^anthropic\\.", ""));
            case OPENAI:
            case AZURE_OPENAI:
                // Azure OpenAI uses user-defined deployment names rather than canonical
                // model ids, so this returns null for most real Azure deployments.
                return findPricing(OPENAI_PRICING, model);
            case OPENAI_RESPONSES:
                return findPricing(OPENAI_PRICING, model);
            case GEMINI:
                return findPricing(GEMINI_PRICING, model);
            case OLLAMA:
                return OLLAMA_PRICING;
            case MISTRAL:
                return findPricing(MISTRAL_PRICING, model);
            case XAI:
                return findPricing(XAI_PRICING, model);
            case DEEPSEEK:
                return findPricing(DEEPSEEK_PRICING, model);
            case GROQ:
                return findPricing(GROQ_PRICING, model);
            case OPENROUTER:
                // OpenRouter fronts many upstream models with vendor-prefixed ids
                // (e.g. "openai/gpt-4o", "anthropic/claude-sonnet-4", "google/gemini-2.5-pro").
                // Route to the underlying vendor's table by stripping the prefix, so the
                // pass-through price is approximated. Unknown/unprefixed ids resolve to null.
                return openRouterPricing(model);
            case ORCAROUTER:
                // OrcaRouter also fronts many upstream models with vendor-prefixed ids
                // (e.g. "anthropic/claude-sonnet-5", "anthropic/claude-opus-5"), so it uses
                // the same prefix-stripping approximation as OpenRouter.
                return openRouterPricing(model);
            default:
                return null;
        }
    }

    /**
     * Approximate OpenRouter pricing by routing a vendor-prefixed model id
     * ({@code <vendor>/<model>}) to the underlying vendor's pricing table. Returns
     * {@code null} for an unrecognised vendor prefix or a bare (unprefixed) id.
     */
    private static PricingEntry openRouterPricing(String model) {
        if (model == null) {
            return null;
        }
        int slash = model.indexOf('/');
        if (slash <= 0 || slash == model.length() - 1) {
            return null;
        }
        String vendor = model.substring(0, slash).toLowerCase();
        String rest = model.substring(slash + 1);
        switch (vendor) {
            case "openai":
                return findPricing(OPENAI_PRICING, rest);
            case "anthropic":
                return findPricing(ANTHROPIC_PRICING, rest);
            case "google":
                return findPricing(GEMINI_PRICING, rest);
            case "mistralai":
            case "mistral":
                return findPricing(MISTRAL_PRICING, rest);
            case "x-ai":
            case "xai":
                return findPricing(XAI_PRICING, rest);
            case "deepseek":
                return findPricing(DEEPSEEK_PRICING, rest);
            default:
                return null;
        }
    }

    /**
     * Estimate the cost in USD for a provider, model, and token counts.
     *
     * @return the estimated cost in USD, or {@code null} if the model is
     * unknown. Returns {@code 0.0} for zero tokens on a known (priced) model.
     */
    public static Double estimateCostUsd(Provider provider, String model, long inputTokens, long outputTokens) {
        PricingEntry pricing = getPricing(provider, model);
        if (pricing == null) {
            return null;
        }
        if (inputTokens == 0 && outputTokens == 0) {
            return 0.0;
        }
        return (inputTokens / 1_000_000.0) * pricing.getInputPerMillion()
            + (outputTokens / 1_000_000.0) * pricing.getOutputPerMillion();
    }

    /**
     * True when the provider/model resolves to an admitted-placeholder / approximate rate
     * (e.g. gpt-5*), so any cost derived from it must be flagged as an estimate rather than
     * fact. False for an unpriced model (no entry) or a verified list price.
     */
    public static boolean isApproximateRate(Provider provider, String model) {
        PricingEntry pricing = getPricing(provider, model);
        return pricing != null && pricing.isApproximate();
    }

    /**
     * Return the provider's pricing table, or {@code null} when the provider has
     * no prefix table (OLLAMA flat-free, AZURE deployment names, unknown).
     */
    private static List<Map.Entry<String, PricingEntry>> tableFor(Provider provider) {
        if (provider == null) {
            return null;
        }
        switch (provider) {
            case ANTHROPIC:
            case BEDROCK:
                return ANTHROPIC_PRICING;
            case OPENAI:
            case OPENAI_RESPONSES:
                return OPENAI_PRICING;
            case GEMINI:
                return GEMINI_PRICING;
            case MISTRAL:
                return MISTRAL_PRICING;
            case XAI:
                return XAI_PRICING;
            case DEEPSEEK:
                return DEEPSEEK_PRICING;
            case GROQ:
                return GROQ_PRICING;
            default:
                // AZURE_OPENAI (deployment names), OLLAMA (flat free), OPENROUTER
                // (per-model pass-through, no single table), unknown.
                return null;
        }
    }

    /**
     * The cheapest model in the provider's pricing table by blended
     * (input+output per-million) rate, or {@code null} for OLLAMA / unknown /
     * tableless providers. Deterministic: ties resolve to the first table entry.
     */
    public static ModelOption cheapestModel(Provider provider) {
        List<Map.Entry<String, PricingEntry>> table = tableFor(provider);
        if (table == null || table.isEmpty()) {
            return null;
        }
        Map.Entry<String, PricingEntry> cheapest = null;
        double cheapestBlended = Double.MAX_VALUE;
        for (Map.Entry<String, PricingEntry> e : table) {
            double blended = e.getValue().getInputPerMillion() + e.getValue().getOutputPerMillion();
            if (blended < cheapestBlended) {
                cheapestBlended = blended;
                cheapest = e;
            }
        }
        return cheapest == null ? null : new ModelOption(cheapest.getKey(), cheapestBlended);
    }

    /**
     * The blended (input+output per-million) rate for a provider/model, or
     * {@code null} when the model is unpriced.
     */
    public static Double blendedPerMillion(Provider provider, String model) {
        PricingEntry pricing = getPricing(provider, model);
        if (pricing == null) {
            return null;
        }
        return pricing.getInputPerMillion() + pricing.getOutputPerMillion();
    }
}
