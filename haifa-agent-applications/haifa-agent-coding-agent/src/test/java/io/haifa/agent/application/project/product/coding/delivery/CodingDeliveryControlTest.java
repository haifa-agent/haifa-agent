package io.haifa.agent.application.project.product.coding.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.product.coding.verification.CodingSessionVerificationConfiguration;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationCandidate;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationCost;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationProfile;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationProfileProvider;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationSource;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationTrigger;
import io.haifa.agent.application.project.product.coding.verification.PersistedCodingVerificationProfileProvider;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
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
import io.haifa.agent.core.run.AgentRunResult;
import io.haifa.agent.core.run.AgentRunSpec;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.SessionScope;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.runtime.core.completion.CompletionBlocker;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;
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
    void changeRequiresWorkspaceValidationAndDeterministicReviewEvidence() {
        Fixture fixture = fixture("fix the implementation", trusted("CHANGE"));
        CodingCompletionPolicy policy = policy(fixture.store());

        var initialBlockers = policy.evaluate(fixture.run(), finalDecision()).blockers();
        assertThat(initialBlockers)
                .extracting(blocker -> blocker.code())
                .containsExactlyInAnyOrder("WORKSPACE_CHANGE_MISSING", "VALIDATION_ATTEMPT_MISSING");

        tool(fixture, "file_write", Map.of("path", "src/First.java"), Map.of("path", "src/First.java"));
        assertThat(policy.evaluate(fixture.run(), finalDecision()).blockers())
                .extracting(blocker -> blocker.code())
                .containsExactly("VALIDATION_ATTEMPT_MISSING");

        tool(fixture, "file_write", Map.of("path", "src/Main.java"), Map.of("path", "src/Main.java"));
        validationTool(fixture, true, 1, 1, 0);

        var complete = policy.evaluate(fixture.run(), finalDecision());
        assertThat(complete.allowed()).isTrue();
        assertThat(complete.evidenceCodes())
                .contains("WORKSPACE_CHANGE", "VALIDATION_ATTEMPT")
                .doesNotContain("DIFF_INSPECTION");
    }

    @Test
    void trustedStructuredValidationEvidenceDoesNotDependOnTheOptionalOperationFamilyHint() {
        Fixture fixture = fixture("fix the implementation", trusted("CHANGE"));
        changeTool(fixture, "file_write", "change-1");
        CodingValidationAttemptEvidence evidence = new CodingValidationAttemptEvidence(
                CodingValidationAttemptEvidence.SCHEMA_VERSION,
                CodingValidationScope.FULL,
                "BUILD_CONFIGURATION",
                "TRUSTED_FULL_SCOPE",
                "d".repeat(64),
                "e".repeat(64));
        tool(
                fixture,
                "execution_run",
                Map.of(),
                Map.of(
                        "operationFamily",
                        "UNKNOWN",
                        "processState",
                        "EXITED",
                        "validationEvidence",
                        evidence.toStructuredData()));

        assertThat(policy(fixture.store())
                        .evaluate(fixture.run(), finalDecision())
                        .allowed())
                .isTrue();

        Fixture arbitrary = fixture("fix the implementation", trusted("CHANGE"));
        changeTool(arbitrary, "file_write", "change-1");
        tool(arbitrary, "execution_run", Map.of(), Map.of("operationFamily", "UNKNOWN", "processState", "EXITED"));
        assertThat(policy(arbitrary.store())
                        .evaluate(arbitrary.run(), finalDecision())
                        .blockers())
                .extracting(blocker -> blocker.code())
                .contains("VALIDATION_ATTEMPT_MISSING");

        Fixture failedDispatch = fixture("fix the implementation", trusted("CHANGE"));
        changeTool(failedDispatch, "file_write", "change-1");
        tool(
                failedDispatch,
                "execution_run",
                Map.of(),
                Map.of(
                        "operationFamily", "TEST",
                        "processState", "FAILED",
                        "validationEvidence", evidence.toStructuredData()));
        assertThat(policy(failedDispatch.store())
                        .evaluate(failedDispatch.run(), finalDecision())
                        .allowed())
                .isTrue();
    }

    @Test
    void declaredOperationFamilyCannotManufactureDeliveryEvidence() {
        for (String declared : List.of("DIFF", "INSPECT")) {
            Fixture fixture = fixture("analyze the repository", trusted("ANALYZE"));
            tool(
                    fixture,
                    "execution_run",
                    Map.of(),
                    Map.of("operationFamily", declared, "processState", "EXITED", "exitCode", 0));

            CodingDeliveryEvidenceLedger.Snapshot snapshot = new CodingDeliveryEvidenceLedger(fixture.store())
                    .reconstruct(fixture.run().id());
            assertThat(snapshot.kinds())
                    .as(declared)
                    .doesNotContain(
                            CodingDeliveryEvidenceKind.READ_ONLY_INSPECTION,
                            CodingDeliveryEvidenceKind.DIFF_INSPECTION);
            assertThat(policy(fixture.store())
                            .evaluate(fixture.run(), finalDecision())
                            .blockers())
                    .as(declared)
                    .extracting(CompletionBlocker::code)
                    .contains("ANALYSIS_EVIDENCE_MISSING");
        }
    }

    @Test
    void nonZeroValidationExitStillRecordsATrustedAttempt() {
        Fixture fixture = fixture("change the implementation", trusted("CHANGE"));
        changeTool(fixture, "file_write", "change-1");
        validationTool(fixture, false, 1, 1, 0);

        assertThat(policy(fixture.store())
                        .evaluate(fixture.run(), finalDecision())
                        .allowed())
                .isTrue();
    }

    @Test
    void trustedReadOnlyModeRejectsWorkspaceChanges() {
        Fixture fixture = fixture("please fix this", trusted("ANALYZE"));
        tool(fixture, "file_read", Map.of("path", "README.md"), Map.of("path", "README.md"));
        assertThat(policy(fixture.store())
                        .evaluate(fixture.run(), finalDecision())
                        .allowed())
                .isTrue();

        tool(fixture, "file_write", Map.of("path", "README.md"), Map.of("changeSetId", "change-2"));
        assertThat(policy(fixture.store())
                        .evaluate(fixture.run(), finalDecision())
                        .blockers())
                .extracting(blocker -> blocker.code())
                .contains("READ_ONLY_INTENT_HAS_CHANGES");
    }

    @Test
    void readOnlyAnalysisEvidenceComesFromInspectionToolsNotExecutionHints() {
        Fixture fixture = fixture("analyze the repository", trusted("ANALYZE"));
        tool(fixture, "execution_run", Map.of(), Map.of("processState", "EXITED"));
        assertThat(policy(fixture.store())
                        .evaluate(fixture.run(), finalDecision())
                        .blockers())
                .extracting(blocker -> blocker.code())
                .containsExactly("ANALYSIS_EVIDENCE_MISSING");

        tool(fixture, "execution_run", Map.of(), Map.of("operationFamily", "INSPECT", "processState", "EXITED"));
        assertThat(policy(fixture.store())
                        .evaluate(fixture.run(), finalDecision())
                        .blockers())
                .extracting(blocker -> blocker.code())
                .containsExactly("ANALYSIS_EVIDENCE_MISSING");

        tool(fixture, "file_read", Map.of("path", "README.md"), Map.of("path", "README.md"));
        assertThat(policy(fixture.store())
                        .evaluate(fixture.run(), finalDecision())
                        .allowed())
                .isTrue();
    }

    @Test
    void canonicalUnderscoreToolNamesProduceDeliveryEvidence() {
        Fixture fixture = fixture("fix the implementation", trusted("CHANGE"));
        tool(fixture, "file_write", Map.of(), Map.of("path", "src/Main.java"));
        tool(
                fixture,
                "execution_run",
                Map.of(),
                Map.of(
                        "operationFamily",
                        "TEST",
                        "processState",
                        "EXITED",
                        "validationEvidence",
                        validationEvidence(true).toStructuredData()));

        assertThat(policy(fixture.store())
                        .evaluate(fixture.run(), finalDecision())
                        .allowed())
                .isTrue();
    }

    @Test
    void dottedLegacyToolNamesDoNotProduceDeliveryEvidence() {
        Fixture fixture = fixture("fix the implementation", trusted("CHANGE"));
        tool(fixture, "file.write", Map.of(), Map.of("path", "src/Main.java"));
        tool(
                fixture,
                "execution.run",
                Map.of(),
                Map.of(
                        "operationFamily",
                        "TEST",
                        "processState",
                        "EXITED",
                        "validationEvidence",
                        validationEvidence(true).toStructuredData()));

        assertThat(policy(fixture.store())
                        .evaluate(fixture.run(), finalDecision())
                        .blockers())
                .extracting(CompletionBlocker::code)
                .containsExactlyInAnyOrder("WORKSPACE_CHANGE_MISSING", "VALIDATION_ATTEMPT_MISSING");
    }

    @Test
    void persistedVerificationConfigurationFailsClosedWhenSessionMetadataIsMissingOrInvalid() {
        Fixture missing = fixture("fix the implementation", trusted("CHANGE"));
        var missingProvider = new PersistedCodingVerificationProfileProvider(missing.store(), missing.store());
        assertThatThrownBy(() -> missingProvider.configurationFor(missing.run().id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Coding Session is unavailable");

        Fixture invalid = fixture("fix the implementation", trusted("CHANGE"));
        invalid.store()
                .insert(AgentSession.open(
                        invalid.run().sessionId(),
                        invalid.run().tenant(),
                        invalid.run().principal(),
                        null,
                        SessionScope.USER,
                        NOW,
                        Map.of(CodingSessionVerificationConfiguration.METADATA_KEY, Map.of("schemaVersion", "old"))));
        var invalidProvider = new PersistedCodingVerificationProfileProvider(invalid.store(), invalid.store());
        assertThatThrownBy(() -> invalidProvider.configurationFor(invalid.run().id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Coding Session verification configuration is unavailable or invalid");
    }

    @Test
    void unknownModeAcceptsConversationAndUsesObservedReadOnlyOrChangeEvidence() {
        Fixture readOnly = fixture("fix this if needed", Map.of());
        CodingCompletionPolicy readOnlyPolicy = policy(readOnly.store());
        var conversation = readOnlyPolicy.evaluate(readOnly.run(), finalDecision());
        assertThat(conversation.allowed()).isTrue();
        assertThat(conversation.evidenceCodes()).isEmpty();
        tool(readOnly, "file_search", Map.of("path", "."), Map.of("matches", List.of()));
        assertThat(readOnlyPolicy.evaluate(readOnly.run(), finalDecision()).allowed())
                .isTrue();

        Fixture changed = fixture("please take a look", Map.of());
        tool(changed, "file_write", Map.of("path", "README.md"), Map.of("changeSetId", "change-3"));
        assertThat(policy(changed.store())
                        .evaluate(changed.run(), finalDecision())
                        .blockers())
                .extracting(blocker -> blocker.code())
                .containsExactlyInAnyOrder("VALIDATION_ATTEMPT_MISSING");
    }

    @Test
    void documentChangeWithoutExplicitVerificationPromiseCompletesWithoutValidation() {
        Fixture fixture = fixture("update the docs", trusted("CHANGE"));
        CodingCompletionPolicy policy = policy(fixture.store(), environmentOnlyVerificationProfiles());
        tool(fixture, "file_write", Map.of("path", "docs/notes.md"), Map.of("path", "docs/notes.md"));

        var result = policy.evaluate(fixture.run(), finalDecision());

        assertThat(result.allowed()).isTrue();
        assertThat(result.evidenceCodes()).contains("WORKSPACE_CHANGE");
    }

    @Test
    void repositoryInstructionCandidatesAreTrustedFrozenVerificationRequirements() {
        Fixture fixture = fixture("fix the implementation", trusted("CHANGE"));
        CodingCompletionPolicy policy = policy(fixture.store(), repositoryInstructionVerificationProfiles());
        tool(fixture, "file_write", Map.of("path", "src/Main.java"), Map.of("path", "src/Main.java"));

        assertThat(policy.evaluate(fixture.run(), finalDecision()).blockers())
                .extracting(blocker -> blocker.code())
                .containsExactly("VALIDATION_ATTEMPT_MISSING");

        validationTool(fixture, true, 8, 8, 0);
        assertThat(policy.evaluate(fixture.run(), finalDecision()).allowed()).isTrue();
    }

    @Test
    void unknownModeObservedChangeWithoutVerificationPromiseNeedsOnlyMutationEvidence() {
        Fixture changed = fixture("please take a look", Map.of());
        CodingCompletionPolicy policy = policy(changed.store(), environmentOnlyVerificationProfiles());
        tool(changed, "file_write", Map.of("path", "README.md"), Map.of("path", "README.md"));

        var result = policy.evaluate(changed.run(), finalDecision());

        assertThat(result.allowed()).isTrue();
        assertThat(result.evidenceCodes()).contains("WORKSPACE_CHANGE");
    }

    @Test
    void genericExecutionCannotManufactureNoChangeEvidence() {
        Fixture fixture = fixture("fix the implementation", trusted("CHANGE"));
        tool(
                fixture,
                "execution_run",
                Map.of(),
                Map.of(
                        "operationFamily",
                        "TEST",
                        "processState",
                        "EXITED",
                        "exitCode",
                        0,
                        "validationEvidence",
                        validationEvidence(true).toStructuredData(),
                        "noChangeJustificationCode",
                        "ALREADY_SATISFIED"));
        var result = policy(fixture.store()).evaluate(fixture.run(), finalDecision());
        assertThat(result.allowed()).isFalse();
        assertThat(result.blockers()).extracting(CompletionBlocker::code).containsExactly("WORKSPACE_CHANGE_MISSING");
        assertThat(result.evidenceCodes()).containsExactly("VALIDATION_ATTEMPT");
    }

    @Test
    void retainsValidationAttemptsWithoutInterpretingTheirExitCodes() {
        Fixture fixture = fixture("fix the implementation", trusted("CHANGE"));
        changeTool(fixture, "file_write", "change-1");
        validationTool(fixture, false, 4, 4, 0);
        validationTool(fixture, true, 1, 1, 0);

        CodingDeliveryEvidenceLedger.Snapshot snapshot = new CodingDeliveryEvidenceLedger(fixture.store())
                .reconstruct(fixture.run().id());

        assertThat(snapshot.validationAttempts()).hasSize(2);
        assertThat(policy(fixture.store())
                        .evaluate(fixture.run(), finalDecision())
                        .allowed())
                .isTrue();

        validationTool(fixture, false, 1, 1, 0);
        assertThat(new CodingDeliveryEvidenceLedger(fixture.store())
                        .reconstruct(fixture.run().id())
                        .validationAttempts())
                .hasSize(3);
        assertThat(policy(fixture.store())
                        .evaluate(fixture.run(), finalDecision())
                        .allowed())
                .isTrue();
    }

    @Test
    void requiresValidationAndReviewEvidenceToCoverTheLatestWorkspaceChange() {
        Fixture staleValidation = fixture("fix the implementation", trusted("CHANGE"));
        changeTool(staleValidation, "file_write", "change-1");
        validationTool(staleValidation, true, 8, 8, 0);
        changeTool(staleValidation, "file_write", "change-2");

        assertThat(policy(staleValidation.store())
                        .evaluate(staleValidation.run(), finalDecision())
                        .blockers())
                .extracting(blocker -> blocker.code())
                .contains("VALIDATION_ATTEMPT_MISSING");

        Fixture staleReview = fixture("fix the implementation", trusted("CHANGE"));
        tool(staleReview, "file_write", Map.of("path", "src/Main.java"), Map.of("path", "src/Main.java"));
        validationTool(staleReview, true, 8, 8, 0);
        tool(staleReview, "file_write", Map.of("path", "src/Other.java"), Map.of("path", "src/Other.java"));

        assertThat(policy(staleReview.store())
                        .evaluate(staleReview.run(), finalDecision())
                        .blockers())
                .extracting(blocker -> blocker.code())
                .contains("VALIDATION_ATTEMPT_MISSING");
    }

    @Test
    void projectsVerifiedCodeResultSeparatelyFromUncleanRunProtocol() {
        Fixture fixture = fixture("fix the implementation", trusted("CHANGE"));
        changeTool(fixture, "file_write", "change-1");
        validationTool(fixture, true, 8, 8, 0);
        fixture.run()
                .fail(
                        new AgentError(
                                AgentErrorCode.COMPLETION_REPAIR_EXHAUSTED,
                                Map.of("blockerCodes", List.of("OUTPUT_CONTRACT_INVALID")),
                                "diagnostic-1",
                                NOW.plusSeconds(30)),
                        NOW.plusSeconds(30));
        fixture.store()
                .append(
                        fixture.run().id(),
                        "run.structured-termination",
                        Map.of("reason", "COMPLETION_REPAIR_EXHAUSTED", "attempts", 2),
                        NOW.plusSeconds(30));

        CodingRunOutcomeProjection outcome =
                new CodingRunOutcomeProjectionService(policy(fixture.store()), fixture.store()).project(fixture.run());

        assertThat(outcome.deliveryEvidenceStatus()).isEqualTo(CodingDeliveryEvidenceStatus.SATISFIED);
        assertThat(outcome.protocolStatus()).isEqualTo(CodingRunProtocolStatus.UNCLEAN);
        assertThat(outcome.diagnosticCodes()).contains("COMPLETION_REPAIR_EXHAUSTED", "OUTPUT_CONTRACT_INVALID");
    }

    @Test
    void projectsCompletedPartialSuccessSeparatelyFromCleanCompletion() {
        Fixture fixture = fixture("summarize the repository", trusted("ANALYZE"));
        fixture.run().start(NOW.plusSeconds(1));
        fixture.run().beginCompleting(NOW.plusSeconds(2));
        fixture.run()
                .complete(
                        new AgentRunResult(
                                AgentRunOutcome.PARTIAL_SUCCESS,
                                "budget-limited summary",
                                "haifa.agent.partial-result",
                                "1",
                                Map.of("completionReason", "BUDGET_LIMITED"),
                                List.of(),
                                List.of("BUDGET_LIMITED:TOOL_CALLS")),
                        NOW.plusSeconds(3));

        CodingRunOutcomeProjection outcome =
                new CodingRunOutcomeProjectionService(policy(fixture.store()), fixture.store()).project(fixture.run());

        assertThat(outcome.protocolStatus()).isEqualTo(CodingRunProtocolStatus.PARTIAL);
        assertThat(outcome.diagnosticCodes()).contains("BUDGET_LIMITED:TOOL_CALLS");
    }

    @Test
    void genericNonZeroCommitAndPushCannotManufactureDeliveryCompletionEvidence() {
        Fixture fixture = fixture("fix and open a pull request", trusted("CHANGE"));
        CodingCompletionPolicy policy = policy(fixture.store());
        changeTool(fixture, "file_write", "change-1");
        validationTool(fixture, true, 1, 1, 0);
        tool(
                fixture,
                "execution_run",
                Map.of(),
                Map.of(
                        "processState", "EXITED",
                        "exitCode", 1,
                        "operationFamily", "DELIVERY"));
        tool(
                fixture,
                "execution_run",
                Map.of(),
                Map.of(
                        "processState", "EXITED",
                        "exitCode", 128,
                        "operationFamily", "DELIVERY"));

        assertThat(policy.evaluate(fixture.run(), finalDecision()).allowed()).isTrue();
        assertThat(new CodingDeliveryEvidenceLedger(fixture.store())
                        .reconstruct(fixture.run().id())
                        .codes())
                .containsExactlyInAnyOrder("WORKSPACE_CHANGE", "VALIDATION_ATTEMPT");
    }

    private static Map<String, Object> trusted(String intent) {
        return Map.of("codingTaskIntentTrusted", true, "codingTaskIntent", intent);
    }

    private static CodingCompletionPolicy policy(InMemoryRuntimeStore store) {
        return policy(store, promisedVerificationProfiles());
    }

    private static CodingCompletionPolicy policy(
            InMemoryRuntimeStore store, CodingVerificationProfileProvider verificationProfiles) {
        return new CodingCompletionPolicy(
                new CodingTaskModeResolver(store), new CodingDeliveryEvidenceLedger(store), verificationProfiles);
    }

    private static CodingVerificationProfileProvider promisedVerificationProfiles() {
        return frozenVerificationProfiles(CodingVerificationSource.USER_EXPLICIT);
    }

    private static CodingVerificationProfileProvider repositoryInstructionVerificationProfiles() {
        return frozenVerificationProfiles(CodingVerificationSource.REPOSITORY_INSTRUCTIONS);
    }

    private static CodingVerificationProfileProvider environmentOnlyVerificationProfiles() {
        return frozenVerificationProfiles(CodingVerificationSource.BUILD_CONFIGURATION);
    }

    private static CodingVerificationProfileProvider frozenVerificationProfiles(CodingVerificationSource source) {
        return ignored -> CodingSessionVerificationConfiguration.freeze(
                new CodingVerificationProfile(List.of(candidate(source, source.name()))));
    }

    private static CodingVerificationCandidate candidate(CodingVerificationSource source, String reference) {
        return new CodingVerificationCandidate(
                "mvn test", CodingVerificationCost.HIGH, CodingVerificationTrigger.FINAL_GATE, source, reference);
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

    private static void changeTool(Fixture fixture, String name, String changeSetId) {
        tool(fixture, name, Map.of("path", "src/Main.java"), Map.of("changeSetId", changeSetId));
    }

    private static void validationTool(Fixture fixture, boolean passed, int discovered, int selected, int ignored) {
        CodingValidationAttemptEvidence evidence = validationEvidence(passed, discovered, selected);
        tool(
                fixture,
                "execution_run",
                Map.of(),
                Map.of(
                        "operationFamily",
                        "TEST",
                        "processState",
                        "EXITED",
                        "exitCode",
                        passed ? 0 : 1,
                        "validationEvidence",
                        evidence.toStructuredData()));
    }

    private static CodingValidationAttemptEvidence validationEvidence(boolean passed) {
        return validationEvidence(passed, 1, 1);
    }

    private static CodingValidationAttemptEvidence validationEvidence(boolean passed, int discovered, int selected) {
        return new CodingValidationAttemptEvidence(
                CodingValidationAttemptEvidence.SCHEMA_VERSION,
                selected < discovered ? CodingValidationScope.SELECTED : CodingValidationScope.FULL,
                "BUILD_CONFIGURATION",
                selected < discovered ? "TRUSTED_SELECTED_SCOPE" : "TRUSTED_FULL_SCOPE",
                "d".repeat(64),
                "e".repeat(64));
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
