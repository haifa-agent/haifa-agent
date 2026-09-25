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
 * session.
 */
public final class ChildRunResults {
    static final int MAX_SUMMARY_LENGTH = 16_000;

    private ChildRunResults() {}

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
