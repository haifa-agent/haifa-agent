package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.requireIdentifier;
import static io.haifa.agent.policy.api.PolicyValues.requireSafeText;

import java.util.Objects;
import java.util.Optional;

public record PolicyRule(
        PolicyRuleRef ref,
        PolicyRuleSource source,
        int priority,
        PolicyRuleMatcher matcher,
        PolicyEffect effect,
        Optional<PolicyChallenge> challenge,
        String reasonCode,
        String safeExplanation) {
    public PolicyRule {
        ref = Objects.requireNonNull(ref, "ref must not be null");
        source = Objects.requireNonNull(source, "source must not be null");
        matcher = Objects.requireNonNull(matcher, "matcher must not be null");
        effect = Objects.requireNonNull(effect, "effect must not be null");
        challenge = Objects.requireNonNull(challenge, "challenge must not be null");
        reasonCode = requireIdentifier(reasonCode, "reasonCode");
        safeExplanation = requireSafeText(safeExplanation, "safeExplanation");
        if (effect == PolicyEffect.ASK && challenge.isEmpty()) {
            throw new IllegalArgumentException("ASK rule requires a challenge");
        }
        if (effect != PolicyEffect.ASK && challenge.isPresent()) {
            throw new IllegalArgumentException("only ASK rule may carry a challenge");
        }
    }
}
