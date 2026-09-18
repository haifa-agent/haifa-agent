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

    private RuntimeControlOptions() {}

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
                || key.equals(MAX_REASONING_DURATION_MILLIS);
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
