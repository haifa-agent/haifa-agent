package io.haifa.agent.runtime.core.input;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;
import io.haifa.agent.runtime.core.storage.OutboxMessage;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import io.haifa.agent.runtime.core.storage.RuntimeOutboxPublisher;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.runtime.core.storage.RuntimeUnitOfWork;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Applies accepted steer input only at a declared loop safe point. */
public final class RunInputApplier {
    private final RunInputPort inputs;
    private final RuntimeStateRepository state;
    private final RuntimeEventAppender events;
    private final RuntimeOutboxPublisher outbox;
    private final RuntimeUnitOfWork unitOfWork;
    private final IdentifierGenerator ids;
    private final TimeProvider time;

    public RunInputApplier(
            RunInputPort inputs,
            RuntimeStateRepository state,
            RuntimeEventAppender events,
            RuntimeOutboxPublisher outbox,
            RuntimeUnitOfWork unitOfWork,
            IdentifierGenerator ids,
            TimeProvider time) {
        this.inputs = Objects.requireNonNull(inputs);
        this.state = Objects.requireNonNull(state);
        this.events = Objects.requireNonNull(events);
        this.outbox = Objects.requireNonNull(outbox);
        this.unitOfWork = Objects.requireNonNull(unitOfWork);
        this.ids = Objects.requireNonNull(ids);
        this.time = Objects.requireNonNull(time);
    }

    public List<RunInputRecord> applyPending(AgentRun run, AgentRunExecutionAttempt attempt, int iteration) {
        List<RunInputRecord> appliedInputs = new ArrayList<>();
        for (RunInputRecord pending : inputs.pending(run.id(), 100)) {
            appliedInputs.add(unitOfWork.execute(() -> {
                state.appendSessionMessage(new SessionMessageDraft(
                        new AgentMessageId(ids.nextValue()),
                        run.sessionId(),
                        java.util.Optional.of(run.id()),
                        java.util.Optional.empty(),
                        MessageRole.USER,
                        MessageStatus.COMPLETED,
                        MessageVisibility.USER_VISIBLE,
                        pending.submission().contents(),
                        Map.of(
                                "runInputId", pending.submission().inputId().value(),
                                "runInputKind", "steer",
                                "safePoint", "BEFORE_ITERATION"),
                        time.now()));
                RunInputRecord applied = inputs.markApplied(
                        pending.submission().inputId(), attempt.attemptId().value(), iteration, time.now());
                var event = events.append(
                        run.id(),
                        "run.input.applied",
                        Map.of(
                                "inputId",
                                applied.submission().inputId().value(),
                                "attemptId",
                                attempt.attemptId().value(),
                                "iteration",
                                iteration,
                                "safePoint",
                                "BEFORE_ITERATION"),
                        time.now());
                outbox.append(new OutboxMessage(
                        event.eventId(),
                        event.runId(),
                        event.sequence(),
                        event.type(),
                        OutboxMessage.CURRENT_SCHEMA_VERSION,
                        event.data(),
                        event.occurredAt()));
                return applied;
            }));
        }
        return List.copyOf(appliedInputs);
    }
}
