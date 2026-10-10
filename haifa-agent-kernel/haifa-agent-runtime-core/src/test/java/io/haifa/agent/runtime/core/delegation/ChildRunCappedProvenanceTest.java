package io.haifa.agent.runtime.core.delegation;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.run.AgentRunResult;
import io.haifa.agent.core.run.AgentRunSpec;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.runtime.core.model.AgentChatResponseMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ChildRunCappedProvenanceTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @Test
    void attachesCappedModelProvenanceOnlyWhenAuthoritativeWarningPresent() {
        String summary = "Partial summary capped at token limit";
        AgentRun run = AgentRun.createRoot(new AgentRunId("child-run-1"), runSpec(1), NOW);
        run.start(NOW.plusSeconds(1));
        run.beginCompleting(NOW.plusSeconds(2));
        run.complete(result(summary, List.of(AgentChatResponseMapper.TRUNCATED_LENGTH_WARNING)), NOW.plusSeconds(3));

        ToolResult toolResult = ChildRunResults.toolResult(run, Optional.of(summary));

        assertThat(toolResult.structuredData()).containsEntry("modelOutputTruncated", true);
        assertThat(toolResult.structuredData()).containsEntry("modelFinishReason", "LENGTH");
        assertThat(toolResult.structuredData())
                .containsEntry("warnings", List.of(AgentChatResponseMapper.TRUNCATED_LENGTH_WARNING));
        // Original summary truncation flag must NOT be conflated with provider length cap
        assertThat(toolResult.truncated()).isFalse();
        assertThat(toolResult.structuredData()).containsEntry("truncated", false);
    }

    @Test
    void preservesDefaultMetadataWithoutTruncationProvenanceWhenNotCapped() {
        String summary = "Complete normal summary";
        AgentRun run = AgentRun.createRoot(new AgentRunId("child-run-2"), runSpec(1), NOW);
        run.start(NOW.plusSeconds(1));
        run.beginCompleting(NOW.plusSeconds(2));
        run.complete(result(summary, List.of()), NOW.plusSeconds(3));

        ToolResult toolResult = ChildRunResults.toolResult(run, Optional.of(summary));

        assertThat(toolResult.structuredData()).doesNotContainKey("modelOutputTruncated");
        assertThat(toolResult.structuredData()).doesNotContainKey("modelFinishReason");
        assertThat(toolResult.structuredData()).doesNotContainKey("warnings");
        assertThat(toolResult.truncated()).isFalse();
    }

    @Test
    void separatesSummaryLengthTruncationFromModelCap() {
        String summary = "a".repeat(17_000);
        AgentRun run = AgentRun.createRoot(new AgentRunId("child-run-3"), runSpec(1), NOW);
        run.start(NOW.plusSeconds(1));
        run.beginCompleting(NOW.plusSeconds(2));
        run.complete(result(summary, List.of()), NOW.plusSeconds(3));

        ToolResult toolResult = ChildRunResults.toolResult(run, Optional.of(summary));

        // Summary cap is triggered by length > MAX_SUMMARY_LENGTH (= 16000)
        assertThat(toolResult.truncated()).isTrue();
        assertThat(toolResult.structuredData()).containsEntry("truncated", true);
        // Model cap provenance is NOT present because the model did not truncate
        assertThat(toolResult.structuredData()).doesNotContainKey("modelOutputTruncated");
        assertThat(toolResult.structuredData()).doesNotContainKey("modelFinishReason");
    }

    private static AgentRunResult result(String summary, List<String> warnings) {
        return new AgentRunResult(
                AgentRunOutcome.SUCCESS, summary, "io.haifa.agent.test", "1.0", Map.of(), List.of(), warnings);
    }

    private static AgentRunSpec runSpec(int maxDepth) {
        return new AgentRunSpec(
                new AgentSessionId("session-child-" + maxDepth),
                null,
                new TenantRef("tenant"),
                new PrincipalRef("principal", "user"),
                new AgentDefinitionId("agent"),
                new AgentDefinitionVersion(1, 0, 0),
                "profile",
                "1",
                AgentRunType.CHAT,
                "objective",
                new AgentRunBudget(100, 100, 100, 10, 10, 2, "USD", 100),
                new AgentRunLimits(10, maxDepth, 1, 60_000, 10_000),
                new RunConfigurationSnapshotRef("config", "sha256:config"));
    }
}
