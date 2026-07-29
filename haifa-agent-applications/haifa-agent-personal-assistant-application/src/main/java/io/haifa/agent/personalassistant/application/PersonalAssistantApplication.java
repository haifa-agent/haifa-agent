package io.haifa.agent.personalassistant.application;

import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.memory.api.MemoryCandidateId;
import io.haifa.agent.memory.api.MemoryCandidateStatus;
import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryRef;
import io.haifa.agent.memory.api.MemoryStatus;
import io.haifa.agent.memory.api.MemoryVersion;
import io.haifa.agent.personalassistant.application.mcp.PersonalMcpPlatform;
import io.haifa.agent.personalassistant.application.product.PersonalAssistantProfile;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.RunOutputCursor;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.conversation.ChangeConversationStatusCommand;
import io.haifa.agent.sdk.conversation.ConversationQuery;
import io.haifa.agent.sdk.conversation.ConversationRecord;
import io.haifa.agent.sdk.conversation.ConversationStatus;
import io.haifa.agent.sdk.conversation.ConversationTurn;
import io.haifa.agent.sdk.conversation.ConversationTurnQuery;
import io.haifa.agent.sdk.conversation.RenameConversationCommand;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.conversation.SubmitConversationTurnCommand;
import io.haifa.agent.sdk.memory.InvalidateMemoryCommand;
import io.haifa.agent.sdk.memory.MemoryCandidateListQuery;
import io.haifa.agent.sdk.memory.MemoryListQuery;
import io.haifa.agent.sdk.memory.MemoryScopeSpec;
import io.haifa.agent.sdk.memory.RejectMemoryCandidateCommand;
import io.haifa.agent.sdk.memory.ReviewMemoryCandidateCommand;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** Pure-Java product use cases over the Phase 20 SDK and public Runtime views. */
public final class PersonalAssistantApplication implements AutoCloseable {
    private final HaifaAgent agent;
    private final PersonalMcpPlatform mcp;
    private final Clock clock;
    private final Set<String> mcpToolAliases;

    public PersonalAssistantApplication(HaifaAgent agent, PersonalMcpPlatform mcp, Clock clock) {
        this.agent = Objects.requireNonNull(agent);
        this.mcp = Objects.requireNonNull(mcp);
        this.clock = Objects.requireNonNull(clock);
        this.mcpToolAliases = mcp.aliases();
    }

    public ConversationView start(String idempotencyKey, String displayName, String message) {
        return conversation(
                agent.conversations().start(new StartConversationCommand(idempotencyKey, displayName, message)));
    }

    public Optional<ConversationView> conversation(String sessionId) {
        return agent.conversations()
                .find(new AgentSessionId(sessionId))
                .map(PersonalAssistantApplication::conversation);
    }

    public List<ConversationView> conversations(Optional<String> query, Set<String> statuses, int limit) {
        Set<ConversationStatus> mapped = statuses.stream()
                .map(value -> ConversationStatus.valueOf(value.toUpperCase(java.util.Locale.ROOT)))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return agent
                .conversations()
                .list(new ConversationQuery(query, mapped, Optional.empty(), limit))
                .items()
                .stream()
                .map(PersonalAssistantApplication::conversation)
                .toList();
    }

    public List<TurnView> turns(String sessionId, int limit) {
        return agent
                .conversations()
                .turns(new AgentSessionId(sessionId), ConversationTurnQuery.first(limit))
                .items()
                .stream()
                .map(PersonalAssistantApplication::turn)
                .toList();
    }

    public ConversationView submit(String sessionId, long expectedRevision, String idempotencyKey, String message) {
        return conversation(agent.conversations()
                .submit(new SubmitConversationTurnCommand(
                        new AgentSessionId(sessionId), expectedRevision, idempotencyKey, message)));
    }

    public ConversationView rename(String sessionId, long expectedRevision, String idempotencyKey, String displayName) {
        return conversation(agent.conversations()
                .rename(new RenameConversationCommand(
                        new AgentSessionId(sessionId), expectedRevision, idempotencyKey, displayName)));
    }

