package io.haifa.agent.application.coding.terminal.application;

import io.haifa.agent.application.coding.terminal.event.TerminalEventPump;
import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.application.coding.terminal.jline.JLineCompleter;
import io.haifa.agent.application.coding.terminal.jline.TerminalInput;
import io.haifa.agent.application.coding.terminal.session.CodingSessionClient;
import io.haifa.agent.application.coding.terminal.state.PendingMessage;
import io.haifa.agent.application.coding.terminal.state.TerminalSelector;
import io.haifa.agent.application.coding.terminal.state.TerminalUiReducer;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.application.project.product.coding.CodingQueuedMessage;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.application.project.product.coding.CodingShellPlan;
import io.haifa.agent.application.project.product.coding.CodingShellResult;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.RunEventSubscription;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Single-threaded application controller. Runtime callbacks only enqueue actions. */
public final class CodingTerminalController implements AutoCloseable {
    private static final int PAGE_SIZE = 200;
    private static final Set<String> RETRYABLE_SESSION_RACES =
            Set.of("ACTIVE_RUN_SETTLED", "ACTIVE_RUN_MISMATCH", "CODING_SESSION_ACTIVE");

    private final ProjectId projectId;
    private final CodingSessionClient client;
    private final TerminalEventPump pump;
    private final TerminalUiReducer reducer;
    private final TerminalCommandRouter commands = new TerminalCommandRouter();
    private final JLineCompleter completions;
    private TerminalUiState state;
    private RunEventSubscription subscription;
    private boolean awaitingNewSessionMessage;
    private List<CodingSessionSummary> resumeOptions = List.of();
    private List<CodingQueuedMessage> restoreOptions = List.of();
    private CompletionContext completionContext;
    private CodingShellPlan pendingShellPlan;

    public CodingTerminalController(
            ProjectId projectId,
            CodingSessionClient client,
            TerminalEventPump pump,
            TerminalUiReducer reducer,
            TerminalUiState initialState) {
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.pump = Objects.requireNonNull(pump, "pump must not be null");
        this.reducer = Objects.requireNonNull(reducer, "reducer must not be null");
        this.state = Objects.requireNonNull(initialState, "initialState must not be null");
        this.completions = new JLineCompleter(client::logicalPaths);
    }

    public TerminalUiState state() {
        return state;
    }

    public void open(AgentSessionId sessionId) {
        load(client.open(sessionId));
        replayAndTail();
    }

    public void drainEvents() {
        try {
            drainEventsGuarded();
        } catch (ProjectProductException exception) {
            apply(new TerminalUiAction.RecoverableFailure(exception.code()));
        }
    }

