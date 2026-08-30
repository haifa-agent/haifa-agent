package io.haifa.agent.runtime.core.recovery;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;

/** Bounded cross-call semantic failure controller. */
public final class RecoveryController {
    private static final int HISTORY_LIMIT = 16;
    private final Deque<FailureCluster> history = new ArrayDeque<>();
    private FailureCluster active;

    public Update observe(ToolOutcomeObservation observation) {
        Objects.requireNonNull(observation, "observation must not be null");
        if (observation.category() == ToolFailureCategory.CANCELLED
                || observation.category() == ToolFailureCategory.OUTCOME_UNKNOWN) {
            active = null;
            return new Update(observation, 0, direct(observation.category(), 0));
        }
        if (active != null
                && active.fingerprintDigest().equals(observation.fingerprint().digest())) {
            active = new FailureCluster(active.fingerprintDigest(), active.attempts() + 1, observation.category());
        } else {
            active = new FailureCluster(observation.fingerprint().digest(), 1, observation.category());
        }
        history.addLast(active);
        while (history.size() > HISTORY_LIMIT) history.removeFirst();
        RecoveryDirective directive = direct(observation.category(), active.attempts());
        return new Update(observation, active.attempts(), directive);
    }

    public void meaningfulProgress() {
        active = null;
    }

    public int activeAttempts() {
        return active == null ? 0 : active.attempts();
    }

    public Optional<String> activeFingerprintDigest() {
        return Optional.ofNullable(active).map(FailureCluster::fingerprintDigest);
    }

    public Optional<ToolFailureCategory> activeCategory() {
        return Optional.ofNullable(active).map(FailureCluster::category);
    }

    private static RecoveryDirective direct(ToolFailureCategory category, int attempts) {
        if (category == ToolFailureCategory.OUTCOME_UNKNOWN) return RecoveryDirective.TERMINATE_OUTCOME_UNKNOWN;
        if (category == ToolFailureCategory.CANCELLED) return RecoveryDirective.TERMINATE_CANCELLED;
        if (category == ToolFailureCategory.POLICY_DENIED) return RecoveryDirective.WAIT_FOR_INTERACTION;
        return switch (attempts) {
            case 1 -> RecoveryDirective.CONTINUE_WITH_DIAGNOSTIC;
            case 2 -> RecoveryDirective.REQUIRE_STRATEGY_CHANGE;
            default -> RecoveryDirective.TERMINATE_REPEATED_FAILURE;
        };
    }

    private record FailureCluster(String fingerprintDigest, int attempts, ToolFailureCategory category) {}

    public record Update(ToolOutcomeObservation observation, int attempts, RecoveryDirective directive) {}
}
