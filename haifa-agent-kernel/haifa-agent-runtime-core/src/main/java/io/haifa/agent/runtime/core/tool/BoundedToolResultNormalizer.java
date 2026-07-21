package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Applies deterministic size limits and key-based secret redaction before tool data enters model context. */
public final class BoundedToolResultNormalizer implements ToolResultNormalizer {
    private final int maxSummaryCharacters;
    private final int maxStructuredEntries;

    public BoundedToolResultNormalizer(int maxSummaryCharacters, int maxStructuredEntries) {
        if (maxSummaryCharacters < 1 || maxStructuredEntries < 1) {
            throw new IllegalArgumentException("tool result limits must be positive");
        }
        this.maxSummaryCharacters = maxSummaryCharacters;
        this.maxStructuredEntries = maxStructuredEntries;
    }

    @Override
    public ToolResult normalize(ToolDefinition definition, ToolResult result) {
        String summary = result.summary();
        boolean truncated = result.truncated();
        if (summary.length() > maxSummaryCharacters) {
            summary = summary.substring(0, maxSummaryCharacters);
            truncated = true;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        for (var entry : result.structuredData().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            if (data.size() >= maxStructuredEntries) {
                truncated = true;
                break;
            }
            data.put(entry.getKey(), sanitize(entry.getKey(), entry.getValue()));
        }
        return new ToolResult(result.successful(), summary, data, result.assets(), result.artifacts(), truncated);
    }

    private Object sanitize(String key, Object value) {
        String normalized = key.toLowerCase(Locale.ROOT);
        if (normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("apikey")
                || normalized.contains("api_key")) {
            return "[REDACTED]";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (var entry : map.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .toList()) {
                String nestedKey = String.valueOf(entry.getKey());
                nested.put(nestedKey, sanitize(nestedKey, entry.getValue()));
            }
            return Map.copyOf(nested);
        }
        if (value instanceof List<?> list) {
            List<Object> nested = new ArrayList<>(list.size());
            for (Object item : list) nested.add(sanitize("item", item));
            return List.copyOf(nested);
        }
        return value;
    }
}
