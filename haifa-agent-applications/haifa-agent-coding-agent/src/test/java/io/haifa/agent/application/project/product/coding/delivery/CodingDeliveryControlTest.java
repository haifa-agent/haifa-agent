package io.haifa.agent.application.project.product.coding.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.context.api.ContextBuildRequest;
import io.haifa.agent.context.item.TextContextContent;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.run.AgentRunSpec;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.run.AgentRunUsage;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CodingDeliveryControlTest {
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @Test
    void resolverUsesOnlyTrustedModeAndRejectsInvalidTrustedMetadata() {
        Fixture ordinary = fixture("fix the implementation and add tests", Map.of());
        assertThat(new CodingTaskModeResolver(ordinary.store()).resolve(ordinary.run()))
                .isEqualTo(CodingTaskIntent.UNKNOWN);

        Fixture trusted = fixture("analyze only", trusted("CHANGE"));
        assertThat(new CodingTaskModeResolver(trusted.store()).resolve(trusted.run()))
                .isEqualTo(CodingTaskIntent.CHANGE);

        Fixture invalid = fixture(
                "analyze the repository", Map.of("codingTaskIntentTrusted", true, "codingTaskIntent", "NOT_SUPPORTED"));
        assertThatThrownBy(() -> new CodingTaskModeResolver(invalid.store()).resolve(invalid.run()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trusted coding task intent is invalid");
    }

    @Test
    void changeRequiresWorkspaceValidationAndDiffEvidence() {
        Fixture fixture = fixture("fix the implementation", trusted("CHANGE"));
        CodingCompletionPolicy policy = policy(fixture.store());

        assertThat(policy.evaluate(fixture.run(), finalDecision()).blockers())
                .extracting(blocker -> blocker.code())
                .containsExactlyInAnyOrder(
                        "WORKSPACE_CHANGE_MISSING", "VALIDATION_ATTEMPT_MISSING", "DIFF_INSPECTION_MISSING");

        tool(fixture, "file.write", Map.of("path", "src/Main.java"), Map.of("changeSetId", "change-1"));
        tool(
                fixture,
                "execution.run",
                Map.of(),
                Map.of("operationFamily", "TEST", "status", "SUCCEEDED", "exitCode", 0));
        tool(
                fixture,
                "execution.run",
                Map.of(),
                Map.of("operationFamily", "DIFF", "status", "SUCCEEDED", "exitCode", 0));

        var complete = policy.evaluate(fixture.run(), finalDecision());
        assertThat(complete.allowed()).isTrue();
        assertThat(complete.evidenceCodes())
                .contains("WORKSPACE_CHANGE", "VALIDATION_ATTEMPT", "VALIDATION_PASSED", "DIFF_INSPECTION");
    }

    @Test
    void validationFailureRequiresPassUnlessProfileAllowsConfirmedBlocker() {
        Fixture fixture = fixture("change the implementation", trusted("CHANGE"));
        tool(fixture, "file.write", Map.of("path", "README.md"), Map.of("changeSetId", "change-1"));
        tool(
                fixture,
                "execution.run",
                Map.of(),
                Map.of("operationFamily", "TEST", "status", "FAILED", "exitCode", 1, "failureCategory", "ENVIRONMENT"));
        tool(fixture, "execution.run", Map.of(), Map.of("operationFamily", "DIFF", "status", "SUCCEEDED"));

        assertThat(policy(fixture.store())
                        .evaluate(fixture.run(), finalDecision())
                        .blockers())
                .extracting(blocker -> blocker.code())
                .contains("VALIDATION_NOT_PASSED");
        assertThat(new CodingCompletionPolicy(
                                new CodingTaskModeResolver(fixture.store()),
                                new CodingDeliveryEvidenceLedger(fixture.store()),
                                new CodingDeliveryProfile(20, 25, 20, true))
                        .evaluate(fixture.run(), finalDecision())
                        .allowed())
                .isTrue();
    }

    @Test
    void trustedReadOnlyModeRejectsWorkspaceChanges() {
        Fixture fixture = fixture("please fix this", trusted("ANALYZE"));
        tool(fixture, "file.read", Map.of("path", "README.md"), Map.of("path", "README.md"));
        assertThat(policy(fixture.store())
                        .evaluate(fixture.run(), finalDecision())
                        .allowed())
                .isTrue();

        tool(fixture, "file.write", Map.of("path", "README.md"), Map.of("changeSetId", "change-2"));
        assertThat(policy(fixture.store())
                        .evaluate(fixture.run(), finalDecision())
                        .blockers())
                .extracting(blocker -> blocker.code())
                .contains("READ_ONLY_INTENT_HAS_CHANGES");
    }

    @Test
    void unknownModeUsesObservedReadOnlyOrChangeEvidence() {
        Fixture readOnly = fixture("fix this if needed", Map.of());
        CodingCompletionPolicy readOnlyPolicy = policy(readOnly.store());
        assertThat(readOnlyPolicy.evaluate(readOnly.run(), finalDecision()).blockers())
                .extracting(blocker -> blocker.code())
                .containsExactly("UNKNOWN_INTENT_EVIDENCE_MISSING");
        tool(readOnly, "file.search", Map.of("path", "."), Map.of("matches", List.of()));
        assertThat(readOnlyPolicy.evaluate(readOnly.run(), finalDecision()).allowed())
                .isTrue();

        Fixture changed = fixture("please take a look", Map.of());
        tool(changed, "file.write", Map.of("path", "README.md"), Map.of("changeSetId", "change-3"));
        assertThat(policy(changed.store())
                        .evaluate(changed.run(), finalDecision())
                        .blockers())
                .extracting(blocker -> blocker.code())
                .containsExactlyInAnyOrder("VALIDATION_ATTEMPT_MISSING", "DIFF_INSPECTION_MISSING");
    }

    @Test
    void deliveryContextContainsOnlyBoundedFactsAndActivatesReserveForTrustedChange() {
        Fixture fixture = fixture("fix behavior according to PERF.md", trusted("CHANGE"));
        CodingDeliveryContextSource source = new CodingDeliveryContextSource(
                fixture.store(),
                new CodingTaskModeResolver(fixture.store()),
                new CodingDeliveryEvidenceLedger(fixture.store()),
                CodingDeliveryProfile.safeDefault());
        AgentRunUsage usage = new AgentRunUsage(0, 0, 0, 0, 0, 0, 0, 48_000);

        String text = ((TextContextContent)
                        source.load(request(fixture.run(), usage)).getFirst().content())
                .text();

        assertThat(text)
                .startsWith("[CODING_RUN_STATE]")
                .contains(
                        "remainingModelCalls=20",
                        "remainingToolCalls=20",
                        "remainingIterations=19",
                        "remainingWallTimeSeconds=12",
                        "workspaceChanged=false",
                        "validationAttempted=false",
                        "validationPassed=unknown",
                        "diffInspected=false",
                        "deliveryReserve=ACTIVE",
                        "missingDeliveryEvidence=WORKSPACE_CHANGE|VALIDATION_ATTEMPT|DIFF_INSPECTION")
                .doesNotContain("PERF.md", "before editing", "malformed", "deduplication", "/Users/");
        assertThat(fixture.run().budget().maxModelCalls()).isEqualTo(20);
        assertThat(fixture.run().limits().maxWallTimeMillis()).isEqualTo(60_000);
    }

    @Test
    void evidenceBackedNoChangeCanComplete() {
        Fixture fixture = fixture("fix the implementation", trusted("CHANGE"));
        tool(
                fixture,
                "execution.run",
                Map.of(),
                Map.of(
                        "operationFamily",
                        "TEST",
                        "status",
                        "SUCCEEDED",
                        "exitCode",
                        0,
                        "noChangeJustificationCode",
                        "ALREADY_SATISFIED"));
        tool(
                fixture,
                "execution.run",
                Map.of(),
                Map.of("operationFamily", "DIFF", "status", "SUCCEEDED", "exitCode", 0));

        var result = policy(fixture.store()).evaluate(fixture.run(), finalDecision());
        assertThat(result.allowed()).isTrue();
        assertThat(result.evidenceCodes()).contains("NO_CHANGE_JUSTIFICATION", "VALIDATION_PASSED", "DIFF_INSPECTION");
    }

    private static Map<String, Object> trusted(String intent) {
        return Map.of("codingTaskIntentTrusted", true, "codingTaskIntent", intent);
    }

    private static CodingCompletionPolicy policy(InMemoryRuntimeStore store) {
        return new CodingCompletionPolicy(
                new CodingTaskModeResolver(store),
                new CodingDeliveryEvidenceLedger(store),
                CodingDeliveryProfile.safeDefault());
    }

    private static void tool(
            Fixture fixture, String name, Map<String, Object> arguments, Map<String, Object> resultData) {
        int sequence = fixture.store().toolCalls(fixture.run().id()).size() + 1;
        ToolCall call = new ToolCall(
                new ToolCallId("tool-" + sequence),
                fixture.run().id(),
                new AgentStepId("step-" + sequence),
                new ProviderToolCallCorrelationId("provider-" + sequence),
                new RuntimeIdempotencyKey("idempotency-" + sequence),
                name,
                "1.0.0",
                new ToolArguments("input", "1.0", arguments),
                NOW.plusSeconds(sequence));
        call.beginValidation();
        call.beginPolicyCheck();
        call.start(NOW.plusSeconds(sequence));
        call.complete(
                new ToolResult(true, "completed", resultData, List.of(), List.of(), false), NOW.plusSeconds(sequence));
        fixture.store().appendToolCall(call);
    }

    private static Fixture fixture(String request, Map<String, Object> metadata) {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = AgentRun.createRoot(
                new AgentRunId("run-1"),
                new AgentRunSpec(
                        new AgentSessionId("session-1"),
                        null,
                        new TenantRef("tenant"),
                        new PrincipalRef("principal", "user"),
                        new AgentDefinitionId("coding-agent"),
                        new AgentDefinitionVersion(1, 0, 0),
                        "coding",
                        "1.0",
                        AgentRunType.CHAT,
                        request,
                        new AgentRunBudget(1000, 1000, 1000, 20, 20, 0, "USD", 1000),
                        new AgentRunLimits(20, 0, 1, 60_000, 60_000),
                        new RunConfigurationSnapshotRef("config-1", "sha256:config")),
                NOW);
        store.insert(run);
        store.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId("message-1"),
                run.sessionId(),
                Optional.of(run.id()),
                Optional.empty(),
                MessageRole.USER,
                MessageStatus.COMPLETED,
                MessageVisibility.USER_VISIBLE,
                List.of(new TextPart(request, "plain")),
                metadata,
                NOW));
        return new Fixture(store, run);
    }

    private static FinalAnswerDecision finalDecision() {
        return new FinalAnswerDecision(
                AgentRunOutcome.SUCCESS, "done", "output", "1.0", Map.of("answer", "done"), List.of(), List.of());
    }

    private static ContextBuildRequest request(AgentRun run, AgentRunUsage usage) {
        return new ContextBuildRequest(
                run.id(),
                run.sessionId(),
                run.tenant(),
                run.principal(),
                1,
                ResolvedModelSnapshot.create(
                        new ModelProviderId("provider"),
                        "provider-v1",
                        new ModelDefinitionId("model"),
                        "model-v1",
                        "model",
                        "adapter",
                        "adapter-v1",
                        URI.create("https://provider.example.invalid"),
                        new CredentialRef("env://MODEL_KEY"),
                        Set.of(ModelCapability.TEXT_CHAT),
                        1_000,
                        100,
                        Map.of(),
                        Map.of()),
                run.budget(),
                usage,
                List.of(),
                List.of(),
                List.of(),
                100,
                10,
                "none-v1",
                "none-v1",
                0);
    }

    private record Fixture(InMemoryRuntimeStore store, AgentRun run) {}
}
