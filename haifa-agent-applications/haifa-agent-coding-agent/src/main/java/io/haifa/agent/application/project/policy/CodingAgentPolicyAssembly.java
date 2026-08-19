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
        return create(mode, CodingApprovalThreshold.compatibleWith(mode), clock, identifiers);
    }

    public static CodingAgentPolicyAssembly create(
            ApprovalMode mode, CodingApprovalThreshold threshold, Clock clock, Supplier<String> identifiers) {
        var store = new InMemoryPolicyStore();
        return create(
                mode,
                threshold,
                clock,
                identifiers,
                new PolicyPersistencePorts(store, store, new InMemoryPolicyAuthorizationEvidenceStore(), store, store));
    }

    public static CodingAgentPolicyAssembly create(
            ApprovalMode mode, Clock clock, Supplier<String> identifiers, PolicyPersistencePorts persistence) {
        return create(mode, CodingApprovalThreshold.compatibleWith(mode), clock, identifiers, persistence);
    }

    public static CodingAgentPolicyAssembly create(
            ApprovalMode mode,
            CodingApprovalThreshold threshold,
            Clock clock,
            Supplier<String> identifiers,
            PolicyPersistencePorts persistence) {
        Objects.requireNonNull(persistence, "persistence must not be null");
        Objects.requireNonNull(threshold, "threshold must not be null");
        var snapshot = snapshot(mode, threshold, clock);
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

    private static PolicySnapshot snapshot(ApprovalMode mode, CodingApprovalThreshold threshold, Clock clock) {
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
        if (mode == ApprovalMode.DENY) {
            for (PolicySideEffect effect : List.of(
                    PolicySideEffect.FILE_WRITE,
                    PolicySideEffect.PROCESS_EXECUTION,
                    PolicySideEffect.NETWORK_ACCESS,
                    PolicySideEffect.EXTERNAL_SYSTEM_MUTATION,
                    PolicySideEffect.PERMISSION_ELEVATION)) {
                rules.add(rule(
                        "coding-deny-" + effect.name().toLowerCase(java.util.Locale.ROOT),
                        sideEffectMatcher(effect),
                        PolicyEffect.DENY,
                        Optional.empty(),
                        "CODING_DISABLED_SIDE_EFFECT_DENY"));
            }
        } else {
            rules.add(rule(
                    "coding-permission-elevation",
                    sideEffectMatcher(PolicySideEffect.PERMISSION_ELEVATION),
                    PolicyEffect.ASK,
                    Optional.of(PolicyChallenge.APPROVAL),
                    "MANAGED_PERMISSION_ELEVATION_APPROVAL_REQUIRED"));
            threshold
                    .minimumRisk()
                    .ifPresent(minimumRisk -> rules.add(rule(
                            "coding-risk-threshold-" + threshold.name().toLowerCase(java.util.Locale.ROOT),
                            new PolicyRuleMatcher(
                                    Optional.empty(),
                                    Optional.of("haifa-coding-agent"),
                                    Optional.empty(),
                                    Optional.empty(),
                                    Optional.of("execution.run"),
                                    Optional.of("invoke"),
                                    Optional.empty(),
                                    Optional.of(minimumRisk),
                                    Set.of()),
                            PolicyEffect.ASK,
                            Optional.of(PolicyChallenge.APPROVAL),
                            "RISK_THRESHOLD_APPROVAL_REQUIRED")));
            if (mode == ApprovalMode.ASK) {
                for (String capability :
                        List.of("file.create", "file.write", "file.delete", "file.move", "file.patch")) {
                    rules.add(rule(
                            "coding-" + capability.replace('.', '-'),
                            capabilitySideEffectMatcher(capability, PolicySideEffect.FILE_WRITE),
                            PolicyEffect.ASK,
                            Optional.of(PolicyChallenge.APPROVAL),
                            "CODING_SIDE_EFFECT_ASK"));
                }
                for (String capability : List.of("web.search", "web.fetch")) {
                    rules.add(rule(
                            "coding-" + capability.replace('.', '-'),
                            capabilitySideEffectMatcher(capability, PolicySideEffect.NETWORK_ACCESS),
                            PolicyEffect.ASK,
                            Optional.of(PolicyChallenge.APPROVAL),
                            "CODING_SIDE_EFFECT_ASK"));
                }
            }
        }
        PolicyRule defaultRule = rule(
                "coding-default",
                PolicyRuleMatcher.any(),
                PolicyEffect.ALLOW,
                Optional.empty(),
                "CODING_RISK_BELOW_THRESHOLD_ALLOW");
        String digest = PolicyDigest.sha256Fields(List.of("haifa-coding-agent", mode.name(), threshold.name(), "v3"));
        String refSuffix = mode.name().toLowerCase(java.util.Locale.ROOT)
                + "-"
                + threshold.name().toLowerCase(java.util.Locale.ROOT)
                + "-v3";
        return new PolicySnapshot(
                new PolicySnapshotRef("coding-" + refSuffix),
                rules,
                Optional.of(defaultRule),
                mode,
                "coding-default-" + refSuffix,
                Optional.empty(),
                digest,
                Instant.ofEpochMilli(clock.millis()));
    }

    private static PolicyRuleMatcher sideEffectMatcher(PolicySideEffect effect) {
        return new PolicyRuleMatcher(
                Optional.empty(),
                Optional.of("haifa-coding-agent"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Set.of(effect));
    }

    private static PolicyRuleMatcher capabilitySideEffectMatcher(String capability, PolicySideEffect effect) {
        return new PolicyRuleMatcher(
                Optional.empty(),
                Optional.of("haifa-coding-agent"),
                Optional.empty(),
                Optional.empty(),
                Optional.of(capability),
                Optional.of("invoke"),
                Optional.empty(),
                Optional.empty(),
                Set.of(effect));
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
