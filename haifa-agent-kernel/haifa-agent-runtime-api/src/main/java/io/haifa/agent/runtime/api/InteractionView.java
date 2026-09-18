package io.haifa.agent.runtime.api;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Safe public view of one interaction. */
public record InteractionView(
        InteractionRequestId requestId,
        AgentRunId runId,
        AgentSessionId sessionId,
        long revision,
        InteractionKind kind,
        InteractionState state,
        String title,
        String safePrompt,
        List<InteractionAction> allowedActions,
        InteractionInputContract inputContract,
        InteractionTargetView target,
        InteractionRequesterView requester,
        Instant createdAt,
        Optional<Instant> expiresAt,
        InteractionConsequenceView consequences) {
    public InteractionView(
            InteractionRequestId requestId,
            AgentRunId runId,
            AgentSessionId sessionId,
            long revision,
            InteractionKind kind,
            InteractionState state,
            String title,
            String safePrompt,
            List<InteractionAction> allowedActions,
            InteractionInputContract inputContract,
            InteractionTargetView target,
            InteractionRequesterView requester,
            Instant createdAt,
            Instant expiresAt,
            InteractionConsequenceView consequences) {
        this(
                requestId,
                runId,
                sessionId,
                revision,
                kind,
                state,
                title,
                safePrompt,
                allowedActions,
                inputContract,
                target,
                requester,
                createdAt,
                Optional.of(Objects.requireNonNull(expiresAt, "expiresAt must not be null")),
                consequences);
    }

    public InteractionView {
        requestId = Objects.requireNonNull(requestId, "requestId must not be null");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
        kind = Objects.requireNonNull(kind, "kind must not be null");
        state = Objects.requireNonNull(state, "state must not be null");
        title = InteractionOption.requireText(title, "title", 256);
        safePrompt = InteractionOption.requireText(safePrompt, "safePrompt", 2_048);
        allowedActions = List.copyOf(Objects.requireNonNull(allowedActions, "allowedActions must not be null"));
        if (allowedActions.size() > 8 || allowedActions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("allowedActions must contain at most 8 values");
        }
        inputContract = Objects.requireNonNull(inputContract, "inputContract must not be null");
        target = Objects.requireNonNull(target, "target must not be null");
        requester = Objects.requireNonNull(requester, "requester must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        consequences = Objects.requireNonNull(consequences, "consequences must not be null");
        if (expiresAt.isPresent() && !expiresAt.orElseThrow().isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }
}
