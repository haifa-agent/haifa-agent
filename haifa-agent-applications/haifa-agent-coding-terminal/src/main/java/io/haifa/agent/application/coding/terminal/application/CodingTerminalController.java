package io.haifa.agent.application.coding.terminal.application;

import io.haifa.agent.application.coding.terminal.event.TerminalEventPump;
import io.haifa.agent.application.coding.terminal.event.TerminalInput;
import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.application.coding.terminal.state.PendingMessage;
import io.haifa.agent.application.coding.terminal.state.TerminalRecovery;
import io.haifa.agent.application.coding.terminal.state.TerminalSelector;
import io.haifa.agent.application.coding.terminal.state.TerminalThemes;
import io.haifa.agent.application.coding.terminal.state.TerminalUiReducer;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.application.project.product.coding.CodingModelOption;
import io.haifa.agent.application.project.product.coding.CodingModelState;
import io.haifa.agent.application.project.product.coding.CodingQueuedMessage;
import io.haifa.agent.application.project.product.coding.CodingSessionCreateOptions;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.application.project.product.coding.CodingShellPlan;
import io.haifa.agent.application.project.product.coding.CodingShellResult;
import io.haifa.agent.application.project.product.coding.CodingWorkspaceView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationClient;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationMethodView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationProviderView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationView;
import io.haifa.agent.application.project.product.coding.client.CodingSessionClient;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.execution.api.ToolOutputPreviewPublisher;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.RunEventSubscription;
import io.haifa.agent.runtime.api.RunOutputCursor;
import io.haifa.agent.runtime.api.RunOutputSubscription;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Single-threaded application controller. Runtime callbacks only enqueue actions. */
public final class CodingTerminalController implements AutoCloseable {
    private static final int PAGE_SIZE = 200;
    private static final int HISTORY_LIMIT = 100;
    private static final int MAX_REPLAY_EVENTS = 2_000;
    private static final int CONTROL_COMPLETION_QUEUE_CAPACITY = 64;
    private static final int COMPLETION_QUEUE_CAPACITY = 256;
    private static final Set<String> RETRYABLE_SESSION_RACES =
            Set.of("ACTIVE_RUN_SETTLED", "ACTIVE_RUN_MISMATCH", "CODING_SESSION_ACTIVE");
    private final ProjectId projectId;
    private final CodingSessionClient client;
    private final CodingAuthenticationClient authentication;
    private final TerminalEventPump pump;
    private final TerminalUiReducer reducer;
    private final Executor controlEffects;
    private final Executor effects;
    private final Executor maintenanceEffects;
    private final ExecutorService ownedControlEffects;
    private final ExecutorService ownedEffects;
    private final ExecutorService ownedMaintenanceEffects;
    private final ArrayBlockingQueue<Runnable> controlCompletions =
            new ArrayBlockingQueue<>(CONTROL_COMPLETION_QUEUE_CAPACITY);
    private final ArrayBlockingQueue<Runnable> effectCompletions = new ArrayBlockingQueue<>(COMPLETION_QUEUE_CAPACITY);
    private final ConcurrentLinkedQueue<AutoCloseable> deferredSubscriptionCloses = new ConcurrentLinkedQueue<>();
    private final AtomicLong completionRequestIds = new AtomicLong();
    private final TerminalCommandRouter commands = new TerminalCommandRouter();
    private final TerminalCompletionProvider completions;
    private TerminalUiState state;
    private RunEventSubscription subscription;
    private RunOutputSubscription outputSubscription;
    private ToolOutputPreviewPublisher.ToolOutputPreviewSubscription previewSubscription;
    private ToolOutputPreviewPublisher previewPublisher = ToolOutputPreviewPublisher.noop();
    private AgentRunId previewRunId;
    private io.haifa.agent.core.run.AgentRunId outputRunId;
    private RunOutputCursor outputCursor = RunOutputCursor.BEFORE_FIRST;
    private boolean awaitingNewSessionMessage;
    private boolean mouseReportingEnabled = true;
    private boolean automaticLastSessionLoadPending;
    private List<CodingSessionSummary> resumeOptions = List.of();
    private List<CodingQueuedMessage> restoreOptions = List.of();
    private List<CodingModelOption> modelOptions = List.of();
    private List<CodingModelOption> modelSelectionOptions = List.of();
    private CodingModelOption inspectedModel;
    private List<CodingModelProviderGroup> modelProviderGroups = List.of();
    private List<CodingAuthenticationView> authenticationOptions = List.of();
    private CodingAuthenticationView selectedAuthenticationConnection;
    private String modelReadinessProviderId = "";
    private List<CodingAuthenticationProviderView> authenticationProviders = List.of();
    private CodingAuthenticationProviderView activeAuthenticationProvider;
    private boolean authenticationInProgress;
    private boolean authenticationCenterLoading;
    private CompletionContext completionContext;
    private CodingShellPlan pendingShellPlan;
    private String preservedDraftBuffer;
    private int preservedDraftCursor;
    private boolean renamingSession;
    private boolean modelSwitchInProgress;

    public enum BailianConfigStep {
        NONE,
        API_KEY,
        WORKSPACE_ID,
        REGION
    }

    private static final java.util.regex.Pattern DNS_LABEL_PATTERN =
            java.util.regex.Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    private String pendingApiKeyProvider;
    private boolean pendingApiKeyIsUpdate;
    private BailianConfigStep bailianStep = BailianConfigStep.NONE;
    private char[] pendingBailianApiKey;
    private String pendingBailianWorkspaceId;
    private String pendingBailianRegion;
    private String existingBailianWorkspaceId;
    private String existingBailianRegion;
    private String pendingNewSessionModelId;
    private RunEventCursor pendingAcknowledgement;
    private boolean acknowledgementInFlight;
    private boolean reconcileInFlight;
    private InteractionHydrationRequest interactionHydrationInFlight;
    private InteractionView activeInteraction;
    private long activeCompletionRequestId;
    private static final long CANCEL_ARMED_TIMEOUT_MILLIS = 5_000L;
    private long cancelArmedAtMillis = 0L;

    public CodingTerminalController(
            ProjectId projectId,
            CodingSessionClient client,
            TerminalEventPump pump,
            TerminalUiReducer reducer,
            TerminalUiState initialState) {
        this(
                projectId,
                client,
                CodingAuthenticationClient.unavailable(),
                pump,
                reducer,
                initialState,
                newEffectExecutor("haifa-coding-terminal-control"),
                newEffectExecutor("haifa-coding-terminal-effects"),
                newEffectExecutor("haifa-coding-terminal-maintenance"),
                true);
    }

    public CodingTerminalController(
            ProjectId projectId,
            CodingSessionClient client,
            CodingAuthenticationClient authentication,
            TerminalEventPump pump,
            TerminalUiReducer reducer,
            TerminalUiState initialState) {
        this(
                projectId,
                client,
                authentication,
                pump,
                reducer,
                initialState,
                newEffectExecutor("haifa-coding-terminal-control"),
                newEffectExecutor("haifa-coding-terminal-effects"),
                newEffectExecutor("haifa-coding-terminal-maintenance"),
                true);
    }

    public CodingTerminalController(
            ProjectId projectId,
            CodingSessionClient client,
            TerminalEventPump pump,
            TerminalUiReducer reducer,
            TerminalUiState initialState,
            Executor effects) {
        this(
                projectId,
                client,
                CodingAuthenticationClient.unavailable(),
                pump,
                reducer,
                initialState,
                effects,
                effects,
                effects,
                false);
    }

    public CodingTerminalController(
            ProjectId projectId,
            CodingSessionClient client,
            CodingAuthenticationClient authentication,
            TerminalEventPump pump,
            TerminalUiReducer reducer,
            TerminalUiState initialState,
            Executor effects) {
        this(projectId, client, authentication, pump, reducer, initialState, effects, effects, effects, false);
    }

    public CodingTerminalController(
            ProjectId projectId,
            CodingSessionClient client,
            TerminalEventPump pump,
            TerminalUiReducer reducer,
            TerminalUiState initialState,
            Executor effects,
            Executor maintenanceEffects) {
        this(
                projectId,
                client,
                CodingAuthenticationClient.unavailable(),
                pump,
                reducer,
                initialState,
                effects,
                effects,
                maintenanceEffects,
                false);
    }

    public CodingTerminalController(
            ProjectId projectId,
            CodingSessionClient client,
            TerminalEventPump pump,
            TerminalUiReducer reducer,
            TerminalUiState initialState,
            Executor controlEffects,
            Executor effects,
            Executor maintenanceEffects) {
        this(
                projectId,
                client,
                CodingAuthenticationClient.unavailable(),
                pump,
                reducer,
                initialState,
                controlEffects,
                effects,
                maintenanceEffects,
                false);
    }

    private CodingTerminalController(
            ProjectId projectId,
            CodingSessionClient client,
            CodingAuthenticationClient authentication,
            TerminalEventPump pump,
            TerminalUiReducer reducer,
            TerminalUiState initialState,
            Executor controlEffects,
            Executor effects,
            Executor maintenanceEffects,
            boolean ownsEffects) {
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.authentication = Objects.requireNonNull(authentication, "authentication must not be null");
        this.pump = Objects.requireNonNull(pump, "pump must not be null");
        this.reducer = Objects.requireNonNull(reducer, "reducer must not be null");
        this.state = Objects.requireNonNull(initialState, "initialState must not be null");
        this.controlEffects = Objects.requireNonNull(controlEffects, "controlEffects must not be null");
        this.effects = Objects.requireNonNull(effects, "effects must not be null");
        this.maintenanceEffects = Objects.requireNonNull(maintenanceEffects, "maintenanceEffects must not be null");
        this.ownedControlEffects = ownsEffects ? (ExecutorService) controlEffects : null;
        this.ownedEffects = ownsEffects ? (ExecutorService) effects : null;
        this.ownedMaintenanceEffects = ownsEffects ? (ExecutorService) maintenanceEffects : null;
        this.completions = new TerminalCompletionProvider(client::logicalPaths);
    }

    private static ExecutorService newEffectExecutor(String threadName) {
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64),
                runnable -> {
                    Thread thread = new Thread(runnable, threadName);
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public boolean mouseReportingEnabled() {
        return mouseReportingEnabled;
    }

    public void initializeMouseReporting(boolean enabled) {
        mouseReportingEnabled = enabled;
    }

    public TerminalUiState state() {
        return state;
    }

    public void attachToolOutputPreviewPublisher(ToolOutputPreviewPublisher publisher) {
        this.previewPublisher = Objects.requireNonNull(publisher, "publisher must not be null");
    }

    /**
     * Starts a normal conversation submission without performing Runtime or storage IO.
     *
     * <p>The terminal adapter can render the cleared editor and optimistic user message before it executes the
     * returned submission on a command worker. Slash commands, shell commands, selectors, and empty input continue
     * through {@link #accept(TerminalInput)} and therefore return an empty result here.
     */
    public Optional<PreparedMessageSubmission> prepareMessageSubmission(TerminalInput input) {
        return prepareMessageSubmission(input, input.text());
    }

    /**
     * Routes on {@code routingText} (what the user sees) while submitting {@code input.text()} (the
     * expanded draft), so text hidden behind an editor placeholder cannot trigger shell or slash
     * command routing.
     */
    public Optional<PreparedMessageSubmission> prepareMessageSubmission(TerminalInput input, String routingText) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(routingText, "routingText must not be null");
        drainEvents();
        if ((input.kind() != TerminalInput.Kind.SUBMIT && input.kind() != TerminalInput.Kind.FOLLOW_UP)
                || renamingSession
                || state.selector().isPresent()
                || input.text().isBlank()
                || routingText.startsWith("!")
                || commands.route(routingText) != TerminalCommand.MESSAGE) {
            return Optional.empty();
        }
        return Optional.of(beginMessageSubmission(input.text(), input.kind() == TerminalInput.Kind.FOLLOW_UP));
    }

    /** Performs only client IO and does not mutate Controller state. */
    public MessageSubmissionResult executeMessageSubmission(PreparedMessageSubmission submission) {
        Objects.requireNonNull(submission, "submission must not be null");
        try {
            CodingSessionView view;
            if (submission.sessionId().isEmpty()) {
                CodingSessionCreateOptions options = submission
                        .initialModelId()
                        .map(CodingSessionCreateOptions::withInitialModel)
                        .orElseGet(CodingSessionCreateOptions::defaults);
                view = client.create(projectId, submission.text(), submission.idempotencyKey(), options);
            } else {
                AgentSessionId sessionId = submission.sessionId().orElseThrow();
                dispatchMessage(
                        sessionId,
                        submission.activeRunId(),
                        submission.text(),
                        submission.followUp(),
                        submission.idempotencyKey(),
                        true);
                view = client.reconcile(sessionId);
            }
            LoadedSession loaded =
                    readSession(view, submission.appliedCursor(), submission.outputRunId(), submission.outputCursor());
            return MessageSubmissionResult.succeeded(submission, loaded);
        } catch (ProjectProductException exception) {
            return MessageSubmissionResult.failed(submission, exception.code());
        } catch (IllegalArgumentException
                | IllegalStateException
                | SecurityException
                | UnsupportedOperationException exception) {
            return MessageSubmissionResult.failed(submission, safeFailureCode(exception));
        }
    }

    /** Applies a completed background submission on the single terminal UI thread. */
    public void completeMessageSubmission(MessageSubmissionResult result) {
        Objects.requireNonNull(result, "result must not be null");
        if (result.failureCode().isPresent()) {
            String code = result.failureCode().orElseThrow();
            apply(new TerminalUiAction.UserMessageRejected(result.submission().idempotencyKey()));
            if (state.editorBuffer().isBlank()) {
                apply(new TerminalUiAction.EditorChanged(
                        result.submission().text(), result.submission().text().length()));
            }
            apply(new TerminalUiAction.RecoverableFailure(code));
            if ("MODEL_AUTHENTICATION_REQUIRED".equals(code) && state.selector().isEmpty()) {
                modelReadinessProviderId = state.session()
                        .map(value -> value.model().model().providerId())
                        .orElse("");
                String providerName = modelReadinessProviderId.isBlank()
                        ? "模型"
                        : AuthenticationProviderPresentation.providerName(
                                modelReadinessProviderId, modelReadinessProviderId);
                apply(new TerminalUiAction.SelectorOpened(new TerminalSelector(
                        "model-readiness",
                        "模型凭据不可用",
                        List.of(
                                "切换模型 (/model)",
                                "重新登录 " + providerName + " (/connect)",
                                "保留草稿并关闭"),
                        0)));
            }
            return;
        }
        try {
            applyLoadedSession(result.loadedSession().orElseThrow());
            if (result.submission().sessionId().isEmpty()) pendingNewSessionModelId = null;
        } catch (ProjectProductException exception) {
            apply(new TerminalUiAction.RecoverableFailure(exception.code()));
        }
    }

    /** Loads an explicitly selected session; interactive session changes use background effects. */
    public void open(AgentSessionId sessionId) {
        automaticLastSessionLoadPending = false;
        applyLoadedSession(readSession(client.open(sessionId), Optional.empty(), null, RunOutputCursor.BEFORE_FIRST));
    }

