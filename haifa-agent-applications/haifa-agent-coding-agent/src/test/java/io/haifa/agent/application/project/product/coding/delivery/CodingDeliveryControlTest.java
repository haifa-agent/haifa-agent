package io.haifa.agent.application.project.product.coding.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.product.coding.verification.CodingVerificationContextSource;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationDimension;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationEvidenceLedger;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationPlanResolver;
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
    void resolverFreezesTrustedIntentAndRestoresTheSameDigest() {
        Fixture fixture =
                fixture("任何语言都不应覆盖可信调用方意图", Map.of("codingTaskIntentTrusted", true, "codingTaskIntent", "CHANGE"));
        CodingTaskContractResolver resolver = new CodingTaskContractResolver(fixture.store());

        CodingTaskContract first = resolver.resolve(fixture.run());
        CodingTaskContract restored = resolver.resolve(fixture.run());

        assertThat(first.intent()).isEqualTo(CodingTaskIntent.CHANGE);
        assertThat(first.intentSource()).isEqualTo(CodingTaskIntentSource.TRUSTED_CALLER);
        assertThat(first.confidencePercent()).isEqualTo(100);
        assertThat(restored).isEqualTo(first);
        assertThat(first.contractDigest()).startsWith("sha256:");
    }

    @Test
    void resolverRejectsInvalidTrustedIntentInsteadOfAcceptingModelShapedMetadata() {
        Fixture fixture = fixture(
                "analyze the repository",
                Map.of("codingTaskIntentTrusted", true, "codingTaskIntent", "NOT_A_SUPPORTED_INTENT"));

        assertThatThrownBy(() -> new CodingTaskContractResolver(fixture.store()).resolve(fixture.run()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trusted coding task intent is invalid");
    }

    @Test
    void resolverHandlesReadOnlyAndReviewShapesWithoutTreatingUnknownAsChange() {
        assertThat(contract("analyze why this branch fails?").intent()).isEqualTo(CodingTaskIntent.ANALYZE);
        assertThat(contract("分析 根因并说明证据").intent()).isEqualTo(CodingTaskIntent.ANALYZE);
        assertThat(contract("分析根因并说明证据").intent()).isEqualTo(CodingTaskIntent.ANALYZE);
        assertThat(contract("review the completion policy").intent()).isEqualTo(CodingTaskIntent.REVIEW);
        assertThat(contract("请看一下这个情况").intent()).isEqualTo(CodingTaskIntent.UNKNOWN);
        assertThat(contract("please investigate and maybe change it").intent()).isEqualTo(CodingTaskIntent.UNKNOWN);
    }

    @Test
    void resolverFreezesBoundedWorkspaceRelativeAcceptanceCriteriaReferences() {
        CodingTaskContract contract =
                contract("fix the end-to-end behavior required by PERF.md and docs/CONCURRENCY.md; "
                        + "ignore /tmp/SECRET.md, ../outside.md, https://example.test/remote.md");

        assertThat(contract.acceptanceCriteriaRefs()).containsExactlyInAnyOrder("PERF.md", "docs/CONCURRENCY.md");
    }

    @Test
    void changeRequiresWorkspaceValidationAndDiffEvidence() {
        Fixture fixture = fixture("fix the implementation", Map.of());
        CodingCompletionPolicy policy = policy(fixture.store());

        var empty = policy.evaluate(fixture.run(), finalDecision());
        assertThat(empty.allowed()).isFalse();
        assertThat(empty.blockers())
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
    void validationFailureIsNotPassedAndReadOnlyIntentRejectsUnexpectedChange() {
        Fixture change = fixture("change the implementation", Map.of());
        tool(change, "file.write", Map.of("path", "README.md"), Map.of("changeSetId", "change-1"));
        tool(
                change,
                "execution.run",
                Map.of(),
                Map.of("operationFamily", "TEST", "status", "FAILED", "exitCode", 1, "failureCategory", "ENVIRONMENT"));
        tool(change, "execution.run", Map.of(), Map.of("operationFamily", "DIFF", "status", "SUCCEEDED"));
        assertThat(policy(change.store())
                        .evaluate(change.run(), finalDecision())
                        .blockers())
                .extracting(blocker -> blocker.code())
                .contains("VALIDATION_NOT_PASSED");
        assertThat(new CodingCompletionPolicy(
                                new CodingTaskContractResolver(change.store()),
                                new CodingDeliveryEvidenceLedger(change.store()),
                                new CodingDeliveryProfile(20, 25, 20, true))
                        .evaluate(change.run(), finalDecision())
                        .allowed())
                .isTrue();

        Fixture analyze = fixture("analyze why this happens?", Map.of());
        tool(analyze, "file.read", Map.of("path", "README.md"), Map.of("path", "README.md"));
        assertThat(policy(analyze.store())
                        .evaluate(analyze.run(), finalDecision())
                        .allowed())
                .isTrue();
        tool(analyze, "file.write", Map.of("path", "README.md"), Map.of("changeSetId", "change-2"));
        assertThat(policy(analyze.store())
                        .evaluate(analyze.run(), finalDecision())
                        .blockers())
                .extracting(blocker -> blocker.code())
                .contains("READ_ONLY_INTENT_HAS_CHANGES");
    }

    @Test
    void unknownNeedsAuthoritativeReadOnlyOrChangeDeliveryEvidence() {
        Fixture fixture = fixture("请看一下这个情况", Map.of());
        CodingCompletionPolicy policy = policy(fixture.store());
        assertThat(policy.evaluate(fixture.run(), finalDecision()).allowed()).isFalse();
        tool(fixture, "file.search", Map.of("path", "."), Map.of("matches", List.of()));
        assertThat(policy.evaluate(fixture.run(), finalDecision()).allowed()).isTrue();
    }

    @Test
    void deliveryReserveIncludesWallTimeWithoutIncreasingRunBudget() {
        Fixture fixture = fixture("fix the implementation", Map.of());
        AgentRunUsage usage = new AgentRunUsage(0, 0, 0, 0, 0, 0, 0, 48_000);
        CodingDeliveryContextSource source = new CodingDeliveryContextSource(
                fixture.store(),
                new CodingTaskContractResolver(fixture.store()),
                new CodingDeliveryEvidenceLedger(fixture.store()),
                CodingDeliveryProfile.safeDefault());

        String text = ((TextContextContent)
                        source.load(request(fixture.run(), usage)).getFirst().content())
                .text();

        assertThat(text)
                .contains("remainingModelCalls=20")
                .contains("remainingToolCalls=20")
                .contains("remainingWallTimeSeconds=12")
                .contains("deliveryReserve=ACTIVE");
        assertThat(fixture.run().budget().maxModelCalls()).isEqualTo(20);
        assertThat(fixture.run().budget().maxToolCalls()).isEqualTo(20);
        assertThat(fixture.run().limits().maxWallTimeMillis()).isEqualTo(60_000);
    }

    @Test
    void deliveryContextMakesReferencedAcceptanceDocumentsAnExplicitPreEditAction() {
        Fixture fixture = fixture("fix behavior according to PERF.md and docs/CONCURRENCY.md", Map.of());
        CodingDeliveryContextSource source = new CodingDeliveryContextSource(
                fixture.store(),
                new CodingTaskContractResolver(fixture.store()),
                new CodingDeliveryEvidenceLedger(fixture.store()),
                CodingDeliveryProfile.safeDefault());

        String text = ((TextContextContent)
                        source.load(request(fixture.run(), new AgentRunUsage(0, 0, 0, 0, 0, 0, 0, 0)))
                                .getFirst()
                                .content())
                .text();

        assertThat(text)
                .contains("acceptanceCriteriaRefs=PERF.md|docs/CONCURRENCY.md")
                .contains("before editing, read every referenced criteria file")
                .contains("map every stated constraint")
                .contains("each affected entry point and end-to-end boundary")
                .contains("implement all mapped constraints before validation")
                .contains("malformed, boundary, and operational-failure paths")
                .contains("keep their meanings and metrics disjoint")
                .contains("inspect every changed classification and counter branch")
                .contains("run one mixed scenario containing each outcome")
                .contains("ignored only as a duplicate is not rejected or invalid");
    }

    @Test
    void evidenceBackedNoChangeCanCompleteButFreeTextCannotManufactureIt() {
        Fixture fixture = fixture("fix the implementation", Map.of());
        CodingCompletionPolicy policy = policy(fixture.store());

        assertThat(policy.evaluate(fixture.run(), finalDecision()).allowed()).isFalse();
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

        assertThat(policy.evaluate(fixture.run(), finalDecision()).allowed()).isTrue();
        assertThat(new CodingDeliveryEvidenceLedger(fixture.store())
                        .reconstruct(fixture.run().id())
                        .codes())
                .contains("NO_CHANGE_JUSTIFICATION", "VALIDATION_PASSED", "DIFF_INSPECTION");
    }

    @Test
    void verificationPlanIsBoundedFrozenAndMapsGenericRiskFacts() {
        Fixture fixture = fixture("fix the concurrent database migration and preserve API compatibility", Map.of());
        var contracts = new CodingTaskContractResolver(fixture.store());
        var plans = new CodingVerificationPlanResolver(fixture.store(), contracts);

        var first = plans.resolve(fixture.run());
        var restored = plans.resolve(fixture.run());

        assertThat(restored).isEqualTo(first);
        assertThat(first.digest()).startsWith("sha256:");
        assertThat(first.dimensions())
                .contains(
                        CodingVerificationDimension.SUCCESS_PATH,
                        CodingVerificationDimension.BOUNDARY,
                        CodingVerificationDimension.FAILURE_PATH,
                        CodingVerificationDimension.FAILURE_ATOMICITY,
                        CodingVerificationDimension.RESOURCE_CLEANUP,
                        CodingVerificationDimension.COMPATIBILITY,
                        CodingVerificationDimension.IDEMPOTENCY,
                        CodingVerificationDimension.CONCURRENCY)
                .hasSizeLessThanOrEqualTo(9);
    }

    @Test
    void completionRequiresEveryPlanDimensionFromMatchingSuccessfulExecution() {
        Fixture fixture = fixture("fix the implementation", Map.of());
        var contracts = new CodingTaskContractResolver(fixture.store());
        var plans = new CodingVerificationPlanResolver(fixture.store(), contracts);
        var verification = new CodingVerificationEvidenceLedger(fixture.store());
        var policy = new CodingCompletionPolicy(
                contracts,
                new CodingDeliveryEvidenceLedger(fixture.store()),
                CodingDeliveryProfile.safeDefault(),
                plans,
                verification);
        var plan = plans.resolve(fixture.run());
        tool(fixture, "file.write", Map.of("path", "src/Main.java"), Map.of("changeSetId", "change-1"));
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
                        "verificationPlanDigest",
                        plan.digest(),
                        "verificationDimensions",
                        plan.dimensions().stream().map(Enum::name).toList()));
        tool(
                fixture,
                "execution.run",
                Map.of(),
                Map.of("operationFamily", "DIFF", "status", "SUCCEEDED", "exitCode", 0));

        assertThat(policy.evaluate(fixture.run(), finalDecision()).allowed()).isTrue();
        assertThat(verification.reconstruct(fixture.run().id(), plan).passedDimensions())
                .containsExactlyInAnyOrderElementsOf(plan.dimensions());
    }

    @Test
    void failedOrMismatchedVerificationCannotPassAndContextContainsNoExecutableCheck() {
        Fixture fixture = fixture("fix the implementation", Map.of());
        var contracts = new CodingTaskContractResolver(fixture.store());
        var plans = new CodingVerificationPlanResolver(fixture.store(), contracts);
        var verification = new CodingVerificationEvidenceLedger(fixture.store());
        var plan = plans.resolve(fixture.run());
        tool(
                fixture,
                "execution.run",
                Map.of(),
                Map.of(
                        "operationFamily",
                        "TEST",
                        "status",
                        "FAILED",
                        "exitCode",
                        1,
                        "verificationPlanDigest",
                        plan.digest(),
                        "verificationDimensions",
                        List.of("SUCCESS_PATH")));
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
                        "verificationPlanDigest",
                        "sha256:" + "0".repeat(64),
                        "verificationDimensions",
                        List.of("BOUNDARY")));

        assertThat(verification.reconstruct(fixture.run().id(), plan).passedDimensions())
                .isEmpty();
        var contextItem = new CodingVerificationContextSource(fixture.store(), plans, verification)
                .load(request(fixture.run(), new AgentRunUsage(0, 0, 0, 0, 0, 0, 0, 0)))
                .getFirst();
        String text = ((TextContextContent) contextItem.content()).text();
        assertThat(text)
                .contains("planDigest=" + plan.digest())
                .contains("requiredDimensions=")
                .doesNotContain("mvn", "bash", "python", "/Users/");
        assertThat(contextItem.security().providerDisclosureAllowed()).isTrue();
    }

    private static CodingTaskContract contract(String request) {
        Fixture fixture = fixture(request, Map.of());
        return new CodingTaskContractResolver(fixture.store()).resolve(fixture.run());
    }

    private static CodingCompletionPolicy policy(InMemoryRuntimeStore store) {
        return new CodingCompletionPolicy(
                new CodingTaskContractResolver(store),
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
