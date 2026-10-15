package io.haifa.agent.personalassistant.application;

import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.common.time.TimePrecision;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.ImageUrlContentPart;
import io.haifa.agent.core.content.StoredAudioContentPart;
import io.haifa.agent.core.content.StoredImageContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.execution.api.ToolOutputPreview;
import io.haifa.agent.execution.api.ToolOutputPreviewPublisher;
import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.personalassistant.application.mcp.PersonalMcpPlatform;
import io.haifa.agent.personalassistant.application.mission.MissionModelBinding;
import io.haifa.agent.personalassistant.application.mission.MissionRuntimeAccess;
import io.haifa.agent.personalassistant.application.product.PersonalAssistantProfile;
import io.haifa.agent.personalassistant.application.recommendation.PersonalQuestionRecommender;
import io.haifa.agent.personalassistant.application.recommendation.PersonalQuestionRecommender.RecommendationTurn;
import io.haifa.agent.personalassistant.application.research.ResearchFetchEvidenceReader;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import io.haifa.agent.runtime.api.ApprovalPresentation;
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
import io.haifa.agent.sdk.conversation.ConversationRun;
import io.haifa.agent.sdk.conversation.ConversationStatus;
import io.haifa.agent.sdk.conversation.ConversationTurn;
import io.haifa.agent.sdk.conversation.ConversationTurnQuery;
import io.haifa.agent.sdk.conversation.RenameConversationCommand;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.conversation.SubmitConversationTurnCommand;
import io.haifa.agent.sdk.memory.MemoryListQuery;
import io.haifa.agent.sdk.memory.MemoryScopeSpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/** Pure-Java product use cases over the Phase 20 SDK and public Runtime views. */
public final class PersonalAssistantApplication implements AutoCloseable {
    private final HaifaAgent agent;
    private final PersonalMcpPlatform mcp;
    private final Clock clock;
    private final PersonalCapabilityRegistry capabilities;
    private final PersonalQuestionRecommender questionRecommender;
    private final Set<String> mcpToolAliases;
    private final PersonalModelCatalog models;
    private final PersonalModelPreferenceStore modelPreferences;
    private final MissionRuntimeAccess missionRuntime;
    private final ArtifactService artifacts;
    private final Map<String, String> skillBindingReferences;
    private final String productDigest;
    private final ResearchFetchEvidenceReader fetchEvidenceReader;
    private final ToolOutputPreviewPublisher previewPublisher;
    private final ConcurrentMap<String, List<String>> recommendedQuestions = new ConcurrentHashMap<>();

    public PersonalAssistantApplication(
            HaifaAgent agent,
            PersonalMcpPlatform mcp,
            Clock clock,
            PersonalCapabilityRegistry capabilities,
            PersonalModelCatalog models,
            PersonalModelPreferenceStore modelPreferences,
            PersonalQuestionRecommender questionRecommender,
            MissionRuntimeAccess missionRuntime,
            ArtifactService artifacts,
            Map<String, String> skillBindingReferences) {
        this(
                agent,
                mcp,
                clock,
                capabilities,
                models,
                modelPreferences,
                questionRecommender,
                missionRuntime,
                artifacts,
                skillBindingReferences,
                ResearchFetchEvidenceReader.empty());
    }

    public PersonalAssistantApplication(
            HaifaAgent agent,
            PersonalMcpPlatform mcp,
            Clock clock,
            PersonalCapabilityRegistry capabilities,
            PersonalModelCatalog models,
            PersonalModelPreferenceStore modelPreferences,
            PersonalQuestionRecommender questionRecommender,
            MissionRuntimeAccess missionRuntime,
            ArtifactService artifacts,
            Map<String, String> skillBindingReferences,
            ResearchFetchEvidenceReader fetchEvidenceReader) {
        this(
                agent,
                mcp,
                clock,
                capabilities,
                models,
                modelPreferences,
                questionRecommender,
                missionRuntime,
                artifacts,
                skillBindingReferences,
                agent.profile().productId().value() + "@"
                        + agent.profile().productVersion().value(),
                fetchEvidenceReader,
                ToolOutputPreviewPublisher.noop());
    }

    public PersonalAssistantApplication(
            HaifaAgent agent,
            PersonalMcpPlatform mcp,
            Clock clock,
            PersonalCapabilityRegistry capabilities,
            PersonalModelCatalog models,
            PersonalModelPreferenceStore modelPreferences,
            PersonalQuestionRecommender questionRecommender,
            MissionRuntimeAccess missionRuntime,
            ArtifactService artifacts,
            Map<String, String> skillBindingReferences,
            String productDigest,
            ResearchFetchEvidenceReader fetchEvidenceReader) {
        this(
                agent,
                mcp,
                clock,
                capabilities,
                models,
                modelPreferences,
                questionRecommender,
                missionRuntime,
                artifacts,
                skillBindingReferences,
                productDigest,
                fetchEvidenceReader,
                ToolOutputPreviewPublisher.noop());
    }

