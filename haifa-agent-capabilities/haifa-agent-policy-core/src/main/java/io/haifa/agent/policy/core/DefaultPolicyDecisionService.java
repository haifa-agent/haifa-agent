package io.haifa.agent.policy.core;

import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyDecisionService;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyRequirementDigest;
import io.haifa.agent.policy.api.PolicyRule;
import io.haifa.agent.policy.api.PolicyRuleMatcher;
import io.haifa.agent.policy.api.PolicyRuleSet;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/** Pure deterministic policy evaluator shared by products. */
public final class DefaultPolicyDecisionService implements PolicyDecisionService {
    private static final Comparator<PolicyRule> DECISION_ORDER = Comparator.comparingInt(
                    (PolicyRule rule) -> effectRank(rule.effect()))
            .reversed()
            .thenComparing(Comparator.comparingInt(PolicyRule::priority).reversed())
            .thenComparing(rule -> rule.ref().ruleId())
            .thenComparing(rule -> rule.ref().version());

    @Override
    public PolicyDecision evaluate(PolicyRequest request, PolicyRuleSet rules) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(rules, "rules must not be null");
        String digest = PolicyRequirementDigest.compute(request, rules);
        PolicyRule selected = rules.rules().stream()
                .filter(rule -> matches(rule, request))
                .min(DECISION_ORDER)
                .or(() -> rules.defaultRule().filter(rule -> matches(rule, request)))
                .orElse(null);
        if (selected == null) {
            return new PolicyDecision(
                    PolicyEffect.DENY,
                    Optional.empty(),
                    "POLICY_NO_MATCH",
                    "No explicit policy rule allows this action",
                    digest);
        }
        return new PolicyDecision(
                selected.effect(), selected.challenge(), selected.reasonCode(), selected.safeExplanation(), digest);
    }

    private static boolean matches(PolicyRule rule, PolicyRequest request) {
        PolicyRuleMatcher matcher = rule.matcher();
        return matches(matcher.tenantId(), request.subject().tenant().tenantId())
                && matches(matcher.productId(), request.subject().productId())
                && matches(matcher.projectRef(), request.context().projectRef())
                && matches(matcher.sessionRef(), request.context().sessionRef())
                && matches(matcher.capability(), request.action().capability())
                && matches(matcher.operation(), request.action().operation())
                && matches(matcher.resourceType(), request.resource().resourceType())
                && matcher.minimumRisk()
                        .map(minimum -> request.risk().level().ordinal() >= minimum.ordinal())
                        .orElse(true)
                && request.risk().sideEffects().containsAll(matcher.requiredSideEffects());
    }

    private static boolean matches(Optional<String> expected, String actual) {
        return expected.map(actual::equals).orElse(true);
    }

    private static boolean matches(Optional<String> expected, Optional<String> actual) {
        return expected.map(value -> actual.map(value::equals).orElse(false)).orElse(true);
    }

    private static int effectRank(PolicyEffect effect) {
        return switch (effect) {
            case DENY -> 3;
            case ASK -> 2;
            case ALLOW -> 1;
        };
    }
}
