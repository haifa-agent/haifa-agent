package io.haifa.agent.application.project.policy;

import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.ApprovalTargetStatus;
import io.haifa.agent.policy.api.ApprovalTargetValidation;
import io.haifa.agent.policy.api.ApprovalVerificationService;
import io.haifa.agent.policy.api.PolicyChallenge;
import io.haifa.agent.policy.api.PolicyDecisionService;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import io.haifa.agent.policy.api.PolicyRule;
import io.haifa.agent.policy.api.PolicyRuleMatcher;
import io.haifa.agent.policy.api.PolicyRuleRef;
import io.haifa.agent.policy.api.PolicyRuleSet;
import io.haifa.agent.policy.api.PolicyRuleSource;
import io.haifa.agent.policy.api.PolicySideEffect;
import io.haifa.agent.policy.core.DefaultApprovalVerificationService;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.policy.core.LocalCapabilityAuthorityVerifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Storeless Coding Agent policy assembly. Product rules are immutable and share only the evaluator mechanism. */
public final class CodingAgentPolicyAssembly {
    private final PolicyDecisionService evaluator;
    private final PolicyRuleSet rules;
    private final ApprovalVerificationService approvalVerification;

    private CodingAgentPolicyAssembly(
            PolicyDecisionService evaluator, PolicyRuleSet rules, ApprovalVerificationService approvalVerification) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator must not be null");
        this.rules = Objects.requireNonNull(rules, "rules must not be null");
        this.approvalVerification =
                Objects.requireNonNull(approvalVerification, "approvalVerification must not be null");
    }

    public static CodingAgentPolicyAssembly create(ApprovalMode mode, CodingApprovalThreshold threshold) {
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(threshold, "threshold must not be null");
        ApprovalVerificationService verification = new DefaultApprovalVerificationService(
                new LocalCapabilityAuthorityVerifier(),
                Map.of(
                        "tool",
                        target -> new ApprovalTargetValidation(
                                ApprovalTargetStatus.CURRENT, "TOOL_TARGET_STRUCTURALLY_CURRENT")));
        return new CodingAgentPolicyAssembly(new DefaultPolicyDecisionService(), rules(mode, threshold), verification);
    }

    private static PolicyRuleSet rules(ApprovalMode mode, CodingApprovalThreshold threshold) {
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
                                    Optional.of("execution_run"),
                                    Optional.of("invoke"),
                                    Optional.empty(),
                                    Optional.of(minimumRisk),
                                    Set.of()),
                            PolicyEffect.ASK,
                            Optional.of(PolicyChallenge.APPROVAL),
                            "RISK_THRESHOLD_APPROVAL_REQUIRED")));
            if (mode == ApprovalMode.ASK) {
                for (String capability :
                        List.of("file_create", "file_write", "file_delete", "file_move", "file_patch")) {
                    rules.add(rule(
                            "coding-" + capability.replace('.', '-'),
                            capabilitySideEffectMatcher(capability, PolicySideEffect.FILE_WRITE),
                            PolicyEffect.ASK,
                            Optional.of(PolicyChallenge.APPROVAL),
                            "CODING_SIDE_EFFECT_ASK"));
                }
                for (String capability : List.of("web_search", "web_fetch")) {
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
        return PolicyRuleSet.of(rules, Optional.of(defaultRule), mode);
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

    public PolicyDecisionService evaluator() {
        return evaluator;
    }

    public PolicyRuleSet rules() {
        return rules;
    }

    public ApprovalVerificationService approvalVerification() {
        return approvalVerification;
    }
}
