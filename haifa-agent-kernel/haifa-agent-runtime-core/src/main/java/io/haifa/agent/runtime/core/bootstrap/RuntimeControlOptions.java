package io.haifa.agent.runtime.core.bootstrap;

import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** Frozen Runtime-owned controls carried beside, but never forwarded with, provider request options. */
public final class RuntimeControlOptions {
    public static final String PREFIX = "haifa.runtime.";
    public static final String FINALIZE_AFTER_TOOL_CALLS = PREFIX + "finalize_after_tool_calls";
    public static final String MAX_REASONING_BYTES = PREFIX + "max_reasoning_bytes";
    public static final String MAX_REASONING_DURATION_MILLIS = PREFIX + "max_reasoning_duration_millis";
    public static final String INPUT_ACCOUNTING_ENABLED = PREFIX + "input_accounting_enabled";

    public static final String WORKING_SET = PREFIX + "working_set";
    public static final String WORKING_SET_TARGET = PREFIX + "working_set_target_tokens";
    public static final String WORKING_SET_RETENTION = PREFIX + "working_set_retention_tokens";
    public static final String WORKING_SET_CACHE = PREFIX + "working_set_cache_contract";
    public static final String CONTEXT_BUDGET_POLICY = PREFIX + "context_budget_policy";
    public static final String UNIFIED_CONTEXT_V1 = "unified-context-v1";
    public static final String CONTEXT_PREVIEW_CHARS = PREFIX + "context_preview_chars";
    public static final String CONTEXT_MIN_SAVING_TOKENS = PREFIX + "context_min_saving_tokens";

    public static boolean unifiedContextBudget(Map<String, Object> options) {
        Object version = options.get(CONTEXT_BUDGET_POLICY);
        if (version != null && !UNIFIED_CONTEXT_V1.equals(version) && !"legacy".equals(version))
            throw new IllegalArgumentException("unsupported context budget policy");
        return workingSetEnabled(options) && UNIFIED_CONTEXT_V1.equals(version);
    }

    public static int contextPreviewChars(Map<String, Object> options) {
        return (int) bounded(options, CONTEXT_PREVIEW_CHARS, 2048, 128, 8192);
    }

    public static int contextMinSavingTokens(Map<String, Object> options) {
        return (int) bounded(options, CONTEXT_MIN_SAVING_TOKENS, 256, 1, 8192);
    }

    public static boolean workingSetEnabled(Map<String, Object> options) {
        Object value = options.get(WORKING_SET);
        if (value == null) return false;
        if (!(value instanceof Boolean enabled)) throw new IllegalArgumentException("working set must be boolean");
        return enabled;
    }

    public static long workingSetTarget(Map<String, Object> options) {
        return bounded(options, WORKING_SET_TARGET, 2048, 128, 65536);
    }

    public static long workingSetRetention(Map<String, Object> options) {
        return bounded(options, WORKING_SET_RETENTION, 20000, 128, 131072);
    }

