package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.runtime.api.ToolCallView;
import io.haifa.agent.runtime.api.ToolDataView;
import io.haifa.agent.runtime.api.ToolResultView;
import io.haifa.agent.runtime.api.display.BoundedText;
import io.haifa.agent.runtime.api.display.ToolDisplayBudget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Builds a bounded display copy without exposing the Runtime store or mutable domain aggregate. */
public final class ToolCallViewProjector {
    private static final int MAX_DEPTH = 16;
    private static final int MAX_NODES = 1_024;
    private static final int MAX_STRING_BYTES = 16_384;
    private static final int MAX_STRING_LINES = 200;
    private static final String REDACTED = "[REDACTED]";
    private static final String TRUNCATED = "[TRUNCATED]";
    private static final Set<String> SENSITIVE_NAMES = Set.of(
            "authorization",
            "apikey",
            "cookie",
            "credential",
            "credentials",
            "continuation",
            "ciphertext",
            "password",
            "passwd",
            "privatekey",
            "secret",
            "signature",
            "token");
    private static final Pattern LABELED_SECRET = Pattern.compile(
            "(?i)((?:authorization|api[_-]?key|token|password|secret|credential|continuation|signature)\\s*[:=]\\s*(?:bearer\\s+)?)\\S+");
    private static final Pattern PROVIDER_KEY = Pattern.compile("(?i)\\bsk-[A-Za-z0-9_-]{8,}\\b");

    private ToolCallViewProjector() {}

    public static ToolCallView project(ToolCall call) {
        ProjectionBudget argumentsBudget = new ProjectionBudget();
        ToolDataView arguments =
                new ToolDataView(projectMap(call.arguments().values(), argumentsBudget, 0), argumentsBudget.truncated);
        return new ToolCallView(
                call.id(),
                call.runId(),
                call.toolName(),
                call.toolVersion(),
                arguments,
                call.status(),
                call.result().map(result -> {
                    ProjectionBudget resultBudget = new ProjectionBudget();
                    ToolDataView structuredData = new ToolDataView(
                            projectMap(result.structuredData(), resultBudget, 0), resultBudget.truncated);
                    String safeSummary = redactText(result.summary());
                    BoundedText boundedSummary = BoundedText.of(safeSummary, ToolDisplayBudget.defaultOutput());
                    return new ToolResultView(
                            result.successful(),
                            boundedSummary,
                            structuredData,
                            result.assets(),
                            result.artifacts(),
                            result.truncated() || resultBudget.truncated || boundedSummary.truncated());
                }),
                call.error().map(error -> error.error().code()),
                call.requestedAt(),
                call.startedAt(),
                call.completedAt());
    }

    private static Map<String, Object> projectMap(Map<String, Object> source, ProjectionBudget budget, int depth) {
        if (depth > MAX_DEPTH || !budget.consume()) {
            budget.truncated = true;
            return Map.of("truncated", TRUNCATED);
        }
        Map<String, Object> projected = new LinkedHashMap<>();
        for (var entry : source.entrySet()) {
            if (!budget.consume()) {
                projected.put("truncated", TRUNCATED);
                budget.truncated = true;
                break;
            }
            String key = boundedText(redactText(entry.getKey()), budget);
            projected.put(key, sensitiveName(key) ? REDACTED : projectValue(entry.getValue(), budget, depth + 1));
        }
        return Map.copyOf(projected);
    }

    private static Object projectValue(Object value, ProjectionBudget budget, int depth) {
        if (depth > MAX_DEPTH || !budget.consume()) {
            budget.truncated = true;
            return TRUNCATED;
        }
        if (value instanceof String text) return boundedText(redactText(text), budget);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> typed = new LinkedHashMap<>();
            map.forEach((key, element) -> typed.put(String.valueOf(key), element));
            return projectMap(typed, budget, depth);
        }
        if (value instanceof List<?> list) return projectList(list, budget, depth);
        if (value instanceof Set<?> set) return projectList(new ArrayList<>(set), budget, depth);
        if (value instanceof Number || value instanceof Boolean) return value;
        budget.truncated = true;
        return TRUNCATED;
    }

    private static List<Object> projectList(List<?> source, ProjectionBudget budget, int depth) {
        List<Object> projected = new ArrayList<>();
        for (Object value : source) {
            if (!budget.consume()) {
                projected.add(TRUNCATED);
                budget.truncated = true;
                break;
            }
            projected.add(projectValue(value, budget, depth + 1));
        }
        return List.copyOf(projected);
    }

    private static String boundedText(String value, ProjectionBudget budget) {
        BoundedText bounded = BoundedText.of(value, MAX_STRING_BYTES, MAX_STRING_LINES);
        if (bounded.truncated()) budget.truncated = true;
        return bounded.text();
    }

    private static String redactText(String value) {
        return PROVIDER_KEY
                .matcher(LABELED_SECRET.matcher(value).replaceAll("$1" + REDACTED))
                .replaceAll(REDACTED);
    }

    private static boolean sensitiveName(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return SENSITIVE_NAMES.stream().anyMatch(normalized::contains);
    }

    private static final class ProjectionBudget {
        private int nodes;
        private boolean truncated;

        private boolean consume() {
            if (nodes >= MAX_NODES) return false;
            nodes++;
            return true;
        }
    }
}
