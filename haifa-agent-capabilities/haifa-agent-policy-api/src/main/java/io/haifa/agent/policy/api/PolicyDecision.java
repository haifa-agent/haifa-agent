package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.requireIdentifier;
import static io.haifa.agent.policy.api.PolicyValues.requireSafeText;

import java.util.Objects;
import java.util.Optional;

/** One transient policy evaluation result. It is neither an identity nor an authorization bearer. */
public record PolicyDecision(
        PolicyEffect effect,
        Optional<PolicyChallenge> challenge,
        String reasonCode,
        String safeExplanation,
        String requirementDigest) {
    public PolicyDecision {
        effect = Objects.requireNonNull(effect, "effect must not be null");
        challenge = Objects.requireNonNull(challenge, "challenge must not be null");
        reasonCode = requireIdentifier(reasonCode, "reasonCode");
        safeExplanation = requireSafeText(safeExplanation, "safeExplanation");
        requirementDigest = requireIdentifier(requirementDigest, "requirementDigest");
        if (effect == PolicyEffect.ASK && challenge.isEmpty()) {
            throw new IllegalArgumentException("ASK decision requires a challenge");
        }
        if (effect != PolicyEffect.ASK && challenge.isPresent()) {
            throw new IllegalArgumentException("only ASK decision may carry a challenge");
        }
    }
}
