package io.haifa.agent.policy.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable, storeless product policy configuration. */
public record PolicyRuleSet(
        List<PolicyRule> rules, Optional<PolicyRule> defaultRule, ApprovalMode approvalMode, String contentDigest) {
    public PolicyRuleSet {
        rules = List.copyOf(Objects.requireNonNull(rules, "rules must not be null"));
        defaultRule = Objects.requireNonNull(defaultRule, "defaultRule must not be null");
        approvalMode = Objects.requireNonNull(approvalMode, "approvalMode must not be null");
        contentDigest = PolicyValues.requireIdentifier(contentDigest, "contentDigest");
        var refs = new HashSet<PolicyRuleRef>();
        for (PolicyRule rule : rules) {
            if (!refs.add(rule.ref())) throw new IllegalArgumentException("duplicate policy rule ref");
        }
        defaultRule.ifPresent(rule -> {
            if (!refs.add(rule.ref())) throw new IllegalArgumentException("default rule duplicates a policy rule ref");
        });
        if (!digest(rules, defaultRule, approvalMode).equals(contentDigest)) {
            throw new IllegalArgumentException("contentDigest does not match rules");
        }
    }

    public static PolicyRuleSet of(
            List<PolicyRule> rules, Optional<PolicyRule> defaultRule, ApprovalMode approvalMode) {
        return new PolicyRuleSet(rules, defaultRule, approvalMode, digest(rules, defaultRule, approvalMode));
    }

    private static String digest(List<PolicyRule> rules, Optional<PolicyRule> defaultRule, ApprovalMode approvalMode) {
        Objects.requireNonNull(rules, "rules must not be null");
        Objects.requireNonNull(defaultRule, "defaultRule must not be null");
        Objects.requireNonNull(approvalMode, "approvalMode must not be null");
        List<String> fields = new ArrayList<>();
        fields.add("policy-rule-set-v1");
        fields.add(approvalMode.name());
        rules.stream().map(PolicyRuleSet::canonical).sorted().forEach(value -> fields.add("rule:" + value));
        fields.add("default:" + defaultRule.map(PolicyRuleSet::canonical).orElse(""));
        return "sha256:" + PolicyDigest.sha256Fields(fields);
    }

    private static String canonical(PolicyRule rule) {
        PolicyRuleMatcher matcher = rule.matcher();
        return PolicyDigest.sha256Fields(List.of(
                rule.ref().ruleId(),
                rule.ref().version(),
                rule.source().name(),
                Integer.toString(rule.priority()),
                matcher.tenantId().orElse(""),
                matcher.productId().orElse(""),
                matcher.projectRef().orElse(""),
                matcher.sessionRef().orElse(""),
                matcher.capability().orElse(""),
                matcher.operation().orElse(""),
                matcher.resourceType().orElse(""),
                matcher.minimumRisk().map(Enum::name).orElse(""),
                matcher.requiredSideEffects().stream()
                        .map(Enum::name)
                        .sorted()
                        .reduce((a, b) -> a + "," + b)
                        .orElse(""),
                rule.effect().name(),
                rule.challenge().map(Enum::name).orElse(""),
                rule.reasonCode(),
                rule.safeExplanation()));
    }
}
