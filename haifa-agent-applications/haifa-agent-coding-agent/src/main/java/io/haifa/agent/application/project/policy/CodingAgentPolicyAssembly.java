package io.haifa.agent.application.project.policy;

import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.ApprovalTargetStatus;
import io.haifa.agent.policy.api.ApprovalTargetValidation;
import io.haifa.agent.policy.api.ApprovalVerificationService;
import io.haifa.agent.policy.api.PolicyAuthorizationEvidenceStore;
import io.haifa.agent.policy.api.PolicyChallenge;
import io.haifa.agent.policy.api.PolicyDecisionId;
import io.haifa.agent.policy.api.PolicyDecisionService;
import io.haifa.agent.policy.api.PolicyDecisionStore;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyPersistencePorts;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import io.haifa.agent.policy.api.PolicyRule;
import io.haifa.agent.policy.api.PolicyRuleMatcher;
import io.haifa.agent.policy.api.PolicyRuleRef;
import io.haifa.agent.policy.api.PolicyRuleSource;
import io.haifa.agent.policy.api.PolicySideEffect;
import io.haifa.agent.policy.api.PolicySnapshot;
import io.haifa.agent.policy.api.PolicySnapshotRef;
import io.haifa.agent.policy.api.PolicySnapshotStore;
import io.haifa.agent.policy.core.DefaultApprovalVerificationService;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.policy.core.InMemoryPolicyAuthorizationEvidenceStore;
import io.haifa.agent.policy.core.InMemoryPolicyStore;
import io.haifa.agent.policy.core.LocalCapabilityAuthorityVerifier;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Product-owned local assembly. It contains no organization or workflow model. */
public final class CodingAgentPolicyAssembly {
    private final PolicySnapshotStore snapshots;
    private final PolicyDecisionStore decisionsStore;
    private final PolicyAuthorizationEvidenceStore evidence;
    private final PolicyDecisionService decisions;
    private final PolicySnapshot snapshot;
    private final ApprovalVerificationService approvalVerification;

    private CodingAgentPolicyAssembly(
            PolicySnapshotStore snapshots,
            PolicyDecisionStore decisionsStore,
            PolicyAuthorizationEvidenceStore evidence,
            PolicyDecisionService decisions,
            PolicySnapshot snapshot,
            ApprovalVerificationService approvalVerification) {
        this.snapshots = snapshots;
        this.decisionsStore = decisionsStore;
        this.evidence = evidence;
        this.decisions = decisions;
        this.snapshot = snapshot;
        this.approvalVerification = approvalVerification;
    }

    public static CodingAgentPolicyAssembly create(ApprovalMode mode, Clock clock, Supplier<String> identifiers) {
        var store = new InMemoryPolicyStore();
        return create(
                mode,
                clock,
                identifiers,
                new PolicyPersistencePorts(store, store, new InMemoryPolicyAuthorizationEvidenceStore(), store, store));
    }

    public static CodingAgentPolicyAssembly create(
            ApprovalMode mode, Clock clock, Supplier<String> identifiers, PolicyPersistencePorts persistence) {
        Objects.requireNonNull(persistence, "persistence must not be null");
        var snapshot = snapshot(mode, clock);
        PolicySnapshot effectiveSnapshot =
                persistence.snapshots().find(snapshot.ref()).orElse(null);
        if (effectiveSnapshot == null) {
            persistence.snapshots().save(snapshot);
            effectiveSnapshot = snapshot;
        } else if (effectiveSnapshot.approvalMode() != snapshot.approvalMode()
                || !effectiveSnapshot.productProfileRef().equals(snapshot.productProfileRef())
                || !effectiveSnapshot.contentDigest().equals(snapshot.contentDigest())) {
            throw new IllegalStateException("persisted coding policy snapshot has incompatible content");
        }
        PolicyDecisionService decisions =
                new DefaultPolicyDecisionService(clock, () -> new PolicyDecisionId(identifiers.get()));
        ApprovalVerificationService verification = new DefaultApprovalVerificationService(
                new LocalCapabilityAuthorityVerifier(),
                Map.of(),
                Map.of(
                        "tool",
                        target -> new ApprovalTargetValidation(
                                ApprovalTargetStatus.CURRENT, "TOOL_TARGET_STRUCTURALLY_CURRENT")));
        return new CodingAgentPolicyAssembly(
                persistence.snapshots(),
                persistence.decisions(),
                persistence.authorizationEvidence(),
                decisions,
                effectiveSnapshot,
                verification);
    }

