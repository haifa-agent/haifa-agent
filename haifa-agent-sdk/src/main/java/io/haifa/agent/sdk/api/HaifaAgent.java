package io.haifa.agent.sdk.api;

import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.runtime.core.execution.LocalExecutionScheduler;
import io.haifa.agent.sdk.conversation.ConversationService;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.internal.StructuredOutputRecords;
import io.haifa.agent.sdk.memory.AgentMemories;
import io.haifa.agent.sdk.product.ProductProfile;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fully assembled product runtime with stable product-facing services. */
public final class HaifaAgent implements AutoCloseable {
    private final ProductProfile profile;
    private final AgentMetadata metadata;
    private final List<AgentDiagnostic> diagnostics;
    private final AgentRuns runs;
    private final ConversationService conversations;
    private final Optional<AgentMemories> memories;
    private final Optional<ArtifactService> artifacts;
    private final LocalExecutionScheduler scheduler;
    private final List<AutoCloseable> lifecycle;
    private final AtomicBoolean closed;
    private final io.haifa.agent.common.id.IdentifierGenerator ids;

    HaifaAgent(
            ProductProfile profile,
            AgentMetadata metadata,
            List<AgentDiagnostic> diagnostics,
            AgentRuns runs,
            ConversationService conversations,
            Optional<AgentMemories> memories,
            Optional<ArtifactService> artifacts,
            LocalExecutionScheduler scheduler,
            List<AutoCloseable> lifecycle,
            AtomicBoolean closed,
            io.haifa.agent.common.id.IdentifierGenerator ids) {
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
        this.memories = Objects.requireNonNull(memories, "memories must not be null");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.lifecycle = List.copyOf(Objects.requireNonNull(lifecycle, "lifecycle must not be null"));
        this.closed = Objects.requireNonNull(closed, "closed must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
    }

    /** The frozen product profile this agent was assembled from. */
    public ProductProfile profile() {
        return profile;
    }

    /** Non-secret assembly diagnostics; empty when the product configured everything explicitly. */
    public List<AgentDiagnostic> diagnostics() {
        return diagnostics;
    }

    /** Returns immutable display/diagnostic metadata; it is not part of Prompt or selection. */
    public AgentMetadata metadata() {
        return metadata;
    }

    /** Starts a new Conversation and Run through the existing authoritative services. */
    public AgentChatHandle chat(String message) {
        return chat(message, List.of());
    }

    /** Starts a new Conversation and Run with media inputs (such as images) through the existing authoritative services. */
    public AgentChatHandle chat(String message, List<ContentPart> inputs) {
        requireOpen();
        String idempotencyKey = "sdk-chat-" + ids.nextValue();
        var conversation = conversations.start(new StartConversationCommand(
                idempotencyKey, metadata.name(), message, Optional.empty(), inputs, Optional.empty()));
        return new AgentChatHandle(conversation.record().sessionId(), conversation.runId(), runs);
    }

    /** Starts a new Conversation and Run with media inputs (such as images) through the existing authoritative services. */
    public AgentChatHandle chat(String message, ContentPart... inputs) {
        return chat(message, List.of(inputs));
    }

    /** Starts a chat whose terminal result must satisfy the bounded schema generated from a public Java record. */
    public <T extends Record> AgentResponseHandle<T> chat(String message, Class<T> responseType) {
        requireOpen();
        var requirement = StructuredOutputRecords.requirement(responseType);
        String idempotencyKey = "sdk-chat-" + ids.nextValue();
        var conversation = conversations.start(new StartConversationCommand(
                idempotencyKey, metadata.name(), message, Optional.empty(), List.of(), Optional.of(requirement)));
        return new AgentResponseHandle<>(conversation.record().sessionId(), conversation.runId(), runs, responseType);
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
            } catch (Exception exception) {
                RuntimeException mapped = exception instanceof RuntimeException runtime
                        ? runtime
                        : new IllegalStateException("component close failed", exception);
                if (failure == null) failure = mapped;
                else failure.addSuppressed(mapped);
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
