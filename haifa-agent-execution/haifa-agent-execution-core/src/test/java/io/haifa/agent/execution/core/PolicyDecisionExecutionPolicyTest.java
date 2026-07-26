package io.haifa.agent.execution.core;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.api.TrustedExecutionContext;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.ApprovalRequester;
import io.haifa.agent.policy.api.ApprovalResponder;
import io.haifa.agent.policy.api.PolicyAction;
import io.haifa.agent.policy.api.PolicyAuthorizationEvidence;
import io.haifa.agent.policy.api.PolicyAuthorizationEvidenceStore;
import io.haifa.agent.policy.api.PolicyContext;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyDecisionId;
import io.haifa.agent.policy.api.PolicyDecisionStore;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyRequestDigest;
import io.haifa.agent.policy.api.PolicyResource;
import io.haifa.agent.policy.api.PolicyRisk;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import io.haifa.agent.policy.api.PolicySideEffect;
import io.haifa.agent.policy.api.PolicySnapshot;
import io.haifa.agent.policy.api.PolicySnapshotRef;
import io.haifa.agent.policy.api.PolicySnapshotStore;
import io.haifa.agent.policy.api.PolicySubject;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicyDecisionExecutionPolicyTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
    private static final TenantRef TENANT = new TenantRef("tenant");
    private static final PrincipalRef ACTOR = new PrincipalRef("actor", "user");

    @Test
    void requiresBoundDecisionAndApprovalEvidenceForAsk() {
        var decisions = new DecisionStore();
        var snapshots = new SnapshotStore();
        var evidence = new EvidenceStore();
        ExecutionRequest request = request("decision-1", "echo ok", ".");
        PolicySnapshot snapshot = snapshot();
        snapshots.save(snapshot);
        PolicyRequest bound = policyRequest(request);
        PolicyDecision decision = new PolicyDecision(
                new PolicyDecisionId("decision-1"),
                Optional.of(bound),
                PolicyRequestDigest.compute(bound),
                PolicyEffect.ASK,
                Optional.of(io.haifa.agent.policy.api.PolicyChallenge.APPROVAL),
                "EXECUTION_APPROVAL_REQUIRED",
                "Execution requires confirmation",
                snapshot.ref(),
                Optional.empty(),
                NOW);
        decisions.save(decision);
        var policy = new PolicyDecisionExecutionPolicy(
                decisions, snapshots, evidence, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));

        assertThatThrownBy(() -> policy.authorize(request))
                .isInstanceOfSatisfying(
                        ExecutionRejectedException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo("POLICY_CHALLENGE_UNSATISFIED"));

        evidence.save(new PolicyAuthorizationEvidence(
                decision.id(),
                decision.requestDigest(),
                new ApprovalRequester(TENANT, ACTOR),
                new ApprovalResponder(TENANT, ACTOR),
                NOW,
                NOW.plusSeconds(60)));
        assertThatCode(() -> policy.authorize(request)).doesNotThrowAnyException();

        assertThatThrownBy(() -> policy.authorize(request("decision-1", "echo changed", ".")))
                .isInstanceOfSatisfying(
                        ExecutionRejectedException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo("POLICY_RESOURCE_MISMATCH"));
        assertThatThrownBy(() -> policy.authorize(request("unknown", "echo ok", ".")))
                .isInstanceOfSatisfying(
                        ExecutionRejectedException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo("POLICY_DECISION_NOT_FOUND"));
    }

    private static ExecutionRequest request(String decisionId, String command, String workdir) {
        WorkspaceId workspace = new WorkspaceId("workspace");
        return new ExecutionRequest(
                new ExecutionId("execution-" + decisionId),
                "key-" + decisionId + "-" + command.hashCode(),
                new TrustedExecutionContext(TENANT, "run", ACTOR, Set.of("execution.run"), decisionId),
                workspace,
                new WorkspacePath(
                        workspace,
                        ".".equals(workdir)
                                ? io.haifa.agent.project.path.ProjectPath.root()
                                : io.haifa.agent.project.path.ProjectPath.of(workdir)),
                ExecutionCommand.shell(command),
                ExecutionEnvironmentRef.empty(),
                new ExecutionLimits(Duration.ofSeconds(5), 1024, 1024, 1),
                new SandboxProfileRef("profile", "1"));
    }

    private static PolicyRequest policyRequest(ExecutionRequest request) {
        return new PolicyRequest(
                new PolicySubject(TENANT, ACTOR, "test-product"),
                PolicyContext.run("run", ApprovalMode.ASK),
                new PolicyAction("execution.run", "invoke"),
                new PolicyResource(
                        "tool",
                        "execution.run",
                        Optional.of(ExecutionPolicyBinding.resourceDigest(request)),
                        "Execute command"),
                new PolicyRisk(
                        PolicyRiskLevel.HIGH, Set.of(PolicySideEffect.PROCESS_EXECUTION), false, Optional.empty()));
    }

    private static PolicySnapshot snapshot() {
        return new PolicySnapshot(
                new PolicySnapshotRef("snapshot"),
                List.of(),
                Optional.empty(),
                ApprovalMode.ASK,
                "test-profile",
                Optional.empty(),
                "a".repeat(64),
                NOW);
    }

    private static final class DecisionStore implements PolicyDecisionStore {
        private final Map<PolicyDecisionId, PolicyDecision> values = new HashMap<>();

        @Override
        public void save(PolicyDecision decision) {
            values.put(decision.id(), decision);
        }

        @Override
        public Optional<PolicyDecision> find(PolicyDecisionId id) {
            return Optional.ofNullable(values.get(id));
        }
    }

    private static final class SnapshotStore implements PolicySnapshotStore {
        private final Map<PolicySnapshotRef, PolicySnapshot> values = new HashMap<>();

        @Override
        public void save(PolicySnapshot snapshot) {
            values.put(snapshot.ref(), snapshot);
        }

        @Override
        public Optional<PolicySnapshot> find(PolicySnapshotRef ref) {
            return Optional.ofNullable(values.get(ref));
        }
    }

    private static final class EvidenceStore implements PolicyAuthorizationEvidenceStore {
        private final Map<PolicyDecisionId, PolicyAuthorizationEvidence> values = new HashMap<>();

        @Override
        public void save(PolicyAuthorizationEvidence value) {
            values.put(value.decisionId(), value);
        }

        @Override
        public Optional<PolicyAuthorizationEvidence> find(PolicyDecisionId decisionId) {
            return Optional.ofNullable(values.get(decisionId));
        }
    }
}