    private static PolicySnapshot snapshot(ApprovalMode mode, Clock clock) {
        List<PolicyRule> rules = new ArrayList<>();
        rules.add(rule(
                "coding-critical-risk",
                new PolicyRuleMatcher(
                        Optional.empty(),
                        Optional.of("haifa-coding-agent"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(PolicyRiskLevel.CRITICAL),
                        Set.of()),
                PolicyEffect.DENY,
                Optional.empty(),
                "CODING_CRITICAL_RISK_DENY"));
        PolicyEffect sideEffect =
                switch (mode) {
                    case ASK -> PolicyEffect.ASK;
                    case AUTO -> PolicyEffect.ALLOW;
                    case DENY -> PolicyEffect.DENY;
                };
        Optional<PolicyChallenge> sideEffectChallenge =
                sideEffect == PolicyEffect.ASK ? Optional.of(PolicyChallenge.APPROVAL) : Optional.empty();
        for (PolicySideEffect effect : List.of(
                PolicySideEffect.FILE_WRITE,
                PolicySideEffect.PROCESS_EXECUTION,
                PolicySideEffect.NETWORK_ACCESS,
                PolicySideEffect.EXTERNAL_SYSTEM_MUTATION)) {
            rules.add(rule(
                    "coding-" + effect.name().toLowerCase(java.util.Locale.ROOT),
                    new PolicyRuleMatcher(
                            Optional.empty(),
                            Optional.of("haifa-coding-agent"),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Set.of(effect)),
                    sideEffect,
                    sideEffectChallenge,
                    "CODING_SIDE_EFFECT_" + sideEffect.name()));
        }
        PolicyEffect highRisk = sideEffect;
        rules.add(rule(
                "coding-high-risk",
                new PolicyRuleMatcher(
                        Optional.empty(),
                        Optional.of("haifa-coding-agent"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(PolicyRiskLevel.HIGH),
                        Set.of()),
                highRisk,
                sideEffectChallenge,
                "CODING_HIGH_RISK_" + highRisk.name()));
        rules.add(rule(
                "coding-credential",
                new PolicyRuleMatcher(
                        Optional.empty(),
                        Optional.of("haifa-coding-agent"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Set.of(PolicySideEffect.CREDENTIAL_USE)),
                mode == ApprovalMode.DENY ? PolicyEffect.DENY : PolicyEffect.ASK,
                mode == ApprovalMode.DENY ? Optional.empty() : Optional.of(PolicyChallenge.REAUTHENTICATE),
                "CODING_CREDENTIAL_" + mode.name()));
        PolicyRule defaultRule = rule(
                "coding-default",
                PolicyRuleMatcher.any(),
                PolicyEffect.ALLOW,
                Optional.empty(),
                "CODING_LOW_RISK_ALLOW");
        String digest = PolicyDigest.sha256Fields(List.of("haifa-coding-agent", mode.name(), "v2"));
        return new PolicySnapshot(
                new PolicySnapshotRef("coding-" + mode.name().toLowerCase(java.util.Locale.ROOT) + "-v2"),
                rules,
                Optional.of(defaultRule),
                mode,
                "coding-default-v2",
                Optional.empty(),
                digest,
                Instant.ofEpochMilli(clock.millis()));
    }

    private static PolicyRule rule(
            String id,
            PolicyRuleMatcher matcher,
            PolicyEffect effect,
            Optional<PolicyChallenge> challenge,
            String reason) {
        return new PolicyRule(
                new PolicyRuleRef(id, "1"),
                PolicyRuleSource.MANAGED,
                100,
                matcher,
                effect,
                challenge,
                reason,
                "Coding Agent product policy");
    }

    public PolicySnapshotStore snapshots() {
        return snapshots;
    }

    public PolicyDecisionStore decisionsStore() {
        return decisionsStore;
    }

    public PolicyAuthorizationEvidenceStore evidence() {
        return evidence;
    }

    public PolicyDecisionService decisions() {
        return decisions;
    }

    public PolicySnapshot snapshot() {
        return snapshot;
    }

    public ApprovalVerificationService approvalVerification() {
        return approvalVerification;
    }
}
