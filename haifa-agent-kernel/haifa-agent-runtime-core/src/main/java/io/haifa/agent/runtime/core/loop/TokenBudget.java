package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.context.api.ContextBuildException;
import io.haifa.agent.context.api.ContextBuildFailure;
import io.haifa.agent.context.budget.ContextWindowBudget;
import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.context.prompt.PromptComponent;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.lang.reflect.Array;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime's single conservative budget and token-estimation entry point for normal context assembly.
 *
 * <p>Semantic compaction retains its isolated estimator during the observation period because its
 * trigger and window calculations are intentionally out of scope for Phase C.
 */
public final class TokenBudget {
    private static final String ESTIMATOR_VERSION = "runtime-structured-v1";

    private final ContextWindowBudget window;

    private TokenBudget(ContextWindowBudget window) {
        this.window = window;
    }

    static TokenBudget forModel(ResolvedModelSnapshot model, int requestedOutputTokens, int safetyMarginTokens) {
        Objects.requireNonNull(model, "model must not be null");
        if (requestedOutputTokens < 1) {
            throw new IllegalArgumentException("requestedOutputTokens must be positive");
        }
        if (safetyMarginTokens < 0) {
            throw new IllegalArgumentException("safetyMarginTokens must not be negative");
        }
        long outputReserve = Math.min((long) model.maxOutputTokens(), (long) requestedOutputTokens);
        long availableInput = (long) model.contextWindow() - outputReserve - safetyMarginTokens;
        if (availableInput < 1) {
            throw new ContextBuildException(
                    ContextBuildFailure.MODEL_WINDOW_TOO_SMALL,
                    "model context window cannot fit output reserve and safety margin");
        }
        return new TokenBudget(
                new ContextWindowBudget(model.contextWindow(), outputReserve, safetyMarginTokens, availableInput));
    }

    ContextWindowBudget window() {
        return window;
    }

    long availableInputTokens() {
        return window.availableInputTokens();
    }

    int estimate(PromptComponent prompt) {
        return saturatedSum(tokens(prompt.text()), 6);
    }

    int estimate(ContextItem item) {
        return item.estimatedTokens();
    }

    int estimate(ModelToolSpecification tool) {
        return saturatedSum(tokens(tool.name()), tokens(tool.description()), tokens(tool.inputJsonSchema()), 20);
    }

    String estimatorVersion() {
        return ESTIMATOR_VERSION;
    }

    public static int tokens(String value) {
        long ascii = 0L;
        long nonAscii = 0L;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (codePoint <= 0x7f) ascii++;
            else nonAscii++;
            offset += Character.charCount(codePoint);
        }
        long estimated = Math.max(1L, (ascii + 2L) / 3L + nonAscii);
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, estimated));
    }

    public static int tokens(Object value) {
        if (value == null) return 1;
        if (value instanceof String text) return tokens(text) + 2;
        if (value instanceof Character character) return tokens(character.toString()) + 2;
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>)
            return tokens(value.toString());
        if (value instanceof Map<?, ?> map) {
            long estimated = 2L;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                estimated = saturatedAdd(estimated, tokens(String.valueOf(entry.getKey())) + 3L);
                estimated = saturatedAdd(estimated, tokens(entry.getValue()) + 1L);
            }
            return (int) Math.min(Integer.MAX_VALUE, estimated);
        }
        if (value instanceof Iterable<?> iterable) {
            long estimated = 2L;
            for (Object element : iterable) {
                estimated = saturatedAdd(estimated, tokens(element) + 1L);
            }
            return (int) Math.min(Integer.MAX_VALUE, estimated);
        }
        if (value.getClass().isArray()) {
            long estimated = 2L;
            for (int index = 0; index < Array.getLength(value); index++) {
                estimated = saturatedAdd(estimated, tokens(Array.get(value, index)) + 1L);
            }
            return (int) Math.min(Integer.MAX_VALUE, estimated);
        }
        return tokens(String.valueOf(value));
    }

    private static int saturatedSum(int... values) {
        long result = 0L;
        for (int value : values) result = saturatedAdd(result, value);
        return (int) result;
    }

    private static long saturatedAdd(long left, long right) {
        return Math.min(Integer.MAX_VALUE, left + right);
    }
}
