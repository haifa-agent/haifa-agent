package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.api.TruncatedOutputPolicy;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.skill.api.SkillContentDigest;
import io.haifa.agent.skill.api.SkillTrustSnapshot;
import io.haifa.agent.store.sqlite.codec.EncodedPayload;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.payload.SqliteRuntimePayloadTypes;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolCoordinate;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolDefinitionHash;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolProviderId;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A persisted Tool binding must survive the SQLite JSON codec unchanged, so a resume can re-validate
 * it against the frozen catalog. The host execution Tool declares its model-visible maximum timeout
 * as a {@code Long}; JSON carries no integer width, so the decoded schema document used to come back
 * as an {@code Integer} and the binding no longer equalled the frozen one.
 */
final class SqliteToolBindingRoundTripTest {

    @Test
    void keepsTheFrozenExecutionBindingEqualAfterTheConfigurationCodecRoundTrip() {
        FrozenToolBinding binding = executionBinding();
        RuntimeConfigurationSnapshot snapshot = snapshot(binding);

        VersionedPayloadCodecRegistry codecs = SqliteRuntimePayloadTypes.create(1024 * 1024);
        EncodedPayload encoded = codecs.encode(SqliteRuntimePayloadTypes.CONFIGURATION, snapshot);
        RuntimeConfigurationSnapshot decoded = codecs.decode(SqliteRuntimePayloadTypes.CONFIGURATION, encoded);

        assertThat(decoded.toolBindings()).containsExactly(binding);
        assertThat(decoded).isEqualTo(snapshot);
    }

    private static FrozenToolBinding executionBinding() {
        ToolName name = new ToolName("execution_run");
        SemanticVersion version = new SemanticVersion("3.0.0");
        ToolProviderId providerId = new ToolProviderId("execution_run");
        ToolDefinition definition = new ToolDefinition(
                name,
                version,
                providerId,
                "Run an approved command or script",
                "fixture",
                new ToolSchema(
                        "haifa.execution.run.input",
                        "3.0.0",
                        Map.<String, Object>of(
                                "type",
                                "object",
                                "properties",
                                Map.<String, Object>of(
                                        "timeoutMillis",
                                        Map.<String, Object>of(
                                                "type", "integer", "minimum", 1000, "maximum", 600_000L)))),
                new ToolSchema("haifa.execution.run.output", "3.0.0", Map.<String, Object>of("type", "object")),
                ToolExecutionMode.HOST_PROCESS,
                true,
                Duration.ofMillis(600_000L),
                "per-workspace-execution",
                ToolIdempotency.NON_IDEMPOTENT,
                ToolRisk.HIGH,
                Set.of(ToolSideEffect.PROCESS_EXECUTION, ToolSideEffect.NETWORK_ACCESS),
                new ToolResourceRequirements(Set.of("execution_run"), Set.of(), Set.of("host-execution")),
                List.of(),
                ToolApprovalRequirement.ALWAYS,
                "haifa-execution",
                false,
                Set.of("execution"));
        return new FrozenToolBinding(
                new ToolAlias("execution_run"),
                new ToolCoordinate(name, version, providerId, new ToolDefinitionHash("1".repeat(64))),
                definition,
                "host:execution:v1:sha256:" + "2".repeat(64),
                "3".repeat(64));
    }

    private static RuntimeConfigurationSnapshot snapshot(FrozenToolBinding binding) {
        ResolvedModelSnapshot model = ResolvedModelSnapshot.create(
                new ModelProviderId("deepseek"),
                "1",
                new ModelDefinitionId("deepseek-chat"),
                "1",
                "deepseek-chat",
                "openai-compatible",
                "1",
                new ApiStyleId("openai-chat-completions"),
                "standard",
                URI.create("https://api.deepseek.com"),
                new CredentialRef("env://DEEPSEEK_API_KEY"),
                true,
                Set.of(ModelCapability.TEXT_CHAT),
                8_192,
                2_048,
                Map.of(),
                Map.of());

        return new RuntimeConfigurationSnapshot(
                new RunConfigurationSnapshotRef("config-binding", "sha256:0123456789abcdef"),
                new AgentDefinitionId("test-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                "test-profile",
                "1.0.0",
                AgentRunType.CHAT,
                new AgentRunBudget(100, 100, 100, 10, 10, 2, "USD", 100),
                new AgentRunLimits(10, 2, 1, 60_000, 10_000),
                List.of(binding),
                List.of(),
                new SkillContentDigest("sha256:" + "0".repeat(64)),
                "policy-1",
                SkillTrustSnapshot.empty(),
                Set.of(),
                "Execute objective",
                RuntimeOverrides.NONE,
                List.of(),
                model,
                Map.of(),
                Optional.empty(),
                TruncatedOutputPolicy.FAIL_CLOSED);
    }
}
