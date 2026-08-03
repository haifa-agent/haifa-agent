package io.haifa.agent.context.budget;

import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.context.prompt.PromptComponent;
import io.haifa.agent.model.api.ModelToolSpecification;
import java.lang.reflect.Array;
import java.util.Map;

/** Deterministic conservative fallback until a model-specific tokenizer is registered. */
public final class HeuristicTokenEstimator implements TokenEstimator {
    @Override
    public int estimate(PromptComponent prompt) {
        return saturatedSum(tokens("[" + prompt.layer() + "/" + prompt.role() + "] "), tokens(prompt.text()), 6);
    }

    @Override
    public int estimate(ContextItem item) {
        return item.estimatedTokens();
    }

    @Override
    public int estimate(ModelToolSpecification tool) {
        return saturatedSum(tokens(tool.name()), tokens(tool.description()), tokens(tool.inputJsonSchema()), 20);
    }

    @Override
    public String version() {
        return "heuristic-structured-v2";
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

    /** Estimates the JSON-like wire representation of structured tool arguments and results. */
    public static int tokens(Object value) {
        if (value == null) return 1;
        if (value instanceof String text) return tokens(text) + 2;
        if (value instanceof Character character) return tokens(character.toString()) + 2;
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return tokens(value.toString());
        }
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