    private static long bounded(Map<String, Object> options, String key, long fallback, long min, long max) {
        Object value = options.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number n)
                || n.doubleValue() != n.longValue()
                || n.longValue() < min
                || n.longValue() > max) throw new IllegalArgumentException("invalid working set bound");
        return ((Number) value).longValue();
    }

    private static void validateWorkingSet(Map<String, Object> options) {
        workingSetEnabled(options);
        unifiedContextBudget(options);
        contextPreviewChars(options);
        contextMinSavingTokens(options);
        workingSetTarget(options);
        workingSetRetention(options);
        Object contract = options.get(WORKING_SET_CACHE);
        if (contract != null) {
            if (!(contract instanceof Map<?, ?> map)
                    || !map.keySet()
                            .equals(java.util.Set.of("modelConfigurationDigest", "source", "date", "ttlMillis")))
                throw new IllegalArgumentException("cache contract requires exact binding, evidence and TTL");
            // Reuse the existing price-evidence validator; no price assumptions are introduced here.
            var evidence = new LinkedHashMap<String, Object>();
            for (var key : java.util.List.of("modelConfigurationDigest", "source", "date"))
                evidence.put(key, map.get(key));
            evidence.put("uncached", 1);
            evidence.put("cacheRead", 1);
            evidence.put("cacheWrite", 1);
            inputPriceRatios(Map.of(INPUT_PRICE_RATIOS, evidence));
            bounded(Map.of("ttl", map.get("ttlMillis")), "ttl", 1, 1, 3600000);
        }
    }

    public static final String INPUT_PRICE_RATIOS = PREFIX + "input_price_ratios";

    private RuntimeControlOptions() {}

    /** Caller-supplied, frozen price evidence; never a Provider option or a guessed default. */
    public static java.util.Optional<Map<String, Object>> inputPriceRatios(Map<String, Object> options) {
        Object configured = options.get(INPUT_PRICE_RATIOS);
        if (configured == null) return java.util.Optional.empty();
        if (!(configured instanceof Map<?, ?> map)
                || !map.keySet()
                        .equals(java.util.Set.of(
                                "modelConfigurationDigest", "source", "date", "uncached", "cacheRead", "cacheWrite")))
            throw new IllegalArgumentException("input price ratios require exact model, source, date and three ratios");
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : java.util.List.of("modelConfigurationDigest", "source", "date")) {
            if (!(map.get(key) instanceof String value) || value.isBlank() || value.length() > 512)
                throw new IllegalArgumentException("invalid input price evidence");
            result.put(key, value);
        }
        java.time.LocalDate.parse((String) result.get("date"));
        var priceSource = java.net.URI.create((String) result.get("source"));
        if (!"https".equals(priceSource.getScheme())
                || priceSource.getHost() == null
                || priceSource.getUserInfo() != null
                || priceSource.getQuery() != null
                || priceSource.getFragment() != null)
            throw new IllegalArgumentException("input price source must be a public HTTPS page");
        for (String key : java.util.List.of("uncached", "cacheRead", "cacheWrite")) {
            if (!(map.get(key) instanceof Number number))
                throw new IllegalArgumentException("invalid input price ratio");
            var value = new java.math.BigDecimal(number.toString());
            if (value.signum() < 0 || value.precision() > 20 || Math.abs(value.scale()) > 12)
                throw new IllegalArgumentException("invalid input price ratio");
            result.put(key, value);
        }
        return java.util.Optional.of(Map.copyOf(result));
    }

    public static boolean inputAccountingEnabled(Map<String, Object> options) {
        Object value = options.get(INPUT_ACCOUNTING_ENABLED);
        if (value == null) return true;
        if (!(value instanceof Boolean enabled))
            throw new IllegalArgumentException(INPUT_ACCOUNTING_ENABLED + " must be boolean");
        return enabled;
    }

    public static OptionalInt finalizeAfterToolCalls(Map<String, Object> options) {
        Objects.requireNonNull(options, "options must not be null");
        Object configured = options.get(FINALIZE_AFTER_TOOL_CALLS);
        if (configured == null) return OptionalInt.empty();
        if (!(configured instanceof Number number)) {
            throw new IllegalArgumentException(FINALIZE_AFTER_TOOL_CALLS + " must be a positive integer");
        }
        double numeric = number.doubleValue();
        if (!Double.isFinite(numeric) || numeric < 1 || numeric != Math.rint(numeric) || numeric > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(FINALIZE_AFTER_TOOL_CALLS + " must be a positive integer");
        }
        return OptionalInt.of(number.intValue());
    }

    public static OptionalLong maxReasoningBytes(Map<String, Object> options) {
        return positiveLong(options, MAX_REASONING_BYTES);
    }

    public static OptionalLong maxReasoningDurationMillis(Map<String, Object> options) {
        return positiveLong(options, MAX_REASONING_DURATION_MILLIS);
    }

    public static void validate(Map<String, Object> options, AgentRunLimits limits) {
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
        options.keySet().stream()
                .filter(key -> key.startsWith(PREFIX) && !supported(key))
                .findFirst()
                .ifPresent(key -> {
                    throw new IllegalArgumentException("unsupported Runtime control option: " + key);
                });
        OptionalInt threshold = finalizeAfterToolCalls(options);
        if (threshold.isPresent() && threshold.getAsInt() >= limits.maxToolCalls()) {
            throw new IllegalArgumentException(
                    FINALIZE_AFTER_TOOL_CALLS + " must be lower than the hard Tool-call limit");
        }
        maxReasoningBytes(options);
        inputAccountingEnabled(options);
        inputPriceRatios(options);
        validateWorkingSet(options);
        OptionalLong duration = maxReasoningDurationMillis(options);
        if (duration.isPresent() && duration.getAsLong() > limits.maxWallTimeMillis()) {
            throw new IllegalArgumentException(
                    MAX_REASONING_DURATION_MILLIS + " must not exceed the Run wall-time limit");
        }
    }

    public static void validate(Map<String, Object> options, AgentRunBudget budget) {
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(budget, "budget must not be null");
        options.keySet().stream()
                .filter(key -> key.startsWith(PREFIX) && !supported(key))
                .findFirst()
                .ifPresent(key -> {
                    throw new IllegalArgumentException("unsupported Runtime control option: " + key);
                });
        OptionalInt threshold = finalizeAfterToolCalls(options);
        if (threshold.isPresent() && threshold.getAsInt() >= budget.maxToolCalls()) {
            throw new IllegalArgumentException(
                    FINALIZE_AFTER_TOOL_CALLS + " must be lower than the hard Tool-call budget");
        }
        maxReasoningBytes(options);
        inputAccountingEnabled(options);
        inputPriceRatios(options);
        validateWorkingSet(options);
        maxReasoningDurationMillis(options);
    }

    public static boolean finalizeOnly(Map<String, Object> options, long completedToolCalls) {
        OptionalInt threshold = finalizeAfterToolCalls(options);
        return threshold.isPresent() && completedToolCalls >= threshold.getAsInt();
    }

    public static Map<String, Object> providerOptions(Map<String, Object> options) {
        Objects.requireNonNull(options, "options must not be null");
        Map<String, Object> provider = new LinkedHashMap<>();
        options.forEach((key, value) -> {
            if (!key.startsWith(PREFIX)) provider.put(key, value);
        });
        return Map.copyOf(provider);
    }

    private static boolean supported(String key) {
        return key.equals(FINALIZE_AFTER_TOOL_CALLS)
                || key.equals(MAX_REASONING_BYTES)
                || key.equals(MAX_REASONING_DURATION_MILLIS)
                || key.equals(INPUT_ACCOUNTING_ENABLED)
                || key.equals(INPUT_PRICE_RATIOS)
                || key.equals(WORKING_SET)
                || key.equals(WORKING_SET_TARGET)
                || key.equals(WORKING_SET_RETENTION)
                || key.equals(WORKING_SET_CACHE)
                || key.equals(CONTEXT_BUDGET_POLICY)
                || key.equals(CONTEXT_PREVIEW_CHARS)
                || key.equals(CONTEXT_MIN_SAVING_TOKENS);
    }

    private static OptionalLong positiveLong(Map<String, Object> options, String key) {
        Objects.requireNonNull(options, "options must not be null");
        Object configured = options.get(key);
        if (configured == null) return OptionalLong.empty();
        if (!(configured instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be a positive integer");
        }
        double numeric = number.doubleValue();
        long value = number.longValue();
        if (!Double.isFinite(numeric) || numeric < 1 || numeric != Math.rint(numeric) || value < 1) {
            throw new IllegalArgumentException(key + " must be a positive integer");
        }
        return OptionalLong.of(value);
    }
}