    public ConversationView status(
            String sessionId, long expectedRevision, String idempotencyKey, ConversationStatus status) {
        var command =
                new ChangeConversationStatusCommand(new AgentSessionId(sessionId), expectedRevision, idempotencyKey);
        return conversation(
                status == ConversationStatus.ARCHIVED
                        ? agent.conversations().archive(command)
                        : agent.conversations().unarchive(command));
    }

    public Optional<RunView> run(String runId) {
        return agent.runs().view(new AgentRunId(runId)).map(view -> {
            var snapshot = view.snapshot();
            var usage = snapshot.usage();
            return new RunView(
                    snapshot.runId().value(),
                    view.sessionId().value(),
                    snapshot.status().name(),
                    snapshot.version(),
                    snapshot.updatedAt(),
                    snapshot.output(),
                    snapshot.result().map(result -> result.summary()),
                    snapshot.error().map(error -> error.code().value()),
                    new UsageView(
                            usage.inputTokens(),
                            usage.outputTokens(),
                            Math.addExact(usage.inputTokens(), usage.outputTokens()),
                            usage.cachedInputTokens(),
                            usage.modelCalls(),
                            usage.toolCalls()));
        });
    }

    public RunView cancel(String runId) {
        return run(agent.runs()
                        .handle(new AgentRunId(runId))
                        .cancel()
                        .snapshot()
                        .runId()
                        .value())
                .orElseThrow();
    }

    public Optional<InteractionViewValue> pendingInteraction(String runId) {
        return agent.runs().pendingInteraction(new AgentRunId(runId)).map(PersonalAssistantApplication::interaction);
    }

    public InteractionReceipt respond(
            String runId,
            String interactionId,
            long expectedRevision,
            String action,
            Optional<String> text,
            String idempotencyKey) {
        List<io.haifa.agent.core.content.ContentPart> inputs = text.filter(value -> !value.isBlank())
                .<List<io.haifa.agent.core.content.ContentPart>>map(value -> List.of(new TextPart(value, "text/plain")))
                .orElseGet(List::of);
        var receipt = agent.runs()
                .respond(new InteractionResponseSubmission(
                        new InteractionResponseId("personal:" + idempotencyKey),
                        new io.haifa.agent.runtime.api.InteractionRequestId(interactionId),
                        new AgentRunId(runId),
                        expectedRevision,
                        new InteractionAction(action),
                        inputs,
                        idempotencyKey,
                        clock.instant()));
        return new InteractionReceipt(
                receipt.responseId().value(),
                receipt.requestId().value(),
                receipt.runId().value(),
                receipt.status().name(),
                receipt.interactionState().name(),
                receipt.revision(),
                receipt.runVersion());
    }

    public List<ActivityView> activities(String runId, int limit) {
        AgentRunId id = new AgentRunId(runId);
        return agent.runs().events(id, RunEventCursor.beforeFirst(id), limit).items().stream()
                .map(this::activity)
                .flatMap(Optional::stream)
                .toList();
    }

    public StreamSubscription subscribe(String runId, StreamListener listener) {
        return subscribe(runId, initialStreamCursor(runId), listener);
    }

    /**
     * Returns the initial source-local cursors for a new SSE connection.
     *
     * <p>Durable history starts at the current journal head; transient output starts before the
     * bounded active-Run buffer so a slightly late UI can reconstruct the current draft.
     */
    public StreamCursor initialStreamCursor(String runId) {
        AgentRunId id = new AgentRunId(runId);
        RunEventCursor durable =
                agent.runs().events(id, RunEventCursor.beforeFirst(id), 1).headCursor();
        return new StreamCursor(durable.exclusiveSequence().orElse(0), 0);
    }

