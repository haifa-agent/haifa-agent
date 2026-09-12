package io.haifa.agent.runtime.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunSpec;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.policy.api.PolicyChallenge;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.interaction.InMemoryInteractionPort;
import io.haifa.agent.runtime.core.interaction.InteractionRequest;
import io.haifa.agent.runtime.core.interaction.ToolApprovalTargets;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.skill.api.SkillContentDigest;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RuntimeToolExecutionVerifierTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private static final TenantRef TENANT = new TenantRef("tenant");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("owner", "user");

    @Test
    void allowsCurrentRunningSourceAndRejectsUnknownCrossSubjectAndTerminalSources() {
        Fixture fixture = fixture(PolicyEffect.ALLOW);
        AtomicBoolean intentChecked = new AtomicBoolean();

        assertThatCode(() -> fixture.verifier()
                        .verify(
                                TENANT,
                                fixture.run().id().value(),
                                PRINCIPAL,
                                fixture.call().id(),
                                (configuration, binding, request) -> intentChecked.set(true)))
                .doesNotThrowAnyException();
        assertThat(intentChecked).isTrue();
        assertThatThrownBy(() -> fixture.verifier()
                        .verify(
                                TENANT,
                                fixture.run().id().value(),
                                PRINCIPAL,
                                new ToolCallId("forged"),
                                (configuration, binding, request) -> {}))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("uniquely persisted");
        assertThatThrownBy(() -> fixture.verifier()
                        .verify(
                                TENANT,
                                fixture.run().id().value(),
                                new PrincipalRef("other", "user"),
                                fixture.call().id(),
                                (configuration, binding, request) -> {}))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("does not own");

        fixture.call()
                .complete(
                        new io.haifa.agent.core.tool.ToolResult(true, "done", Map.of(), List.of(), List.of(), false),
                        NOW.plusSeconds(1));
        fixture.store().appendToolCall(fixture.call());
        assertThatThrownBy(() -> fixture.verifier()
                        .verify(
                                TENANT,
                                fixture.run().id().value(),
                                PRINCIPAL,
                                fixture.call().id(),
                                (configuration, binding, request) -> {}))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    void askRequiresOneCurrentExactAppliedApprovalAndRejectsAmbiguity() {
        Fixture fixture = fixture(PolicyEffect.ASK);

        assertThatThrownBy(() -> verify(fixture))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("lacks a current exact approval");

        InteractionRequest historical = approvalRequest(fixture, "historical-invalidated");
        fixture.interactions().create(historical);
        fixture.interactions().invalidate(historical.id(), 0, "SUPERSEDED", NOW.plusMillis(1));
        assertThatThrownBy(() -> verify(fixture))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("lacks a current exact approval");

        approve(fixture, "wrong-type", "execution-recovery");
        assertThatThrownBy(() -> verify(fixture))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("lacks a current exact approval");

        approve(fixture, "approval-1");
        assertThatCode(() -> verify(fixture)).doesNotThrowAnyException();

        approve(fixture, "approval-2");
        assertThatThrownBy(() -> verify(fixture))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("ambiguous");
    }

    private static void verify(Fixture fixture) {
        fixture.verifier()
                .verify(
                        TENANT,
                        fixture.run().id().value(),
                        PRINCIPAL,
                        fixture.call().id(),
                        (configuration, binding, request) -> {});
    }

    private static void approve(Fixture fixture, String id) {
        approve(fixture, id, "tool-approval");
    }

    private static void approve(Fixture fixture, String id, String type) {
        InteractionRequest approval = approvalRequest(fixture, id, type);
        fixture.interactions().create(approval);
        fixture.interactions()
                .respond(
                        new InteractionResponseSubmission(
                                new InteractionResponseId(id + "-response"),
                                approval.id(),
                                approval.runId(),
                                0,
                                InteractionAction.APPROVE,
                                List.of(),
                                id + "-key",
                                NOW.plusMillis(1)),
                        new RuntimeCallerContext(TENANT, PRINCIPAL),
                        NOW.plusMillis(1));
        fixture.interactions().markResolutionApplied(approval.id());
    }

    private static InteractionRequest approvalRequest(Fixture fixture, String id) {
        return approvalRequest(fixture, id, "tool-approval");
    }

    private static InteractionRequest approvalRequest(Fixture fixture, String id, String type) {
        PolicyDecision decision = fixture.decision();
        var request = new io.haifa.agent.runtime.core.decision.ToolRequest(
                fixture.call().id(),
                fixture.call().providerCorrelationId(),
                fixture.call().idempotencyKey(),
                fixture.call().toolName(),
                fixture.call().toolVersion(),
                fixture.call().arguments());
        return new InteractionRequest(
                new InteractionRequestId(id),
                fixture.run().id(),
                TENANT,
                PRINCIPAL,
                type,
                "Safe approval",
                true,
                ToolApprovalTargets.ordinary(fixture.run(), fixture.call().id(), fixture.binding(), request, decision),
                NOW,
                Optional.empty());
    }

    private static Fixture fixture(PolicyEffect effect) {
        Map<String, Object> schema =
                Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", true);
        var definition = new ToolDefinition(
                new ToolName("execution_run"),
                new SemanticVersion("2.0.0"),
                new ToolProviderId("haifa-execution"),
                "Run execution",
                "Run a bounded execution",
                new ToolSchema("haifa.execution.run.input", "2.0.0", schema),
                new ToolSchema("haifa.execution.run.output", "2.0.0", schema),
                ToolExecutionMode.HOST_PROCESS,
                true,
                java.time.Duration.ofSeconds(30),
                "execution",
                ToolIdempotency.NON_IDEMPOTENT,
                ToolRisk.HIGH,
                Set.of(ToolSideEffect.PROCESS_EXECUTION),
                new ToolResourceRequirements(Set.of("execution_run"), Set.of(), Set.of("test@1")),
                List.of(),
                ToolApprovalRequirement.ALWAYS,
                "test",
                false,
                Set.of());
        var binding = new FrozenToolBinding(
                new ToolAlias("execution_run"),
                new ToolCoordinate(
                        definition.name(),
                        definition.version(),
                        definition.providerId(),
                        new ToolDefinitionHash("1".repeat(64))),
                definition,
                "provider-binding",
                "catalog");
        var reference = new RunConfigurationSnapshotRef("configuration", "sha256:" + "2".repeat(64));
        var configuration = new RuntimeConfigurationSnapshot(
                reference,
                new AgentDefinitionId("agent"),
                new AgentDefinitionVersion(1, 0, 0),
                "product",
                "1",
                AgentRunType.CHAT,
                budget(),
                limits(),
                List.of(binding),
                List.of(),
                new SkillContentDigest("sha256:" + "3".repeat(64)),
                "skill-policy",
                Set.of(),
                "Test instruction",
                io.haifa.agent.runtime.api.RuntimeOverrides.NONE,
                List.of(),
                model());
        var run = AgentRun.createRoot(
                new AgentRunId("run"),
                new AgentRunSpec(
                        new AgentSessionId("session"),
                        new ProjectRef("project"),
                        TENANT,
                        PRINCIPAL,
                        new AgentDefinitionId("agent"),
                        new AgentDefinitionVersion(1, 0, 0),
                        "product",
                        "1",
                        AgentRunType.CHAT,
                        "test",
                        budget(),
                        limits(),
                        reference),
                NOW);
        ToolArguments arguments = new ToolArguments(
                definition.inputSchema().id(),
                definition.inputSchema().version(),
                Map.of("mode", "COMMAND", "content", "echo safe", "purpose", "test"));
        var call = new ToolCall(
                new ToolCallId("tool-call"),
                run.id(),
                new AgentStepId("step"),
                new ProviderToolCallCorrelationId("provider-call"),
                new RuntimeIdempotencyKey("tool-key"),
                binding.alias().value(),
                definition.version().value(),
                arguments,
                NOW);
        call.beginValidation();
        call.beginPolicyCheck();
        call.start(NOW);
        var store = new InMemoryRuntimeStore();
        store.saveConfiguration(configuration);
        store.insert(run);
        store.appendToolCall(call);
        var interactions = new InMemoryInteractionPort();
        PolicyDecision decision = effect == PolicyEffect.ASK
                ? new PolicyDecision(
                        PolicyEffect.ASK,
                        Optional.of(PolicyChallenge.APPROVAL),
                        "APPROVAL_REQUIRED",
                        "Approval required",
                        "requirement")
                : new PolicyDecision(PolicyEffect.ALLOW, Optional.empty(), "ALLOW", "Allowed", "requirement");
        var verifier = new RuntimeToolExecutionVerifier(
                store,
                store,
                interactions,
                ToolRequestCanonicalizer.identity(),
                (ignoredRun, ignoredBinding, ignoredRequest) -> decision);
        return new Fixture(verifier, store, interactions, run, call, binding, decision);
    }

    private static AgentRunBudget budget() {
        return new AgentRunBudget(100, 100, 100, 10, 10, 2, "USD", 100);
    }

    private static AgentRunLimits limits() {
        return new AgentRunLimits(10, 2, 1, 60_000, 10_000);
    }

    private static ResolvedModelSnapshot model() {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("provider"),
                "1",
                new ModelDefinitionId("model"),
                "1",
                "model",
                "adapter",
                "1",
                new io.haifa.agent.model.api.ApiStyleId("style"),
                "standard",
                URI.create("https://example.test"),
                new CredentialRef("credential"),
                true,
                Set.of(ModelCapability.TEXT_CHAT),
                4096,
                1024,
                Map.of(),
                Map.of());
    }

    private record Fixture(
            RuntimeToolExecutionVerifier verifier,
            InMemoryRuntimeStore store,
            InMemoryInteractionPort interactions,
            AgentRun run,
            ToolCall call,
            FrozenToolBinding binding,
            PolicyDecision decision) {}
}