    public PersonalAssistantApplication(
            HaifaAgent agent,
            PersonalMcpPlatform mcp,
            Clock clock,
            PersonalCapabilityRegistry capabilities,
            PersonalModelCatalog models,
            PersonalModelPreferenceStore modelPreferences,
            PersonalQuestionRecommender questionRecommender,
            MissionRuntimeAccess missionRuntime,
            ArtifactService artifacts,
            Map<String, String> skillBindingReferences,
            String productDigest,
            ResearchFetchEvidenceReader fetchEvidenceReader,
            ToolOutputPreviewPublisher previewPublisher) {
        this.agent = Objects.requireNonNull(agent);
        this.mcp = Objects.requireNonNull(mcp);
        this.clock = Objects.requireNonNull(clock);
        this.capabilities = Objects.requireNonNull(capabilities);
        this.models = Objects.requireNonNull(models);
        this.modelPreferences = Objects.requireNonNull(modelPreferences);
        this.questionRecommender = Objects.requireNonNull(questionRecommender);
        this.missionRuntime = Objects.requireNonNull(missionRuntime);
        this.artifacts = Objects.requireNonNull(artifacts);
        this.skillBindingReferences = Map.copyOf(skillBindingReferences);
        this.productDigest = Objects.requireNonNull(productDigest, "productDigest must not be null");
        this.fetchEvidenceReader = Objects.requireNonNull(fetchEvidenceReader, "fetchEvidenceReader must not be null");
        this.previewPublisher = Objects.requireNonNull(previewPublisher, "previewPublisher must not be null");
        this.mcpToolAliases = mcp.aliases();
    }

    public ArtifactService artifacts() {
        return artifacts;
    }

    public MissionRuntimeAccess missionRuntime() {
        return missionRuntime;
    }

    public ResearchFetchEvidenceReader fetchEvidenceReader() {
        return fetchEvidenceReader;
    }

    public Instant now() {
        return clock.instant();
    }

    public StreamSubscription subscribeToolOutput(String runId, Consumer<ToolOutputPreview> listener) {
        ToolOutputPreviewPublisher.ToolOutputPreviewSubscription subscription = previewPublisher.subscribe(
                new AgentRunId(runId), Objects.requireNonNull(listener, "listener must not be null"));
        return subscription::close;
    }

    public Optional<String> skillBindingReference(String alias) {
        return Optional.ofNullable(skillBindingReferences.get(Objects.requireNonNull(alias)));
    }

    public ConversationView start(String idempotencyKey, String displayName, String message) {
        return start(idempotencyKey, displayName, message, models.defaultModelId());
    }

    public ConversationView start(String idempotencyKey, String displayName, String message, String modelId) {
        return start(idempotencyKey, displayName, message, modelId, List.of());
    }

    public ConversationView start(
            String idempotencyKey, String displayName, String message, String modelId, List<ContentPart> inputs) {
        PersonalModelOption selected = requireModel(modelId);
        PersonalResolvedModelSelection selection = models.resolve(new PersonalModelSelectionRequest(
                selected.id(),
                selected.preferenceSchemaVersion(),
                selected.profileVersion(),
                selected.profileDigest(),
                selected.recommendedPreferences()));
        return start(idempotencyKey, displayName, message, selection, inputs);
    }

    /** Starts with a safe client request; trusted Profile identity is resolved only inside the application. */
    public ConversationView start(
            String idempotencyKey,
            String displayName,
            String message,
            String modelId,
            String preferenceSchemaVersion,
            PersonalModelPreferences preferences,
            List<ContentPart> inputs) {
        PersonalModelOption selected = requireModel(modelId);
        var profile = models.profile(selected.id())
                .orElseThrow(() -> new IllegalArgumentException("MODEL_PROFILE_RESELECTION_REQUIRED"));
        return start(
                idempotencyKey,
                displayName,
                message,
                new PersonalModelSelectionRequest(
                        selected.id(), preferenceSchemaVersion, profile.version(), profile.digest(), preferences),
                inputs);
    }

    public ConversationView start(
            String idempotencyKey,
            String displayName,
            String message,
            PersonalModelSelectionRequest request,
            List<ContentPart> inputs) {
        return start(idempotencyKey, displayName, message, models.resolve(request), inputs);
    }