    private void drainEventsGuarded() {
        boolean reconcile = false;
        for (TerminalUiAction action : pump.drain(PAGE_SIZE)) {
            apply(action);
            if (action instanceof TerminalUiAction.RunEventReceived received
                    && shouldReconcile(received.event().payload())) {
                reconcile = true;
            }
        }
        if (reconcile && state.session().isPresent()) {
            refresh();
        }
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
            if (cancelCurrentRunIfPresent()) {
                return;
            }
            if (state.selector().isPresent()) {
                discardPendingShell();
                apply(new TerminalUiAction.SelectorClosed());
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
        String key = UUID.randomUUID().toString();
        if (awaitingNewSessionMessage || state.session().isEmpty()) {
            awaitingNewSessionMessage = false;
            CodingSessionView created = client.create(projectId, text, key);
            apply(new TerminalUiAction.EditorChanged("", 0));
            try {
                load(created);
                apply(new TerminalUiAction.UserMessageCommitted(key, text));
                replayAndTail();
            } catch (ProjectProductException exception) {
                apply(new TerminalUiAction.UserMessageCommitted(key, text));
                apply(new TerminalUiAction.RecoverableFailure(exception.code()));
            }
            return;
        }
        sendToCurrentSession(text, followUp, key, true);
        apply(new TerminalUiAction.EditorChanged("", 0));
        apply(new TerminalUiAction.UserMessageCommitted(key, text));
        try {
            refresh();
        } catch (ProjectProductException exception) {
            apply(new TerminalUiAction.RecoverableFailure(exception.code()));
        }
    }

    private void shell(String input) {
        CodingSessionView current = requireCurrentSession();
        boolean includeInContext = !input.startsWith("!!");
        int prefix = includeInContext ? 1 : 2;
        String command = input.substring(prefix).strip();
        CodingShellPlan plan = client.planShell(current.summary().sessionId(), command, includeInContext);
        apply(new TerminalUiAction.EditorChanged("", 0));
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
        completeShell(plan, false);
    }

    private void completeShell(CodingShellPlan plan, boolean approved) {
        CodingShellResult result = client.executeShell(plan.token(), approved);
        String prefix = plan.includeInContext() ? "!" : "!!";
        apply(new TerminalUiAction.ShellCompleted(prefix + plan.safeCommand(), result.safeSummary(), result.status()));
        apply(new TerminalUiAction.StatusChanged(
                result.includedInContext()
                        ? "Shell result added to Session context"
                        : "Shell result excluded from model context"));
    }

    private void sendToCurrentSession(String text, boolean followUp, String key, boolean retrySessionRace) {
        AgentSessionId sessionId = state.session().orElseThrow().summary().sessionId();
        try {
            if (state.currentRunId().isPresent()) {
                if (followUp) {
                    client.enqueueFollowUp(sessionId, state.currentRunId().orElseThrow(), text, key);
                } else {
                    client.steer(sessionId, state.currentRunId().orElseThrow(), text, key);
                }
            } else {
                client.submit(sessionId, text, key);
            }
        } catch (ProjectProductException exception) {
            if (!retrySessionRace || !RETRYABLE_SESSION_RACES.contains(exception.code())) {
                throw exception;
            }
            refresh();
            sendToCurrentSession(text, followUp, key, false);
        }
    }

    private void command(TerminalCommand command, String rawInput) {
        String argument = commandArgument(rawInput);
        switch (command) {
            case NEW -> {
                closeSubscription();
                awaitingNewSessionMessage = true;
                apply(new TerminalUiAction.StatusChanged("New session: enter the first message"));
            }
            case RESUME -> {
                resumeOptions = client.search(projectId, argument, 50);
                var options = resumeOptions.stream()
                        .map(summary -> summary.sessionId().value() + " · " + summary.displayName() + " · "
                                + summary.status().name())
                        .toList();
                if (options.isEmpty()) {
                    apply(new TerminalUiAction.RecoverableFailure("SESSION_LIST_EMPTY"));
                } else {
                    apply(new TerminalUiAction.SelectorOpened(
                            new TerminalSelector("resume", "Resume session", options, 0)));
                }
            }
            case RENAME -> {
                CodingSessionView current = refresh();
                CodingSessionSummary renamed = client.rename(
                        current.summary().sessionId(),
                        argument,
                        current.summary().revision());
                load(client.open(renamed.sessionId()));
                apply(new TerminalUiAction.StatusChanged("Session renamed"));
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
                List<String> resources = client.reloadResources();
                apply(new TerminalUiAction.ResourcesChanged(resources));
                apply(new TerminalUiAction.StatusChanged("Resources reloaded for future new Runs"));
            }
            case COMPACT -> {
                CodingSessionView current = refresh();
                var result = client.compact(current.summary().sessionId(), argument);
                apply(new TerminalUiAction.ContextChanged(result.safeIndicator()));
                apply(new TerminalUiAction.StatusChanged("Session context compacted"));
            }
            case EXPORT -> {
                CodingSessionView current = refresh();
                var exported = client.export(current.summary().sessionId(), argument);
                apply(new TerminalUiAction.ExportCompleted(exported.logicalPath(), exported.messageCount()));
                apply(new TerminalUiAction.StatusChanged("Session exported"));
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
        if (state.currentRunId().isEmpty() && state.session().isPresent()) {
            refresh();
        }
        if (state.currentRunId().isEmpty()) {
            return false;
        }
        if (state.selector().isPresent()) {
            apply(new TerminalUiAction.SelectorClosed());
            completionContext = null;
        }
        apply(new TerminalUiAction.StatusChanged("Cancelling"));
        client.cancel(
                state.session().orElseThrow().summary().sessionId(),
                UUID.randomUUID().toString());
        return true;
    }

    private CodingSessionView refresh() {
        AgentSessionId sessionId = state.session().orElseThrow().summary().sessionId();
        CodingSessionView reconciled = client.reconcile(sessionId);
        load(reconciled);
        replayAndTail();
        return reconciled;
    }

    private void load(CodingSessionView view) {
        apply(new TerminalUiAction.SessionLoaded(view, client.loadedResources()));
        List<PendingMessage> pending = client.restorableMessages(view.summary().sessionId(), 100).stream()
                .map(value -> new PendingMessage(
                        value.followUpId(), PendingMessage.Kind.FOLLOW_UP, value.summary(), value.revision()))
                .toList();
        apply(new TerminalUiAction.PendingChanged(pending));
        view.pendingInteraction().ifPresent(this::openInteractionSelector);
    }

    private void openRestoreSelector() {
        if (state.session().isEmpty()) {
            apply(new TerminalUiAction.RecoverableFailure("SESSION_REQUIRED"));
            return;
        }
        AgentSessionId sessionId = state.session().orElseThrow().summary().sessionId();
        restoreOptions = client.restorableMessages(sessionId, 100);
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
                "interaction:" + interaction.requestId().value(), interaction.safePrompt(), options, 0)));
    }

    private void openCommandSelector() {
        completionContext = new CompletionContext("", 0, 0);
        apply(new TerminalUiAction.SelectorOpened(
                new TerminalSelector("completion", "Commands", JLineCompleter.COMMANDS, 0)));
    }

