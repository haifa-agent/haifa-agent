package io.haifa.agent.application.project.product.coding.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.product.coding.CodingCommandBinding;
import io.haifa.agent.application.project.product.coding.InMemoryCodingSessionStore;
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
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;
import io.haifa.agent.runtime.core.middleware.RuntimeMiddlewareContext;
import io.haifa.agent.runtime.core.middleware.RuntimePhase;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

        var initialBlockers = policy.evaluate(fixture.run(), finalDecision()).blockers();
        assertThat(initialBlockers)
                .extracting(blocker -> blocker.code())
                .containsExactlyInAnyOrder(
                        "WORKSPACE_CHANGE_MISSING", "VALIDATION_ATTEMPT_MISSING", "DIFF_INSPECTION_MISSING");
        assertThat(initialBlockers)
                .filteredOn(blocker -> blocker.code().equals("DIFF_INSPECTION_MISSING"))
                .singleElement()
                .satisfies(blocker -> assertThat(blocker.safeMessage())
                        .contains("execution.run", "operationFamily=DIFF", "git diff"));

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
    void newExecutionEvidenceRequiresTrustedDiffOperationClassification() {
        Fixture fixture = fixture("fix the implementation", trusted("CHANGE"));
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
                Map.of(
                        "operationFamily",
                        "DIFF",
                        "status",
                        "SUCCEEDED",
                        "commandTarget",
                        "GIT",
                        "commandRisk",
                        "LOCAL_READ",
                        "commandOperation",
                        "INSPECT",
                        "commandClassificationReason",
                        "GIT_STATUS"));

        assertThat(policy(fixture.store())
                        .evaluate(fixture.run(), finalDecision())
                        .blockers())
                .extracting(blocker -> blocker.code())
                .contains("DIFF_INSPECTION_MISSING");

        tool(
                fixture,
                "execution.run",
                Map.of(),
                Map.of(
                        "operationFamily",
                        "DIFF",
                        "status",
                        "SUCCEEDED",
                        "commandTarget",
                        "GIT",
                        "commandRisk",
                        "LOCAL_READ",
                        "commandOperation",
                        "DIFF",
                        "commandClassificationReason",
                        "GIT_DIFF"));

        assertThat(policy(fixture.store())
                        .evaluate(fixture.run(), finalDecision())
                        .allowed())
                .isTrue();
    }

    @Test
    void genericCommandsRetainDeclaredDeliveryIntentWithoutOverridingGitClassification() {
        Fixture fixture = fixture("fix the implementation", trusted("CHANGE"));
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
                Map.ofEntries(
                        Map.entry("declaredOperationFamily", "DIFF"),
                        Map.entry("effectiveOperationFamily", "UNKNOWN"),
                        Map.entry("status", "SUCCEEDED"),
                        Map.entry("commandTarget", "OTHER"),
                        Map.entry("commandRisk", "UNKNOWN"),
                        Map.entry("commandOperation", "UNKNOWN")));

        assertThat(policy(fixture.store())
                        .evaluate(fixture.run(), finalDecision())
                        .allowed())
                .isTrue();
    }

    @Test
    void expectedDiffVariantCountsAsDiffInspectionWithoutPassingValidation() {
        Fixture fixture = fixture("fix the implementation", trusted("CHANGE"));
        tool(fixture, "file.write", Map.of("path", "src/Main.java"), Map.of("changeSetId", "change-1"));
        tool(
                fixture,
                "execution.run",
                Map.of(),
                Map.of(
                        "operationFamily",
                        "TEST",
                        "status",
                        "FAILED",
                        "semanticOutcome",
                        "COMMAND_FAILED",
                        "exitCode",
                        1));
        tool(
                fixture,
                "execution.run",
                Map.of(),
                Map.ofEntries(
                        Map.entry("declaredOperationFamily", "DIFF"),
                        Map.entry("effectiveOperationFamily", "DIFF"),
                        Map.entry("status", "FAILED"),
                        Map.entry("semanticOutcome", "EXPECTED_VARIANT"),
                        Map.entry("semanticReasonCode", "DIFFERENCES_FOUND"),
                        Map.entry("commandTarget", "GIT"),
                        Map.entry("commandRisk", "LOCAL_READ"),
                        Map.entry("commandOperation", "DIFF"),
                        Map.entry("exitCode", 1)));

        assertThat(policy(fixture.store())
                        .evaluate(fixture.run(), finalDecision())
                        .blockers())
                .extracting(blocker -> blocker.code())
                .contains("VALIDATION_NOT_PASSED")
                .doesNotContain("DIFF_INSPECTION_MISSING");
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
    void unknownModeAcceptsConversationAndUsesObservedReadOnlyOrChangeEvidence() {
        Fixture readOnly = fixture("fix this if needed", Map.of());
        CodingCompletionPolicy readOnlyPolicy = policy(readOnly.store());
        var conversation = readOnlyPolicy.evaluate(readOnly.run(), finalDecision());
        assertThat(conversation.allowed()).isTrue();
        assertThat(conversation.evidenceCodes()).isEmpty();
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

    @Test
    void pullRequestIntentRequiresOrderedAuthoritativeDeliveryEvidence() {
        Fixture fixture = fixture("fix and open a pull request", trusted("CHANGE"));
        CodingDeliveryIntentResolver intents = deliveryResolver(fixture, CodingDeliveryIntent.PULL_REQUEST);
        CodingCompletionPolicy policy = new CodingCompletionPolicy(
                new CodingTaskModeResolver(fixture.store()),
                new CodingDeliveryEvidenceLedger(fixture.store()),
                CodingDeliveryProfile.safeDefault(),
                intents);
        tool(fixture, "file.write", Map.of(), Map.of("changeSetId", "change-1"));
        tool(fixture, "execution.run", Map.of(), Map.of("operationFamily", "TEST", "status", "SUCCEEDED"));
        tool(fixture, "execution.run", Map.of(), Map.of("operationFamily", "DIFF", "status", "SUCCEEDED"));
        deliveryEvidence(fixture, "STAGE_COMPLETED");
        deliveryEvidence(fixture, "HEAD_VERIFIED");
        deliveryEvidence(fixture, "STAGED_DIFF_INSPECTED");
        deliveryEvidence(fixture, "COMMIT_COMPLETED");

        assertThat(policy.evaluate(fixture.run(), finalDecision()).blockers())
                .extracting(blocker -> blocker.code())
                .contains("HEAD_VERIFIED_MISSING");

        deliveryEvidence(fixture, "HEAD_VERIFIED");
        deliveryEvidence(fixture, "PUSH_COMPLETED");
        deliveryEvidence(fixture, "REMOTE_REF_VERIFIED");
        deliveryEvidence(fixture, "PULL_REQUEST_COMPLETED");
        deliveryEvidence(fixture, "PULL_REQUEST_VERIFIED");

        assertThat(policy.evaluate(fixture.run(), finalDecision()).allowed()).isTrue();
        assertThat(new CodingWorkProjectionService(
                                fixture.store(),
                                new CodingTaskModeResolver(fixture.store()),
                                new CodingDeliveryEvidenceLedger(fixture.store()),
                                CodingDeliveryProfile.safeDefault(),
                                () -> NOW.plusSeconds(20),
                                intents)
                        .project(fixture.run()))
                .satisfies(projection -> {
                    assertThat(projection.deliveryIntent()).isEqualTo("PULL_REQUEST");
                    assertThat(projection.missingEvidence()).isEmpty();
                    assertThat(projection.phase()).isEqualTo(CodingWorkPhase.DELIVER);
                });
    }

    @Test
    void workProjectionAdvancesFromAuthoritativeEvidenceAndKeepsOnlySafeBoundedRefs() {
        Fixture fixture = fixture("fix the implementation", trusted("CHANGE"));
        CodingWorkProjectionService projections = projection(fixture.store());

        assertThat(projections.project(fixture.run()).phase()).isEqualTo(CodingWorkPhase.ORIENT);

        tool(
                fixture,
                "file.read",
                Map.of("path", "private/source/Main.java"),
                Map.of("path", "private/source/Main.java", "contentVersion", "version-1"));
        CodingWorkProjection changed = projections.project(fixture.run());
        assertThat(changed.phase()).isEqualTo(CodingWorkPhase.CHANGE);
        assertThat(changed.readFileRefs()).singleElement().satisfies(value -> assertThat(value)
                .matches("read:[0-9a-f]{64}"));
        assertThat(changed.contextText()).doesNotContain("private/source/Main.java", "version-1");

        tool(fixture, "file.write", Map.of("path", "src/Main.java"), Map.of("changeSetId", "change-1"));
        assertThat(projections.project(fixture.run()).phase()).isEqualTo(CodingWorkPhase.VERIFY);

        tool(
                fixture,
                "execution.run",
                Map.of("command", "test-command"),
                Map.of("operationFamily", "TEST", "status", "SUCCEEDED", "exitCode", 0));
        assertThat(projections.project(fixture.run()).phase()).isEqualTo(CodingWorkPhase.REVIEW);

        tool(
                fixture,
                "execution.run",
                Map.of("command", "diff-command"),
                Map.of("operationFamily", "DIFF", "status", "SUCCEEDED", "exitCode", 0));
        CodingWorkProjection delivered = projections.project(fixture.run());
        assertThat(delivered.phase()).isEqualTo(CodingWorkPhase.DELIVER);
        assertThat(delivered.missingEvidence()).isEmpty();
        assertThat(delivered.deliveryIntent()).isEqualTo("WORKTREE_ONLY");
        assertThat(delivered.contextText()).doesNotContain("test-command", "diff-command", "change-1");
    }

    @Test
    void workProjectionDoesNotForceReadOnlyTasksIntoChangeAndReportsStructuredBlockers() {
        Fixture review = fixture("review only", trusted("REVIEW"));
        tool(review, "file.read", Map.of("path", "README.md"), Map.of("path", "README.md"));
        assertThat(projection(review.store()).project(review.run()).phase()).isEqualTo(CodingWorkPhase.REVIEW);

        Fixture blocked = fixture("fix the implementation", trusted("CHANGE"));
        tool(blocked, "file.write", Map.of("path", "src/Main.java"), Map.of("changeSetId", "change-1"));
        tool(
                blocked,
                "execution.run",
                Map.of("command", "test-secret-path"),
                Map.of(
                        "operationFamily",
                        "TEST",
                        "status",
                        "FAILED",
                        "failureCategory",
                        "DEPENDENCY_UNAVAILABLE",
                        "stableFailureCode",
                        "TOOLCHAIN_UNAVAILABLE",
                        "resourceClass",
                        "TOOLCHAIN"));
        CodingWorkProjection projection = projection(blocked.store()).project(blocked.run());
        assertThat(projection.phase()).isEqualTo(CodingWorkPhase.BLOCKED);
        assertThat(projection.failureClusterSummaries()).containsExactly("TOOLCHAIN_UNAVAILABLE:TOOLCHAIN:1");
        assertThat(projection.contextText()).doesNotContain("test-secret-path");
    }

    @Test
    void workProjectionMiddlewareAppendsSafeControlMessagesOnlyForMaterialPhaseChanges() {
        Fixture eventFixture = fixture("fix the implementation", trusted("CHANGE"));
        CodingWorkProjectionService projections = projection(eventFixture.store());
        RuntimeMiddlewareContext eventContext = new RuntimeMiddlewareContext(eventFixture.run(), eventFixture.store());
        var beforeRun = CodingWorkProjectionMiddleware.events(
                projections, RuntimePhase.BEFORE_RUN, eventFixture.store(), () -> NOW.plusSeconds(20));
        beforeRun.apply(eventContext);
        beforeRun.apply(eventContext);
        assertThat(eventFixture.store().eventsFor(eventFixture.run().id()))
                .filteredOn(event -> event.type().equals("coding.work-phase"))
                .singleElement()
                .satisfies(event -> assertThat(event.data()).containsEntry("phase", "ORIENT"));
        assertThat(eventFixture.store().messages(eventFixture.run().id()))
                .filteredOn(message -> Boolean.TRUE.equals(message.metadata().get("codingWorkProjection")))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.role()).isEqualTo(MessageRole.RUNTIME);
                    assertThat(message.visibility()).isEqualTo(MessageVisibility.AGENT_VISIBLE);
                    assertThat(message.contents().toString())
                            .contains("phase=ORIENT")
                            .doesNotContain("fix the implementation");
                });

        tool(eventFixture, "file.write", Map.of("path", "src/Main.java"), Map.of("changeSetId", "change-1"));
        CodingWorkProjectionMiddleware.events(
                        projections,
                        RuntimePhase.AFTER_DECISION_EXECUTION,
                        eventFixture.store(),
                        () -> NOW.plusSeconds(30))
                .apply(eventContext);

        assertThat(eventFixture.store().eventsFor(eventFixture.run().id()))
                .filteredOn(event -> event.type().equals("coding.work-phase"))
                .extracting(event -> event.data().get("phase"))
                .containsExactly("ORIENT", "VERIFY");
        assertThat(eventFixture.store().messages(eventFixture.run().id()))
                .filteredOn(message -> Boolean.TRUE.equals(message.metadata().get("codingWorkProjection")))
                .extracting(message -> message.metadata().get("phase"))
                .containsExactly("ORIENT", "VERIFY");
    }

    @Test
    void workProjectionKeepsTheMostRecentBoundedReferencesDeterministically() {
        Fixture fixture = fixture("review the repository", trusted("REVIEW"));
        for (int index = 1; index <= 17; index++) {
            tool(
                    fixture,
                    "file.read",
                    Map.of("path", "src/File" + index + ".java"),
                    Map.of("path", "src/File" + index + ".java", "contentVersion", "version-" + index));
        }

        CodingWorkProjection projection = projection(fixture.store()).project(fixture.run());

        assertThat(projection.readFileRefs())
                .hasSize(CodingWorkProjection.MAXIMUM_REFERENCES_PER_KIND)
                .doesNotContain("read:" + PolicyDigest.sha256Fields(List.of("src/File1.java", "version-1")))
                .contains("read:" + PolicyDigest.sha256Fields(List.of("src/File17.java", "version-17")));
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

    private static CodingWorkProjectionService projection(InMemoryRuntimeStore store) {
        var taskModes = new CodingTaskModeResolver(store);
        var evidence = new CodingDeliveryEvidenceLedger(store);
        return new CodingWorkProjectionService(
                store, taskModes, evidence, CodingDeliveryProfile.safeDefault(), () -> NOW.plusSeconds(10));
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

    private static void deliveryEvidence(Fixture fixture, String code) {
        tool(fixture, "execution.run", Map.of(), Map.of("status", "SUCCEEDED", "deliveryEvidenceCode", code));
    }

    private static CodingDeliveryIntentResolver deliveryResolver(Fixture fixture, CodingDeliveryIntent intent) {
        var sessions = new InMemoryCodingSessionStore();
        sessions.reserveCommand(new CodingCommandBinding(
                "caller",
                "create-session",
                "idempotency",
                "request",
                "dispatch",
                fixture.run().sessionId(),
                new ProjectId("project-1"),
                "deliver",
                List.of(),
                intent,
                Optional.of(fixture.run().id()),
                NOW));
        return new CodingDeliveryIntentResolver(sessions, fixture.store());
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

    private record Fixture(InMemoryRuntimeStore store, AgentRun run) {}
}