    /** Starts a bounded startup intent without blocking the terminal event loop. */
    public void start(CodingTerminalStartup startup) {
        Objects.requireNonNull(startup, "startup must not be null");
        try {
            switch (startup.mode()) {
                case EMPTY -> {}
                case SELECTOR -> showResumeOptions(client.list(projectId, 50));
                case AUTO_LAST -> {
                    startAutomaticLastSessionLoad();
                    return;
                }
                case LAST -> {
                    List<CodingSessionSummary> sessions = client.list(projectId, 1);
                    if (sessions.isEmpty()) {
                        apply(new TerminalUiAction.RecoverableFailure("SESSION_LIST_EMPTY"));
                        return;
                    }
                    openForResume(sessions.getFirst().sessionId(), startup.prompt());
                }
                case SESSION -> openForResume(startup.sessionId().orElseThrow(), startup.prompt());
            }
            offerAuthenticationOnboarding();
        } catch (ProjectProductException exception) {
            apply(new TerminalUiAction.RecoverableFailure(exception.code()));
        } catch (IllegalArgumentException
                | IllegalStateException
                | SecurityException
                | UnsupportedOperationException exception) {
            apply(new TerminalUiAction.RecoverableFailure(safeFailureCode(exception)));
        }
    }

    private void startAutomaticLastSessionLoad() {
        automaticLastSessionLoadPending = true;
        apply(new TerminalUiAction.StatusChanged("Loading most recent session"));
        submitEffect(
                () -> {
                    List<CodingSessionSummary> sessions = client.list(projectId, 1);
                    Optional<LoadedSession> loaded = sessions.isEmpty()
                            ? Optional.empty()
                            : Optional.of(readSession(
                                    client.open(sessions.getFirst().sessionId()),
                                    Optional.empty(),
                                    null,
                                    RunOutputCursor.BEFORE_FIRST));
                    return () -> completeAutomaticLastSessionLoad(loaded);
                },
                this::completeAutomaticLastSessionLoadFailure);
    }

    private void completeAutomaticLastSessionLoad(Optional<LoadedSession> loaded) {
        if (!automaticLastSessionLoadCanApply()) return;
        automaticLastSessionLoadPending = false;
        if (loaded.isPresent()) {
            applyLoadedSession(loaded.orElseThrow());
        } else {
            apply(new TerminalUiAction.StatusChanged("Idle"));
        }
        offerAuthenticationOnboarding();
    }

    private void completeAutomaticLastSessionLoadFailure(String code) {
        if (!automaticLastSessionLoadCanApply()) return;
        automaticLastSessionLoadPending = false;
        apply(new TerminalUiAction.RecoverableFailure(code));
        offerAuthenticationOnboarding();
    }

    private boolean automaticLastSessionLoadCanApply() {
        return automaticLastSessionLoadPending
                && state.session().isEmpty()
                && state.selector().isEmpty()
                && !awaitingNewSessionMessage;
    }

    private void offerAuthenticationOnboarding() {
        if (state.selector().isEmpty() && authentication.connectionRequired()) {
            apply(new TerminalUiAction.StatusChanged("尚未连接模型，先选择一个提供商"));
            openConnectionCenter("");
        }
    }

    private void openConnectionCenter(String argument) {
        String normalized = argument == null ? "" : argument.strip();
        if (!normalized.isEmpty()) {
            routeConnectionArgument(normalized);
            return;
        }
        if (authenticationCenterLoading || state.selector().isPresent()) {
            return;
        }
        authenticationCenterLoading = true;
        apply(new TerminalUiAction.StatusChanged("正在加载模型提供商"));
        submitEffect(
                () -> {
                    List<CodingAuthenticationProviderView> providers = authentication.providers();
                    return () -> showConnectionCenter(providers);
                },
                code -> {
                    authenticationCenterLoading = false;
                    apply(new TerminalUiAction.RecoverableFailure(code));
                });
    }

    private void showConnectionCenter(List<CodingAuthenticationProviderView> providers) {
        authenticationCenterLoading = false;
        authenticationProviders = List.copyOf(providers);
        if (authenticationProviders.isEmpty()) {
            apply(new TerminalUiAction.RecoverableFailure("AUTH_PROVIDER_LIST_EMPTY"));
            return;
        }
        authenticationProviders = authenticationProviders.stream()
                .sorted(java.util.Comparator
                        .comparingInt((CodingAuthenticationProviderView value) ->
                                value.category() == CodingAuthenticationProviderView.Category.SUBSCRIPTION ? 0 : 1)
                        .thenComparing(value -> AuthenticationProviderPresentation.providerName(value)))
                .toList();
        List<String> options =
                authenticationProviders.stream().map(AuthenticationProviderPresentation::providerOption).toList();
        int selected = java.util.stream.IntStream.range(0, authenticationProviders.size())
                .filter(index -> authenticationProviders.get(index).current()
                        || authenticationProviders.get(index).connected())
                .findFirst()
                .orElse(0);
        apply(new TerminalUiAction.SelectorOpened(
                new TerminalSelector("auth-provider", "连接模型", options, selected)));
    }

    private void routeConnectionArgument(String argument) {
        String[] parts = argument.split("\\s+");
        String provider = parts[0].toLowerCase(java.util.Locale.ROOT);
        if ("api".equals(provider)) {
            int separator = argument.indexOf(' ');
            beginApiKeyInput(separator < 0
                    ? authentication.apiKeyProviderId()
                    : argument.substring(separator + 1).strip());
            return;
        }
        if ("openai".equals(provider) || "chatgpt".equals(provider) || "codex".equals(provider)) {
            if (parts.length > 1 && "browser".equals(parts[1])) {
                startCodexLogin();
            } else if (parts.length > 1 && "device".equals(parts[1])) {
                startCodexDeviceLogin();
            } else {
                openProviderMethods("openai-codex");
            }
            return;
        }
        if ("antigravity".equals(provider) || "google-antigravity".equals(provider)) {
            startAntigravityLogin();
            return;
        }
        openProviderMethods(provider);
    }

    private void openProviderMethods(String providerId) {
        if (authenticationCenterLoading) {
            apply(new TerminalUiAction.StatusChanged("正在加载模型提供商"));
            return;
        }
        if (authenticationProviders.isEmpty()) {
            openConnectionCenter("");
            return;
        }
        Optional<CodingAuthenticationProviderView> found = authenticationProviders.stream()
                .filter(value -> value.providerId().equalsIgnoreCase(providerId))
                .findFirst();
        if (found.isEmpty()) {
            apply(new TerminalUiAction.RecoverableFailure("AUTH_PROVIDER_INVALID"));
            return;
        }
        openProviderMethods(found.orElseThrow());
    }

    private void openProviderMethods(CodingAuthenticationProviderView provider) {
        List<CodingAuthenticationMethodView> methods = provider.methods().stream()
                .filter(CodingAuthenticationMethodView::available)
                .toList();
        if (methods.isEmpty()) {
            String reason = provider.methods().stream()
                    .map(CodingAuthenticationMethodView::unavailableReason)
                    .flatMap(Optional::stream)
                    .findFirst()
                    .orElse("AUTH_METHOD_UNSUPPORTED");
            apply(new TerminalUiAction.RecoverableFailure(reason));
            return;
        }
        activeAuthenticationProvider = provider;
        if (methods.size() == 1) {
            startAuthenticationMethod(provider, methods.getFirst());
            return;
        }
        apply(new TerminalUiAction.SelectorOpened(new TerminalSelector(
                "auth-method",
                "连接 " + AuthenticationProviderPresentation.providerName(provider),
                methods.stream()
                        .map(method -> AuthenticationProviderPresentation.methodLabel(method) + " · "
                                + AuthenticationProviderPresentation.methodDescription(method))
                        .toList(),
                0)));
    }

    private void startAuthenticationMethod(
            CodingAuthenticationProviderView provider, CodingAuthenticationMethodView method) {
        if (!method.available()) {
            apply(new TerminalUiAction.RecoverableFailure(
                    method.unavailableReason().orElse("AUTH_METHOD_UNSUPPORTED")));
            return;
        }
        if (method.type() == CodingAuthenticationMethodView.Type.API_KEY) {
            beginApiKeyInput(provider.providerId());
            return;
        }
        switch (provider.providerId()) {
            case "openai-codex" -> {
                if ("device".equals(method.id())) {
                    startCodexDeviceLogin();
                } else {
                    startCodexLogin();
                }
            }
            case "google-antigravity" -> startAntigravityLogin();
            default -> apply(new TerminalUiAction.RecoverableFailure("AUTH_METHOD_UNSUPPORTED"));
        }
    }

    private void openForResume(AgentSessionId sessionId, Optional<String> prompt) {
        open(sessionId);
        if (prompt.isEmpty()) return;
        String text = prompt.orElseThrow();
        if (state.currentRunId().isPresent()) {
            apply(new TerminalUiAction.EditorChanged(text, text.length()));
            apply(new TerminalUiAction.RecoverableFailure("RUN_TAKEOVER_NOT_SUPPORTED"));
            return;
        }
        PreparedMessageSubmission submission = beginMessageSubmission(text, false);
        completeMessageSubmission(executeMessageSubmission(submission));
    }

    public void drainEvents() {
        try {
            drainEventsGuarded();
        } catch (ProjectProductException exception) {
            apply(new TerminalUiAction.RecoverableFailure(exception.code()));
        }
    }

    private void drainEventsGuarded() {
        drainEffectCompletions();
        boolean reconcile = pump.consumeOverflow();
        for (TerminalUiAction action : pump.drain(PAGE_SIZE)) {
            apply(action);
            interactionHydration(action).ifPresent(this::scheduleInteractionHydration);
        }
        if (subscription != null
                && subscription.closed()
                && state.currentRunId().isPresent()) {
            reconcile = true;
        }
        if (reconcile && state.session().isPresent()) {
            scheduleReconcile();
        }
        schedulePendingCursorAcknowledgement();
        if (state.cancelArmed()) {
            if (!hasActiveRun()
                    || (cancelArmedAtMillis > 0
                            && (System.currentTimeMillis() - cancelArmedAtMillis) >= CANCEL_ARMED_TIMEOUT_MILLIS)) {
                cancelArmedAtMillis = 0L;
                apply(new TerminalUiAction.CancelArmedChanged(false));
            }
        }
        drainEffectCompletions();
    }

    private static Optional<InteractionHydrationRequest> interactionHydration(TerminalUiAction action) {
        if (!(action instanceof TerminalUiAction.RunEventReceived received)
                || !(received.event().payload() instanceof RunEventPayloads.InteractionLifecycle lifecycle)) {
            return Optional.empty();
        }
        if (!lifecycle.state().equals("PENDING") && !lifecycle.state().equals("REQUESTED")) {
            return Optional.empty();
        }
        return Optional.of(new InteractionHydrationRequest(received.event().runId(), lifecycle.requestId()));
    }

    private void scheduleInteractionHydration(InteractionHydrationRequest request) {
        if (request.equals(interactionHydrationInFlight)
                || (activeInteraction != null
                        && activeInteraction.runId().equals(request.runId())
                        && activeInteraction.requestId().value().equals(request.requestId()))
                || state.currentRunId().filter(request.runId()::equals).isEmpty()) {
            return;
        }
        interactionHydrationInFlight = request;
        submitControlEffect(
                () -> {
                    Optional<InteractionView> interaction = client.pendingInteraction(request.runId());
                    return () -> completeInteractionHydration(request, interaction);
                },
                code -> {
                    if (request.equals(interactionHydrationInFlight)) interactionHydrationInFlight = null;
                    apply(new TerminalUiAction.RecoverableFailure(code));
                });
    }

    private void completeInteractionHydration(
            InteractionHydrationRequest request, Optional<InteractionView> interaction) {
        if (!request.equals(interactionHydrationInFlight)) return;
        interactionHydrationInFlight = null;
        if (state.currentRunId().filter(request.runId()::equals).isEmpty() || !interactionStillPending(request)) return;
        interaction
                .filter(value -> value.requestId().value().equals(request.requestId()))
                .filter(value -> value.state() == io.haifa.agent.runtime.api.InteractionState.PENDING)
                .ifPresent(this::openInteractionSelector);
    }

    private boolean interactionStillPending(InteractionHydrationRequest request) {
        return state.transcript().stream()
                .filter(item -> item.id().equals("interaction-" + request.requestId()))
                .map(item -> item.status().toUpperCase(java.util.Locale.ROOT))
                .anyMatch(status -> status.equals("PENDING") || status.equals("REQUESTED"));
    }

    private void submitControlEffect(Supplier<Runnable> work, Consumer<String> failure) {
        submitEffect(controlEffects, controlCompletions, work, failure);
    }

    private void submitEffect(Supplier<Runnable> work, Consumer<String> failure) {
        submitEffect(effects, effectCompletions, work, failure);
    }

    private void submitMaintenanceEffect(Supplier<Runnable> work, Consumer<String> failure) {
        submitEffect(maintenanceEffects, effectCompletions, work, failure);
    }

    private void submitEffect(
            Executor executor,
            ArrayBlockingQueue<Runnable> completionQueue,
            Supplier<Runnable> work,
            Consumer<String> failure) {
        try {
            executor.execute(() -> {
                Runnable completion;
                try {
                    completion = Objects.requireNonNull(work.get(), "effect completion must not be null");
                } catch (ProjectProductException exception) {
                    completion = () -> failure.accept(exception.code());
                } catch (IllegalArgumentException
                        | IllegalStateException
                        | SecurityException
                        | UnsupportedOperationException exception) {
                    String code = safeFailureCode(exception);
                    completion = () -> failure.accept(code);
                } catch (RuntimeException exception) {
                    completion = () -> failure.accept("OPERATION_REJECTED");
                }
                enqueueCompletion(completionQueue, completion);
            });
        } catch (RejectedExecutionException exception) {
            failure.accept("TERMINAL_BACKGROUND_UNAVAILABLE");
        }
        // Direct executors are useful for deterministic state tests. Production executors
        // return immediately, so this remains an in-memory, non-blocking drain.
        drainEffectCompletions();
    }

