package io.haifa.agent.policy.core;

import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyDecisionIdGenerator;
import io.haifa.agent.policy.api.PolicyDecisionService;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyRule;
import io.haifa.agent.policy.api.PolicyRuleMatcher;
import io.haifa.agent.policy.api.PolicySnapshot;
import java.time.Clock;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

public final class DefaultPolicyDecisionService implements PolicyDecisionService {
    private static final Comparator<PolicyRule> DECISION_ORDER = Comparator.comparingInt(
                    (PolicyRule rule) -> effectRank(rule.effect()))
            .reversed()
            .thenComparing(Comparator.comparingInt(PolicyRule::priority).reversed())
            .thenComparing(rule -> rule.ref().ruleId())
            .thenComparing(rule -> rule.ref().version());

    private final Clock clock;
    private final PolicyDecisionIdGenerator ids;

    public DefaultPolicyDecisionService(Clock clock, PolicyDecisionIdGenerator ids) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
    }

    @Override
    public PolicyDecision evaluate(PolicyRequest request, PolicySnapshot snapshot) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        PolicyRule selected = snapshot.rules().stream()
                .filter(rule -> matches(rule, request))
                .min(DECISION_ORDER)
                .or(() -> snapshot.defaultRule().filter(rule -> matches(rule, request)))
                .orElse(null);
        if (selected == null) {
            return new PolicyDecision(
                    ids.nextId(),
                    PolicyEffect.DENY,
                    Optional.empty(),
                    "POLICY_NO_MATCH",
                    "No explicit policy rule allows this action",
                    snapshot.ref(),
                    Optional.empty(),
                    clock.instant());
        }
        return new PolicyDecision(
                ids.nextId(),
                selected.effect(),
                selected.challenge(),
                selected.reasonCode(),
                selected.safeExplanation(),
                snapshot.ref(),
                Optional.of(selected.ref()),
                clock.instant());
    }

    private static boolean matches(PolicyRule rule, PolicyRequest request) {
        PolicyRuleMatcher matcher = rule.matcher();
        if (rule.source() == io.haifa.agent.policy.api.PolicyRuleSource.PROJECT
                && rule.effect() == PolicyEffect.ALLOW
                && request.context().projectTrustRef().isEmpty()) {
            return false;
        }
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