    private ConversationView start(
            String idempotencyKey,
            String displayName,
            String message,
            PersonalResolvedModelSelection selection,
            List<ContentPart> inputs) {
        PersonalModelOption selected = selection.option();
        requireMediaInput(selected, inputs);
        ConversationRun started = agent.conversations()
                .start(new StartConversationCommand(
                        idempotencyKey, displayName, message, Optional.of(selection.runProfileId()), inputs));
        modelPreferences.create(
                started.record().sessionId().value(),
                PersonalModelPreferenceDraft.from(selection),
                TimePrecision.now(clock));
        return conversation(started);
    }

    public Optional<ConversationView> conversation(String sessionId) {
        return agent.conversations().find(new AgentSessionId(sessionId)).map(this::conversation);
    }

    public MissionModelBinding missionModelBinding(String conversationId) {
        PersonalModelPreference preference = requirePreference(conversationId);
        return models.binding(preference.modelBindingId())
                .orElseThrow(() -> new IllegalStateException("MODEL_SELECTION_REQUIRED"));
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
                .map(this::conversation)
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

    public List<String> recommendQuestions(String sessionId, String runId) {
        RunView completed = run(runId).orElseThrow(() -> new IllegalArgumentException("run is unavailable"));
        if (!completed.conversationId().equals(sessionId) || !"COMPLETED".equals(completed.status())) {
            return List.of();
        }
        List<TurnView> history = turns(sessionId, 100);
        if (history.isEmpty()) return List.of();
        TurnView latest = history.getLast();
        if (!"ASSISTANT".equalsIgnoreCase(latest.role())
                || latest.runId().filter(runId::equals).isEmpty()) {
            return List.of();
        }
        if (recommendedQuestions.size() >= 256 && !recommendedQuestions.containsKey(runId)) {
            recommendedQuestions.keySet().stream().findFirst().ifPresent(recommendedQuestions::remove);
        }
        return recommendedQuestions.computeIfAbsent(
                runId,
                ignored -> questionRecommender.recommend(
                        new AgentRunId(runId),
                        history.stream()
                                .filter(turn -> "USER".equalsIgnoreCase(turn.role())
                                        || "ASSISTANT".equalsIgnoreCase(turn.role()))
                                .map(turn -> new RecommendationTurn(turn.role(), turn.text()))
                                .toList()));
    }

    public ConversationView submit(String sessionId, long expectedRevision, String idempotencyKey, String message) {
        return submit(sessionId, expectedRevision, idempotencyKey, message, List.of());
    }

    public ConversationView submit(
            String sessionId, long expectedRevision, String idempotencyKey, String message, List<ContentPart> inputs) {
        PersonalModelPreference preference = requirePreference(sessionId);
        PersonalModelOption selected = requireModel(preference.modelBindingId());
        PersonalResolvedModelSelection selection = models.resolve(new PersonalModelSelectionRequest(
                selected.id(),
                preference.preferenceSchemaVersion(),
                selected.profileVersion(),
                selected.profileDigest(),
                preference.userPreferences()));
        requireMediaInput(selected, inputs);
        return conversation(agent.conversations()
                .submit(new SubmitConversationTurnCommand(
                        new AgentSessionId(sessionId),
                        expectedRevision,
                        idempotencyKey,
                        message,
                        Optional.of(selection.runProfileId()),
                        inputs)));
    }

    public List<PersonalModelOption> models() {
        return models.available();
    }

    public ModelSelectionView selectModel(
            String sessionId, long expectedRevision, String idempotencyKey, String modelId) {
        PersonalModelOption selected = requireModel(modelId);
        return selectModel(
                sessionId,
                expectedRevision,
                idempotencyKey,
                new PersonalModelSelectionRequest(
                        selected.id(),
                        selected.preferenceSchemaVersion(),
                        selected.profileVersion(),
                        selected.profileDigest(),
                        selected.recommendedPreferences()));
    }

    /**
     * Resolves the current trusted Profile server-side so ordinary clients never receive or echo internal
     * Profile version and digest fields.
     */
    public ModelSelectionView selectModel(
            String sessionId,
            long expectedRevision,
            String idempotencyKey,
            String modelId,
            String preferenceSchemaVersion,
            PersonalModelPreferences preferences) {
        PersonalModelOption selected = requireModel(modelId);
        var profile = models.profile(selected.id())
                .orElseThrow(() -> new IllegalArgumentException("MODEL_PROFILE_RESELECTION_REQUIRED"));
        return selectModel(
                sessionId,
                expectedRevision,
                idempotencyKey,
                new PersonalModelSelectionRequest(
                        selected.id(), preferenceSchemaVersion, profile.version(), profile.digest(), preferences));
    }

    public ModelSelectionView selectModel(
            String sessionId, long expectedRevision, String idempotencyKey, PersonalModelSelectionRequest request) {
        PersonalResolvedModelSelection selection = models.resolve(request);
        PersonalModelOption selected = selection.option();
        agent.conversations()
                .find(new AgentSessionId(sessionId))
                .orElseThrow(() -> new IllegalStateException("CONVERSATION_UNAVAILABLE"));
        PersonalModelPreference changed = modelPreferences.change(
                sessionId,
                expectedRevision,
                PersonalModelPreferenceDraft.from(selection),
                digest(idempotencyKey),
                digest(sessionId + "|" + selected.id() + "|" + selected.preferenceSchemaVersion() + "|"
                        + selection.preferences().digest()),
                TimePrecision.now(clock));
        return new ModelSelectionView(
                selected, selection.preferences(), changed.revision(), true, PersonalSelectionCompatibility.CURRENT);
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
                    snapshot.error().map(error -> error.code().wireCode()),
                    snapshot.terminationReason().map(reason -> reason.code()),
                    snapshot.terminationReason().map(reason -> reason.description()),
                    snapshot.error()
                            .map(error -> new ExecutionErrorView(
                                    error.code().wireCode(),
                                    error.message(),
                                    error.category().name(),
                                    error.retryability().name(),
                                    error.details(),
                                    error.optionalDiagnosticId(),
                                    error.occurredAt())),
                    agent.runs().plan(snapshot.runId()).map(PersonalAssistantApplication::plan),
                    new UsageView(
                            usage.inputTokens(),
                            usage.outputTokens(),
                            Math.addExact(usage.inputTokens(), usage.outputTokens()),
                            usage.cachedInputTokens(),
                            usage.modelCalls(),
                            usage.toolCalls()));
        });
    }

    public RunView recover(String runId) {
        return run(agent.runs().recover(new AgentRunId(runId)).runId().value()).orElseThrow();
    }

    public RunView cancel(String runId) {
        return run(agent.runs()
                        .handle(new AgentRunId(runId))
                        .cancel(io.haifa.agent.runtime.api.RunCancellation.userRequest())
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
                        TimePrecision.now(clock)));
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
        Map<String, ActivityView> activities = new LinkedHashMap<>();
        RunEventCursor cursor = RunEventCursor.beforeFirst(id);
        boolean hasMore;
        do {
            var page = agent.runs().events(id, cursor, 1_000);
            page.items().stream()
                    .map(this::activity)
                    .flatMap(Optional::stream)
                    .forEach(activity -> activities.merge(
                            activity.activityId(), activity, PersonalAssistantApplication::mergeActivity));
            cursor = page.nextCursor();
            hasMore = page.hasMore();
        } while (hasMore);
        List<ActivityView> ordered = activities.values().stream()
                .sorted(Comparator.comparing(PersonalAssistantApplication::activitySortTime)
                        .thenComparingLong(ActivityView::version))
                .toList();
        return ordered.subList(Math.max(0, ordered.size() - limit), ordered.size());
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
            var output = agent.runs().subscribeOutput(id, new RunOutputCursor(after.transientSequence()), event -> {
                if (event.type() != AgentRunOutputEventType.MODEL_ACTIVITY) {
                    listener.onEvent(streamEvent(event));
                }
            });
            return new CompositeStreamSubscription(durable, output);
        } catch (RuntimeException failure) {
            durable.close();
            throw failure;
        }
    }

    public List<MemoryView> memories(int limit) {
        return agent.memories().orElseThrow().list(MemoryListQuery.of(MemoryScopeSpec.user(), limit)).items().stream()
                .map(PersonalAssistantApplication::memory)
                .toList();
    }

    public MemoryView updateMemory(String memoryId, long expectedRevision, String content) {
        return memory(agent.memories().orElseThrow().update(new MemoryId(memoryId), expectedRevision, content));
    }

    public void deleteMemory(String memoryId, long expectedRevision) {
        agent.memories().orElseThrow().delete(new MemoryId(memoryId), expectedRevision);
    }

    public int clearMemories() {
        return agent.memories().orElseThrow().clear(MemoryScopeSpec.user());
    }

    public String productDigest() {
        return productDigest;
    }

    public PersonalCapabilityRegistry capabilities() {
        return capabilities;
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

    private ConversationView conversation(ConversationRecord value) {
        return conversation(value, Optional.empty());
    }

    private ConversationView conversation(ConversationRun value) {
        return conversation(value.record(), Optional.of(value.runId().value()));
    }

    private ConversationView conversation(ConversationRecord value, Optional<String> activeRunId) {
        ModelSelectionView model = modelSelection(value.sessionId().value());
        return new ConversationView(
                value.sessionId().value(),
                value.displayName(),
                value.status().name(),
                activeRunId,
                value.createdAt(),
                value.lastActivityAt(),
                value.revision(),
                model);
    }

    private ModelSelectionView modelSelection(String conversationId) {
        PersonalModelPreference preference = modelPreferences
                .find(conversationId)
                .orElseThrow(() -> new IllegalStateException("MODEL_SELECTION_REQUIRED"));
        PersonalSelectionCompatibility compatibility = models.selectionCompatibility(
                preference.modelBindingId(), preference.preferenceSchemaVersion(), preference.userPreferences());
        PersonalModelOption value = models.optionById(preference.modelBindingId())
                .orElseGet(() -> unavailableModelOption(preference.modelBindingId()));
        boolean available = "AVAILABLE".equals(value.availability());
        return new ModelSelectionView(
                value, preference.userPreferences(), preference.revision(), available, compatibility);
    }

    static PersonalModelOption unavailableModelOption(String modelId) {
        return new PersonalModelOption(
                modelId,
                "unavailable:" + modelId,
                modelId,
                modelId + " (Unavailable)",
                "unavailable",
                "Unavailable Provider",
                "unavailable",
                "Unavailable",
                "UNAVAILABLE",
                "Configured model is no longer available in the catalog",
                Set.of(),
                131072,
                8192,
                "1.0",
                "unavailable",
                "unavailable",
                io.haifa.agent.model.api.ModelProfileStatus.UNVERIFIED,
                java.time.LocalDate.EPOCH,
                new PersonalModelControls(
                        new PersonalModelControls.ResponseModeControl(
                                "responseMode",
                                false,
                                true,
                                List.of(PersonalResponseMode.RECOMMENDED),
                                PersonalResponseMode.RECOMMENDED,
                                "Unavailable",
                                "Configured model is no longer available in the catalog."),
                        new PersonalModelControls.ReasoningEffortControl(
                                "reasoningEffort",
                                false,
                                true,
                                List.of(),
                                null,
                                "Unavailable",
                                "Configured model is no longer available in the catalog."),
                        new PersonalModelControls.ResponseLengthControl(
                                "responseLength",
                                false,
                                true,
                                List.of(PersonalResponseLength.RECOMMENDED),
                                PersonalResponseLength.RECOMMENDED,
                                "Unavailable",
                                "Configured model is no longer available in the catalog."),
                        new PersonalModelControls.ApiStyleControl(
                                "apiStyle",
                                false,
                                true,
                                List.of(modelId),
                                modelId,
                                "Unavailable",
                                "Configured model is no longer available in the catalog.")),
                new PersonalModelPreferences(
                        PersonalResponseMode.RECOMMENDED, Optional.empty(), PersonalResponseLength.RECOMMENDED),
                Optional.empty());
    }

    private PersonalModelPreference requirePreference(String conversationId) {
        return modelPreferences
                .find(conversationId)
                .orElseThrow(() -> new IllegalStateException("MODEL_SELECTION_REQUIRED"));
    }

    private PersonalModelOption requireModel(String modelId) {
        return models.find(Objects.requireNonNull(modelId).trim())
                .orElseThrow(() -> new IllegalStateException("MODEL_SELECTION_REQUIRED"));
    }

    private static String digest(String value) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static TurnView turn(ConversationTurn value) {
        return new TurnView(
                value.messageId(),
                value.role().name(),
                value.runId().map(AgentRunId::value),
                value.sequence(),
                value.text(),
                value.contents().stream()
                        .filter(content ->
                                content instanceof ImageUrlContentPart || content instanceof StoredImageContentPart)
                        .map(PersonalAssistantApplication::image)
                        .toList(),
                value.contents().stream()
                        .filter(StoredAudioContentPart.class::isInstance)
                        .map(StoredAudioContentPart.class::cast)
                        .map(audio -> new AudioView(
                                audio.audioId(), audio.mediaType(), audio.sizeBytes(), audio.originalFilename()))
                        .toList(),
                value.createdAt());
    }

    private static ImageView image(ContentPart value) {
        return switch (value) {
            case ImageUrlContentPart image ->
                new ImageView(
                        "url", Optional.of(image.url().toASCIIString()), Optional.empty(), Optional.empty(), 0, "");
            case StoredImageContentPart image ->
                new ImageView(
                        "upload",
                        Optional.empty(),
                        Optional.of(image.imageId()),
                        Optional.of(image.mediaType()),
                        image.sizeBytes(),
                        image.originalFilename());
            default -> throw new IllegalArgumentException("unsupported image content");
        };
    }

    private static void requireMediaInput(PersonalModelOption model, List<ContentPart> inputs) {
        List<ImageUrlContentPart> urlImages = inputs.stream()
                .filter(ImageUrlContentPart.class::isInstance)
                .map(ImageUrlContentPart.class::cast)
                .toList();
        List<StoredImageContentPart> uploadImages = inputs.stream()
                .filter(StoredImageContentPart.class::isInstance)
                .map(StoredImageContentPart.class::cast)
                .toList();

        int totalImages = urlImages.size() + uploadImages.size();
        if (totalImages > 0) {
            var imageInputOpt = model.imageInput();
            if (imageInputOpt.isEmpty()) {
                throw new IllegalArgumentException("selected model does not support image input");
            }
            var imageInput = imageInputOpt.get();
            if (totalImages > imageInput.maxImagesPerRequest()) {
                throw new IllegalArgumentException("number of images (" + totalImages + ") exceeds maximum allowed ("
                        + imageInput.maxImagesPerRequest() + ")");
            }
            if (!urlImages.isEmpty()) {
                if (!imageInput.allowedSources().contains(io.haifa.agent.model.api.ModelImageSource.URL)
                        || !model.capabilities().contains("IMAGE_URL_INPUT")) {
                    throw new IllegalArgumentException("selected model does not support image URL input");
                }
                for (var urlImg : urlImages) {
                    if (urlImg.url().toASCIIString().length() > imageInput.maxUrlCharacters()) {
                        throw new IllegalArgumentException("image URL length exceeds maximum allowed");
                    }
                }
            }
            if (!uploadImages.isEmpty()) {
                if (!imageInput.allowedSources().contains(io.haifa.agent.model.api.ModelImageSource.UPLOAD)
                        || !model.capabilities().contains("IMAGE_UPLOAD_INPUT")) {
                    throw new IllegalArgumentException("selected model does not support uploaded image input");
                }
                long totalBytes = 0;
                for (var uploadImg : uploadImages) {
                    if (!imageInput.supportedMediaTypes().contains(uploadImg.mediaType())) {
                        throw new IllegalArgumentException("image media type '" + uploadImg.mediaType()
                                + "' is not supported by the selected model");
                    }
                    if (uploadImg.sizeBytes() > imageInput.maxBytesPerItem()) {
                        throw new IllegalArgumentException("image size exceeds maximum allowed per item");
                    }
                    totalBytes += uploadImg.sizeBytes();
                }
                if (totalBytes > imageInput.maxTotalBytes()) {
                    throw new IllegalArgumentException("total image data bytes exceeds request maximum");
                }
            }
        }

        if (inputs.stream().anyMatch(StoredAudioContentPart.class::isInstance)
                && !model.capabilities().contains("AUDIO_INPUT")) {
            throw new IllegalArgumentException("selected model does not support audio input");
        }
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
                value.expiresAt(),
                value.approvalPresentation().map(PersonalAssistantApplication::approvalPresentation));
    }

    private static ApprovalPresentationValue approvalPresentation(ApprovalPresentation value) {
        return new ApprovalPresentationValue(
                value.title(),
                value.purpose(),
                value.contentType(),
                value.content(),
                value.environment().stream()
                        .map(PersonalAssistantApplication::approvalFact)
                        .toList(),
                value.technical().stream()
                        .map(PersonalAssistantApplication::approvalFact)
                        .toList(),
                value.risk());
    }

    private static ApprovalFactValue approvalFact(ApprovalPresentation.Fact fact) {
        return new ApprovalFactValue(fact.label(), fact.value());
    }

    private Optional<ActivityView> activity(AgentRunEvent event) {
        if (event.payload() instanceof RunEventPayloads.ModelLifecycle model) {
            return Optional.of(new ActivityView(
                    "model:" + model.modelCallId(),
                    event.eventId(),
                    Optional.empty(),
                    event.runId().value(),
                    ActivityKind.MODEL,
                    model.modelId(),
                    model.providerId() + " · iteration " + model.iteration() + " · attempt " + model.attempt(),
                    model.status(),
                    Optional.empty(),
                    "STARTED".equals(model.status()) ? Optional.of(event.occurredAt()) : Optional.empty(),
                    terminal(model.status()) ? Optional.of(event.occurredAt()) : Optional.empty(),
                    event.occurredAt(),
                    safeResult(model),
                    Optional.empty(),
                    event.sequence(),
                    Optional.empty()));
        }
        if (!(event.payload() instanceof RunEventPayloads.ToolLifecycle tool)) return Optional.empty();
        return Optional.of(toolActivity(
                event.eventId(), event.runId().value(), event.occurredAt(), event.sequence(), toolKind(tool), tool));
    }

    private ActivityKind toolKind(RunEventPayloads.ToolLifecycle tool) {
        return Set.of(PersonalAssistantProfile.SKILL_LOAD_ALIAS, PersonalAssistantProfile.SKILL_RESOURCE_ALIAS)
                                .contains(tool.displayName())
                        || tool.displayName().startsWith("skill.")
                ? ActivityKind.SKILL
                : mcpToolAliases.contains(tool.displayName())
                                || tool.displayName().startsWith("mcp.")
                        ? ActivityKind.MCP
                        : ActivityKind.TOOL;
    }

    /** Maps one authoritative tool lifecycle fact into the PA activity read model. */
    static ActivityView toolActivity(
            String eventId,
            String runId,
            Instant occurredAt,
            long sequence,
            ActivityKind kind,
            RunEventPayloads.ToolLifecycle tool) {
        return new ActivityView(
                "tool:" + tool.toolCallId(),
                eventId,
                Optional.empty(),
                runId,
                kind,
                tool.displayName(),
                tool.targetSummary(),
                tool.status(),
                "REQUESTED".equals(tool.status()) ? Optional.of(occurredAt) : Optional.empty(),
                "STARTED".equals(tool.status()) ? Optional.of(occurredAt) : Optional.empty(),
                terminal(tool.status()) ? Optional.of(occurredAt) : Optional.empty(),
                occurredAt,
                safeResult(tool),
                Optional.empty(),
                sequence,
                toolDetail(tool));
    }

    private static Optional<ToolDetailView> toolDetail(RunEventPayloads.ToolLifecycle tool) {
        var observation = tool.observation();
        boolean outcomeUnknown = unknownOutcome(tool.status(), tool.reasonCode());
        Optional<String> resultRef = tool.resultRef().isBlank() ? Optional.empty() : Optional.of(tool.resultRef());
        if (observation.isEmpty() && !outcomeUnknown && resultRef.isEmpty()) return Optional.empty();
        var preview = observation.flatMap(RunEventPayloads.ToolObservation::outputPreview);
        return Optional.of(new ToolDetailView(
                preview.map(value -> value.text()),
                preview.map(value -> value.truncated()).orElse(false),
                preview.map(value -> value.byteCount()).orElse(0L),
                preview.map(value -> value.lineCount()).orElse(0L),
                preview.flatMap(value -> value.truncationReason()).map(Enum::name),
                observation.flatMap(RunEventPayloads.ToolObservation::processState),
                observation.flatMap(RunEventPayloads.ToolObservation::exitCode),
                resultRef,
                outcomeUnknown));
    }

    static ActivityView mergeActivity(ActivityView previous, ActivityView next) {
        if (next.version() < previous.version()) return previous;
        return new ActivityView(
                next.activityId(),
                next.eventId(),
                next.parentActivityId().or(() -> previous.parentActivityId()),
                next.runId(),
                next.kind(),
                next.displayName(),
                prefer(next.safeTargetSummary(), previous.safeTargetSummary()),
                next.status(),
                earliest(previous.requestedAt(), next.requestedAt()),
                earliest(previous.startedAt(), next.startedAt()),
                next.completedAt().or(() -> previous.completedAt()),
                next.occurredAt(),
                prefer(next.safeResultSummary(), previous.safeResultSummary()),
                next.interactionRef().or(() -> previous.interactionRef()),
                next.version(),
                next.toolDetail().or(() -> previous.toolDetail()));
    }

    private static Instant activitySortTime(ActivityView activity) {
        return activity.requestedAt().or(() -> activity.startedAt()).orElse(activity.occurredAt());
    }

    private static Optional<Instant> earliest(Optional<Instant> left, Optional<Instant> right) {
        if (left.isEmpty()) return right;
        if (right.isEmpty()) return left;
        Instant leftValue = left.orElseThrow();
        Instant rightValue = right.orElseThrow();
        return Optional.of(leftValue.isBefore(rightValue) ? leftValue : rightValue);
    }

    private static String prefer(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static PlanView plan(io.haifa.agent.runtime.api.AgentPlanView value) {
        return new PlanView(
                value.id(),
                value.objective(),
                value.items().stream()
                        .map(item -> new TodoView(
                                item.id(),
                                item.title(),
                                item.priority(),
                                item.status(),
                                item.startedAt(),
                                item.completedAt()))
                        .toList(),
                value.revision(),
                value.updatedAt());
    }

    private static String safeResult(RunEventPayloads.ToolLifecycle tool) {
        if (unknownOutcome(tool.status(), tool.reasonCode())) {
            return tool.reasonCode().isBlank() ? "Outcome unknown" : tool.reasonCode();
        }
        if ("SUCCEEDED".equals(tool.status())) return "Completed";
        if ("FAILED".equals(tool.status())
                || "DENIED".equals(tool.status())
                || "CANCELLED".equals(tool.status())
                || "TIMEOUT".equals(tool.status())) return tool.reasonCode();
        return "";
    }

    /** Unknown side-effecting outcomes are never projected as a successful result. */
    private static boolean unknownOutcome(String status, String reasonCode) {
        return "OUTCOME_UNKNOWN".equalsIgnoreCase(status)
                || "UNKNOWN_OUTCOME".equalsIgnoreCase(status)
                || "TOOL_OUTCOME_UNKNOWN".equalsIgnoreCase(reasonCode);
    }

    private static String safeResult(RunEventPayloads.ModelLifecycle model) {
        if ("SUCCEEDED".equals(model.status())) {
            return "Input " + model.inputTokens() + " · Output " + model.outputTokens();
        }
        if ("FAILED".equals(model.status())) return model.reasonCode();
        return "";
    }

    private static boolean terminal(String status) {
        return Set.of("SUCCEEDED", "FAILED", "DENIED", "CANCELLED", "TIMEOUT", "OUTCOME_UNKNOWN")
                .contains(status);
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
                    case MODEL_ACTIVITY -> throw new IllegalStateException("model activity is not a PA stream event");
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
                value.revision(),
                value.kind().name(),
                value.subjectKey(),
                value.content(),
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
            long revision,
            ModelSelectionView model) {}

    public record ModelSelectionView(
            PersonalModelOption model,
            PersonalModelPreferences preferences,
            long revision,
            boolean available,
            PersonalSelectionCompatibility selectionCompatibility) {}

    public record TurnView(
            String id,
            String role,
            Optional<String> runId,
            long sequence,
            String text,
            List<ImageView> images,
            List<AudioView> audios,
            Instant createdAt) {}

    public record ImageView(
            String kind,
            Optional<String> url,
            Optional<String> imageId,
            Optional<String> mediaType,
            long sizeBytes,
            String originalFilename) {}

    public record AudioView(String audioId, String mediaType, long sizeBytes, String originalFilename) {}

    public record UsageView(
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long cachedInputTokens,
            long modelCalls,
            long toolCalls) {}

    public record ExecutionErrorView(
            String code,
            String message,
            String category,
            String retryability,
            Map<String, Object> details,
            Optional<String> diagnosticId,
            Instant occurredAt) {}

    public record RunView(
            String id,
            String conversationId,
            String status,
            long version,
            Instant updatedAt,
            Optional<String> output,
            Optional<String> resultSummary,
            Optional<String> errorCode,
            Optional<String> terminationReason,
            Optional<String> terminationDescription,
            Optional<ExecutionErrorView> error,
            Optional<PlanView> plan,
            UsageView usage) {
        public RunView(
                String id,
                String conversationId,
                String status,
                long version,
                Instant updatedAt,
                Optional<String> output,
                Optional<String> resultSummary,
                Optional<String> errorCode,
                Optional<ExecutionErrorView> error,
                Optional<PlanView> plan,
                UsageView usage) {
            this(
                    id,
                    conversationId,
                    status,
                    version,
                    updatedAt,
                    output,
                    resultSummary,
                    errorCode,
                    Optional.empty(),
                    Optional.empty(),
                    error,
                    plan,
                    usage);
        }
    }

    public record PlanView(String id, String objective, List<TodoView> items, long revision, Instant updatedAt) {}

    public record TodoView(
            String id,
            String title,
            String priority,
            String status,
            Optional<Instant> startedAt,
            Optional<Instant> completedAt) {}

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
            Optional<Instant> expiresAt,
            Optional<ApprovalPresentationValue> approvalPresentation) {}

    public record ApprovalFactValue(String label, String value) {}

    public record ApprovalPresentationValue(
            String title,
            String purpose,
            String contentType,
            String content,
            List<ApprovalFactValue> environment,
            List<ApprovalFactValue> technical,
            Optional<String> risk) {}

    public record InteractionReceipt(
            String responseId,
            String interactionId,
            String runId,
            String status,
            String interactionState,
            long revision,
            long runVersion) {}

    public enum ActivityKind {
        MODEL,
        TOOL,
        SKILL,
        MCP
    }

    /**
     * Bounded, display-only tool detail derived from the Runtime observation. It never carries raw
     * arguments, provider payloads or full output, and the authoritative result still lives in the asset chain.
     */
    public record ToolDetailView(
            Optional<String> outputPreview,
            boolean truncated,
            long byteCount,
            long lineCount,
            Optional<String> truncationReason,
            Optional<String> processState,
            Optional<Integer> exitCode,
            Optional<String> resultRef,
            boolean outcomeUnknown) {}

    public record ActivityView(
            String activityId,
            String eventId,
            Optional<String> parentActivityId,
            String runId,
            ActivityKind kind,
            String displayName,
            String safeTargetSummary,
            String status,
            Optional<Instant> requestedAt,
            Optional<Instant> startedAt,
            Optional<Instant> completedAt,
            Instant occurredAt,
            String safeResultSummary,
            Optional<String> interactionRef,
            long version,
            Optional<ToolDetailView> toolDetail) {}

    public record MemoryView(
            String id,
            long revision,
            String kind,
            String subjectKey,
            String content,
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
