package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.requireIdentifier;
import static io.haifa.agent.policy.api.PolicyValues.requireSafeText;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record PolicyDecision(
        PolicyDecisionId id,
        PolicyEffect effect,
        Optional<PolicyChallenge> challenge,
        String reasonCode,
        String safeExplanation,
        PolicySnapshotRef snapshot,
        Optional<PolicyRuleRef> matchedRule,
        Instant decidedAt) {
    public PolicyDecision {
        id = Objects.requireNonNull(id, "id must not be null");
        effect = Objects.requireNonNull(effect, "effect must not be null");
        challenge = Objects.requireNonNull(challenge, "challenge must not be null");
        reasonCode = requireIdentifier(reasonCode, "reasonCode");
        safeExplanation = requireSafeText(safeExplanation, "safeExplanation");
        snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        matchedRule = Objects.requireNonNull(matchedRule, "matchedRule must not be null");
        decidedAt = Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        if (effect == PolicyEffect.ASK && challenge.isEmpty()) {
            throw new IllegalArgumentException("ASK decision requires a challenge");
        }
        if (effect != PolicyEffect.ASK && challenge.isPresent()) {
            throw new IllegalArgumentException("only ASK decision may carry a challenge");
        }
    }
}