    private void enqueueCompletion(ArrayBlockingQueue<Runnable> completionQueue, Runnable completion) {
        try {
            completionQueue.put(completion);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void drainEffectCompletions() {
        int remaining = PAGE_SIZE - drainCompletions(controlCompletions, PAGE_SIZE);
        drainCompletions(effectCompletions, remaining);
    }

    private static int drainCompletions(ArrayBlockingQueue<Runnable> completions, int limit) {
        int drained = 0;
        while (drained < limit) {
            Runnable completion = completions.poll();
            if (completion == null) break;
            completion.run();
            drained++;
        }
        return drained;
    }

    public void accept(TerminalInput input) {
        try {
            acceptGuarded(input);
        } catch (ProjectProductException exception) {
            if ((input.kind() == TerminalInput.Kind.SUBMIT || input.kind() == TerminalInput.Kind.FOLLOW_UP)
                    && !input.text().isBlank()) {
                apply(new TerminalUiAction.EditorChanged(input.text(), input.cursor()));
            }
            apply(new TerminalUiAction.RecoverableFailure(exception.code()));
        } catch (IllegalArgumentException
                | IllegalStateException
                | SecurityException
                | UnsupportedOperationException exception) {
            String code = exception.getMessage();
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{2,63}")) {
                code = "OPERATION_REJECTED";
            }
            apply(new TerminalUiAction.RecoverableFailure(code));
        }
    }

    private void preserveDraft() {
        if (preservedDraftBuffer == null) {
            preservedDraftBuffer = state.editorBuffer();
            preservedDraftCursor = state.editorCursor();
        }
    }

    public Optional<String> preservedDraft() {
        return Optional.ofNullable(preservedDraftBuffer);
    }

    public boolean isRenamingSession() {
        return renamingSession;
    }

    private void restorePreservedDraft() {
        if (preservedDraftBuffer != null) {
            String text = preservedDraftBuffer;
            int cursor = preservedDraftCursor;
            preservedDraftBuffer = null;
            preservedDraftCursor = 0;
            apply(new TerminalUiAction.EditorChanged(text, cursor));
        }
    }

    private void acceptGuarded(TerminalInput input) {
        drainEvents();
        if (input.kind() == TerminalInput.Kind.TICK) {
            return;
        }
        if (renamingSession) {
            if (input.kind() == TerminalInput.Kind.CANCEL_OR_CLOSE || input.kind() == TerminalInput.Kind.INTERRUPT) {
                renamingSession = false;
                restorePreservedDraft();
                apply(new TerminalUiAction.StatusChanged("Rename cancelled"));
                return;
            }
            if (input.kind() == TerminalInput.Kind.SUBMIT) {
                renamingSession = false;
                String newName = input.text().strip();
                restorePreservedDraft();
                if (newName.isBlank()) {
                    apply(new TerminalUiAction.StatusChanged("Rename cancelled"));
                    return;
                }
                executeRename(newName);
                return;
            }
            if (input.kind() == TerminalInput.Kind.EDITOR_CHANGED) {
                apply(new TerminalUiAction.EditorChanged(input.text(), input.cursor()));
                return;
            }
            return;
        }
        if (input.kind() == TerminalInput.Kind.EXECUTE_COMMAND) {
            if (state.selector().isPresent()) {
                return;
            }
            executeNonDraftCommand(input.text());
            return;
        }
        if (input.kind() == TerminalInput.Kind.EDITOR_CHANGED) {
            apply(new TerminalUiAction.EditorChanged(input.text(), input.cursor()));
            return;
        }
        if (input.kind() == TerminalInput.Kind.COMPLETION_REQUESTED) {
            openCompletionSelector(input.text(), input.cursor());
            return;
        }
        if (input.kind() == TerminalInput.Kind.COMMAND_PALETTE) {
            if (state.selector().isPresent()) {
                return;
            }
            openCommandSelector();
            return;
        }
        if (input.kind() == TerminalInput.Kind.CYCLE_MODEL_NEXT) {
            if (state.selector().isPresent()) {
                return;
            }
            cycleModel(1);
            return;
        }
        if (input.kind() == TerminalInput.Kind.CYCLE_MODEL_PREVIOUS) {
            if (state.selector().isPresent()) {
                return;
            }
            cycleModel(-1);
            return;
        }
        if (input.kind() == TerminalInput.Kind.CANCEL_OR_CLOSE) {
            if (state.selector().isPresent()) {
                discardPendingShell();
                apply(new TerminalUiAction.SelectorClosed());
                completionContext = null;
                restorePreservedDraft();
                return;
            }
            if (authenticationInProgress) {
                cancelAuthentication();
                return;
            }
            if (hasActiveRun()) {
                long now = System.currentTimeMillis();
                if (state.cancelArmed() && (now - cancelArmedAtMillis) <= CANCEL_ARMED_TIMEOUT_MILLIS) {
                    cancelArmedAtMillis = 0L;
                    apply(new TerminalUiAction.CancelArmedChanged(false));
                    cancelCurrentRunIfPresent();
                } else {
                    cancelArmedAtMillis = now;
                    apply(new TerminalUiAction.CancelArmedChanged(true));
                    CompletableFuture.delayedExecutor(CANCEL_ARMED_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                            .execute(() -> enqueueCompletion(controlCompletions, () -> {
                                if (state.cancelArmed()
                                        && (System.currentTimeMillis() - cancelArmedAtMillis)
                                                >= CANCEL_ARMED_TIMEOUT_MILLIS) {
                                    cancelArmedAtMillis = 0L;
                                    apply(new TerminalUiAction.CancelArmedChanged(false));
                                }
                            }));
                }
                return;
            }
            if (cancelCurrentRunIfPresent()) {
                return;
            }
            return;
        }
        if (input.kind() == TerminalInput.Kind.TOGGLE_EXPANSION) {
            state.transcript().stream()
                    .filter(io.haifa.agent.application.coding.terminal.state.TranscriptItem::toggleable)
                    .reduce((first, second) -> second)
                    .ifPresent(value -> apply(new TerminalUiAction.ToggleExpanded(value.id())));
            return;
        }
        if (state.selector().isPresent()) {
            acceptSelector(input);
            return;
        }
        if (input.kind() == TerminalInput.Kind.EOF) {
            if (state.currentRunId().isPresent()) {
                apply(new TerminalUiAction.SelectorOpened(new TerminalSelector(
                        "active-exit", "Active Run", List.of("Keep Run recoverable and quit", "Return to editor"), 1)));
            } else {
                apply(new TerminalUiAction.ExitRequested());
            }
            return;
        }
        if (input.kind() == TerminalInput.Kind.INTERRUPT) {
            if (!input.text().isBlank()) {
                apply(new TerminalUiAction.EditorChanged("", 0));
            } else if (authenticationInProgress) {
                cancelAuthentication();
            } else if (!cancelCurrentRunIfPresent()) {
                apply(new TerminalUiAction.ExitRequested());
            }
            return;
        }
        if (input.kind() == TerminalInput.Kind.RESTORE) {
            openRestoreSelector();
            return;
        }
        if (input.kind() == TerminalInput.Kind.SELECT_PREVIOUS
                || input.kind() == TerminalInput.Kind.SELECT_NEXT
                || input.kind() == TerminalInput.Kind.NAVIGATE_BACK) {
            return;
        }
        submitText(input.text(), input.kind() == TerminalInput.Kind.FOLLOW_UP);
    }

    private void submitText(String text, boolean followUp) {
        if (text.isBlank()) {
            return;
        }
        if (text.startsWith("!")) {
            shell(text);
            return;
        }
        TerminalCommand command = commands.route(text);
        if (command != TerminalCommand.MESSAGE) {
            apply(new TerminalUiAction.EditorChanged("", 0));
            command(command, text);
            return;
        }
        PreparedMessageSubmission submission = beginMessageSubmission(text, followUp);
        submitEffect(
                () -> {
                    MessageSubmissionResult result = executeMessageSubmission(submission);
                    return () -> completeMessageSubmission(result);
                },
                code -> apply(new TerminalUiAction.RecoverableFailure(code)));
    }

    private PreparedMessageSubmission beginMessageSubmission(String text, boolean followUp) {
        String key = UUID.randomUUID().toString();
        Optional<AgentSessionId> sessionId =
                state.session().map(value -> value.summary().sessionId());
        Optional<AgentRunId> activeRunId = state.currentRunId();
        Optional<String> initialModelId = Optional.empty();
        if (awaitingNewSessionMessage || sessionId.isEmpty()) {
            awaitingNewSessionMessage = false;
            sessionId = Optional.empty();
            activeRunId = Optional.empty();
            initialModelId = Optional.ofNullable(pendingNewSessionModelId);
        }
        apply(new TerminalUiAction.EditorChanged("", 0));
        apply(new TerminalUiAction.UserMessageCommitted(key, text));
        apply(new TerminalUiAction.StatusChanged("Submitting"));
        return new PreparedMessageSubmission(
                text,
                followUp,
                key,
                sessionId,
                activeRunId,
                initialModelId,
                state.appliedCursor(),
                outputRunId,
                outputCursor);
    }

    private void shell(String input) {
        CodingSessionView current = requireCurrentSession();
        boolean includeInContext = !input.startsWith("!!");
        int prefix = includeInContext ? 1 : 2;
        String command = input.substring(prefix).strip();
        apply(new TerminalUiAction.EditorChanged("", 0));
        apply(new TerminalUiAction.StatusChanged("Checking shell command"));
        submitEffect(
                () -> {
                    CodingShellPlan plan = client.planShell(current.summary().sessionId(), command, includeInContext);
                    return () -> completeShellPlan(plan);
                },
                code -> apply(new TerminalUiAction.RecoverableFailure(code)));
    }

    private void completeShellPlan(CodingShellPlan plan) {
        if (plan.state() == CodingShellPlan.State.DENIED) {
            apply(new TerminalUiAction.RecoverableFailure(plan.reasonCode()));
            return;
        }
        if (plan.state() == CodingShellPlan.State.APPROVAL_REQUIRED) {
            pendingShellPlan = plan;
            apply(new TerminalUiAction.SelectorOpened(new TerminalSelector(
                    "shell-approval",
                    "Run governed shell command?",
                    List.of("Approve once: " + plan.safeCommand(), "Deny"),
                    1)));
            return;
        }
        scheduleShellExecution(plan, false);
    }

    private void scheduleShellExecution(CodingShellPlan plan, boolean approved) {
        apply(new TerminalUiAction.StatusChanged("Running shell command"));
        submitEffect(
                () -> {
                    CodingShellResult result = client.executeShell(plan.token(), approved);
                    return () -> completeShell(plan, result);
                },
                code -> apply(new TerminalUiAction.RecoverableFailure(code)));
    }

    private void completeShell(CodingShellPlan plan, CodingShellResult result) {
        String prefix = plan.includeInContext() ? "!" : "!!";
        apply(new TerminalUiAction.ShellCompleted(
                prefix + plan.safeCommand(), result.safeSummary(), result.processState()));
        apply(new TerminalUiAction.StatusChanged(
                result.includedInContext()
                        ? "Shell result added to Session context"
                        : "Shell result excluded from model context"));
    }

    private void dispatchMessage(
            AgentSessionId sessionId,
            Optional<AgentRunId> activeRunId,
            String text,
            boolean followUp,
            String key,
            boolean retrySessionRace) {
        try {
            if (activeRunId.isPresent()) {
                if (followUp) {
                    client.enqueueFollowUp(sessionId, activeRunId.orElseThrow(), text, key);
                } else {
                    client.steer(sessionId, activeRunId.orElseThrow(), text, key);
                }
            } else {
                client.submit(sessionId, text, key);
            }
        } catch (ProjectProductException exception) {
            if (!retrySessionRace || !RETRYABLE_SESSION_RACES.contains(exception.code())) {
                throw exception;
            }
            CodingSessionView reconciled = client.reconcile(sessionId);
            dispatchMessage(sessionId, reconciled.activeRun().map(value -> value.runId()), text, followUp, key, false);
        }
    }

    private static String safeFailureCode(RuntimeException exception) {
        String code = exception.getMessage();
        return code != null && code.matches("[A-Z][A-Z0-9_]{2,63}") ? code : "OPERATION_REJECTED";
    }

    public record PreparedMessageSubmission(
            String text,
            boolean followUp,
            String idempotencyKey,
            Optional<AgentSessionId> sessionId,
            Optional<AgentRunId> activeRunId,
            Optional<String> initialModelId,
            Optional<RunEventCursor> appliedCursor,
            AgentRunId outputRunId,
            RunOutputCursor outputCursor) {
        public PreparedMessageSubmission {
            text = Objects.requireNonNull(text, "text must not be null");
            idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
            sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
            activeRunId = Objects.requireNonNull(activeRunId, "activeRunId must not be null");
            initialModelId = Objects.requireNonNull(initialModelId, "initialModelId must not be null");
            appliedCursor = Objects.requireNonNull(appliedCursor, "appliedCursor must not be null");
            outputCursor = Objects.requireNonNull(outputCursor, "outputCursor must not be null");
        }
    }

    public record MessageSubmissionResult(
            PreparedMessageSubmission submission, Optional<LoadedSession> loadedSession, Optional<String> failureCode) {
        public MessageSubmissionResult {
            submission = Objects.requireNonNull(submission, "submission must not be null");
            loadedSession = Objects.requireNonNull(loadedSession, "loadedSession must not be null");
            failureCode = Objects.requireNonNull(failureCode, "failureCode must not be null");
            if (loadedSession.isPresent() == failureCode.isPresent()) {
                throw new IllegalArgumentException("submission result must contain exactly one outcome");
            }
        }

        static MessageSubmissionResult succeeded(PreparedMessageSubmission submission, LoadedSession loadedSession) {
            return new MessageSubmissionResult(submission, Optional.of(loadedSession), Optional.empty());
        }

        static MessageSubmissionResult failed(PreparedMessageSubmission submission, String failureCode) {
            return new MessageSubmissionResult(submission, Optional.empty(), Optional.of(failureCode));
        }
    }

    private void command(TerminalCommand command, String rawInput) {
        String argument = commandArgument(rawInput);
        switch (command) {
            case NEW -> {
                detachSubscriptions();
                awaitingNewSessionMessage = true;
                pendingNewSessionModelId = null;
                apply(new TerminalUiAction.StatusChanged("New session: enter the first message"));
            }
            case RESUME -> {
                apply(new TerminalUiAction.StatusChanged("Searching sessions"));
                submitEffect(
                        () -> {
                            List<CodingSessionSummary> found = client.search(projectId, argument, 50);
                            return () -> showResumeOptions(found);
                        },
                        code -> apply(new TerminalUiAction.RecoverableFailure(code)));
            }
            case RENAME -> {
                if (state.session().isEmpty()) {
                    apply(new TerminalUiAction.StatusChanged("No active session to rename"));
                    return;
                }
                if (argument.isBlank()) {
                    preserveDraft();
                    renamingSession = true;
                    String currentTitle =
                            state.session().orElseThrow().summary().displayName();
                    apply(new TerminalUiAction.EditorChanged(currentTitle, currentTitle.length()));
                    apply(new TerminalUiAction.StatusChanged("Rename session · Enter to confirm · Esc to cancel"));
                    return;
                }
                executeRename(argument);
            }
            case ARCHIVE -> {
                requireCurrentSession();
                apply(new TerminalUiAction.SelectorOpened(new TerminalSelector(
                        "archive-session", "Archive current session?", List.of("Archive session", "Cancel"), 1)));
            }
            case DELETE -> {
                requireCurrentSession();
                apply(new TerminalUiAction.SelectorOpened(new TerminalSelector(
                        "delete-session", "Delete current session?", List.of("Delete session", "Cancel"), 1)));
            }
            case RELOAD -> {
                apply(new TerminalUiAction.StatusChanged("Reloading resources"));
                submitEffect(
                        () -> {
                            List<String> resources = client.reloadResources();
                            return () -> {
                                apply(new TerminalUiAction.ResourcesChanged(resources));
                                apply(new TerminalUiAction.StatusChanged("Resources reloaded for future new Runs"));
                            };
                        },
                        code -> apply(new TerminalUiAction.RecoverableFailure(code)));
            }
            case COMPACT -> {
                CodingSessionView current = requireCurrentSession();
                apply(new TerminalUiAction.StatusChanged("Compacting session context"));
                submitEffect(
                        () -> {
                            CodingSessionView reconciled =
                                    client.reconcile(current.summary().sessionId());
                            var result = client.compact(reconciled.summary().sessionId(), argument);
                            return () -> {
                                apply(new TerminalUiAction.ContextChanged(result.safeIndicator()));
                                apply(new TerminalUiAction.StatusChanged("Session context compacted"));
                            };
                        },
                        code -> apply(new TerminalUiAction.RecoverableFailure(code)));
            }
            case EXPORT -> {
                CodingSessionView current = requireCurrentSession();
                apply(new TerminalUiAction.StatusChanged("Exporting session"));
                submitEffect(
                        () -> {
                            CodingSessionView reconciled =
                                    client.reconcile(current.summary().sessionId());
                            var exported = client.export(reconciled.summary().sessionId(), argument);
                            return () -> {
                                apply(new TerminalUiAction.ExportCompleted(
                                        exported.logicalPath(), exported.messageCount()));
                                apply(new TerminalUiAction.StatusChanged("Session exported"));
                            };
                        },
                        code -> apply(new TerminalUiAction.RecoverableFailure(code)));
            }
            case CONNECT -> openConnectionCenter(argument);
            case LOGIN_REMOVED -> apply(new TerminalUiAction.StatusChanged("登录命令已改为 /connect"));
            case ACCOUNT -> loadAuthenticationOptions(false);
            case LOGOUT -> loadAuthenticationOptions(true);
            case MODEL -> openModelSelector(argument);
            case SETTINGS ->
                apply(new TerminalUiAction.RecoverableFailure(TerminalCommandRouter.CAPABILITY_NOT_IMPLEMENTED));
            case TRUST -> workspaceTrust(argument);
            case SESSION -> {
                List<String> options = new ArrayList<>();
                state.session()
                        .ifPresentOrElse(
                                value -> {
                                    options.add("Session: " + value.summary().displayName());
                                    options.add(
                                            "ID: " + value.summary().sessionId().value());
                                    if (!state.footer().project().isBlank()) {
                                        options.add(
                                                "Directory: " + state.footer().project());
                                    }
                                    if (!state.footer().gitBranch().isBlank()) {
                                        options.add(
                                                "Git branch: " + state.footer().gitBranch());
                                    }
                                    if (!state.footer().model().isBlank()) {
                                        options.add("Model: " + state.footer().model()
                                                + (state.footer().provider().isBlank()
                                                        ? ""
                                                        : " (" + state.footer().provider() + ")"));
                                    }
                                    if (!state.loadedResources().isEmpty()) {
                                        options.add("Resources: " + String.join(", ", state.loadedResources()));
                                    }
                                    options.add("Profile: " + value.productProfileRef());
                                },
                                () -> options.add("No active session"));
                apply(new TerminalUiAction.SelectorOpened(
                        new TerminalSelector("session", "Session & Environment", options, 0)));
            }
            case COMMANDS -> openCommandSelector();
            case THEME -> handleThemeCommand(argument);
            case MOUSE -> handleMouseCommand(argument);
            case QUIT -> apply(new TerminalUiAction.ExitRequested());
            case NOT_IMPLEMENTED ->
                apply(new TerminalUiAction.RecoverableFailure(TerminalCommandRouter.CAPABILITY_NOT_IMPLEMENTED));
            case UNKNOWN -> apply(new TerminalUiAction.RecoverableFailure(TerminalCommandRouter.COMMAND_UNKNOWN));
            case MESSAGE -> throw new IllegalStateException("message must be routed separately");
        }
    }

    private void handleThemeCommand(String argument) {
        if (argument.isBlank()) {
            preserveDraft();
            List<String> options = TerminalThemes.AVAILABLE;
            int selected = Math.max(0, options.indexOf(state.themeName()));
            apply(new TerminalUiAction.SelectorOpened(
                    new TerminalSelector("theme-selector", "Theme", options, selected)));
            return;
        }
        String canonical = argument.strip().toLowerCase(java.util.Locale.ROOT);
        if (TerminalThemes.isValid(canonical)) {
            apply(new TerminalUiAction.ThemeChanged(canonical));
            apply(new TerminalUiAction.StatusChanged("Theme set to " + canonical));
        } else {
            apply(new TerminalUiAction.StatusChanged(
                    "Unknown theme: " + argument + ". Available: " + String.join(", ", TerminalThemes.AVAILABLE)));
        }
    }

    private void handleMouseCommand(String argument) {
        String value = argument == null ? "" : argument.strip().toLowerCase(java.util.Locale.ROOT);
        boolean enabled;
        if (value.isEmpty()) {
            enabled = !mouseReportingEnabled;
        } else if (value.equals("on")) {
            enabled = true;
        } else if (value.equals("off")) {
            enabled = false;
        } else {
            apply(new TerminalUiAction.StatusChanged("Unknown mouse mode: " + argument + ". Use /mouse on|off"));
            return;
        }
        mouseReportingEnabled = enabled;
        apply(new TerminalUiAction.StatusChanged(
                enabled ? "Mouse reporting on · drag to select" : "native selection · PageUp/PageDown scroll"));
    }

    private void executeRename(String newTitle) {
        CodingSessionView current = requireCurrentSession();
        apply(new TerminalUiAction.StatusChanged("Renaming session"));
        submitEffect(
                () -> {
                    CodingSessionView reconciled =
                            client.reconcile(current.summary().sessionId());
                    CodingSessionSummary renamed = client.rename(
                            reconciled.summary().sessionId(),
                            newTitle,
                            reconciled.summary().revision());
                    LoadedSession loaded = readSession(
                            client.open(renamed.sessionId()), Optional.empty(), null, RunOutputCursor.BEFORE_FIRST);
                    return () -> {
                        applyLoadedSession(loaded);
                        apply(new TerminalUiAction.StatusChanged("Session renamed"));
                    };
                },
                code -> apply(new TerminalUiAction.RecoverableFailure(code)));
    }

    private void executeNonDraftCommand(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        TerminalCommand command = commands.route(text);
        if (command == TerminalCommand.MESSAGE) {
            return;
        }
        command(command, text);
    }

    private boolean hasActiveRun() {
        return state.currentRunId().isPresent()
                || state.session()
                        .flatMap(io.haifa.agent.application.project.product.coding.CodingSessionView::activeRun)
                        .isPresent();
    }

    private boolean cancelCurrentRunIfPresent() {
        if (state.currentRunId().isEmpty()) {
            if (state.session().isEmpty()) return false;
            AgentSessionId sessionId = state.session().orElseThrow().summary().sessionId();
            Optional<RunEventCursor> cursor = state.appliedCursor();
            AgentRunId previousOutputRunId = outputRunId;
            RunOutputCursor previousOutputCursor = outputCursor;
            apply(new TerminalUiAction.StatusChanged("Checking active run"));
            submitEffect(
                    () -> {
                        LoadedSession loaded = readSession(
                                client.reconcile(sessionId), cursor, previousOutputRunId, previousOutputCursor);
                        return () -> {
                            applyLoadedSession(loaded);
                            if (state.currentRunId().isPresent()) {
                                cancelCurrentRunIfPresent();
                            } else {
                                apply(new TerminalUiAction.StatusChanged("Idle"));
                            }
                        };
                    },
                    code -> apply(new TerminalUiAction.RecoverableFailure(code)));
            return true;
        }
        if (state.selector().isPresent()) {
            discardPendingShell();
            apply(new TerminalUiAction.SelectorClosed());
            completionContext = null;
        }
        apply(new TerminalUiAction.StatusChanged("Cancelling"));
        AgentSessionId sessionId = state.session().orElseThrow().summary().sessionId();
        String idempotencyKey = UUID.randomUUID().toString();
        submitControlEffect(
                () -> {
                    client.cancel(sessionId, idempotencyKey);
                    return () -> {};
                },
                code -> apply(new TerminalUiAction.RecoverableFailure(code)));
        return true;
    }

    private void workspaceTrust(String argument) {
        if (argument.isBlank()) {
            apply(new TerminalUiAction.StatusChanged("Loading workspace trust"));
            submitEffect(
                    () -> {
                        List<String> options = client.workspaces().stream()
                                .map(CodingTerminalController::workspaceOption)
                                .toList();
                        return () -> {
                            apply(new TerminalUiAction.SelectorOpened(new TerminalSelector(
                                    "workspace-trust",
                                    "Workspace trust",
                                    options.isEmpty() ? List.of("No registered workspaces") : options,
                                    0)));
                            apply(new TerminalUiAction.StatusChanged("Workspace trust loaded"));
                        };
                    },
                    code -> apply(new TerminalUiAction.RecoverableFailure(code)));
            return;
        }
        String[] parts = argument.split("\\s+", 2);
        if (parts.length != 2
                || !parts[0].equalsIgnoreCase("revoke")
                || parts[1].isBlank()
                || parts[1].chars().anyMatch(Character::isWhitespace)) {
            apply(new TerminalUiAction.RecoverableFailure("WORKSPACE_TRUST_COMMAND_INVALID"));
            return;
        }
        String workspaceRef = parts[1];
        apply(new TerminalUiAction.StatusChanged("Revoking workspace access"));
        submitEffect(
                () -> {
                    client.revokeWorkspace(workspaceRef);
                    return () -> apply(new TerminalUiAction.StatusChanged("Workspace access revoked"));
                },
                code -> apply(new TerminalUiAction.RecoverableFailure(code)));
    }

    private static String workspaceOption(CodingWorkspaceView workspace) {
        return String.join(
                        " · ",
                        workspace.safeDisplayName(),
                        workspace.mode(),
                        workspace.status(),
                        workspace.workspaceRef())
                + (workspace.revocable() ? " · revocable" : "");
    }

    private LoadedSession readSession(
            CodingSessionView view,
            Optional<RunEventCursor> previousCursor,
            AgentRunId previousOutputRunId,
            RunOutputCursor previousOutputCursor) {
        List<String> resources = client.loadedResources();
        var history = client.history(view.summary().sessionId(), HISTORY_LIMIT);
        List<PendingMessage> pending = client.restorableMessages(view.summary().sessionId(), 100).stream()
                .map(value -> new PendingMessage(
                        value.followUpId(), PendingMessage.Kind.FOLLOW_UP, value.summary(), value.revision()))
                .toList();
        if (view.activeRun().isEmpty()) {
            return new LoadedSession(
                    view, resources, history, pending, List.of(), null, null, null, RunOutputCursor.BEFORE_FIRST);
        }
        AgentRunId runId = view.activeRun().orElseThrow().runId();
        RunEventCursor cursor = previousCursor
                .filter(value -> value.runId().equals(runId))
                .orElseGet(() -> RunEventCursor.beforeFirst(runId));
        ArrayDeque<AgentRunEvent> events = new ArrayDeque<>(MAX_REPLAY_EVENTS);
        boolean more;
        do {
            var page = client.events(runId, cursor, PAGE_SIZE);
            for (AgentRunEvent event : page.items()) {
                if (events.size() == MAX_REPLAY_EVENTS) events.removeFirst();
                events.addLast(event);
            }
            cursor = page.nextCursor();
            more = page.hasMore();
        } while (more);
        RunEventCursor subscribeAfter = cursor;
        RunEventSubscription nextSubscription = client.subscribe(
                runId, subscribeAfter, event -> pump.offer(new TerminalUiAction.RunEventReceived(event)));
        RunOutputCursor nextOutputCursor =
                runId.equals(previousOutputRunId) ? previousOutputCursor : RunOutputCursor.BEFORE_FIRST;
        RunOutputSubscription nextOutputSubscription;
        try {
            nextOutputSubscription = client.subscribeOutput(
                    runId, nextOutputCursor, event -> pump.offer(new TerminalUiAction.RunOutputReceived(event)));
        } catch (RuntimeException exception) {
            nextSubscription.close();
            throw exception;
        }
        return new LoadedSession(
                view,
                resources,
                history,
                pending,
                List.copyOf(events),
                nextSubscription,
                nextOutputSubscription,
                runId,
                nextOutputCursor);
    }

    private void applyLoadedSession(LoadedSession loaded) {
        RunEventSubscription previousSubscription = subscription;
        RunOutputSubscription previousOutputSubscription = outputSubscription;
        boolean sameSession = state.session()
                .map(value -> value.summary()
                        .sessionId()
                        .equals(loaded.view().summary().sessionId()))
                .orElse(false);
        subscription = loaded.subscription();
        outputSubscription = loaded.outputSubscription();
        outputRunId = loaded.outputRunId();
        outputCursor = loaded.outputCursor();
        activeInteraction = null;
        interactionHydrationInFlight = null;
        apply(new TerminalUiAction.SessionLoaded(loaded.view(), loaded.resources()));
        if (!sameSession) apply(new TerminalUiAction.HistoryLoaded(loaded.history()));
        apply(new TerminalUiAction.PendingChanged(loaded.pending()));
        for (AgentRunEvent event : loaded.events()) {
            apply(new TerminalUiAction.RunEventReceived(event));
        }
        loaded.view().pendingInteraction().ifPresent(this::openInteractionSelector);
        replacePreviewSubscription(
                loaded.view().activeRun().map(value -> value.runId()).orElse(null));
        closeSubscriptionsInBackground(previousSubscription, previousOutputSubscription);
    }

    private void replacePreviewSubscription(AgentRunId runId) {
        if (Objects.equals(previewRunId, runId) && previewSubscription != null) return;
        if (previewSubscription != null) previewSubscription.close();
        previewSubscription = null;
        previewRunId = runId;
        if (runId != null) {
            previewSubscription = previewPublisher.subscribe(
                    runId, preview -> pump.offer(new TerminalUiAction.ToolOutputPreviewReceived(preview)));
        }
    }

    private void openRestoreSelector() {
        if (state.session().isEmpty()) {
            apply(new TerminalUiAction.RecoverableFailure("SESSION_REQUIRED"));
            return;
        }
        AgentSessionId sessionId = state.session().orElseThrow().summary().sessionId();
        apply(new TerminalUiAction.StatusChanged("Loading queued messages"));
        submitEffect(
                () -> {
                    List<CodingQueuedMessage> found = client.restorableMessages(sessionId, 100);
                    return () -> showRestoreOptions(found);
                },
                code -> apply(new TerminalUiAction.RecoverableFailure(code)));
    }

    private void showRestoreOptions(List<CodingQueuedMessage> found) {
        restoreOptions = List.copyOf(found);
        if (restoreOptions.isEmpty()) {
            apply(new TerminalUiAction.RecoverableFailure("RESTORABLE_QUEUE_EMPTY"));
            return;
        }
        apply(new TerminalUiAction.SelectorOpened(new TerminalSelector(
                "restore",
                "Restore queued follow-up",
                restoreOptions.stream()
                        .map(value -> value.sequence() + " · " + value.summary())
                        .toList(),
                restoreOptions.size() - 1)));
    }

    private void openInteractionSelector(InteractionView interaction) {
        activeInteraction = interaction;
        apply(new TerminalUiAction.InteractionPresented(interaction));
        if (state.selector().isPresent()
                && state.selector().orElseThrow().kind().startsWith("interaction:")) {
            return;
        }
        List<String> options = interaction.allowedActions().stream()
                .map(InteractionAction::value)
                .toList();
        if (options.isEmpty()) {
            apply(new TerminalUiAction.RecoverableFailure("INTERACTION_ACTIONS_EMPTY"));
            return;
        }
        apply(new TerminalUiAction.SelectorOpened(new TerminalSelector(
                "interaction:" + interaction.requestId().value(), "Approval · " + interaction.title(), options, 0)));
    }

    private void startCodexLogin() {
        authenticationInProgress = true;
        apply(new TerminalUiAction.StatusChanged("正在打开浏览器完成 ChatGPT 授权"));
        submitEffect(
                () -> {
                    CodingAuthenticationView connected = authentication.loginCodexBrowser(
                            instructions -> pump.offer(new TerminalUiAction.BrowserLoginInstructionsPresented(
                                    "ChatGPT", instructions.authorizationUri().toString())),
                            progress -> pump.offer(new TerminalUiAction.AuthenticationProgressed(progress.phase())));
                    return () -> {
                        authenticationInProgress = false;
                        pump.offer(new TerminalUiAction.AuthenticationCompleted(
                                "ChatGPT", connected.unofficialLocalCompatibility()));
                        offerConnectionNextStep("openai-codex", "ChatGPT");
                    };
                },
                code -> completeAuthenticationFailure("ChatGPT", code));
    }

    private void startAntigravityLogin() {
        authenticationInProgress = true;
        apply(new TerminalUiAction.StatusChanged("正在打开浏览器完成 Antigravity 授权"));
        submitEffect(
                () -> {
                    CodingAuthenticationView connected = authentication.loginAntigravityBrowser(
                            instructions -> pump.offer(new TerminalUiAction.BrowserLoginInstructionsPresented(
                                    "Antigravity", instructions.authorizationUri().toString())),
                            progress -> pump.offer(
                                    new TerminalUiAction.AuthenticationProgressed("Antigravity", progress.phase())));
                    return () -> {
                        authenticationInProgress = false;
                        pump.offer(new TerminalUiAction.AuthenticationCompleted(
                                "Antigravity", connected.unofficialLocalCompatibility()));
                        offerConnectionNextStep("google-antigravity", "Antigravity");
                    };
                },
                code -> completeAuthenticationFailure("Antigravity", code));
    }

    public boolean authenticationInProgress() {
        return authenticationInProgress;
    }

    public void cancelAuthentication() {
        if (!authenticationInProgress) {
            return;
        }
        apply(new TerminalUiAction.StatusChanged("正在取消登录"));
        submitControlEffect(
                () -> {
                    boolean cancelled = authentication.cancelLogin();
                    return () -> {
                        if (!cancelled) {
                            apply(new TerminalUiAction.StatusChanged("正在保存凭据，无法取消"));
                        }
                    };
                },
                code -> apply(new TerminalUiAction.RecoverableFailure(code)));
    }

    private void completeAuthenticationFailure(String connectionName, String code) {
        authenticationInProgress = false;
        if ("AUTH_CANCELLED".equals(code)) {
            pump.offer(new TerminalUiAction.StatusChanged("已取消登录"));
            return;
        }
        pump.offer(new TerminalUiAction.AuthenticationFailed(connectionName, code));
    }

    private void offerConnectionNextStep(String providerId, String providerName) {
        pump.offer(new TerminalUiAction.StatusChanged("已连接 " + providerName + "，正在加载模型"));
        submitEffect(
                () -> {
                    List<CodingModelOption> loaded = client.models();
                    return () -> {
                        modelOptions = List.copyOf(loaded);
                        if (state.currentRunId().isPresent()) {
                            pump.offer(new TerminalUiAction.StatusChanged(
                                    "已连接 " + providerName + "，当前任务结束后可用 /model 选择模型"));
                            return;
                        }
                        List<CodingModelOption> providerModels = modelOptions.stream()
                                .filter(option -> option.providerId().equals(providerId))
                                .toList();
                        if (providerModels.isEmpty()) {
                            pump.offer(new TerminalUiAction.StatusChanged("已连接 " + providerName
                                    + "，但当前配置没有该提供商的模型；请在配置文件的 models 中加入后重启"));
                            return;
                        }
                        if (providerModels.size() == 1
                                && providerModels.getFirst().state().bindingAvailability()
                                        == CodingModelState.BindingAvailability.AVAILABLE) {
                            CodingModelOption only = providerModels.getFirst();
                            confirmModelSelection(only);
                            pump.offer(new TerminalUiAction.StatusChanged(
                                    "已连接 " + providerName + "，已选择 " + only.displayName()));
                            return;
                        }
                        openModelOptions(providerModels, null, "选择 " + providerName + " 模型");
                    };
                },
                code -> apply(new TerminalUiAction.RecoverableFailure(code)));
    }

    private void openModelSelector(String argument) {
        Optional<CodingSessionView> current = awaitingNewSessionMessage ? Optional.empty() : state.session();
        Optional<RunEventCursor> cursor = state.appliedCursor();
        AgentRunId previousOutputRunId = outputRunId;
        RunOutputCursor previousOutputCursor = outputCursor;
        apply(new TerminalUiAction.StatusChanged("Loading models"));
        submitEffect(
                () -> loadModels(current, argument, cursor, previousOutputRunId, previousOutputCursor),
                code -> apply(new TerminalUiAction.RecoverableFailure(code)));
    }

    private void startCodexDeviceLogin() {
        authenticationInProgress = true;
        apply(new TerminalUiAction.StatusChanged("正在获取 ChatGPT 设备码"));
        submitEffect(
                () -> {
                    CodingAuthenticationView connected = authentication.loginCodexDevice(
                            instruction -> pump.offer(new TerminalUiAction.DeviceLoginInstructionsPresented(
                                    instruction.verificationUri().toString(), instruction.userCode())),
                            progress -> pump.offer(new TerminalUiAction.AuthenticationProgressed(progress.phase())));
                    return () -> {
                        authenticationInProgress = false;
                        pump.offer(new TerminalUiAction.AuthenticationCompleted(
                                "ChatGPT", connected.unofficialLocalCompatibility()));
                        offerConnectionNextStep("openai-codex", "ChatGPT");
                    };
                },
                code -> completeAuthenticationFailure("ChatGPT", code));
    }

    private void beginApiKeyInput(String input) {
        if (input == null || input.isBlank()) {
            input = authentication.apiKeyProviderId();
        }
        String[] parts = input.strip().split("\\s+");
        String providerId = parts[0].toLowerCase(java.util.Locale.ROOT);
        if (providerId.isEmpty() || !providerId.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            apply(new TerminalUiAction.RecoverableFailure("AUTH_PROVIDER_INVALID"));
            return;
        }
        if (isUnsupportedApiKeyProvider(providerId)) {
            apply(new TerminalUiAction.RecoverableFailure("AUTH_METHOD_UNSUPPORTED"));
            return;
        }
        boolean bailian = "aliyun-bailian".equalsIgnoreCase(providerId);
        String workspaceId = bailian && parts.length > 1 ? parts[1].strip() : null;
        String region = bailian && parts.length > 2 ? parts[2].strip() : null;
        if (workspaceId != null && !DNS_LABEL_PATTERN.matcher(workspaceId).matches()) {
            apply(new TerminalUiAction.RecoverableFailure("AUTH_WORKSPACE_ID_INVALID"));
            return;
        }
        if (region != null && !DNS_LABEL_PATTERN.matcher(region).matches()) {
            apply(new TerminalUiAction.RecoverableFailure("AUTH_REGION_INVALID"));
            return;
        }
        // Credential-store reads may block, so they run off the UI thread; secure-input state is
        // only entered once the lookup succeeds, leaving nothing half-initialised on failure.
        submitEffect(
                () -> {
                    boolean hasExistingKey = authentication.connections().stream()
                            .anyMatch(c -> c.providerId().equalsIgnoreCase(providerId)
                                    && c.method() == CodingAuthenticationView.Method.API_KEY);
                    Map<String, String> attributes = bailian
                            ? authentication.providerAttributes(providerId).orElse(Map.of())
                            : Map.of();
                    return () -> enterApiKeyInput(providerId, hasExistingKey, attributes, workspaceId, region);
                },
                code -> apply(new TerminalUiAction.RecoverableFailure(code)));
    }

    private void enterApiKeyInput(
            String providerId,
            boolean hasExistingKey,
            Map<String, String> attributes,
            String workspaceId,
            String region) {
        resetBailianState();
        pendingApiKeyProvider = providerId;
        pendingApiKeyIsUpdate = hasExistingKey;

        String displayName = AuthenticationProviderPresentation.providerName(providerId, providerId);
        String help = AuthenticationProviderPresentation.helpUrl(providerId)
                .map(url -> " · 获取 Key：" + url)
                .orElse("");
        if ("aliyun-bailian".equalsIgnoreCase(providerId)) {
            existingBailianWorkspaceId = attributes.get("workspace_id");
            existingBailianRegion = attributes.get("region");
            pendingBailianWorkspaceId = workspaceId != null ? workspaceId : existingBailianWorkspaceId;
            pendingBailianRegion =
                    region != null ? region : (existingBailianRegion != null ? existingBailianRegion : "cn-beijing");
            bailianStep = BailianConfigStep.API_KEY;
            String workspaceLabel = pendingBailianWorkspaceId != null ? pendingBailianWorkspaceId : "待填写";
            if (hasExistingKey) {
                apply(new TerminalUiAction.StatusChanged("更新 " + displayName + " 的 API Key（Workspace: "
                        + workspaceLabel + " · Region: " + pendingBailianRegion + " · 回车可保留现有 Key）" + help));
            } else {
                apply(new TerminalUiAction.StatusChanged("请输入 " + displayName + " 的 API Key（Workspace: "
                        + workspaceLabel + " · Region: " + pendingBailianRegion + "）" + help));
            }
            return;
        }

        if (hasExistingKey) {
            apply(new TerminalUiAction.StatusChanged(
                    "更新 " + displayName + " 的 API Key（将覆盖系统凭据库中的现有 Key）" + help));
        } else {
            apply(new TerminalUiAction.StatusChanged("请输入 " + displayName + " 的 API Key" + help));
        }
    }

    private boolean isUnsupportedApiKeyProvider(String providerId) {
        if ("openai-codex".equalsIgnoreCase(providerId) || "google-antigravity".equalsIgnoreCase(providerId)) {
            return true;
        }
        return authentication.apiKeyProviderId().equalsIgnoreCase(providerId)
                && !authentication.apiKeyConnectionSupported();
    }

    public boolean secureInputRequested() {
        return pendingApiKeyProvider != null;
    }

    public boolean secureInputIsUpdate() {
        return pendingApiKeyProvider != null && pendingApiKeyIsUpdate;
    }

    public boolean secureInputIsMasked() {
        if ("aliyun-bailian".equalsIgnoreCase(pendingApiKeyProvider)) {
            return bailianStep == BailianConfigStep.API_KEY;
        }
        return true;
    }

    public String secureInputPrompt() {
        return secureInputIsUpdate() ? "新 Key ┃ " : "API Key ┃ ";
    }

    public String secureInputPlaceholder() {
        if ("aliyun-bailian".equalsIgnoreCase(pendingApiKeyProvider)) {
            String workspace = pendingBailianWorkspaceId != null ? pendingBailianWorkspaceId : "待填写";
            String defaultReg = pendingBailianRegion != null ? pendingBailianRegion : "cn-beijing";
            return secureInputIsUpdate()
                    ? "粘贴或输入新的 API Key（Esc 保留现有 Key）"
                    : "粘贴或输入百炼 API Key [Workspace] [Region]（Workspace: " + workspace + " · Region 默认: "
                            + defaultReg + "）";
        }
        return secureInputIsUpdate() ? "粘贴或输入新的 API Key（Esc 保留现有 Key）" : "粘贴或输入 API Key";
    }

    public String secureInputHint() {
        return secureInputIsUpdate() ? "Enter 更新 · Esc 取消" : "Enter 保存 · Esc 取消";
    }

    public BailianConfigStep bailianConfigStep() {
        return bailianStep;
    }

    public void cancelSecureInput() {
        if (pendingApiKeyProvider == null) {
            return;
        }
        resetBailianState();
        pendingApiKeyProvider = null;
        pendingApiKeyIsUpdate = false;
        apply(new TerminalUiAction.StatusChanged("已取消 API Key 输入"));
    }

    private void resetBailianState() {
        bailianStep = BailianConfigStep.NONE;
        if (pendingBailianApiKey != null) {
            java.util.Arrays.fill(pendingBailianApiKey, '\0');
            pendingBailianApiKey = null;
        }
        pendingBailianWorkspaceId = null;
        pendingBailianRegion = null;
        existingBailianWorkspaceId = null;
        existingBailianRegion = null;
    }

    public void submitApiKey(char[] apiKey) {
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        String providerId = pendingApiKeyProvider;
        if (providerId == null) {
            java.util.Arrays.fill(apiKey, '\0');
            apply(new TerminalUiAction.RecoverableFailure("AUTH_SECURE_INPUT_NOT_REQUESTED"));
            return;
        }
        if ("aliyun-bailian".equalsIgnoreCase(providerId)) {
            handleBailianInput(apiKey);
            return;
        }
        pendingApiKeyProvider = null;
        pendingApiKeyIsUpdate = false;
        char[] owned = new String(apiKey).strip().toCharArray();
        java.util.Arrays.fill(apiKey, '\0');
        apply(new TerminalUiAction.StatusChanged(
                "正在保存 " + AuthenticationProviderPresentation.providerName(providerId, providerId) + " 的 API Key"));
        try {
            effects.execute(() -> {
                Runnable completion;
                try {
                    CodingAuthenticationView connected = authentication.saveApiKey(providerId, owned);
                    completion = () -> {
                        String providerName =
                                AuthenticationProviderPresentation.providerName(providerId, providerId);
                        apply(new TerminalUiAction.StatusChanged("已连接 " + providerName + "，正在加载模型"));
                        scheduleReconcile();
                        offerConnectionNextStep(providerId, providerName);
                    };
                } catch (ProjectProductException exception) {
                    completion = () -> apply(new TerminalUiAction.RecoverableFailure(exception.code()));
                } catch (IllegalArgumentException
                        | IllegalStateException
                        | SecurityException
                        | UnsupportedOperationException exception) {
                    String code = safeFailureCode(exception);
                    completion = () -> apply(new TerminalUiAction.RecoverableFailure(code));
                } catch (RuntimeException exception) {
                    completion = () -> apply(new TerminalUiAction.RecoverableFailure("OPERATION_REJECTED"));
                } finally {
                    java.util.Arrays.fill(owned, '\0');
                }
                enqueueCompletion(effectCompletions, completion);
            });
        } catch (RejectedExecutionException exception) {
            java.util.Arrays.fill(owned, '\0');
            apply(new TerminalUiAction.RecoverableFailure("TERMINAL_BACKGROUND_UNAVAILABLE"));
        }
        drainEffectCompletions();
    }

    private void handleBailianInput(char[] input) {
        String fullText = new String(input).strip();
        if (fullText.isEmpty() && !pendingApiKeyIsUpdate) {
            java.util.Arrays.fill(input, '\0');
            apply(new TerminalUiAction.StatusChanged(
                    "API key must not be empty · enter DashScope API key for aliyun-bailian"));
            return;
        }

        String[] parts = fullText.isEmpty() ? new String[0] : fullText.split("\\s+");
        if (parts.length > 3) {
            java.util.Arrays.fill(input, '\0');
            apply(new TerminalUiAction.StatusChanged(
                    "Too many arguments: expected API key [workspace] [region] for aliyun-bailian"));
            return;
        }
        if (parts.length > 0 && !parts[0].isEmpty()) {
            pendingBailianApiKey = parts[0].toCharArray();
        }
        if (parts.length > 1) {
            String ws = parts[1].strip();
            if (!DNS_LABEL_PATTERN.matcher(ws).matches()) {
                java.util.Arrays.fill(input, '\0');
                apply(new TerminalUiAction.StatusChanged(
                        "Invalid workspace ID: must be lowercase DNS label ([a-z0-9-], 1-63 chars)"));
                return;
            }
            pendingBailianWorkspaceId = ws;
        }
        if (parts.length > 2) {
            String reg = parts[2].strip();
            if (!DNS_LABEL_PATTERN.matcher(reg).matches()) {
                java.util.Arrays.fill(input, '\0');
                apply(new TerminalUiAction.StatusChanged(
                        "Invalid region: must be lowercase DNS label ([a-z0-9-], 1-63 chars)"));
                return;
            }
            pendingBailianRegion = reg;
        }
        if (pendingBailianWorkspaceId == null) {
            java.util.Arrays.fill(input, '\0');
            apply(new TerminalUiAction.StatusChanged(
                    "Workspace ID is required for first-time aliyun-bailian setup; enter API key workspace [region]"));
            return;
        }
        java.util.Arrays.fill(input, '\0');
        if (pendingBailianRegion == null) {
            pendingBailianRegion = existingBailianRegion != null ? existingBailianRegion : "cn-beijing";
        }

        saveBailianCredentials();
    }

    private void saveBailianCredentials() {
        String providerId = pendingApiKeyProvider;
        char[] key = pendingBailianApiKey != null
                ? java.util.Arrays.copyOf(pendingBailianApiKey, pendingBailianApiKey.length)
                : new char[0];
        String workspaceId = pendingBailianWorkspaceId != null ? pendingBailianWorkspaceId : "default-workspace";
        String region = pendingBailianRegion != null ? pendingBailianRegion : "cn-beijing";
        Map<String, String> attributes = Map.of("workspace_id", workspaceId, "region", region);

        resetBailianState();
        pendingApiKeyProvider = null;
        pendingApiKeyIsUpdate = false;

        apply(new TerminalUiAction.StatusChanged(
                "正在保存 " + AuthenticationProviderPresentation.providerName(providerId, providerId) + " 的凭据"));
        try {
            effects.execute(() -> {
                Runnable completion;
                try {
                    CodingAuthenticationView connected = authentication.saveApiKey(providerId, key, attributes);
                    completion = () -> {
                        String providerName =
                                AuthenticationProviderPresentation.providerName(providerId, providerId);
                        apply(new TerminalUiAction.StatusChanged("已连接 " + providerName + "，正在加载模型"));
                        scheduleReconcile();
                        offerConnectionNextStep(providerId, providerName);
                    };
                } catch (ProjectProductException exception) {
                    completion = () -> apply(new TerminalUiAction.RecoverableFailure(exception.code()));
                } catch (IllegalArgumentException
                        | IllegalStateException
                        | SecurityException
                        | UnsupportedOperationException exception) {
                    String code = safeFailureCode(exception);
                    completion = () -> apply(new TerminalUiAction.RecoverableFailure(code));
                } catch (RuntimeException exception) {
                    completion = () -> apply(new TerminalUiAction.RecoverableFailure("OPERATION_REJECTED"));
                } finally {
                    java.util.Arrays.fill(key, '\0');
                }
                enqueueCompletion(effectCompletions, completion);
            });
        } catch (RejectedExecutionException exception) {
            java.util.Arrays.fill(key, '\0');
            apply(new TerminalUiAction.RecoverableFailure("TERMINAL_BACKGROUND_UNAVAILABLE"));
        }
        drainEffectCompletions();
    }

    private void loadAuthenticationOptions(boolean logout) {
        apply(new TerminalUiAction.StatusChanged(logout ? "正在加载模型账号" : "正在加载账号状态"));
        submitEffect(
                () -> {
                    List<CodingAuthenticationView> found = authentication.connections();
                    return () -> showAuthenticationOptions(found, logout);
                },
                code -> apply(new TerminalUiAction.RecoverableFailure(code)));
    }

    private void showAuthenticationOptions(List<CodingAuthenticationView> found, boolean logout) {
        authenticationOptions = List.copyOf(found);
        if (authenticationOptions.isEmpty()) {
            apply(new TerminalUiAction.StatusChanged("尚未连接模型账号，使用 /connect 连接"));
            return;
        }
        List<String> options = authenticationOptions.stream().map(this::accountLabel).toList();
        apply(new TerminalUiAction.SelectorOpened(new TerminalSelector(
                logout ? "auth-logout" : "auth-account",
                logout ? "断开模型账号" : "模型账号",
                options,
                0)));
    }

    private String accountLabel(CodingAuthenticationView connection) {
        String providerName =
                AuthenticationProviderPresentation.providerName(connection.providerId(), connection.providerId());
        String method = connection.method() == CodingAuthenticationView.Method.API_KEY ? "API Key" : "订阅";
        String account = AuthenticationProviderPresentation.maskAccount(connection.accountLabel());
        String status = switch (connection.status()) {
            case AUTHENTICATED -> "已连接";
            case REAUTH_REQUIRED -> "需要重新登录";
            case RATE_LIMITED -> "已限流";
        };
        return providerName + " · " + method + (account.isBlank() ? "" : " · " + account) + " · " + status;
    }

    private void openAccountActions(CodingAuthenticationView connection) {
        selectedAuthenticationConnection = connection;
        List<String> actions = new java.util.ArrayList<>();
        if (connection.method() == CodingAuthenticationView.Method.API_KEY) {
            actions.add("更新 API Key");
        } else {
            actions.add("重新登录");
        }
        actions.add("断开连接");
        actions.add("返回");
        String providerName =
                AuthenticationProviderPresentation.providerName(connection.providerId(), connection.providerId());
        apply(new TerminalUiAction.SelectorOpened(
                new TerminalSelector("auth-account-actions", "管理 " + providerName, actions, 0)));
    }

    private void confirmDisconnect(CodingAuthenticationView connection) {
        selectedAuthenticationConnection = connection;
        String providerName =
                AuthenticationProviderPresentation.providerName(connection.providerId(), connection.providerId());
        apply(new TerminalUiAction.SelectorOpened(new TerminalSelector(
                "auth-logout-confirm",
                "断开 " + providerName + "？",
                List.of("取消", "确认断开 " + providerName),
                0)));
    }

    private void disconnectSelectedAccount() {
        CodingAuthenticationView connection = selectedAuthenticationConnection;
        if (connection == null) {
            apply(new TerminalUiAction.RecoverableFailure("AUTH_ACCOUNT_NOT_SELECTED"));
            return;
        }
        selectedAuthenticationConnection = null;
        apply(new TerminalUiAction.StatusChanged("正在断开模型账号"));
        submitEffect(
                () -> {
                    boolean removed = authentication.logout(connection.connectionId());
                    return () -> apply(new TerminalUiAction.StatusChanged(
                            removed ? "模型账号已断开" : "模型账号已不存在"));
                },
                code -> apply(new TerminalUiAction.RecoverableFailure(code)));
    }

    private void reloginSelectedAccount() {
        CodingAuthenticationView connection = selectedAuthenticationConnection;
        if (connection == null) {
            apply(new TerminalUiAction.RecoverableFailure("AUTH_ACCOUNT_NOT_SELECTED"));
            return;
        }
        selectedAuthenticationConnection = null;
        openProviderMethodsAfterLoad(connection.providerId());
    }

    private void openProviderMethodsAfterLoad(String providerId) {
        if (!authenticationProviders.isEmpty()) {
            openProviderMethods(providerId);
            return;
        }
        apply(new TerminalUiAction.StatusChanged("正在加载模型提供商"));
        submitEffect(
                () -> {
                    List<CodingAuthenticationProviderView> providers = authentication.providers();
                    return () -> {
                        authenticationProviders = List.copyOf(providers);
                        openProviderMethods(providerId);
                    };
                },
                code -> apply(new TerminalUiAction.RecoverableFailure(code)));
    }

    public void openCommandSelector() {
        preserveDraft();
        apply(new TerminalUiAction.SelectorOpened(
                new TerminalSelector("command-palette", "Commands", TerminalCompletionProvider.COMMANDS, 0)));
    }

    private void openCompletionSelector(String buffer, int cursor) {
        if (!state.editorBuffer().equals(buffer) || state.editorCursor() != cursor) {
            apply(new TerminalUiAction.EditorChanged(buffer, cursor));
        }
        int start = cursor;
        while (start > 0 && !Character.isWhitespace(buffer.charAt(start - 1))) {
            start--;
        }
        int end = cursor;
        while (end < buffer.length() && !Character.isWhitespace(buffer.charAt(end))) {
            end++;
        }
        String word = buffer.substring(start, cursor);
        CompletionContext requestedContext = new CompletionContext(buffer, start, end);
        if (word.startsWith("@")) {
            long requestId = completionRequestIds.incrementAndGet();
            activeCompletionRequestId = requestId;
            submitEffect(
                    () -> {
                        List<String> options = completions.suggestions(word, client.logicalPaths());
                        return () -> showCompletionOptions(requestId, requestedContext, word, options);
                    },
                    code -> {
                        if (requestId == activeCompletionRequestId) {
                            apply(new TerminalUiAction.RecoverableFailure(code));
                        }
                    });
            return;
        }
        showCompletionOptions(0, requestedContext, word, completions.suggestions(word, List.of()));
    }

    private void showCompletionOptions(
            long requestId, CompletionContext requestedContext, String word, List<String> options) {
        if (requestId != 0 && requestId != activeCompletionRequestId) return;
        if (!state.editorBuffer().equals(requestedContext.buffer())) return;
        if (options.isEmpty()) {
            if (state.selector()
                    .filter(value -> "completion".equals(value.kind()))
                    .isPresent()) {
                apply(new TerminalUiAction.SelectorClosed());
                completionContext = null;
            }
            return;
        }
        completionContext = requestedContext;
        String title = word.startsWith("@") ? "Workspace paths" : "Commands";
        apply(new TerminalUiAction.SelectorOpened(new TerminalSelector("completion", title, options, 0)));
    }

    private void acceptSelector(TerminalInput input) {
        if (input.kind() == TerminalInput.Kind.CANCEL_OR_CLOSE || input.kind() == TerminalInput.Kind.INTERRUPT) {
            apply(new TerminalUiAction.SelectorClosed());
            discardPendingShell();
            completionContext = null;
            restorePreservedDraft();
            return;
        }
        if (input.kind() == TerminalInput.Kind.SELECT_PREVIOUS) {
            apply(new TerminalUiAction.SelectorMoved(-1));
            return;
        }
        if (input.kind() == TerminalInput.Kind.SELECT_NEXT) {
            apply(new TerminalUiAction.SelectorMoved(1));
            return;
        }
        if (input.kind() == TerminalInput.Kind.NAVIGATE_BACK) {
            navigateSelectorParent();
            return;
        }
        if (input.kind() == TerminalInput.Kind.EOF) {
            apply(new TerminalUiAction.SelectorClosed());
            completionContext = null;
            restorePreservedDraft();
            return;
        }
        if (input.kind() != TerminalInput.Kind.SUBMIT) {
            return;
        }
        TerminalSelector selector = state.selector().orElseThrow();
        int selected = selector.selected();
        switch (selector.kind()) {
            case "command-palette" -> {
                String selectedCommand = selector.options().get(selected);
                apply(new TerminalUiAction.SelectorClosed());
                restorePreservedDraft();
                executeNonDraftCommand(selectedCommand);
            }
            case "completion" -> {
                CompletionContext context = Objects.requireNonNull(completionContext, "completion context is required");
                String replacement = selector.options().get(selected);
                boolean exactWord = context.buffer()
                        .substring(context.start(), context.end())
                        .equals(replacement);
                String completed = context.buffer().substring(0, context.start())
                        + replacement
                        + context.buffer().substring(context.end());
                int cursor = context.start() + replacement.length();
                apply(new TerminalUiAction.SelectorClosed());
                apply(new TerminalUiAction.EditorChanged(completed, cursor));
                completionContext = null;
                if (exactWord
                        && replacement.startsWith("/")
                        && completed.strip().equals(replacement)) {
                    submitText(completed, false);
                }
            }
            case "resume" -> {
                AgentSessionId sessionId = resumeOptions.get(selected).sessionId();
                apply(new TerminalUiAction.SelectorClosed());
                apply(new TerminalUiAction.StatusChanged("Opening session"));
                submitEffect(
                        () -> {
                            LoadedSession loaded = readSession(
                                    client.open(sessionId), Optional.empty(), null, RunOutputCursor.BEFORE_FIRST);
                            return () -> applyLoadedSession(loaded);
                        },
                        code -> apply(new TerminalUiAction.RecoverableFailure(code)));
            }
            case "restore" -> {
                CodingQueuedMessage queued = restoreOptions.get(selected);
                apply(new TerminalUiAction.SelectorClosed());
                apply(new TerminalUiAction.StatusChanged("Restoring queued message"));
                submitEffect(
                        () -> {
                            var restored = client.restore(queued.sessionId(), queued.followUpId(), queued.revision());
                            return () -> {
                                apply(new TerminalUiAction.EditorChanged(
                                        restored.message(), restored.message().length()));
                                scheduleReconcile();
                            };
                        },
                        code -> apply(new TerminalUiAction.RecoverableFailure(code)));
            }
            case "session", "workspace-trust" -> apply(new TerminalUiAction.SelectorClosed());
            case "model-readiness" -> {
                apply(new TerminalUiAction.SelectorClosed());
                switch (selected) {
                    case 0 -> openModelSelector("");
                    case 1 -> {
                        if (modelReadinessProviderId == null || modelReadinessProviderId.isBlank()) {
                            openConnectionCenter("");
                        } else {
                            openProviderMethodsAfterLoad(modelReadinessProviderId);
                        }
                    }
                    default -> {}
                }
                modelReadinessProviderId = "";
            }
            case "model-provider" ->
                openProviderModelList(modelProviderGroups.get(selected).providerId());
            case "model" -> openModelDetails(modelSelectionOptions.get(selected));
            case "model-detail" -> selectModelDetailAction(selected);
            case "model-settings" -> selectModelSettingsAction(selected);
            case "auth-provider" -> {
                apply(new TerminalUiAction.SelectorClosed());
                if (selected < 0 || selected >= authenticationProviders.size()) {
                    apply(new TerminalUiAction.RecoverableFailure("AUTH_PROVIDER_INVALID"));
                    return;
                }
                openProviderMethods(authenticationProviders.get(selected));
            }
            case "auth-method" -> {
                apply(new TerminalUiAction.SelectorClosed());
                CodingAuthenticationProviderView provider = activeAuthenticationProvider;
                if (provider == null) {
                    apply(new TerminalUiAction.RecoverableFailure("AUTH_PROVIDER_INVALID"));
                    return;
                }
                List<CodingAuthenticationMethodView> methods = provider.methods().stream()
                        .filter(CodingAuthenticationMethodView::available)
                        .toList();
                if (selected < 0 || selected >= methods.size()) {
                    apply(new TerminalUiAction.RecoverableFailure("AUTH_METHOD_UNSUPPORTED"));
                    return;
                }
                startAuthenticationMethod(provider, methods.get(selected));
            }
            case "auth-account" -> {
                apply(new TerminalUiAction.SelectorClosed());
                if (selected < 0 || selected >= authenticationOptions.size()) {
                    apply(new TerminalUiAction.RecoverableFailure("AUTH_ACCOUNT_NOT_SELECTED"));
                    return;
                }
                openAccountActions(authenticationOptions.get(selected));
            }
            case "auth-logout" -> {
                apply(new TerminalUiAction.SelectorClosed());
                if (selected < 0 || selected >= authenticationOptions.size()) {
                    apply(new TerminalUiAction.RecoverableFailure("AUTH_ACCOUNT_NOT_SELECTED"));
                    return;
                }
                confirmDisconnect(authenticationOptions.get(selected));
            }
            case "auth-account-actions" -> {
                CodingAuthenticationView connection = selectedAuthenticationConnection;
                apply(new TerminalUiAction.SelectorClosed());
                if (connection == null) {
                    apply(new TerminalUiAction.RecoverableFailure("AUTH_ACCOUNT_NOT_SELECTED"));
                    return;
                }
                if (selected == 0) {
                    if (connection.method() == CodingAuthenticationView.Method.API_KEY) {
                        selectedAuthenticationConnection = null;
                        beginApiKeyInput(connection.providerId());
                    } else {
                        reloginSelectedAccount();
                    }
                } else if (selected == 1) {
                    confirmDisconnect(connection);
                } else {
                    selectedAuthenticationConnection = null;
                }
            }
            case "auth-logout-confirm" -> {
                apply(new TerminalUiAction.SelectorClosed());
                if (selected == 1) {
                    disconnectSelectedAccount();
                } else {
                    selectedAuthenticationConnection = null;
                }
            }
            case "theme-selector" -> {
                String selectedTheme = selector.options().get(selected);
                apply(new TerminalUiAction.SelectorClosed());
                restorePreservedDraft();
                apply(new TerminalUiAction.ThemeChanged(selectedTheme));
                apply(new TerminalUiAction.StatusChanged("Theme set to " + selectedTheme));
            }
            case "archive-session" -> {
                apply(new TerminalUiAction.SelectorClosed());
                if (selected == 0) {
                    CodingSessionView current = requireCurrentSession();
                    apply(new TerminalUiAction.StatusChanged("Archiving session"));
                    submitEffect(
                            () -> {
                                CodingSessionView reconciled =
                                        client.reconcile(current.summary().sessionId());
                                client.archive(
                                        reconciled.summary().sessionId(),
                                        reconciled.summary().revision());
                                LoadedSession loaded = readSession(
                                        client.open(reconciled.summary().sessionId()),
                                        Optional.empty(),
                                        null,
                                        RunOutputCursor.BEFORE_FIRST);
                                return () -> {
                                    applyLoadedSession(loaded);
                                    apply(new TerminalUiAction.StatusChanged("Session archived"));
                                };
                            },
                            code -> apply(new TerminalUiAction.RecoverableFailure(code)));
                }
            }
            case "delete-session" -> {
                apply(new TerminalUiAction.SelectorClosed());
                if (selected == 0) {
                    CodingSessionView current = requireCurrentSession();
                    apply(new TerminalUiAction.StatusChanged("Deleting session"));
                    submitEffect(
                            () -> {
                                CodingSessionView reconciled =
                                        client.reconcile(current.summary().sessionId());
                                client.delete(
                                        reconciled.summary().sessionId(),
                                        reconciled.summary().revision());
                                return () -> {
                                    detachSubscriptions();
                                    awaitingNewSessionMessage = true;
                                    apply(new TerminalUiAction.SessionCleared(
                                            "Session deleted; enter the first message"));
                                };
                            },
                            code -> apply(new TerminalUiAction.RecoverableFailure(code)));
                }
            }
            case "shell-approval" -> {
                CodingShellPlan plan = Objects.requireNonNull(pendingShellPlan, "pending shell plan is required");
                apply(new TerminalUiAction.SelectorClosed());
                pendingShellPlan = null;
                if (selected == 0) {
                    scheduleShellExecution(plan, true);
                } else {
                    apply(new TerminalUiAction.StatusChanged("Shell command denied"));
                    submitEffect(
                            () -> {
                                client.discardShell(plan.token());
                                return () -> {};
                            },
                            code -> apply(new TerminalUiAction.RecoverableFailure(code)));
                }
            }
            case "active-exit" -> {
                apply(new TerminalUiAction.SelectorClosed());
                if (selected == 0) apply(new TerminalUiAction.ExitRequested());
            }
            default -> {
                if (!selector.kind().startsWith("interaction:")) {
                    apply(new TerminalUiAction.RecoverableFailure("SELECTOR_KIND_UNSUPPORTED"));
                    return;
                }
                InteractionView interaction = Optional.ofNullable(activeInteraction)
                        .filter(value -> selector.kind()
                                .equals("interaction:" + value.requestId().value()))
                        .orElseThrow();
                InteractionAction action = interaction.allowedActions().get(selected);
                apply(new TerminalUiAction.SelectorClosed());
                apply(new TerminalUiAction.StatusChanged("Approving"));
                activeInteraction = null;
                String idempotencyKey = UUID.randomUUID().toString();
                submitControlEffect(
                        () -> {
                            var receipt = client.respond(interaction, action, idempotencyKey);
                            return () -> {
                                if (receipt != null) {
                                    apply(new TerminalUiAction.InteractionReceiptReceived(receipt));
                                }
                            };
                        },
                        code -> handleInteractionResponseFailure(code));
            }
        }
    }

    private void handleInteractionResponseFailure(String code) {
        activeInteraction = null;
        interactionHydrationInFlight = null;
        apply(new TerminalUiAction.RecoverableFailure(code));
        if (TerminalRecovery.fromCode(code).category() == TerminalRecovery.Category.RETRYABLE
                && state.session().isPresent()) {
            scheduleReconcile();
        }
    }

    private void discardPendingShell() {
        if (pendingShellPlan == null) return;
        String token = pendingShellPlan.token();
        pendingShellPlan = null;
        submitEffect(
                () -> {
                    client.discardShell(token);
                    return () -> {};
                },
                code -> apply(new TerminalUiAction.RecoverableFailure(code)));
    }

    private CodingSessionView requireCurrentSession() {
        return state.session()
                .orElseThrow(() -> new ProjectProductException("SESSION_REQUIRED", "A Coding Session is required"));
    }

    private static String commandArgument(String input) {
        String value = input.strip();
        int separator = value.indexOf(' ');
        return separator < 0 ? "" : value.substring(separator + 1).strip();
    }

    private void showResumeOptions(List<CodingSessionSummary> found) {
        resumeOptions = List.copyOf(found);
        var options = resumeOptions.stream()
                .map(summary -> summary.displayName() + " · " + resumeStatus(summary) + " · " + summary.lastActivityAt()
                        + " · " + shortSessionId(summary.sessionId()))
                .toList();
        if (options.isEmpty()) {
            apply(new TerminalUiAction.RecoverableFailure("SESSION_LIST_EMPTY"));
        } else {
            apply(new TerminalUiAction.SelectorOpened(new TerminalSelector("resume", "Resume session", options, 0)));
        }
    }

    private static String resumeStatus(CodingSessionSummary summary) {
        return summary.activeRunStatus()
                .map(value -> summary.status().name() + "/" + value.name())
                .orElseGet(() -> summary.status().name());
    }

    private static String shortSessionId(AgentSessionId sessionId) {
        String value = sessionId.value();
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private static String formatContextWindow(int tokens) {
        if (tokens >= 1_000_000) return (tokens / 1_000_000) + "M";
        if (tokens >= 1_000) return (tokens / 1_000) + "K";
        return String.valueOf(tokens);
    }

    private static String formatCapabilities(CodingModelOption value) {
        java.util.List<String> caps = new java.util.ArrayList<>();
        if (value.capabilities().contains("TEXT_CHAT")) caps.add("Text");
        if (value.capabilities().contains("TOOL_CALLING")) caps.add("Tools");
        if (value.capabilities().contains("IMAGE_URL_INPUT")
                || value.capabilities().contains("IMAGE_UPLOAD_INPUT")) {
            caps.add("Images");
        }
        if (value.capabilities().contains("REASONING")) caps.add("Reasoning");
        return String.join(", ", caps);
    }

    private static String formatModelState(
            CodingModelOption value,
            io.haifa.agent.application.project.product.coding.CodingModelState.RunScope runScope) {
        java.util.List<String> states = new java.util.ArrayList<>();
        switch (value.state().connection()) {
            case LOGIN_REQUIRED -> states.add("LoginReq");
            case REAUTH_REQUIRED -> states.add("Re-Auth");
            case CONNECTED -> {}
        }
        switch (value.state().bindingAvailability()) {
            case UNAVAILABLE -> states.add("Unavail");
            case AVAILABLE -> {}
        }
        switch (value.state().runtime()) {
            case RATE_LIMITED -> states.add("RateLtd");
            case UNREACHABLE -> states.add("Unreachable");
            case NORMAL -> {}
        }
        if (runScope == io.haifa.agent.application.project.product.coding.CodingModelState.RunScope.ACTIVE_RUN) {
            states.add("ActiveRun");
        }
        return states.isEmpty() ? "Ready" : String.join(", ", states);
    }

    private void openModelDetails(CodingModelOption option) {
        inspectedModel = Objects.requireNonNull(option, "option must not be null");
        boolean selectable = option.state().bindingAvailability()
                == io.haifa.agent.application.project.product.coding.CodingModelState.BindingAvailability.AVAILABLE;
        var runScope = state.currentRunId().isPresent()
                ? io.haifa.agent.application.project.product.coding.CodingModelState.RunScope.ACTIVE_RUN
                : io.haifa.agent.application.project.product.coding.CodingModelState.RunScope.IDLE;
        String title = "Model details · "
                + option.displayName()
                + " · "
                + option.providerDisplayName()
                + " · "
                + formatContextWindow(option.contextWindow())
                + " context · "
                + formatContextWindow(option.maxOutputTokens())
                + " output · ["
                + formatCapabilities(option)
                + "] · ["
                + formatModelState(option, runScope)
                + "]";
        List<String> actions = selectable
                ? List.of("Confirm and select this model", "Review response settings", "Back to model list")
                : List.of("Unavailable: " + option.unavailableReason(), "Back to model list");
        apply(new TerminalUiAction.SelectorOpened(new TerminalSelector("model-detail", title, actions, 0)));
    }

    private void selectModelDetailAction(int selected) {
        CodingModelOption option = requireInspectedModel();
        boolean selectable = option.state().bindingAvailability()
                == io.haifa.agent.application.project.product.coding.CodingModelState.BindingAvailability.AVAILABLE;
        if (!selectable) {
            if (selected == 0)
                apply(new TerminalUiAction.StatusChanged(
                        "This model cannot be selected: " + option.unavailableReason()));
            else openModelList();
            return;
        }
        switch (selected) {
            case 0 -> confirmModelSelection(option);
            case 1 -> openModelSettings(option);
            default -> openModelList();
        }
    }

    private record CodingModelProviderGroup(String providerId, String providerDisplayName, int modelCount) {}

    private void openModelSettings(CodingModelOption option) {
        var response = option.controls().responseMode();
        var effort = option.controls().reasoningEffort();
        String responseValue = option.recommendedPreferences().responseMode().name();
        String effortValue =
                option.recommendedPreferences().effort().map(Enum::name).orElse("Profile default");
        List<String> settings = new java.util.ArrayList<>();
        if (response.visible()) {
            settings.add("Response mode: " + responseValue + " · " + response.effectiveSummary());
        }
        if (effort.visible()) {
            settings.add("Reasoning effort: " + effortValue + " · " + effort.effectiveSummary());
        }
        settings.add("Back to model details");
        apply(new TerminalUiAction.SelectorOpened(new TerminalSelector(
                "model-settings",
                "Verified response profile · " + option.displayName() + " (read-only in this release)",
                settings,
                0)));
    }

    private void selectModelSettingsAction(int selected) {
        CodingModelOption option = requireInspectedModel();
        int backIndex = (option.controls().responseMode().visible() ? 1 : 0)
                + (option.controls().reasoningEffort().visible() ? 1 : 0);
        if (selected == backIndex) {
            openModelDetails(option);
        } else {
            apply(new TerminalUiAction.StatusChanged("Response settings are defined by the verified model profile"));
        }
    }

    private CodingModelOption requireInspectedModel() {
        if (inspectedModel == null) throw new IllegalStateException("model detail selection is unavailable");
        return inspectedModel;
    }

    private void openModelList() {
        if (modelOptions.isEmpty()) {
            apply(new TerminalUiAction.RecoverableFailure("MODEL_LIST_EMPTY"));
            return;
        }
        CodingModelOption selectedModel = requireInspectedModel();
        if (modelProviderGroups(modelOptions).size() > 1) {
            openProviderModelList(selectedModel.providerId());
            return;
        }
        openModelOptions(modelOptions, selectedModel.id(), modelListTitle());
    }

    private void navigateSelectorParent() {
        TerminalSelector selector = state.selector().orElse(null);
        if (selector == null) {
            return;
        }
        switch (selector.kind()) {
            case "model" -> {
                if (modelProviderGroups(modelOptions).size() > 1) {
                    int selected = selector.selected();
                    String selectedProviderId = (selected >= 0 && selected < modelSelectionOptions.size())
                            ? modelSelectionOptions.get(selected).providerId()
                            : (inspectedModel != null ? inspectedModel.providerId() : null);
                    openModelProviderChooser(selectedProviderId);
                }
            }
            case "model-detail" -> openModelList();
            case "model-settings" -> openModelDetails(requireInspectedModel());
            default -> {}
        }
    }

    private void openModelChooser(String selectedModelId) {
        if (modelOptions.isEmpty()) {
            apply(new TerminalUiAction.RecoverableFailure("MODEL_LIST_EMPTY"));
            return;
        }
        modelProviderGroups = modelProviderGroups(modelOptions);
        if (modelProviderGroups.size() > 1) {
            String selectedProviderId = modelOptions.stream()
                    .filter(option -> option.id().equals(selectedModelId))
                    .map(CodingModelOption::providerId)
                    .findFirst()
                    .orElse(null);
            openModelProviderChooser(selectedProviderId);
            return;
        }
        openModelOptions(modelOptions, selectedModelId, modelListTitle());
    }

    private void openModelProviderChooser(String selectedProviderId) {
        modelProviderGroups = modelProviderGroups(modelOptions);
        int selectedProvider = java.util.stream.IntStream.range(0, modelProviderGroups.size())
                .filter(index -> modelProviderGroups.get(index).providerId().equals(selectedProviderId))
                .findFirst()
                .orElse(0);
        apply(new TerminalUiAction.SelectorOpened(new TerminalSelector(
                "model-provider",
                "Choose model provider",
                modelProviderGroups.stream()
                        .map(group -> group.providerDisplayName()
                                + " · "
                                + group.modelCount()
                                + " model"
                                + (group.modelCount() == 1 ? "" : "s"))
                        .toList(),
                selectedProvider)));
    }

    private void openProviderModelList(String providerId) {
        List<CodingModelOption> providerModels = modelOptions.stream()
                .filter(value -> value.providerId().equals(providerId))
                .toList();
        if (providerModels.isEmpty()) {
            apply(new TerminalUiAction.RecoverableFailure("MODEL_LIST_EMPTY"));
            return;
        }
        String selectedModelId =
                inspectedModel != null && inspectedModel.providerId().equals(providerId) ? inspectedModel.id() : null;
        openModelOptions(
                providerModels,
                selectedModelId,
                "Models from " + providerModels.getFirst().providerDisplayName());
    }

    private void openModelOptions(List<CodingModelOption> options, String selectedModelId, String title) {
        int selected = java.util.stream.IntStream.range(0, options.size())
                .filter(index -> options.get(index).id().equals(selectedModelId))
                .findFirst()
                .orElse(0);
        modelSelectionOptions = List.copyOf(options);
        apply(new TerminalUiAction.SelectorOpened(new TerminalSelector(
                "model", title, options.stream().map(this::modelListOption).toList(), selected)));
    }

    private String modelListTitle() {
        return state.session().isPresent() ? "Model for future new Runs" : "Model for next session";
    }

    private static List<CodingModelProviderGroup> modelProviderGroups(List<CodingModelOption> options) {
        java.util.LinkedHashMap<String, CodingModelProviderGroup> grouped = new java.util.LinkedHashMap<>();
        for (CodingModelOption option : options) {
            CodingModelProviderGroup existing = grouped.get(option.providerId());
            grouped.put(
                    option.providerId(),
                    existing == null
                            ? new CodingModelProviderGroup(option.providerId(), option.providerDisplayName(), 1)
                            : new CodingModelProviderGroup(
                                    existing.providerId(), existing.providerDisplayName(), existing.modelCount() + 1));
        }
        return List.copyOf(grouped.values());
    }

    /** Toggles one transcript item, used by pointer expansion as well as the keyboard path. */
    public void toggleTranscriptItem(String itemId) {
        if (itemId == null || itemId.isBlank()) return;
        apply(new TerminalUiAction.ToggleExpanded(itemId));
    }

    public void cycleModel(int delta) {
        if (state.currentRunId().isPresent()) {
            apply(new TerminalUiAction.StatusChanged("Cannot switch model during active run"));
            return;
        }
        if (state.selector().isPresent() || renamingSession) {
            return;
        }
        if (modelSwitchInProgress) {
            return;
        }
        if (modelOptions.isEmpty()) {
            modelSwitchInProgress = true;
            apply(new TerminalUiAction.StatusChanged("Loading models"));
            Optional<AgentSessionId> requestSessionId =
                    state.session().map(s -> s.summary().sessionId());
            boolean requestAwaitingNew = awaitingNewSessionMessage;
            submitEffect(
                    () -> {
                        List<CodingModelOption> loaded = client.models();
                        return () -> {
                            modelOptions = List.copyOf(loaded);
                            modelSwitchInProgress = false;
                            if (state.selector().isPresent()
                                    || state.currentRunId().isPresent()
                                    || renamingSession
                                    || !Objects.equals(
                                            state.session().map(s -> s.summary().sessionId()), requestSessionId)
                                    || awaitingNewSessionMessage != requestAwaitingNew) {
                                return;
                            }
                            performModelCycle(delta);
                        };
                    },
                    code -> {
                        modelSwitchInProgress = false;
                        apply(new TerminalUiAction.RecoverableFailure(code));
                    });
            return;
        }
        performModelCycle(delta);
    }

    private void performModelCycle(int delta) {
        if (state.selector().isPresent() || state.currentRunId().isPresent() || renamingSession) {
            return;
        }
        List<CodingModelOption> available = modelOptions.stream()
                .filter(option ->
                        option.state().bindingAvailability() == CodingModelState.BindingAvailability.AVAILABLE)
                .toList();
        if (available.isEmpty()) {
            apply(new TerminalUiAction.RecoverableFailure("MODEL_LIST_EMPTY"));
            return;
        }
        String currentModelId = (awaitingNewSessionMessage || state.session().isEmpty())
                ? (pendingNewSessionModelId != null
                        ? pendingNewSessionModelId
                        : state.session().map(s -> s.model().model().id()).orElse(null))
                : state.session().map(s -> s.model().model().id()).orElse(pendingNewSessionModelId);
        int currentIndex = -1;
        for (int i = 0; i < available.size(); i++) {
            if (available.get(i).id().equals(currentModelId)) {
                currentIndex = i;
                break;
            }
        }
        int nextIndex;
        if (currentIndex < 0) {
            nextIndex = delta >= 0 ? 0 : available.size() - 1;
        } else {
            nextIndex = Math.floorMod(currentIndex + delta, available.size());
        }
        CodingModelOption target = available.get(nextIndex);
        if (target.id().equals(currentModelId)) {
            return;
        }
        confirmModelSelection(target);
    }

    private void confirmModelSelection(CodingModelOption option) {
        Optional<RunEventCursor> cursor = state.appliedCursor();
        AgentRunId previousOutputRunId = outputRunId;
        RunOutputCursor previousOutputCursor = outputCursor;
        if (state.selector().map(s -> s.kind().startsWith("model")).orElse(false)) {
            apply(new TerminalUiAction.SelectorClosed());
        }
        if (awaitingNewSessionMessage || state.session().isEmpty()) {
            pendingNewSessionModelId = option.id();
            modelSwitchInProgress = false;
            apply(new TerminalUiAction.StatusChanged("Model selected for next session"));
        } else {
            CodingSessionView current = state.session().orElseThrow();
            modelSwitchInProgress = true;
            apply(new TerminalUiAction.StatusChanged("Changing model"));
            submitEffect(
                    () -> {
                        Runnable select =
                                selectModel(current, option.id(), cursor, previousOutputRunId, previousOutputCursor);
                        return () -> {
                            modelSwitchInProgress = false;
                            select.run();
                        };
                    },
                    code -> {
                        modelSwitchInProgress = false;
                        apply(new TerminalUiAction.RecoverableFailure(code));
                    });
        }
    }

    private String modelListOption(CodingModelOption value) {
        var runScope = state.currentRunId().isPresent()
                ? io.haifa.agent.application.project.product.coding.CodingModelState.RunScope.ACTIVE_RUN
                : io.haifa.agent.application.project.product.coding.CodingModelState.RunScope.IDLE;
        return value.displayName()
                + " · "
                + value.providerDisplayName()
                + " · "
                + formatContextWindow(value.contextWindow())
                + " · ["
                + formatCapabilities(value)
                + "] · ["
                + formatModelState(value, runScope)
                + "]";
    }

    private Runnable loadModels(
            Optional<CodingSessionView> current,
            String argument,
            Optional<RunEventCursor> cursor,
            AgentRunId previousOutputRunId,
            RunOutputCursor previousOutputCursor) {
        Optional<CodingSessionView> reconciled =
                current.map(value -> client.reconcile(value.summary().sessionId()));
        List<CodingModelOption> options = client.models();
        if (!argument.isBlank()) {
            if (reconciled.isPresent()) {
                return selectModel(
                        reconciled.orElseThrow(), argument, cursor, previousOutputRunId, previousOutputCursor);
            }
            CodingModelOption selected = options.stream()
                    .filter(option -> option.id().equals(argument))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("MODEL_UNAVAILABLE"));
            if (selected.state().bindingAvailability() != CodingModelState.BindingAvailability.AVAILABLE) {
                throw new IllegalArgumentException("MODEL_UNAVAILABLE");
            }
            return () -> {
                pendingNewSessionModelId = selected.id();
                apply(new TerminalUiAction.StatusChanged("Model selected for next session"));
            };
        }
        return () -> {
            modelOptions = List.copyOf(options);
            if (modelOptions.isEmpty()) {
                apply(new TerminalUiAction.RecoverableFailure("MODEL_LIST_EMPTY"));
                return;
            }
            String selectedModelId =
                    reconciled.map(value -> value.model().model().id()).orElse(pendingNewSessionModelId);
            inspectedModel = modelOptions.stream()
                    .filter(option -> option.id().equals(selectedModelId))
                    .findFirst()
                    .orElse(null);
            openModelChooser(selectedModelId);
        };
    }

    private Runnable selectModel(
            CodingSessionView current,
            String modelId,
            Optional<RunEventCursor> cursor,
            AgentRunId previousOutputRunId,
            RunOutputCursor previousOutputCursor) {
        client.selectModel(
                current.summary().sessionId(),
                modelId,
                current.model().revision(),
                UUID.randomUUID().toString());
        LoadedSession loaded = readSession(
                client.open(current.summary().sessionId()), cursor, previousOutputRunId, previousOutputCursor);
        return () -> {
            applyLoadedSession(loaded);
            apply(new TerminalUiAction.StatusChanged("Model changed for future new Runs"));
        };
    }

    private void scheduleReconcile() {
        if (reconcileInFlight || state.session().isEmpty()) return;
        reconcileInFlight = true;
        AgentSessionId sessionId = state.session().orElseThrow().summary().sessionId();
        Optional<RunEventCursor> cursor = state.appliedCursor();
        AgentRunId previousOutputRunId = outputRunId;
        RunOutputCursor previousOutputCursor = outputCursor;
        submitMaintenanceEffect(
                () -> {
                    LoadedSession loaded =
                            readSession(client.reconcile(sessionId), cursor, previousOutputRunId, previousOutputCursor);
                    return () -> {
                        reconcileInFlight = false;
                        applyLoadedSession(loaded);
                    };
                },
                code -> {
                    reconcileInFlight = false;
                    apply(new TerminalUiAction.RecoverableFailure(code));
                });
    }

    private void apply(TerminalUiAction action) {
        if (action instanceof TerminalUiAction.RunOutputReceived received) {
            if (!received.event().runId().equals(outputRunId)
                    || received.event().sequence() <= outputCursor.sequence()) {
                return;
            }
            outputCursor = new RunOutputCursor(received.event().sequence());
        }
        state = reducer.reduce(state, action);
        if (action instanceof TerminalUiAction.AuthenticationCompleted) {
            scheduleReconcile();
        }
        if (action instanceof TerminalUiAction.RunEventReceived received
                && received.event().payload() instanceof RunEventPayloads.InteractionLifecycle lifecycle
                && !lifecycle.state().equals("PENDING")
                && !lifecycle.state().equals("REQUESTED")
                && activeInteraction != null
                && activeInteraction.requestId().value().equals(lifecycle.requestId())) {
            activeInteraction = null;
        }
        if (action instanceof TerminalUiAction.RunEventReceived received
                && state.appliedCursor()
                        .filter(received.event().cursor()::equals)
                        .isPresent()
                && state.session().isPresent()) {
            pendingAcknowledgement = received.event().cursor();
        }
    }

    private void schedulePendingCursorAcknowledgement() {
        if (acknowledgementInFlight
                || pendingAcknowledgement == null
                || state.session().isEmpty()) return;
        RunEventCursor requested = pendingAcknowledgement;
        AgentSessionId sessionId = state.session().orElseThrow().summary().sessionId();
        acknowledgementInFlight = true;
        submitMaintenanceEffect(
                () -> {
                    RunEventCursor acknowledged = client.acknowledgeCursor(sessionId, requested);
                    return () -> completeCursorAcknowledgement(requested, acknowledged);
                },
                ignored -> acknowledgementInFlight = false);
    }

    private void completeCursorAcknowledgement(RunEventCursor requested, RunEventCursor acknowledged) {
        acknowledgementInFlight = false;
        if (acknowledged.runId().equals(requested.runId())
                && acknowledged.exclusiveSequence().orElse(0L)
                        >= requested.exclusiveSequence().orElse(0L)
                && pendingAcknowledgement != null
                && pendingAcknowledgement.runId().equals(requested.runId())
                && pendingAcknowledgement.exclusiveSequence().orElse(0L)
                        <= acknowledged.exclusiveSequence().orElse(0L)) {
            pendingAcknowledgement = null;
        }
    }

    private void detachSubscriptions() {
        RunEventSubscription previous = subscription;
        RunOutputSubscription previousOutput = outputSubscription;
        subscription = null;
        outputSubscription = null;
        outputRunId = null;
        outputCursor = RunOutputCursor.BEFORE_FIRST;
        closeSubscriptionsInBackground(previous, previousOutput);
    }

    private void closeSubscriptionsInBackground(RunEventSubscription previous, RunOutputSubscription previousOutput) {
        if (previous == null && previousOutput == null) return;
        try {
            maintenanceEffects.execute(() -> closeSubscriptions(previous, previousOutput));
        } catch (RejectedExecutionException ignored) {
            if (previous != null) deferredSubscriptionCloses.add(previous);
            if (previousOutput != null) deferredSubscriptionCloses.add(previousOutput);
        }
    }

    private static void closeSubscriptions(RunEventSubscription previous, RunOutputSubscription previousOutput) {
        if (previous != null) previous.close();
        if (previousOutput != null) previousOutput.close();
    }

    @Override
    public void close() {
        closeSubscriptions(subscription, outputSubscription);
        subscription = null;
        outputSubscription = null;
        if (previewSubscription != null) previewSubscription.close();
        previewSubscription = null;
        previewRunId = null;
        if (ownedControlEffects != null) ownedControlEffects.shutdownNow();
        if (ownedEffects != null) ownedEffects.shutdownNow();
        if (ownedMaintenanceEffects != null) ownedMaintenanceEffects.shutdownNow();
        AutoCloseable deferred;
        while ((deferred = deferredSubscriptionCloses.poll()) != null) {
            closeQuietly(deferred);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best-effort cleanup during controller shutdown.
        }
    }

    public record LoadedSession(
            CodingSessionView view,
            List<String> resources,
            io.haifa.agent.application.project.product.coding.CodingSessionHistoryPage history,
            List<PendingMessage> pending,
            List<AgentRunEvent> events,
            RunEventSubscription subscription,
            RunOutputSubscription outputSubscription,
            AgentRunId outputRunId,
            RunOutputCursor outputCursor) {
        public LoadedSession {
            view = Objects.requireNonNull(view, "view must not be null");
            resources = List.copyOf(resources);
            history = Objects.requireNonNull(history, "history must not be null");
            pending = List.copyOf(pending);
            events = List.copyOf(events);
            outputCursor = Objects.requireNonNull(outputCursor, "outputCursor must not be null");
        }
    }

    private record CompletionContext(String buffer, int start, int end) {
        private CompletionContext {
            Objects.requireNonNull(buffer, "buffer must not be null");
            if (start < 0 || end < start || end > buffer.length()) {
                throw new IllegalArgumentException("completion range is invalid");
            }
        }
    }

    private record InteractionHydrationRequest(AgentRunId runId, String requestId) {
        private InteractionHydrationRequest {
            Objects.requireNonNull(runId, "runId must not be null");
            requestId = Objects.requireNonNull(requestId, "requestId must not be null")
                    .strip();
            if (requestId.isEmpty()) throw new IllegalArgumentException("requestId must not be blank");
        }
    }
}
