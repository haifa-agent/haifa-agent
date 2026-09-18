package io.haifa.agent.runtime.core.interaction;

import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionState;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Store-facing lifecycle record. Request content remains immutable while revision and state advance. */
public record InteractionRecord(
        InteractionRequest request,
        long revision,
        InteractionState state,
        Optional<InteractionResponseId> responseId,
        Optional<InteractionAction> action,
        Optional<String> reasonCode,
        Optional<Instant> changedAt) {

    public InteractionRecord {
        request = Objects.requireNonNull(request, "request must not be null");
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
        state = Objects.requireNonNull(state, "state must not be null");
        responseId = Objects.requireNonNull(responseId, "responseId must not be null");
        action = Objects.requireNonNull(action, "action must not be null");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        changedAt = Objects.requireNonNull(changedAt, "changedAt must not be null");
        if ((state == InteractionState.RESPONDED || state == InteractionState.APPLIED)
                && (responseId.isEmpty() || action.isEmpty())) {
            throw new IllegalArgumentException("responded/applied interactions require response identity and action");
        }
    }

    public static InteractionRecord pending(InteractionRequest request) {
        return new InteractionRecord(
                request,
                0,
                InteractionState.PENDING,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }
}
