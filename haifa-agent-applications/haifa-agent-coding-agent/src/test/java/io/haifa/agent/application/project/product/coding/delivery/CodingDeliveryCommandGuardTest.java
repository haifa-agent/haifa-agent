package io.haifa.agent.application.project.product.coding.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.product.coding.CodingCommandBinding;
import io.haifa.agent.application.project.product.coding.InMemoryCodingSessionStore;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.PrincipalRef;
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
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.execution.core.command.SystemGitCliCommandClassifier;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CodingDeliveryCommandGuardTest {
    private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");

    @Test
    void worktreeIntentRejectsDeliveryAndPullRequestMergeRemainsHardDenied() {
        Fixture fixture = fixture(CodingDeliveryIntent.WORKTREE_ONLY);

        assertThat(evaluate(fixture, "git commit -m message"))
                .returns(false, CodingDeliveryCommandGuard.Decision::allowed)
                .returns("DELIVERY_INTENT_EXCEEDED", CodingDeliveryCommandGuard.Decision::code);
        assertThat(SystemGitCliCommandClassifier.classify("gh pr merge 42").risk())
                .isEqualTo(SystemGitCliCommandClassifier.Risk.DENIED);
    }

    @Test
    void enforcesExactScopeAndOrderedCommitPushPullRequestEvidence() {
        Fixture fixture = fixture(CodingDeliveryIntent.PULL_REQUEST);
        evidence(fixture, "STATUS_INSPECTED");
        evidence(fixture, "REPOSITORY_ROOT_VERIFIED");
        evidence(fixture, "BRANCH_VERIFIED");
        evidence(fixture, "HEAD_VERIFIED");
        execution(fixture, Map.of("effectiveOperationFamily", "DIFF", "status", "SUCCEEDED"));
        execution(fixture, Map.of("declaredOperationFamily", "TEST", "status", "SUCCEEDED"));

        assertThat(evaluate(fixture, "git add .").code()).isEqualTo("DELIVERY_STAGE_SCOPE_REQUIRED");
        assertThat(evaluate(fixture, "git add src/*.java").code()).isEqualTo("DELIVERY_STAGE_SCOPE_REQUIRED");
        assertThat(evaluate(fixture, "git add --all src/Main.java").code()).isEqualTo("DELIVERY_STAGE_SCOPE_REQUIRED");
        assertThat(evaluate(fixture, "git add src/Main.java docs/README.md").allowed())
                .isTrue();
        evidence(fixture, "STAGE_COMPLETED");
        assertThat(evaluate(fixture, "git commit -m message").code()).isEqualTo("DELIVERY_STAGE_MISSING");
        evidence(fixture, "STAGED_DIFF_INSPECTED");
        assertThat(evaluate(fixture, "git commit -m message").allowed()).isTrue();

        evidence(fixture, "COMMIT_COMPLETED");
        assertThat(evaluate(fixture, "git push origin feat-delivery").code())
                .isEqualTo("DELIVERY_COMMIT_VERIFICATION_MISSING");
        evidence(fixture, "HEAD_VERIFIED");
        assertThat(evaluate(fixture, "git push").code()).isEqualTo("DELIVERY_PUSH_TARGET_REQUIRED");
        assertThat(evaluate(fixture, "git push origin feat-delivery").allowed()).isTrue();

        evidence(fixture, "PUSH_COMPLETED");
        assertThat(evaluate(fixture, "gh pr create --base dev --title title").code())
                .isEqualTo("DELIVERY_REMOTE_VERIFICATION_MISSING");
        evidence(fixture, "REMOTE_REF_VERIFIED");
        assertThat(evaluate(fixture, "gh pr close 42").code()).isEqualTo("DELIVERY_PR_ACTION_DENIED");
        assertThat(evaluate(fixture, "gh pr create --base main --title title").code())
                .isEqualTo("DELIVERY_PR_BASE_REQUIRED");
        assertThat(evaluate(fixture, "gh pr create --base dev --title title").allowed())
                .isTrue();
    }

    @Test
    void unknownPushOutcomeRequiresRemoteVerificationBeforeReplay() {
        Fixture fixture = fixture(CodingDeliveryIntent.REMOTE_PUSH);
        evidence(fixture, "COMMIT_COMPLETED");
        evidence(fixture, "HEAD_VERIFIED");
        execution(fixture, Map.of("deliveryAction", "PUSH", "semanticOutcome", "OUTCOME_UNKNOWN"));

        assertThat(evaluate(fixture, "git push origin feat-delivery").code())
                .isEqualTo("DELIVERY_OUTCOME_VERIFICATION_REQUIRED");
        evidence(fixture, "REMOTE_REF_VERIFIED");
        assertThat(evaluate(fixture, "git push origin feat-delivery").allowed()).isTrue();
    }

    private static CodingDeliveryCommandGuard.Decision evaluate(Fixture fixture, String command) {
        return fixture.guard().evaluate(fixture.run().id(), command, SystemGitCliCommandClassifier.classify(command));
    }

    private static void evidence(Fixture fixture, String code) {
        execution(fixture, Map.of("deliveryEvidenceCode", code, "status", "SUCCEEDED"));
    }

    private static void execution(Fixture fixture, Map<String, Object> data) {
        int sequence = fixture.store().toolCalls(fixture.run().id()).size() + 1;
        ToolCall call = new ToolCall(
                new ToolCallId("tool-" + sequence),
                fixture.run().id(),
                new AgentStepId("step-" + sequence),
                new ProviderToolCallCorrelationId("provider-" + sequence),
                new RuntimeIdempotencyKey("key-" + sequence),
                "execution.run",
                "1.5.0",
                new ToolArguments("input", "1.5.0", Map.of()),
                NOW.plusSeconds(sequence));
        call.beginValidation();
        call.beginPolicyCheck();
        call.start(NOW.plusSeconds(sequence));
        call.complete(new ToolResult(true, "done", data, List.of(), List.of(), false), NOW.plusSeconds(sequence));
        fixture.store().appendToolCall(call);
    }

    private static Fixture fixture(CodingDeliveryIntent intent) {
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
                        "deliver the change",
                        new AgentRunBudget(1000, 1000, 1000, 20, 20, 0, "USD", 1000),
                        new AgentRunLimits(20, 0, 1, 60_000, 60_000),
                        new RunConfigurationSnapshotRef("config-1", "sha256:config")),
                NOW);
        store.insert(run);
        var sessions = new InMemoryCodingSessionStore();
        sessions.reserveCommand(new CodingCommandBinding(
                "caller",
                "create-session",
                "idempotency",
                "request",
                "dispatch",
                run.sessionId(),
                new ProjectId("project-1"),
                "deliver the change",
                List.of(),
                intent,
                Optional.of(run.id()),
                NOW));
        var resolver = new CodingDeliveryIntentResolver(sessions, store);
        return new Fixture(store, run, resolver, new CodingDeliveryCommandGuard(store, resolver));
    }

    private record Fixture(
            InMemoryRuntimeStore store,
            AgentRun run,
            CodingDeliveryIntentResolver resolver,
            CodingDeliveryCommandGuard guard) {}
}
