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
                || key.equals(INPUT_PRICE_RATIOS);
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
