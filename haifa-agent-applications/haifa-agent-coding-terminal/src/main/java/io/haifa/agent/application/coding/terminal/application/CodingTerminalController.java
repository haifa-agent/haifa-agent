package io.haifa.agent.application.coding.terminal.application;

import io.haifa.agent.application.coding.terminal.event.TerminalEventPump;
import io.haifa.agent.application.coding.terminal.event.TerminalInput;
import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.application.coding.terminal.state.PendingMessage;
import io.haifa.agent.application.coding.terminal.state.TerminalSelector;
import io.haifa.agent.application.coding.terminal.state.TerminalUiReducer;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.application.project.product.coding.CodingModelOption;
import io.haifa.agent.application.project.product.coding.CodingQueuedMessage;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.application.project.product.coding.CodingShellPlan;
import io.haifa.agent.application.project.product.coding.CodingShellResult;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationClient;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationView;
import io.haifa.agent.application.project.product.coding.client.CodingSessionClient;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
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
    private io.haifa.agent.core.run.AgentRunId outputRunId;
    private RunOutputCursor outputCursor = RunOutputCursor.BEFORE_FIRST;
    private boolean awaitingNewSessionMessage;
    private List<CodingSessionSummary> resumeOptions = List.of();
    private List<CodingQueuedMessage> restoreOptions = List.of();
    private List<CodingModelOption> modelOptions = List.of();
    private List<CodingAuthenticationView> authenticationOptions = List.of();
    private CompletionContext completionContext;
    private CodingShellPlan pendingShellPlan;
    private String pendingApiKeyProvider;
    private RunEventCursor pendingAcknowledgement;
    private boolean acknowledgementInFlight;
    private boolean reconcileInFlight;
    private InteractionHydrationRequest interactionHydrationInFlight;
    private InteractionView activeInteraction;
    private long activeCompletionRequestId;

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

    public TerminalUiState state() {
        return state;
    }

    /**
     * Starts a normal conversation submission without performing Runtime or storage IO.
     *
     * <p>The terminal adapter can render the cleared editor and optimistic user message before it executes the
     * returned submission on a command worker. Slash commands, shell commands, selectors, and empty input continue
     * through {@link #accept(TerminalInput)} and therefore return an empty result here.
     */
    public Optional<PreparedMessageSubmission> prepareMessageSubmission(TerminalInput input) {
        Objects.requireNonNull(input, "input must not be null");
        drainEvents();
        if ((input.kind() != TerminalInput.Kind.SUBMIT && input.kind() != TerminalInput.Kind.FOLLOW_UP)
                || state.selector().isPresent()
                || input.text().isBlank()
                || input.text().startsWith("!")
                || commands.route(input.text()) != TerminalCommand.MESSAGE) {
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
                view = client.create(projectId, submission.text(), submission.idempotencyKey());
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
            apply(new TerminalUiAction.UserMessageRejected(result.submission().idempotencyKey()));
            if (state.editorBuffer().isBlank()) {
                apply(new TerminalUiAction.EditorChanged(
                        result.submission().text(), result.submission().text().length()));
            }
            apply(new TerminalUiAction.RecoverableFailure(result.failureCode().orElseThrow()));
            return;
        }
        try {
            applyLoadedSession(result.loadedSession().orElseThrow());
        } catch (ProjectProductException exception) {
            apply(new TerminalUiAction.RecoverableFailure(exception.code()));
        }
    }

    /** Loads the initial session before the TUI event loop starts; interactive session changes use background effects. */
    public void open(AgentSessionId sessionId) {
        applyLoadedSession(readSession(client.open(sessionId), Optional.empty(), null, RunOutputCursor.BEFORE_FIRST));
    }

    /** Applies a bounded startup Resume intent before the TUI event loop starts. */
    public void start(CodingTerminalStartup startup) {
        Objects.requireNonNull(startup, "startup must not be null");
        try {
            switch (startup.mode()) {
                case EMPTY -> {}
                case SELECTOR -> showResumeOptions(client.list(projectId, 50));
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
            if (state.selector().isEmpty() && authentication.connectionRequired()) {
                apply(new TerminalUiAction.SelectorOpened(
                        new TerminalSelector("auth-login", "Connect a model to get started", connectionOptions(), 0)));
                apply(new TerminalUiAction.StatusChanged("A model connection is required before the first prompt"));
            }
        } catch (ProjectProductException exception) {
            apply(new TerminalUiAction.RecoverableFailure(exception.code()));
        } catch (IllegalArgumentException
                | IllegalStateException
                | SecurityException
                | UnsupportedOperationException exception) {
            apply(new TerminalUiAction.RecoverableFailure(safeFailureCode(exception)));
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

    private void acceptGuarded(TerminalInput input) {
        drainEvents();
        if (input.kind() == TerminalInput.Kind.TICK) {
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
        if (input.kind() == TerminalInput.Kind.CANCEL_OR_CLOSE) {
            if (state.currentRunId().isPresent() && cancelCurrentRunIfPresent()) {
                return;
            }
            if (state.selector().isPresent()) {
                discardPendingShell();
                apply(new TerminalUiAction.SelectorClosed());
                completionContext = null;
                return;
            }
            if (cancelCurrentRunIfPresent()) {
                return;
            }
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
            } else if (!cancelCurrentRunIfPresent()) {
                apply(new TerminalUiAction.ExitRequested());
            }
            return;
        }
        if (input.kind() == TerminalInput.Kind.RESTORE) {
            openRestoreSelector();
            return;
        }
        if (input.kind() == TerminalInput.Kind.SELECT_PREVIOUS || input.kind() == TerminalInput.Kind.SELECT_NEXT) {
            return;
        }
        if (input.kind() == TerminalInput.Kind.TOGGLE_EXPANSION) {
            state.transcript().stream()
                    .filter(value -> value.kind()
                                    == io.haifa.agent.application.coding.terminal.state.TranscriptItem.Kind.TOOL
                            || value.kind()
                                    == io.haifa.agent.application.coding.terminal.state.TranscriptItem.Kind.EXECUTION)
                    .reduce((first, second) -> second)
                    .ifPresent(value -> apply(new TerminalUiAction.ToggleExpanded(value.id())));
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
        if (awaitingNewSessionMessage || sessionId.isEmpty()) {
            awaitingNewSessionMessage = false;
            sessionId = Optional.empty();
            activeRunId = Optional.empty();
        }
        apply(new TerminalUiAction.EditorChanged("", 0));
        apply(new TerminalUiAction.UserMessageCommitted(key, text));
        apply(new TerminalUiAction.StatusChanged("Submitting"));
        return new PreparedMessageSubmission(
                text, followUp, key, sessionId, activeRunId, state.appliedCursor(), outputRunId, outputCursor);
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
        apply(new TerminalUiAction.ShellCompleted(prefix + plan.safeCommand(), result.safeSummary(), result.status()));
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
            Optional<RunEventCursor> appliedCursor,
            AgentRunId outputRunId,
            RunOutputCursor outputCursor) {
        public PreparedMessageSubmission {
            text = Objects.requireNonNull(text, "text must not be null");
            idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
            sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
            activeRunId = Objects.requireNonNull(activeRunId, "activeRunId must not be null");
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
                CodingSessionView current = requireCurrentSession();
                apply(new TerminalUiAction.StatusChanged("Renaming session"));
                submitEffect(
                        () -> {
                            CodingSessionView reconciled =
                                    client.reconcile(current.summary().sessionId());
                            CodingSessionSummary renamed = client.rename(
                                    reconciled.summary().sessionId(),
                                    argument,
                                    reconciled.summary().revision());
                            LoadedSession loaded = readSession(
                                    client.open(renamed.sessionId()),
                                    Optional.empty(),
                                    null,
                                    RunOutputCursor.BEFORE_FIRST);
                            return () -> {
                                applyLoadedSession(loaded);
                                apply(new TerminalUiAction.StatusChanged("Session renamed"));
                            };
                        },
                        code -> apply(new TerminalUiAction.RecoverableFailure(code)));
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
            case LOGIN -> {
                String[] loginArguments =
                        argument.toLowerCase(java.util.Locale.ROOT).split("\\s+", 2);
                String method = loginArguments.length == 0 ? "" : loginArguments[0];
                if (method.isEmpty()) {
                    apply(new TerminalUiAction.SelectorOpened(
                            new TerminalSelector("auth-login", "Connect a model", connectionOptions(), 0)));
                } else if (method.equals("chatgpt") || method.equals("codex")) {
                    if (loginArguments.length == 1 || loginArguments[1].isBlank()) {
                        openChatGptLoginSelector();
                    } else if (loginArguments[1].equals("browser")) {
                        startCodexLogin();
                    } else if (loginArguments[1].equals("device")) {
                        startCodexDeviceLogin();
                    } else {
                        apply(new TerminalUiAction.RecoverableFailure("AUTH_METHOD_UNSUPPORTED"));
                    }
                } else if (method.equals("api")) {
                    beginApiKeyInput(
                            loginArguments.length == 2 ? loginArguments[1] : authentication.apiKeyProviderId());
                } else {
                    apply(new TerminalUiAction.RecoverableFailure("AUTH_METHOD_UNSUPPORTED"));
                }
            }
            case ACCOUNT -> loadAuthenticationOptions(false);
            case LOGOUT -> loadAuthenticationOptions(true);
            case MODEL -> {
                CodingSessionView current = requireCurrentSession();
                Optional<RunEventCursor> cursor = state.appliedCursor();
                AgentRunId previousOutputRunId = outputRunId;
                RunOutputCursor previousOutputCursor = outputCursor;
                apply(new TerminalUiAction.StatusChanged("Loading models"));
                submitEffect(
                        () -> loadModels(current, argument, cursor, previousOutputRunId, previousOutputCursor),
                        code -> apply(new TerminalUiAction.RecoverableFailure(code)));
            }
            case SETTINGS, TRUST ->
                apply(new TerminalUiAction.RecoverableFailure(TerminalCommandRouter.CAPABILITY_NOT_IMPLEMENTED));
            case SESSION -> {
                List<String> options = state.session()
                        .map(value -> List.of(
                                "session " + value.summary().sessionId().value(),
                                "profile " + value.productProfileRef()))
                        .orElseGet(() -> List.of("No active session"));
                apply(new TerminalUiAction.SelectorOpened(new TerminalSelector("session", "Session", options, 0)));
            }
            case COMMANDS -> openCommandSelector();
            case QUIT -> apply(new TerminalUiAction.ExitRequested());
            case NOT_IMPLEMENTED ->
                apply(new TerminalUiAction.RecoverableFailure(TerminalCommandRouter.CAPABILITY_NOT_IMPLEMENTED));
            case UNKNOWN -> apply(new TerminalUiAction.RecoverableFailure(TerminalCommandRouter.COMMAND_UNKNOWN));
            case MESSAGE -> throw new IllegalStateException("message must be routed separately");
        }
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
        closeSubscriptionsInBackground(previousSubscription, previousOutputSubscription);
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
        apply(new TerminalUiAction.StatusChanged("Opening browser for ChatGPT sign-in"));
        submitEffect(
                () -> {
                    CodingAuthenticationView connected = authentication.loginCodexBrowser(
                            instructions -> pump.offer(new TerminalUiAction.BrowserLoginInstructionsPresented(
                                    instructions.authorizationUri().toString())),
                            progress -> pump.offer(new TerminalUiAction.AuthenticationProgressed(progress.phase())));
                    return () -> pump.offer(
                            new TerminalUiAction.AuthenticationCompleted(connected.unofficialLocalCompatibility()));
                },
                code -> pump.offer(new TerminalUiAction.AuthenticationFailed(code)));
    }

    private void openChatGptLoginSelector() {
        apply(new TerminalUiAction.SelectorOpened(new TerminalSelector(
                "auth-chatgpt",
                "ChatGPT subscription login",
                List.of("Browser callback", "Device code (headless)"),
                0)));
    }

    private List<String> connectionOptions() {
        return authentication.apiKeyConnectionSupported()
                ? List.of("ChatGPT subscription", "Provider API key (secure input)")
                : List.of("ChatGPT subscription");
    }

    private void startCodexDeviceLogin() {
        apply(new TerminalUiAction.StatusChanged("Requesting a ChatGPT device code"));
        submitEffect(
                () -> {
                    CodingAuthenticationView connected = authentication.loginCodexDevice(
                            instruction -> pump.offer(new TerminalUiAction.DeviceLoginInstructionsPresented(
                                    instruction.verificationUri().toString(), instruction.userCode())),
                            progress -> pump.offer(new TerminalUiAction.AuthenticationProgressed(progress.phase())));
                    return () -> pump.offer(
                            new TerminalUiAction.AuthenticationCompleted(connected.unofficialLocalCompatibility()));
                },
                code -> pump.offer(new TerminalUiAction.AuthenticationFailed(code)));
    }

    private void beginApiKeyInput(String providerId) {
        String normalized = Objects.requireNonNull(providerId, "providerId must not be null")
                .strip()
                .toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty() || !normalized.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            apply(new TerminalUiAction.RecoverableFailure("AUTH_PROVIDER_INVALID"));
            return;
        }
        pendingApiKeyProvider = normalized;
        apply(new TerminalUiAction.StatusChanged(
                "Enter API key for " + normalized + " (stored as plaintext on this personal computer)"));
    }

    public boolean secureInputRequested() {
        return pendingApiKeyProvider != null;
    }

    public void cancelSecureInput() {
        if (pendingApiKeyProvider == null) {
            return;
        }
        pendingApiKeyProvider = null;
        apply(new TerminalUiAction.StatusChanged("API key entry cancelled"));
    }

    public void submitApiKey(char[] apiKey) {
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        String providerId = pendingApiKeyProvider;
        pendingApiKeyProvider = null;
        if (providerId == null) {
            java.util.Arrays.fill(apiKey, '\0');
            apply(new TerminalUiAction.RecoverableFailure("AUTH_SECURE_INPUT_NOT_REQUESTED"));
            return;
        }
        char[] owned = java.util.Arrays.copyOf(apiKey, apiKey.length);
        java.util.Arrays.fill(apiKey, '\0');
        apply(new TerminalUiAction.StatusChanged("Saving API key for " + providerId));
        try {
            effects.execute(() -> {
                Runnable completion;
                try {
                    CodingAuthenticationView connected = authentication.saveApiKey(providerId, owned);
                    completion = () -> apply(
                            new TerminalUiAction.StatusChanged("API key connected for " + connected.accountLabel()));
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

    private void loadAuthenticationOptions(boolean logout) {
        apply(new TerminalUiAction.StatusChanged(logout ? "Loading model accounts" : "Loading account status"));
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
            apply(new TerminalUiAction.StatusChanged("No model account is connected; use /login"));
            return;
        }
        List<String> options = authenticationOptions.stream()
                .map(connection -> connection.method()
                        + " | "
                        + connection.accountLabel()
                        + (connection.unofficialLocalCompatibility() ? " | UNOFFICIAL_LOCAL_COMPAT" : ""))
                .toList();
        apply(new TerminalUiAction.SelectorOpened(new TerminalSelector(
                logout ? "auth-logout" : "auth-account",
                logout ? "Disconnect model account" : "Model account",
                options,
                0)));
    }

    private void openCommandSelector() {
        completionContext = new CompletionContext("", 0, 0);
        apply(new TerminalUiAction.SelectorOpened(
                new TerminalSelector("completion", "Commands", TerminalCompletionProvider.COMMANDS, 0)));
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
        if (input.kind() == TerminalInput.Kind.EOF) {
            apply(new TerminalUiAction.SelectorClosed());
            completionContext = null;
            return;
        }
        if (input.kind() != TerminalInput.Kind.SUBMIT) {
            return;
        }
        TerminalSelector selector = state.selector().orElseThrow();
        int selected = selector.selected();
        switch (selector.kind()) {
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
            case "session" -> apply(new TerminalUiAction.SelectorClosed());
            case "model" -> {
                CodingSessionView current = requireCurrentSession();
                CodingModelOption option = modelOptions.get(selected);
                Optional<RunEventCursor> cursor = state.appliedCursor();
                AgentRunId previousOutputRunId = outputRunId;
                RunOutputCursor previousOutputCursor = outputCursor;
                apply(new TerminalUiAction.SelectorClosed());
                apply(new TerminalUiAction.StatusChanged("Changing model"));
                submitEffect(
                        () -> selectModel(current, option.id(), cursor, previousOutputRunId, previousOutputCursor),
                        code -> apply(new TerminalUiAction.RecoverableFailure(code)));
            }
            case "auth-login" -> {
                apply(new TerminalUiAction.SelectorClosed());
                if (selected == 0) {
                    openChatGptLoginSelector();
                } else {
                    beginApiKeyInput(authentication.apiKeyProviderId());
                }
            }
            case "auth-chatgpt" -> {
                apply(new TerminalUiAction.SelectorClosed());
                if (selected == 0) {
                    startCodexLogin();
                } else {
                    startCodexDeviceLogin();
                }
            }
            case "auth-account" -> apply(new TerminalUiAction.SelectorClosed());
            case "auth-logout" -> {
                CodingAuthenticationView connection = authenticationOptions.get(selected);
                apply(new TerminalUiAction.SelectorClosed());
                apply(new TerminalUiAction.StatusChanged("Disconnecting model account"));
                submitEffect(
                        () -> {
                            boolean removed = authentication.logout(connection.connectionId());
                            return () -> apply(new TerminalUiAction.StatusChanged(
                                    removed ? "Model account disconnected" : "Model account was already absent"));
                        },
                        code -> apply(new TerminalUiAction.RecoverableFailure(code)));
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
                        code -> {
                            openInteractionSelector(interaction);
                            apply(new TerminalUiAction.RecoverableFailure(code));
                        });
            }
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

    private Runnable loadModels(
            CodingSessionView current,
            String argument,
            Optional<RunEventCursor> cursor,
            AgentRunId previousOutputRunId,
            RunOutputCursor previousOutputCursor) {
        CodingSessionView reconciled = client.reconcile(current.summary().sessionId());
        List<CodingModelOption> options = client.models();
        if (!argument.isBlank()) {
            return selectModel(reconciled, argument, cursor, previousOutputRunId, previousOutputCursor);
        }
        return () -> {
            modelOptions = List.copyOf(options);
            if (modelOptions.isEmpty()) {
                apply(new TerminalUiAction.RecoverableFailure("MODEL_LIST_EMPTY"));
                return;
            }
            int selected = java.util.stream.IntStream.range(0, modelOptions.size())
                    .filter(index -> modelOptions
                            .get(index)
                            .id()
                            .equals(reconciled.model().model().id()))
                    .findFirst()
                    .orElse(0);
            apply(new TerminalUiAction.SelectorOpened(new TerminalSelector(
                    "model",
                    "Model for future new Runs",
                    modelOptions.stream()
                            .map(value -> value.displayName() + " · " + value.providerDisplayName())
                            .toList(),
                    selected)));
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