    private void openCompletionSelector(String buffer, int cursor) {
        int start = cursor;
        while (start > 0 && !Character.isWhitespace(buffer.charAt(start - 1))) {
            start--;
        }
        int end = cursor;
        while (end < buffer.length() && !Character.isWhitespace(buffer.charAt(end))) {
            end++;
        }
        String word = buffer.substring(start, cursor);
        List<String> options = completions.suggestions(word);
        if (options.isEmpty()) {
            return;
        }
        completionContext = new CompletionContext(buffer, start, end);
        String title = word.startsWith("@") ? "Workspace files" : "Commands";
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
                String completed = context.buffer().substring(0, context.start())
                        + replacement
                        + context.buffer().substring(context.end());
                int cursor = context.start() + replacement.length();
                apply(new TerminalUiAction.SelectorClosed());
                apply(new TerminalUiAction.EditorChanged(completed, cursor));
                completionContext = null;
            }
            case "resume" -> {
                AgentSessionId sessionId = resumeOptions.get(selected).sessionId();
                apply(new TerminalUiAction.SelectorClosed());
                open(sessionId);
            }
            case "restore" -> {
                CodingQueuedMessage queued = restoreOptions.get(selected);
                var restored = client.restore(queued.sessionId(), queued.followUpId(), queued.revision());
                apply(new TerminalUiAction.SelectorClosed());
                apply(new TerminalUiAction.EditorChanged(
                        restored.message(), restored.message().length()));
                refresh();
            }
            case "session" -> apply(new TerminalUiAction.SelectorClosed());
            case "archive-session" -> {
                apply(new TerminalUiAction.SelectorClosed());
                if (selected == 0) {
                    CodingSessionView current = refresh();
                    closeSubscription();
                    client.archive(
                            current.summary().sessionId(), current.summary().revision());
                    load(client.open(current.summary().sessionId()));
                    apply(new TerminalUiAction.StatusChanged("Session archived"));
                }
            }
            case "delete-session" -> {
                apply(new TerminalUiAction.SelectorClosed());
                if (selected == 0) {
                    CodingSessionView current = refresh();
                    closeSubscription();
                    client.delete(
                            current.summary().sessionId(), current.summary().revision());
                    awaitingNewSessionMessage = true;
                    apply(new TerminalUiAction.SessionCleared("Session deleted; enter the first message"));
                }
            }
            case "shell-approval" -> {
                CodingShellPlan plan = Objects.requireNonNull(pendingShellPlan, "pending shell plan is required");
                apply(new TerminalUiAction.SelectorClosed());
                pendingShellPlan = null;
                if (selected == 0) {
                    completeShell(plan, true);
                } else {
                    client.discardShell(plan.token());
                    apply(new TerminalUiAction.StatusChanged("Shell command denied"));
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
                InteractionView interaction = state.session()
                        .flatMap(CodingSessionView::pendingInteraction)
                        .orElseThrow();
                InteractionAction action = interaction.allowedActions().get(selected);
                client.respond(interaction, action, UUID.randomUUID().toString());
                apply(new TerminalUiAction.SelectorClosed());
                refresh();
            }
        }
    }

    private void discardPendingShell() {
        if (pendingShellPlan == null) return;
        client.discardShell(pendingShellPlan.token());
        pendingShellPlan = null;
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

    private static boolean shouldReconcile(AgentRunEvent.Payload payload) {
        if (payload instanceof RunEventPayloads.InteractionLifecycle) return true;
        if (!(payload instanceof RunEventPayloads.RunLifecycle lifecycle)) return false;
        return switch (lifecycle.status()) {
            case "COMPLETED", "FAILED", "CANCELLED", "TIMEOUT" -> true;
            default -> false;
        };
    }

    private void replayAndTail() {
        closeSubscription();
        if (state.currentRunId().isEmpty()) {
            return;
        }
        var runId = state.currentRunId().orElseThrow();
        RunEventCursor cursor = state.appliedCursor().orElseGet(() -> RunEventCursor.beforeFirst(runId));
        boolean more;
        do {
            var page = client.events(runId, cursor, PAGE_SIZE);
            for (var event : page.items()) {
                apply(new TerminalUiAction.RunEventReceived(event));
            }
            cursor = page.nextCursor();
            more = page.hasMore();
        } while (more);
        RunEventCursor subscribeAfter = state.appliedCursor().orElse(cursor);
        subscription = client.subscribe(
                runId, subscribeAfter, event -> pump.offer(new TerminalUiAction.RunEventReceived(event)));
    }

    private void apply(TerminalUiAction action) {
        state = reducer.reduce(state, action);
        if (action instanceof TerminalUiAction.RunEventReceived received
                && state.appliedCursor()
                        .filter(received.event().cursor()::equals)
                        .isPresent()
                && state.session().isPresent()) {
            client.acknowledgeCursor(
                    state.session().orElseThrow().summary().sessionId(),
                    received.event().cursor());
        }
    }

    private void closeSubscription() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
    }

    @Override
    public void close() {
        closeSubscription();
    }

    private record CompletionContext(String buffer, int start, int end) {
        private CompletionContext {
            Objects.requireNonNull(buffer, "buffer must not be null");
            if (start < 0 || end < start || end > buffer.length()) {
                throw new IllegalArgumentException("completion range is invalid");
            }
        }
    }
}