    /** Merges durable Run facts and transient model output without sharing a sequence namespace. */
    public StreamSubscription subscribe(String runId, StreamCursor after, StreamListener listener) {
        Objects.requireNonNull(after, "after must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        AgentRunId id = new AgentRunId(runId);
        RunEventCursor durableCursor = after.durableSequence() == 0
                ? RunEventCursor.beforeFirst(id)
                : new RunEventCursor(id, "1", OptionalLong.of(after.durableSequence()));
        var durable = agent.runs().subscribe(id, durableCursor, event -> {
            StreamEvent safe = streamEvent(event);
            if (safe != null) listener.onEvent(safe);
        });
        try {
            var output = agent.runs()
                    .subscribeOutput(
                            id,
                            new RunOutputCursor(after.transientSequence()),
                            event -> listener.onEvent(streamEvent(event)));
            return new CompositeStreamSubscription(durable, output);
        } catch (RuntimeException failure) {
            durable.close();
            throw failure;
        }
    }

    public List<MemoryCandidateView> memoryCandidates(int limit) {
        var memories = agent.memories().orElseThrow();
        return memories
                .candidates(new MemoryCandidateListQuery(
                        MemoryScopeSpec.user(),
                        Set.of(MemoryCandidateStatus.PENDING),
                        Set.of(),
                        Optional.empty(),
                        Optional.empty(),
                        limit))
                .items()
                .stream()
                .map(candidate -> new MemoryCandidateView(
                        candidate.id().value(),
                        candidate.kind().name(),
                        candidate.subjectKey(),
                        candidate.content().boundedText(),
                        candidate.status().name(),
                        candidate.updatedAt(),
                        candidate.revision()))
                .toList();
    }

    public MemoryView approveMemoryCandidate(String candidateId, long expectedRevision, String idempotencyKey) {
        return memory(agent.memories()
                .orElseThrow()
                .approve(new ReviewMemoryCandidateCommand(
                        new MemoryCandidateId(candidateId), expectedRevision, idempotencyKey)));
    }

    public MemoryCandidateView rejectMemoryCandidate(
            String candidateId, long expectedRevision, String idempotencyKey, String reason) {
        var candidate = agent.memories()
                .orElseThrow()
                .reject(new RejectMemoryCandidateCommand(
                        new MemoryCandidateId(candidateId), expectedRevision, idempotencyKey, reason));
        return new MemoryCandidateView(
                candidate.id().value(),
                candidate.kind().name(),
                candidate.subjectKey(),
                candidate.content().boundedText(),
                candidate.status().name(),
                candidate.updatedAt(),
                candidate.revision());
    }

    public List<MemoryView> memories(int limit) {
        return agent
                .memories()
                .orElseThrow()
                .memories(new MemoryListQuery(
                        MemoryScopeSpec.user(),
                        Set.of(MemoryStatus.ACTIVE, MemoryStatus.INVALIDATED),
                        Set.<MemoryKind>of(),
                        Optional.empty(),
                        Optional.empty(),
                        limit))
                .items()
                .stream()
                .map(PersonalAssistantApplication::memory)
                .toList();
    }

    public MemoryView invalidateMemory(String memoryId, long version, String idempotencyKey, String reason) {
        return memory(agent.memories()
                .orElseThrow()
                .invalidate(new InvalidateMemoryCommand(
                        new MemoryRef(new MemoryId(memoryId), new MemoryVersion(version)), idempotencyKey, reason)));
    }

    public String productDigest() {
        return agent.assembly().assemblyDigest();
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            agent.close();
        } catch (RuntimeException exception) {
            failure = exception;
        }
        try {
            mcp.close();
        } catch (RuntimeException exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        }
        if (failure != null) throw failure;
    }

    private static ConversationView conversation(ConversationRecord value) {
        return new ConversationView(
                value.sessionId().value(),
                value.displayName(),
                value.status().name(),
                value.activeRunId().map(AgentRunId::value),
                value.createdAt(),
                value.lastActivityAt(),
                value.revision());
    }

    private static TurnView turn(ConversationTurn value) {
        return new TurnView(
                value.messageId(),
                value.role().name(),
                value.runId().map(AgentRunId::value),
                value.sequence(),
                value.text(),
                value.createdAt());
    }

