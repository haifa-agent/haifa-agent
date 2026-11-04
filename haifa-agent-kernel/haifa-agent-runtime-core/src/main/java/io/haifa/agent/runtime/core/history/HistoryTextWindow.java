package io.haifa.agent.runtime.core.history;

import io.haifa.agent.runtime.core.tool.ToolCallViewProjector;
import java.util.*;

/** Bounded deterministic text projection; never serializes protected lanes or arbitrary objects. */
final class HistoryTextWindow {
    private final int offset, length, firstLine;
    private int position, line = 1, nodes;
    private boolean more;
    private boolean incomplete;
    private final StringBuilder text = new StringBuilder();

    HistoryTextWindow(int offset, int length, int firstLine) {
        this.offset = offset;
        this.length = length;
        this.firstLine = firstLine;
    }

    boolean incomplete() {
        return incomplete;
    }

    String value() {
        return text.toString();
    }

    boolean more() {
        return more;
    }

    long nextOffset() {
        return (long) offset + text.length();
    }

    void text(String raw) {
        if (more || incomplete) return;
        String safe = ToolCallViewProjector.redactText(raw);
        for (int i = 0; i < safe.length(); i++) {
            char c = safe.charAt(i);
            if (line >= firstLine) {
                if (position++ >= offset) {
                    if (text.length() == length) {
                        more = true;
                        return;
                    }
                    text.append(c);
                }
            }
            if (c == '\n') line++;
        }
    }

    void value(Object value, int depth) {
        if (more || incomplete) return;
        if (++nodes > 10000 || depth > 16) {
            incomplete = true;
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 10000 || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
                incomplete = true;
                return;
            }
            for (var e : map.entrySet().stream()
                    .sorted(Comparator.comparing(e -> (String) e.getKey()))
                    .toList()) {
                String key = (String) e.getKey();
                String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (List.of(
                                "reasoning",
                                "thinking",
                                "continuation",
                                "signature",
                                "ciphertext",
                                "nonce",
                                "apikey",
                                "credential",
                                "authorization",
                                "secret",
                                "password",
                                "cookie",
                                "token")
                        .stream()
                        .anyMatch(normalized::contains)) continue;
                text("\n" + key + ": ");
                value(e.getValue(), depth + 1);
                if (more || incomplete) return;
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                text("\n");
                value(item, depth + 1);
                if (more || incomplete) return;
            }
        } else if (value instanceof String s) text(s);
        else if (value instanceof Number || value instanceof Boolean) text(value.toString());
        else if (value == null) text("null");
    }
}
