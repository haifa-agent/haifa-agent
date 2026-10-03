package io.haifa.agent.runtime.core.delegation;

import io.haifa.agent.core.reference.ArtifactRef;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.AgentRunUsage;
import io.haifa.agent.core.tool.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Maps a terminal child Run to the Tool Result of its delegation call.
 *
 * <p>The status comes from the Runtime, never from the child model's text. The parent only receives the bounded
 * final summary, the child's own usage and its artifact references; intermediate child messages stay in the child
 * session. When the complete successful output is available, structured display metadata also identifies that
 * output without making clients hash a bounded summary. Missing output produces no invented preview or digest.
 */
public final class ChildRunResults {
    static final int MAX_SUMMARY_LENGTH = 16_000;
    static final String OUTPUT_PREVIEW = "outputPreview";
    static final String OUTPUT_SHA256 = "outputSha256";
    static final String OUTPUT_TRUNCATED = "outputTruncated";

    private ChildRunResults() {}

    /** Removes display-only output metadata at the model message boundary. */
    public static Map<String, Object> modelVisibleData(Map<String, Object> source) {
        Map<String, Object> data = new LinkedHashMap<>(source);
        data.remove(OUTPUT_PREVIEW);
        data.remove(OUTPUT_SHA256);
        data.remove(OUTPUT_TRUNCATED);
        return Map.copyOf(data);
    }

    /** Reconciles complete-output metadata with a bounded, redacted display copy. */
    public static Map<String, Object> displayData(
            Map<String, Object> source,
            Map<String, Object> projected,
            boolean summaryRedacted,
            java.util.function.UnaryOperator<String> redactor) {
        Map<String, Object> data = new LinkedHashMap<>(projected);
        // Only Runtime-owned successful Child results can expose complete numeric token counts.
        // Generic token-named fields and incomplete/failed Child usage stay redacted.
        if ("COMPLETED".equals(source.get("status"))
                && source.get("usage") instanceof Map<?, ?> usage
                && data.get("usage") instanceof Map<?, ?> displayed) {
            List<String> counts = List.of("inputTokens", "outputTokens", "cachedInputTokens");
            if (counts.stream().allMatch(key -> nonnegativeCount(usage.get(key)))) {
                Map<String, Object> safeUsage = new LinkedHashMap<>();
                displayed.forEach((key, value) -> safeUsage.put(String.valueOf(key), value));
                counts.forEach(key -> safeUsage.put(key, usage.get(key)));
                data.put("usage", Map.copyOf(safeUsage));
            }
        }

        if (source.containsKey(OUTPUT_PREVIEW)
                && data.containsKey(OUTPUT_PREVIEW)
                && !Objects.equals(source.get(OUTPUT_PREVIEW), data.get(OUTPUT_PREVIEW))) {
            data.put(OUTPUT_TRUNCATED, true);
        }
        // A digest of redacted text can disclose low-entropy secrets through offline guessing.
        boolean previewRedacted =
                source.get(OUTPUT_PREVIEW) instanceof String preview && !preview.equals(redactor.apply(preview));
        if (summaryRedacted || previewRedacted) data.remove(OUTPUT_SHA256);
        return Map.copyOf(data);
    }

    private static boolean nonnegativeCount(Object value) {
        return (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                && ((Number) value).longValue() >= 0;
    }

    public static ToolResult toolResult(AgentRun child, Optional<String> output) {
        Objects.requireNonNull(child, "child must not be null");
        Objects.requireNonNull(output, "output must not be null");
        if (!child.status().isTerminal()) throw new IllegalArgumentException("child run must be terminal");
        String agent = child.agentDefinitionId().value();
        String prefix = "Child run " + child.id().value() + " (" + agent + ") ";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("childRunId", child.id().value());
        data.put("childAgent", agent);
        data.put("status", child.status().name());
        String text;
        String summary = "";
        boolean truncated = false;
        switch (child.status()) {
            case COMPLETED -> {
                var result = child.result().orElseThrow();
                data.put("outcome", result.outcome().name());
                output.ifPresent(value -> data.putAll(DelegationOutput.metadata(value)));
                summary = result.summary();
                text = prefix + "completed with outcome " + result.outcome().name() + ".";
            }
            case FAILED -> {
                var error = child.error().orElseThrow();
                data.put("reasonCode", error.code().wireCode());
                summary = output.orElse("");
                text = prefix + "failed (" + error.code().wireCode() + "): " + error.message();
            }
            case CANCELLED -> {
                String reason =
                        child.terminationReason().map(value -> value.code()).orElse("CANCELLED");
                data.put("reasonCode", reason);
                text = prefix + "was cancelled (" + reason + ").";
            }
            case TIMEOUT -> {
                String reason =
                        child.terminationReason().map(value -> value.code()).orElse("TIMEOUT");
                data.put("reasonCode", reason);
                text = prefix + "timed out (" + reason + ").";
            }
            default -> throw new IllegalArgumentException("child run must be terminal");
        }
        if (summary.length() > MAX_SUMMARY_LENGTH) {
            summary = summary.substring(0, MAX_SUMMARY_LENGTH);
            truncated = true;
        }
        if (!summary.isBlank()) {
            data.put("summary", summary);
            text = text + "\n\n" + (child.status() == AgentRunStatus.COMPLETED ? "" : "Partial output:\n") + summary;
        }
        data.put("usage", usage(child.usage()));
        List<ArtifactRef> artifacts =
                child.result().map(value -> value.artifacts()).orElse(List.of());
        data.put(
                "artifacts",
                artifacts.stream()
                        .map(artifact -> (Object) Map.of(
                                "artifactId", artifact.artifactId(),
                                "artifactType", artifact.artifactType(),
                                "version", artifact.version(),
                                "title", artifact.title()))
                        .toList());
        data.put("truncated", truncated);
        return new ToolResult(
                child.status() == AgentRunStatus.COMPLETED, text, Map.copyOf(data), List.of(), artifacts, truncated);
    }

    private static Map<String, Object> usage(AgentRunUsage usage) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("inputTokens", usage.inputTokens());
        values.put("outputTokens", usage.outputTokens());
        values.put("cachedInputTokens", usage.cachedInputTokens());
        values.put("modelCalls", usage.modelCalls());
        values.put("toolCalls", usage.toolCalls());
        values.put("costMinorUnits", usage.costMinorUnits());
        values.put("wallTimeMillis", usage.wallTimeMillis());
        return Map.copyOf(values);
    }
}
