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
import io.haifa.agent.memory.api.MemoryCandidateId;
import io.haifa.agent.memory.api.MemoryCandidateStatus;
import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryRef;
import io.haifa.agent.memory.api.MemoryStatus;
import io.haifa.agent.memory.api.MemoryVersion;
import io.haifa.agent.personalassistant.application.mcp.PersonalMcpPlatform;
import io.haifa.agent.personalassistant.application.mission.MissionModelBinding;
import io.haifa.agent.personalassistant.application.mission.MissionRuntimeAccess;
import io.haifa.agent.personalassistant.application.product.PersonalAssistantProfile;
import io.haifa.agent.personalassistant.application.recommendation.PersonalQuestionRecommender;
import io.haifa.agent.personalassistant.application.recommendation.PersonalQuestionRecommender.RecommendationTurn;
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
    private final AutoCloseable executionLifecycle;
    private final MissionRuntimeAccess missionRuntime;
    private final ArtifactService artifacts;
    private final Map<String, String> skillBindingReferences;
    private final ConcurrentMap<String, List<String>> recommendedQuestions = new ConcurrentHashMap<>();

    public PersonalAssistantApplication(
            HaifaAgent agent,
            PersonalMcpPlatform mcp,
            Clock clock,
            PersonalCapabilityRegistry capabilities,
            PersonalModelCatalog models,
            PersonalModelPreferenceStore modelPreferences,
            PersonalQuestionRecommender questionRecommender,
            AutoCloseable executionLifecycle,
            MissionRuntimeAccess missionRuntime,
            ArtifactService artifacts,
            Map<String, String> skillBindingReferences) {
        this.agent = Objects.requireNonNull(agent);
        this.mcp = Objects.requireNonNull(mcp);
        this.clock = Objects.requireNonNull(clock);
        this.capabilities = Objects.requireNonNull(capabilities);
        this.models = Objects.requireNonNull(models);
        this.modelPreferences = Objects.requireNonNull(modelPreferences);
        this.questionRecommender = Objects.requireNonNull(questionRecommender);
        this.executionLifecycle = Objects.requireNonNull(executionLifecycle);
        this.missionRuntime = Objects.requireNonNull(missionRuntime);
        this.artifacts = Objects.requireNonNull(artifacts);
        this.skillBindingReferences = Map.copyOf(skillBindingReferences);
        this.mcpToolAliases = mcp.aliases();
    }

    public ArtifactService artifacts() {
        return artifacts;
    }

    public MissionRuntimeAccess missionRuntime() {
        return missionRuntime;
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
        ConversationRecord started = agent.conversations()
                .start(new StartConversationCommand(
                        idempotencyKey, displayName, message, Optional.of(selection.runProfileId()), inputs));
        modelPreferences.create(
                started.sessionId().value(), PersonalModelPreferenceDraft.from(selection), TimePrecision.now(clock));
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
        ConversationRecord conversation = agent.conversations()
                .find(new AgentSessionId(sessionId))
                .orElseThrow(() -> new IllegalStateException("CONVERSATION_UNAVAILABLE"));
        if (conversation.activeRunId().isPresent()
                || conversation.activeDispatchKey().isPresent()) {
            throw new IllegalStateException("MODEL_SELECTION_ACTIVE_RUN");
        }
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
            executionLifecycle.close();
        } catch (Exception exception) {
            RuntimeException normalized = exception instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("Personal execution resources could not be closed", exception);
            if (failure == null) failure = normalized;
            else failure.addSuppressed(normalized);
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
        ModelSelectionView model = modelSelection(value.sessionId().value());
        return new ConversationView(
                value.sessionId().value(),
                value.displayName(),
                value.status().name(),
                value.activeRunId().map(AgentRunId::value),
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
                .orElseThrow(() -> new IllegalStateException("MODEL_SELECTION_REQUIRED"));
        boolean available = "AVAILABLE".equals(value.availability());
        return new ModelSelectionView(
                value, preference.userPreferences(), preference.revision(), available, compatibility);
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
                value.expiresAt());
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
                    event.sequence()));
        }
        if (event.payload() instanceof RunEventPayloads.ExecutionLifecycle execution) {
            return Optional.of(new ActivityView(
                    "execution:" + execution.executionId(),
                    event.eventId(),
                    Optional.of("tool:" + execution.toolCallId()),
                    event.runId().value(),
                    ActivityKind.TOOL,
                    PersonalAssistantProfile.EXECUTION_TOOL_ALIAS,
                    execution.commandSummary(),
                    execution.status(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(event.occurredAt()),
                    event.occurredAt(),
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
                "tool:" + tool.toolCallId(),
                event.eventId(),
                Optional.empty(),
                event.runId().value(),
                kind,
                tool.displayName(),
                tool.targetSummary(),
                tool.status(),
                "REQUESTED".equals(tool.status()) ? Optional.of(event.occurredAt()) : Optional.empty(),
                "STARTED".equals(tool.status()) ? Optional.of(event.occurredAt()) : Optional.empty(),
                terminal(tool.status()) ? Optional.of(event.occurredAt()) : Optional.empty(),
                event.occurredAt(),
                safeResult(tool),
                Optional.empty(),
                event.sequence()));
    }

    private static ActivityView mergeActivity(ActivityView previous, ActivityView next) {
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
                next.version());
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
        if ("SUCCEEDED".equals(tool.status())) return "Completed";
        if ("FAILED".equals(tool.status()) || "CANCELLED".equals(tool.status())) return tool.reasonCode();
        return "";
    }

    private static String safeResult(RunEventPayloads.ModelLifecycle model) {
        if ("SUCCEEDED".equals(model.status())) {
            return "Input " + model.inputTokens() + " · Output " + model.outputTokens();
        }
        if ("FAILED".equals(model.status())) return model.reasonCode();
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
            Optional<ExecutionErrorView> error,
            Optional<PlanView> plan,
            UsageView usage) {}

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
            Optional<Instant> expiresAt) {}

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