    private static InteractionViewValue interaction(InteractionView value) {
        return new InteractionViewValue(
                value.requestId().value(),
                value.runId().value(),
                value.sessionId().value(),
                value.revision(),
                value.kind().value(),
                value.state().name(),
                value.title(),
                value.safePrompt(),
                value.allowedActions().stream().map(InteractionAction::value).toList(),
                value.inputContract().type().value(),
                value.inputContract().maximumCharacters(),
                value.createdAt(),
                value.expiresAt());
    }

    private Optional<ActivityView> activity(AgentRunEvent event) {
        if (event.payload() instanceof RunEventPayloads.ExecutionLifecycle execution) {
            return Optional.of(new ActivityView(
                    event.eventId(),
                    event.runId().value(),
                    ActivityKind.TOOL,
                    PersonalAssistantProfile.EXECUTION_TOOL_ALIAS,
                    execution.commandSummary(),
                    execution.status(),
                    event.occurredAt(),
                    Optional.of(event.occurredAt()),
                    execution.chunkOrRef(),
                    Optional.empty(),
                    event.sequence()));
        }
        if (!(event.payload() instanceof RunEventPayloads.ToolLifecycle tool)) return Optional.empty();
        ActivityKind kind =
                Set.of(PersonalAssistantProfile.SKILL_LOAD_ALIAS, PersonalAssistantProfile.SKILL_RESOURCE_ALIAS)
                                        .contains(tool.displayName())
                                || tool.displayName().startsWith("skill.")
                        ? ActivityKind.SKILL
                        : mcpToolAliases.contains(tool.displayName())
                                        || tool.displayName().startsWith("mcp.")
                                ? ActivityKind.MCP
                                : ActivityKind.TOOL;
        return Optional.of(new ActivityView(
                event.eventId(),
                event.runId().value(),
                kind,
                tool.displayName(),
                tool.targetSummary(),
                tool.status(),
                event.occurredAt(),
                terminal(tool.status()) ? Optional.of(event.occurredAt()) : Optional.empty(),
                safeResult(tool),
                Optional.empty(),
                event.sequence()));
    }

    private static String safeResult(RunEventPayloads.ToolLifecycle tool) {
        if ("SUCCEEDED".equals(tool.status())) return "Completed";
        if ("FAILED".equals(tool.status()) || "CANCELLED".equals(tool.status())) return tool.reasonCode();
        return "";
    }

