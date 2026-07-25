package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.context.compression.ConversationSummaryRepository;
import io.haifa.agent.runtime.core.interaction.InMemoryInteractionPort;
import io.haifa.agent.runtime.core.interaction.InteractionPort;
import io.haifa.agent.runtime.core.tool.InMemoryToolExecutionJournal;
import io.haifa.agent.runtime.core.tool.ToolExecutionJournal;
import io.haifa.agent.runtime.core.tool.ToolResultAssetStore;
import java.util.Objects;

/** Immutable assembly of every persistence boundary required by Runtime Core. */
public record RuntimePersistencePorts(
        AgentSessionRepository sessions,
        RunStateRepository runs,
        ExecutionAttemptRepository attempts,
        CheckpointRepository checkpoints,
        RuntimeStateRepository state,
        RuntimeEventAppender events,
        RuntimeOutboxPublisher outbox,
        IdempotencyRepository idempotency,
        RuntimeUnitOfWork unitOfWork,
        ToolExecutionJournal toolJournal,
        InteractionPort interactions,
        ConversationSummaryRepository conversationSummaries,
        ToolResultAssetStore toolResultAssets,
        MessageRedactionListenerRegistry messageRedactions) {

    public RuntimePersistencePorts {
        sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        runs = Objects.requireNonNull(runs, "runs must not be null");
        attempts = Objects.requireNonNull(attempts, "attempts must not be null");
        checkpoints = Objects.requireNonNull(checkpoints, "checkpoints must not be null");
        state = Objects.requireNonNull(state, "state must not be null");
        events = Objects.requireNonNull(events, "events must not be null");
        outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        idempotency = Objects.requireNonNull(idempotency, "idempotency must not be null");
        unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        toolJournal = Objects.requireNonNull(toolJournal, "toolJournal must not be null");
        interactions = Objects.requireNonNull(interactions, "interactions must not be null");
        conversationSummaries = Objects.requireNonNull(conversationSummaries, "conversationSummaries must not be null");
        toolResultAssets = Objects.requireNonNull(toolResultAssets, "toolResultAssets must not be null");
        messageRedactions = Objects.requireNonNull(messageRedactions, "messageRedactions must not be null");
    }

    public static RuntimePersistencePorts inMemory() {
        return inMemory(new InMemoryRuntimeStore());
    }

    public static RuntimePersistencePorts inMemory(InMemoryRuntimeStore store) {
        return inMemory(store, new InMemoryToolExecutionJournal(), new InMemoryInteractionPort());
    }

    public static RuntimePersistencePorts inMemory(
            InMemoryRuntimeStore store, ToolExecutionJournal toolJournal, InteractionPort interactions) {
        Objects.requireNonNull(store, "store must not be null");
        return new RuntimePersistencePorts(
                store,
                store,
                store,
                store,
                store,
                store,
                store,
                store,
                store,
                toolJournal,
                interactions,
                store,
                store,
                store);
    }
}
