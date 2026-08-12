package io.haifa.agent.sdk.api;

import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.runtime.core.execution.LocalExecutionScheduler;
import io.haifa.agent.sdk.conversation.ConversationService;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.memory.AgentMemories;
import io.haifa.agent.sdk.product.ProductAssembly;
import io.haifa.agent.sdk.product.ProductAssemblyDiagnostic;
import io.haifa.agent.sdk.product.ProductContribution;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fully assembled product runtime with stable product-facing services. */
public final class HaifaAgent implements AutoCloseable {
    private final ProductAssembly assembly;
    private final AgentMetadata metadata;
    private final AgentRuns runs;
    private final ConversationService conversations;
    private final Optional<AgentMemories> memories;
    private final Optional<ArtifactService> artifacts;
    private final LocalExecutionScheduler scheduler;
    private final List<ProductContribution> lifecycle;
    private final AtomicBoolean closed;
    private final io.haifa.agent.common.id.IdentifierGenerator ids;

    HaifaAgent(
            ProductAssembly assembly,
            AgentMetadata metadata,
            AgentRuns runs,
            ConversationService conversations,
            Optional<AgentMemories> memories,
            Optional<ArtifactService> artifacts,
            LocalExecutionScheduler scheduler,
            List<ProductContribution> lifecycle,
            AtomicBoolean closed,
            io.haifa.agent.common.id.IdentifierGenerator ids) {
        this.assembly = Objects.requireNonNull(assembly, "assembly must not be null");
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
        this.memories = Objects.requireNonNull(memories, "memories must not be null");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.lifecycle = List.copyOf(Objects.requireNonNull(lifecycle, "lifecycle must not be null"));
        this.closed = Objects.requireNonNull(closed, "closed must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
    }

    public ProductAssembly assembly() {
        return assembly;
    }

    public List<ProductAssemblyDiagnostic> diagnostics() {
        return assembly.diagnostics();
    }

    /** Returns immutable display/diagnostic metadata; it is not part of Prompt or selection. */
    public AgentMetadata metadata() {
        return metadata;
    }

    /** Starts a new Conversation and Run through the existing authoritative services. */
    public AgentChatHandle chat(String message) {
        requireOpen();
        String idempotencyKey = "sdk-chat-" + ids.nextValue();
        var conversation = conversations.start(new StartConversationCommand(idempotencyKey, metadata.name(), message));
        return new AgentChatHandle(
                conversation.sessionId(), conversation.activeRunId().orElseThrow(), runs);
    }

    public AgentRuns runs() {
        requireOpen();
        return runs;
    }

    public ConversationService conversations() {
        requireOpen();
        return conversations;
    }

    public Optional<AgentMemories> memories() {
        requireOpen();
        return memories;
    }

    /** @deprecated use {@link #memories()} */
    @Deprecated(forRemoval = false)
    public Optional<AgentMemories> memory() {
        return memories();
    }

    public Optional<ArtifactService> artifacts() {
        requireOpen();
        return artifacts;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        RuntimeException failure = null;
        try {
            scheduler.close();
        } catch (RuntimeException exception) {
            failure = exception;
        }
        for (int index = lifecycle.size() - 1; index >= 0; index--) {
            try {
                lifecycle.get(index).close();
            } catch (RuntimeException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new HaifaAgentException("AGENT_CLOSED", "agent.access", "agent", "AGENT_CLOSED");
        }
    }
}