    private static boolean terminal(String status) {
        return Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status);
    }

    private StreamEvent streamEvent(AgentRunEvent event) {
        Object payload = event.payload();
        if (payload instanceof RunEventPayloads.RunLifecycle run) {
            return new StreamEvent(
                    event.eventId(),
                    "run.status",
                    event.runId().value(),
                    event.occurredAt(),
                    run.status(),
                    Optional.empty(),
                    StreamSource.DURABLE,
                    event.sequence());
        }
        if (payload instanceof RunEventPayloads.InteractionLifecycle interaction) {
            return new StreamEvent(
                    event.eventId(),
                    "interaction.status",
                    event.runId().value(),
                    event.occurredAt(),
                    interaction.state(),
                    Optional.empty(),
                    StreamSource.DURABLE,
                    event.sequence());
        }
        return activity(event)
                .map(value -> new StreamEvent(
                        event.eventId(),
                        "activity.committed",
                        event.runId().value(),
                        event.occurredAt(),
                        value.status(),
                        Optional.of(value),
                        StreamSource.DURABLE,
                        event.sequence()))
                .orElse(null);
    }

    private static StreamEvent streamEvent(AgentRunOutputEvent event) {
        String type =
                switch (event.type()) {
                    case RUN_OUTPUT_STARTED -> "answer.started";
                    case ASSISTANT_TEXT_DELTA -> "answer.delta";
                    case ASSISTANT_TEXT_COMMITTED -> "answer.committed";
                    case RUN_OUTPUT_SUPERSEDED -> "answer.superseded";
                    case RUN_OUTPUT_FAILED -> "answer.failed";
                };
        String value =
                event.type() == AgentRunOutputEventType.ASSISTANT_TEXT_DELTA ? event.textDelta() : event.generationId();
        return new StreamEvent(
                "transient-output:" + event.runId().value() + ":" + event.sequence(),
                type,
                event.runId().value(),
                event.occurredAt(),
                value,
                Optional.empty(),
                StreamSource.TRANSIENT,
                event.sequence());
    }

    private static MemoryView memory(io.haifa.agent.memory.api.Memory value) {
        return new MemoryView(
                value.id().value(),
                value.version().value(),
                value.kind().name(),
                value.subjectKey(),
                value.content().map(content -> content.boundedText()).orElse(""),
                value.status().name(),
                value.createdAt(),
                value.updatedAt());
    }

    public record ConversationView(
            String id,
            String displayName,
            String status,
            Optional<String> activeRunId,
            Instant createdAt,
            Instant lastActivityAt,
            long revision) {}

    public record TurnView(
            String id, String role, Optional<String> runId, long sequence, String text, Instant createdAt) {}

    public record UsageView(
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long cachedInputTokens,
            long modelCalls,
            long toolCalls) {}

    public record RunView(
            String id,
            String conversationId,
            String status,
            long version,
            Instant updatedAt,
            Optional<String> output,
            Optional<String> resultSummary,
            Optional<String> errorCode,
            UsageView usage) {}

    public record InteractionViewValue(
            String id,
            String runId,
            String conversationId,
            long revision,
            String kind,
            String state,
            String title,
            String safePrompt,
            List<String> allowedActions,
            String inputType,
            int maximumCharacters,
            Instant createdAt,
            Instant expiresAt) {}

    public record InteractionReceipt(
            String responseId,
            String interactionId,
            String runId,
            String status,
            String interactionState,
            long revision,
            long runVersion) {}

    public enum ActivityKind {
        TOOL,
        SKILL,
        MCP
    }

    public record ActivityView(
            String activityId,
            String runId,
            ActivityKind kind,
            String displayName,
            String safeTargetSummary,
            String status,
            Instant startedAt,
            Optional<Instant> completedAt,
            String safeResultSummary,
            Optional<String> interactionRef,
            long version) {}

    public record MemoryCandidateView(
            String id,
            String kind,
            String subjectKey,
            String content,
            String status,
            Instant updatedAt,
            long revision) {}

    public record MemoryView(
            String id,
            long version,
            String kind,
            String subjectKey,
            String content,
            String status,
            Instant createdAt,
            Instant updatedAt) {}

    public record StreamEvent(
            String id,
            String type,
            String runId,
            Instant occurredAt,
            String value,
            Optional<ActivityView> activity,
            StreamSource source,
            long sequence) {}

    public enum StreamSource {
        DURABLE,
        TRANSIENT,
        SNAPSHOT
    }

    public record StreamCursor(long durableSequence, long transientSequence) {
        public StreamCursor {
            if (durableSequence < 0 || transientSequence < 0) {
                throw new IllegalArgumentException("stream source sequences must not be negative");
            }
        }
    }

    @FunctionalInterface
    public interface StreamListener {
        void onEvent(StreamEvent event);
    }

    @FunctionalInterface
    public interface StreamSubscription extends AutoCloseable {
        @Override
        void close();
    }

    private static final class CompositeStreamSubscription implements StreamSubscription {
        private final AutoCloseable durable;
        private final AutoCloseable transientOutput;
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean();

        private CompositeStreamSubscription(AutoCloseable durable, AutoCloseable transientOutput) {
            this.durable = durable;
            this.transientOutput = transientOutput;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            closeQuietly(transientOutput);
            closeQuietly(durable);
        }

        private static void closeQuietly(AutoCloseable value) {
            try {
                value.close();
            } catch (Exception ignored) {
                // Closing an observational subscription must remain idempotent and best effort.
            }
        }
    }
}
