package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.policy.api.ApprovalGrant;
import io.haifa.agent.policy.api.ApprovalGrantId;
import io.haifa.agent.policy.api.ApprovalGrantState;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.ApprovalRequestContext;
import io.haifa.agent.policy.api.ApprovalRequester;
import io.haifa.agent.policy.api.ApprovalResponder;
import io.haifa.agent.policy.api.ApprovalReuseScope;
import io.haifa.agent.policy.api.ApprovalSemantics;
import io.haifa.agent.policy.api.ApprovalTargetRef;
import io.haifa.agent.policy.api.PolicyAction;
import io.haifa.agent.policy.api.PolicyChallenge;
import io.haifa.agent.policy.api.PolicyContext;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyDecisionId;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyRequestDigest;
import io.haifa.agent.policy.api.PolicyResource;
import io.haifa.agent.policy.api.PolicyRisk;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import io.haifa.agent.policy.api.PolicyRule;
import io.haifa.agent.policy.api.PolicyRuleMatcher;
import io.haifa.agent.policy.api.PolicyRuleRef;
import io.haifa.agent.policy.api.PolicyRuleSource;
import io.haifa.agent.policy.api.PolicySnapshot;
import io.haifa.agent.policy.api.PolicySnapshotRef;
import io.haifa.agent.policy.api.PolicySubject;
import io.haifa.agent.policy.api.ProjectTrust;
import io.haifa.agent.policy.api.ProjectTrustRef;
import io.haifa.agent.policy.api.ProjectTrustState;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseType;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import io.haifa.agent.runtime.core.interaction.InteractionRequest;
import io.haifa.agent.runtime.core.interaction.ToolApprovalTarget;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlitePolicyPersistenceTest {
    private static final Instant NOW = SqliteTestSupport.NOW;
    private static final TenantRef TENANT = new TenantRef("tenant");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("principal", "user");
    private static final ApprovalTargetRef TARGET =
            new ApprovalTargetRef("tool-call", "call", "1", "write", "sha256:args", "Write file");

    @Test
    void policyApprovalGrantAndTrustSurviveRestart(@TempDir Path directory) {
        SqliteStoreFoundation first = SqliteTestSupport.foundation(directory);
        var run = SqliteAggregateTestData.prepareRun(first);
        PolicySnapshot snapshot = snapshot();
        PolicyRequest policyRequest = request(run.id().value());
        PolicyDecision decision = decision(snapshot.ref(), policyRequest);
        first.policySnapshots().save(snapshot);
        first.policyDecisions().save(decision);
        ProjectTrust trust = trust();
        first.projectTrusts().save(trust);

        InteractionRequest approval = approvalRequest(run.id(), decision.id());
        first.interactions().create(approval);
        InteractionResponse response = response(run.id(), approval.id());
        first.interactions().respond(response, new RuntimeCallerContext(TENANT, PRINCIPAL), NOW.plusSeconds(2));
        ApprovalGrant grant = grant(decision.id(), approval.id(), response.responseId(), trust.ref());
        first.approvalGrants().save(grant);

        SqliteStoreFoundation reopened = SqliteTestSupport.foundation(directory);
        assertThat(reopened.policySnapshots().find(snapshot.ref())).contains(snapshot);
        assertThat(reopened.policyDecisions().find(decision.id())).contains(decision);
        assertThat(reopened.interactions().find(approval.id()))
                .get()
                .extracting(InteractionRequest::approvalContext)
                .isEqualTo(approval.approvalContext());
        assertThat(reopened.projectTrusts().find(trust.ref())).contains(trust);
        assertThat(reopened.approvalGrants().find(grant.id())).contains(grant);
    }

    @Test
    void policySnapshotTimestampRoundTripsAcrossSqliteMillisecondColumns(@TempDir Path directory) {
        PolicySnapshot snapshot = snapshot(NOW.plusNanos(456_789));
        SqliteTestSupport.foundation(directory).policySnapshots().save(snapshot);

        assertThat(SqliteTestSupport.foundation(directory).policySnapshots().find(snapshot.ref()))
                .get()
                .extracting(PolicySnapshot::createdAt)
                .isEqualTo(NOW);
    }

    @Test
    void onceGrantHasOneDatabaseWinnerAcrossConnections(@TempDir Path directory) throws Exception {
        SqliteStoreFoundation setup = SqliteTestSupport.foundation(directory);
        ApprovalGrant grant = prepareGrantGraph(setup);
        SqliteStoreFoundation left = SqliteTestSupport.foundation(directory);
        SqliteStoreFoundation right = SqliteTestSupport.foundation(directory);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> consume(left, grant, start));
            var second = executor.submit(() -> consume(right, grant, start));
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(setup.approvalGrants().find(grant.id()))
                .get()
                .extracting(ApprovalGrant::state)
                .isEqualTo(ApprovalGrantState.CONSUMED);
    }

    private static boolean consume(SqliteStoreFoundation foundation, ApprovalGrant grant, CountDownLatch start)
            throws InterruptedException {
        start.await();
        try {
            foundation.approvalGrants().consumeOnce(grant.id(), grant.version(), NOW.plusSeconds(3));
            return true;
        } catch (IllegalStateException conflict) {
            return false;
        }
    }

    private static ApprovalGrant prepareGrantGraph(SqliteStoreFoundation foundation) {
        var run = SqliteAggregateTestData.prepareRun(foundation);
        PolicySnapshot snapshot = snapshot();
        PolicyRequest request = request(run.id().value());
        PolicyDecision decision = decision(snapshot.ref(), request);
        foundation.policySnapshots().save(snapshot);
        foundation.policyDecisions().save(decision);
        ProjectTrust trust = trust();
        foundation.projectTrusts().save(trust);
        InteractionRequest approval = approvalRequest(run.id(), decision.id());
        foundation.interactions().create(approval);
        InteractionResponse response = response(run.id(), approval.id());
        foundation.interactions().respond(response, new RuntimeCallerContext(TENANT, PRINCIPAL), NOW.plusSeconds(2));
        ApprovalGrant grant = grant(decision.id(), approval.id(), response.responseId(), trust.ref());
        foundation.approvalGrants().save(grant);
        return grant;
    }

    private static PolicySnapshot snapshot() {
        return snapshot(NOW);
    }

    private static PolicySnapshot snapshot(Instant createdAt) {
        PolicyRule defaultRule = new PolicyRule(
                new PolicyRuleRef("default", "1"),
                PolicyRuleSource.SYSTEM,
                0,
                PolicyRuleMatcher.any(),
                PolicyEffect.ASK,
                Optional.of(PolicyChallenge.APPROVAL),
                "APPROVAL_REQUIRED",
                "Approval required");
        return new PolicySnapshot(
                new PolicySnapshotRef("snapshot"),
                List.of(),
                Optional.of(defaultRule),
                ApprovalMode.ASK,
                "coding",
                Optional.empty(),
                "sha256:snapshot",
                createdAt);
    }

    private static PolicyRequest request(String runRef) {
        return new PolicyRequest(
                new PolicySubject(TENANT, PRINCIPAL, "coding"),
                new PolicyContext(
                        Optional.of("project"),
                        Optional.of("session"),
                        Optional.of(runRef),
                        Optional.of("attempt"),
                        ApprovalMode.ASK,
                        Optional.of(new ProjectTrustRef("trust")),
                        Optional.of("sha256:config")),
                new PolicyAction("workspace.file", "write"),
                new PolicyResource("file", "workspace:file", Optional.of("sha256:file"), "Write file"),
                new PolicyRisk(PolicyRiskLevel.HIGH, Set.of(), false, Optional.empty()));
    }

    private static PolicyDecision decision(PolicySnapshotRef snapshot, PolicyRequest request) {
        return new PolicyDecision(
                new PolicyDecisionId("decision"),
                Optional.of(request),
                PolicyRequestDigest.compute(request),
                PolicyEffect.ASK,
                Optional.of(PolicyChallenge.APPROVAL),
                "APPROVAL_REQUIRED",
                "Approval required",
                snapshot,
                Optional.empty(),
                NOW);
    }

    private static ProjectTrust trust() {
        return new ProjectTrust(
                new ProjectTrustRef("trust"),
                TENANT,
                PRINCIPAL,
                "project",
                "project-id",
                "root-id",
                "sha256:config",
                "coding",
                ProjectTrustState.TRUSTED,
                NOW,
                Optional.of(NOW.plusSeconds(60)),
                Optional.empty(),
                0);
    }

    private static InteractionRequest approvalRequest(
            io.haifa.agent.core.run.AgentRunId runId, PolicyDecisionId decisionId) {
        return new InteractionRequest(
                new InteractionRequestId("approval-request"),
                runId,
                TENANT,
                PRINCIPAL,
                "tool-approval",
                "Approve tool?",
                true,
                new ToolApprovalTarget(
                        new ToolCallId("call"),
                        "builtin/file.write@1",
                        "sha256:definition",
                        "sha256:args",
                        "tenant:principal"),
                NOW,
                NOW.plusSeconds(60),
                Optional.of(new ApprovalRequestContext(
                        decisionId,
                        ApprovalSemantics.CAPABILITY_CONFIRMATION,
                        Set.of(ApprovalReuseScope.ONCE),
                        new ApprovalRequester(TENANT, PRINCIPAL),
                        TARGET,
                        Optional.empty(),
                        NOW,
                        NOW.plusSeconds(60),
                        Optional.empty())));
    }

    private static InteractionResponse response(
            io.haifa.agent.core.run.AgentRunId runId, InteractionRequestId requestId) {
        return new InteractionResponse(
                new InteractionResponseId("approval-response"),
                requestId,
                runId,
                InteractionResponseType.APPROVE,
                List.of(new TextPart("approved", "plain")),
                "approval-response-key",
                NOW.plusSeconds(1));
    }

    private static ApprovalGrant grant(
            PolicyDecisionId decisionId,
            InteractionRequestId requestId,
            InteractionResponseId responseId,
            ProjectTrustRef trustRef) {
        return new ApprovalGrant(
                new ApprovalGrantId("grant"),
                ApprovalSemantics.CAPABILITY_CONFIRMATION,
                ApprovalReuseScope.ONCE,
                new PolicySubject(TENANT, PRINCIPAL, "coding"),
                new PolicyAction("workspace.file", "write"),
                TARGET,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                decisionId,
                requestId.value(),
                responseId.value(),
                new ApprovalResponder(TENANT, PRINCIPAL),
                NOW.plusSeconds(2),
                Optional.of(NOW.plusSeconds(60)),
                ApprovalGrantState.ACTIVE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0);
    }
}
