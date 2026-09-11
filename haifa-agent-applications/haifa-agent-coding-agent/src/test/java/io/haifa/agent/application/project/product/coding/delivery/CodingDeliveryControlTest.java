package io.haifa.agent.application.project.product.coding.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.product.coding.CodingCommandBinding;
import io.haifa.agent.application.project.product.coding.InMemoryCodingSessionStore;
import io.haifa.agent.application.project.product.coding.verification.CodingSessionVerificationConfiguration;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationCandidate;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationCost;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationProfile;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationProfileProvider;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationSource;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationTrigger;
import io.haifa.agent.common.time.TimeProvider;
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
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;
import io.haifa.agent.runtime.core.middleware.RuntimeMiddlewareContext;
import io.haifa.agent.runtime.core.middleware.RuntimePhase;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.time.Duration;
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

        tool(fixture, "file.write", Map.of("path", "src/First.java"), Map.of("path", "src/First.java"));
        assertThat(policy.evaluate(fixture.run(), finalDecision()).blockers())
                .extracting(blocker -> blocker.code())
                .containsExactly("VALIDATION_ATTEMPT_MISSING");

        tool(fixture, "file.write", Map.of("path", "src/Main.java"), Map.of("path", "src/Main.java"));
        tool(
                fixture,
                "execution.run",
                Map.of(),
                Map.of("operationFamily", "TEST", "status", "SUCCEEDED", "exitCode", 0));

        var complete = policy.evaluate(fixture.run(), finalDecision());
        assertThat(complete.allowed()).isTrue();
        assertThat(complete.evidenceCodes())
                .contains("WORKSPACE_CHANGE", "VALIDATION_ATTEMPT", "VALIDATION_PASSED")
                .doesNotContain("DIFF_INSPECTION");
    }

    @Test
    void trustedStructuredValidationEvidenceDoesNotDependOnTheOptionalOperationFamilyHint() {
        Fixture fixture = fixture("fix the implementation", trusted("CHANGE"));
        changeTool(fixture, "file.write", "change-1");
        CodingValidationAttemptEvidence evidence = new CodingValidationAttemptEvidence(
                CodingValidationAttemptEvidence.SCHEMA_VERSION,
                CodingValidationStatus.PASSED,
                null,
                null,
                null,
                CodingValidationScope.FULL,
                "COUNTS_UNAVAILABLE",
                "BUILD_CONFIGURATION",
                "TRUSTED_FULL_SCOPE",
                "d".repeat(64),
                "e".repeat(64));
        tool(
                fixture,
                "execution.run",
                Map.of(),
                Map.of(
                        "declaredOperationFamily",
                        "UNKNOWN",
                        "status",
                        "SUCCEEDED",
                        "validationEvidence",
                        evidence.toStructuredData()));

        assertThat(policy(fixture.store())
                        .evaluate(fixture.run(), finalDecision())
                        .allowed())
                .isTrue();

        Fixture arbitrary = fixture("fix the implementation", trusted("CHANGE"));
        changeTool(arbitrary, "file.write", "change-1");
        tool(arbitrary, "execution.run", Map.of(), Map.of("declaredOperationFamily", "UNKNOWN", "status", "SUCCEEDED"));
        assertThat(policy(arbitrary.store())
                        .evaluate(arbitrary.run(), finalDecision())
                        .blockers())
                .extracting(blocker -> blocker.code())
                .contains("VALIDATION_ATTEMPT_MISSING");
    }

    @Test
    void trustedDiffClassificationRemainsDiagnosticWithoutCompletingChangeReview() {
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
                        .allowed())
                .isTrue();

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
        assertThat(new CodingDeliveryEvidenceLedger(fixture.store())
                        .reconstruct(fixture.run().id())
                        .kinds())
                .contains(CodingDeliveryEvidenceKind.DIFF_INSPECTION)
                .doesNotContain(CodingDeliveryEvidenceKind.DETERMINISTIC_CHANGE_REVIEW);
    }

    @Test
    void recognizesZeroExitCodeExitedExecutionAsDiffInspectionEvidenceAndReference() {
        Fixture fixture = fixture("fix the implementation", trusted("CHANGE"));
        changeTool(fixture, "file.write", "change-1");
        tool(
                fixture,
                "execution.run",
                Map.of(),
                Map.of("operationFamily", "TEST", "status", "EXITED", "exitCode", 0, "semanticOutcome", "SUCCEEDED"));
        tool(
                fixture,
                "execution.run",
                Map.of(),
                Map.of(
                        "operationFamily", "DIFF",
                        "status", "EXITED",
                        "exitCode", 0,
                        "semanticOutcome", "SUCCEEDED",
                        "commandTarget", "GIT",
                        "commandRisk", "LOCAL_READ",
                        "commandOperation", "DIFF",
                        "commandClassificationReason", "GIT_DIFF"));

        CodingDeliveryEvidenceLedger.Snapshot snapshot = new CodingDeliveryEvidenceLedger(fixture.store())
                .reconstruct(fixture.run().id());
        assertThat(snapshot.kinds()).contains(CodingDeliveryEvidenceKind.DIFF_INSPECTION);
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
                .contains("VALIDATION_NOT_PASSED");
    }

    @Test
    void diffInspectionDoesNotReplaceDeterministicChangeReview() {
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
                                new CodingDeliveryProfile(true),
                                null,
                                promisedVerificationProfiles())
                        .evaluate(fixture.run(), finalDecision())
                        .allowed())
                .isTrue();
    }

    @Test
    void validationFailureCanCompleteOnlyWithConfirmedBlockerAndDeterministicReview() {
        Fixture fixture = fixture("change the implementation", trusted("CHANGE"));
        changeTool(fixture, "file.write", "change-1");
        tool(
                fixture,
                "execution.run",
                Map.of(),
                Map.of("operationFamily", "TEST", "status", "FAILED", "exitCode", 1, "failureCategory", "ENVIRONMENT"));

        assertThat(new CodingCompletionPolicy(
                                new CodingTaskModeResolver(fixture.store()),
                                new CodingDeliveryEvidenceLedger(fixture.store()),
                                new CodingDeliveryProfile(true),
                                null,
                                promisedVerificationProfiles())
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
                .containsExactlyInAnyOrder("VALIDATION_ATTEMPT_MISSING");
    }

    @Test
    void documentChangeWithoutExplicitVerificationPromiseCompletesWithoutValidation() {
        Fixture fixture = fixture("update the docs", trusted("CHANGE"));
        CodingCompletionPolicy policy = policy(fixture.store(), environmentOnlyVerificationProfiles());
        tool(fixture, "file.write", Map.of("path", "docs/notes.md"), Map.of("path", "docs/notes.md"));

        var result = policy.evaluate(fixture.run(), finalDecision());

        assertThat(result.allowed()).isTrue();
        assertThat(result.evidenceCodes()).contains("WORKSPACE_CHANGE");
    }

    @Test
    void repositoryInstructionCandidatesAreTrustedFrozenVerificationRequirements() {
        Fixture fixture = fixture("fix the implementation", trusted("CHANGE"));
        CodingCompletionPolicy policy = policy(fixture.store(), repositoryInstructionVerificationProfiles());
        tool(fixture, "file.write", Map.of("path", "src/Main.java"), Map.of("path", "src/Main.java"));

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
        tool(changed, "file.write", Map.of("path", "README.md"), Map.of("path", "README.md"));

        var result = policy.evaluate(changed.run(), finalDecision());

        assertThat(result.allowed()).isTrue();
        assertThat(result.evidenceCodes()).contains("WORKSPACE_CHANGE");
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
        var result = policy(fixture.store()).evaluate(fixture.run(), finalDecision());
        assertThat(result.allowed()).isTrue();
        assertThat(result.evidenceCodes())
                .contains("NO_CHANGE_JUSTIFICATION", "VALIDATION_PASSED")
                .doesNotContain("DIFF_INSPECTION", "DETERMINISTIC_CHANGE_REVIEW");
    }

    @Test
    void retainsFailedAndPassingValidationAttemptsAndUsesTheLatestOutcome() {
        Fixture fixture = fixture("fix the implementation", trusted("CHANGE"));
        changeTool(fixture, "file.write", "change-1");
        validationTool(fixture, false, 4, 4, 0);
        validationTool(fixture, true, 1, 1, 0);

        CodingDeliveryEvidenceLedger.Snapshot snapshot = new CodingDeliveryEvidenceLedger(fixture.store())
                .reconstruct(fixture.run().id());

        assertThat(snapshot.validationAttempts())
                .extracting(CodingValidationAttemptEvidence::status)
                .containsExactly(CodingValidationStatus.FAILED, CodingValidationStatus.PASSED);
        assertThat(snapshot.latestValidationPassed()).isTrue();
        assertThat(policy(fixture.store())
                        .evaluate(fixture.run(), finalDecision())
                        .allowed())
                .isTrue();

        validationTool(fixture, false, 1, 1, 0);
        assertThat(policy(fixture.store())
                        .evaluate(fixture.run(), finalDecision())
                        .blockers())
                .extracting(blocker -> blocker.code())
                .contains("VALIDATION_NOT_PASSED");
    }

    @Test
    void requiresValidationAndReviewEvidenceToCoverTheLatestWorkspaceChange() {
        Fixture staleValidation = fixture("fix the implementation", trusted("CHANGE"));
        changeTool(staleValidation, "file.write", "change-1");
        validationTool(staleValidation, true, 8, 8, 0);
        changeTool(staleValidation, "file.write", "change-2");

        assertThat(policy(staleValidation.store())
                        .evaluate(staleValidation.run(), finalDecision())
                        .blockers())
                .extracting(blocker -> blocker.code())
                .contains("VALIDATION_ATTEMPT_MISSING");

        Fixture staleReview = fixture("fix the implementation", trusted("CHANGE"));
        tool(staleReview, "file.write", Map.of("path", "src/Main.java"), Map.of("path", "src/Main.java"));
        validationTool(staleReview, true, 8, 8, 0);
        tool(staleReview, "file.write", Map.of("path", "src/Other.java"), Map.of("path", "src/Other.java"));

        assertThat(policy(staleReview.store())
                        .evaluate(staleReview.run(), finalDecision())
                        .blockers())
                .extracting(blocker -> blocker.code())
                .contains("VALIDATION_ATTEMPT_MISSING");
    }

    @Test
    void projectsVerifiedCodeResultSeparatelyFromUncleanRunProtocol() {
        Fixture fixture = fixture("fix the implementation", trusted("CHANGE"));
        changeTool(fixture, "file.write", "change-1");
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

        TimeProvider time = () -> NOW.plusSeconds(31);
        new CodingRunOutcomeProjectionMiddleware(
                        new CodingRunOutcomeProjectionService(policy(fixture.store()), fixture.store()),
                        fixture.store(),
                        time)
                .apply(new RuntimeMiddlewareContext(fixture.run(), fixture.store()));
        assertThat(fixture.store().eventsFor(fixture.run().id()))
                .filteredOn(event -> event.type().equals("coding.task-outcome"))
                .singleElement()
                .satisfies(event -> assertThat(event.data())
                        .containsEntry("schemaVersion", "coding-run-outcome/2")
                        .containsEntry("deliveryEvidenceStatus", "SATISFIED")
                        .containsEntry("protocolStatus", "UNCLEAN")
                        .doesNotContainKeys("codeResult", "requiresCodeReexecution"));
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
        CodingRunOutcomeProjectionMiddleware middleware = new CodingRunOutcomeProjectionMiddleware(
                new CodingRunOutcomeProjectionService(policy(fixture.store()), fixture.store()),
                fixture.store(),
                () -> NOW.plusSeconds(4));
        assertThat(middleware.phase()).isEqualTo(RuntimePhase.AFTER_COMPLETION);
        middleware.apply(new RuntimeMiddlewareContext(fixture.run(), fixture.store()));
        assertThat(fixture.store().eventsFor(fixture.run().id()))
                .filteredOn(event -> event.type().equals("coding.task-outcome"))
                .singleElement()
                .satisfies(event -> assertThat(event.data()).containsEntry("protocolStatus", "PARTIAL"))
                .satisfies(event -> assertThat(String.valueOf(event.data().get("diagnosticCodes")))
                        .contains("BUDGET_LIMITED:TOOL_CALLS"));
    }

    @Test
    void pullRequestIntentRequiresOrderedAuthoritativeDeliveryEvidence() {
        Fixture fixture = fixture("fix and open a pull request", trusted("CHANGE"));
        CodingDeliveryIntentResolver intents = deliveryResolver(fixture, CodingDeliveryIntent.PULL_REQUEST);
        CodingCompletionPolicy policy = new CodingCompletionPolicy(
                new CodingTaskModeResolver(fixture.store()),
                new CodingDeliveryEvidenceLedger(fixture.store()),
                CodingDeliveryProfile.safeDefault(),
                intents,
                promisedVerificationProfiles());
        changeTool(fixture, "file.write", "change-1");
        tool(fixture, "execution.run", Map.of(), Map.of("operationFamily", "TEST", "status", "SUCCEEDED"));
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
                new CodingTaskModeResolver(store),
                new CodingDeliveryEvidenceLedger(store),
                CodingDeliveryProfile.safeDefault(),
                null,
                verificationProfiles);
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
                new CodingVerificationProfile(List.of(candidate(source, source.name())), List.of()));
    }

    private static CodingVerificationCandidate candidate(CodingVerificationSource source, String reference) {
        return new CodingVerificationCandidate(
                "mvn test",
                CodingVerificationCost.HIGH,
                Duration.ofMinutes(10),
                CodingVerificationTrigger.FINAL_GATE,
                source,
                reference);
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
        tool(
                fixture,
                name,
                Map.of("path", "src/Main.java"),
                Map.of("changeSetId", changeSetId, "changeReviewArtifact", deterministicReview(changeSetId)));
    }

    private static void validationTool(Fixture fixture, boolean passed, int discovered, int selected, int ignored) {
        CodingValidationAttemptEvidence evidence = new CodingValidationAttemptEvidence(
                CodingValidationAttemptEvidence.SCHEMA_VERSION,
                passed ? CodingValidationStatus.PASSED : CodingValidationStatus.FAILED,
                null,
                null,
                null,
                selected < discovered ? CodingValidationScope.SELECTED : CodingValidationScope.FULL,
                "COUNTS_UNAVAILABLE",
                "BUILD_CONFIGURATION",
                selected < discovered ? "TRUSTED_SELECTED_SCOPE" : "TRUSTED_FULL_SCOPE",
                "d".repeat(64),
                "e".repeat(64));
        tool(
                fixture,
                "execution.run",
                Map.of(),
                Map.of(
                        "operationFamily",
                        "TEST",
                        "status",
                        passed ? "SUCCEEDED" : "FAILED",
                        "validationEvidence",
                        evidence.toStructuredData()));
    }

    private static Map<String, Object> deterministicReview(String changeSetId) {
        var summary = new CodingChangeReviewArtifact.FileSummary(
                io.haifa.agent.project.changeset.FileChangeType.REPLACE,
                "src/Main.java",
                "",
                "sha256:" + "d".repeat(64),
                "sha256:" + "e".repeat(64),
                10L,
                12L,
                CodingChangeContentKind.TEXT);
        return CodingChangeReviewArtifact.create(
                        List.of(changeSetId),
                        "sha256:" + "b".repeat(64),
                        "sha256:" + "c".repeat(64),
                        List.of(summary),
                        1,
                        false,
                        Map.of(
                                "created", 0,
                                "replaced", 1,
                                "deleted", 0,
                                "moved", 0,
                                "binary", 0,
                                "oversize", 0,
                                "opaque", 0),
                        true)
                .toStructuredData();
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
