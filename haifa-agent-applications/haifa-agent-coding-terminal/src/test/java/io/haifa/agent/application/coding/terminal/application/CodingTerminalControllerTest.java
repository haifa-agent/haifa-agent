package io.haifa.agent.application.coding.terminal.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.coding.terminal.event.TerminalEventPump;
import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.application.coding.terminal.jline.TerminalInput;
import io.haifa.agent.application.coding.terminal.session.CodingSessionClient;
import io.haifa.agent.application.coding.terminal.state.TerminalUiReducer;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.application.project.product.coding.CodingQueuedMessage;
import io.haifa.agent.application.project.product.coding.CodingRestoredMessage;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.api.AgentRunEventListener;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionConsequenceView;
import io.haifa.agent.runtime.api.InteractionInputContract;
import io.haifa.agent.runtime.api.InteractionKind;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionRequesterView;
import io.haifa.agent.runtime.api.InteractionResponseReceipt;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.InteractionTargetView;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.runtime.api.RunEventSubscription;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CodingTerminalControllerTest {
    private static final ProjectId PROJECT_ID = new ProjectId("project-1");
    private static final AgentSessionId SESSION_ID = new AgentSessionId("session-1");

    @Test
    void resumeSelectorOpensTheSelectedRealSession() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.summaries = List.of(client.view.summary());
        var controller = controller(client);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/resume"));
        assertThat(controller.state().selector()).isPresent();
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(client.opened).containsExactly(SESSION_ID);
        assertThat(controller.state().session()).contains(client.view);
        assertThat(controller.state().selector()).isEmpty();
    }

    @Test
    void altUpRestoresTheSelectedDurableFollowUpIntoTheEditor() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        CodingQueuedMessage queued = new CodingQueuedMessage("follow-1", SESSION_ID, "queued task", 1, 2);
        client.restorable = List.of(queued);
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.RESTORE, ""));
        assertThat(controller.state().selector()).isPresent();
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(client.restored).containsExactly("follow-1");
        assertThat(controller.state().editorBuffer()).isEqualTo("queued task");
        assertThat(controller.state().editorCursor()).isEqualTo("queued task".length());
    }

    @Test
    void pendingInteractionUsesTheSameSelectorInputOwnerAndRuntimeClient() {
        InteractionView interaction = new InteractionView(
                new InteractionRequestId("interaction-1"),
                new AgentRunId("run-1"),
                SESSION_ID,
                0,
                InteractionKind.APPROVAL,
                InteractionState.PENDING,
                "Approval",
                "Allow file change?",
                List.of(InteractionAction.REJECT, InteractionAction.APPROVE),
                InteractionInputContract.NONE,
                new InteractionTargetView("tool", "file-write", Optional.empty(), Optional.empty(), "workspace file"),
                new InteractionRequesterView("user", "local user"),
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(60),
                new InteractionConsequenceView("Run tool", "Reject tool", "Expire request"));
        FakeClient client = new FakeClient(view(Optional.of(interaction)));
        client.reconciledView = view(Optional.empty());
        var controller = controller(client);
        controller.open(SESSION_ID);

        assertThat(controller.state().selector()).isPresent();
        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(client.respondedActions).containsExactly(InteractionAction.APPROVE);
        assertThat(controller.state().selector()).isEmpty();
    }

    @Test
    void settingsAndTrustDoNotOpenDecorativeSelectors() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        var controller = controller(client);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/settings"));
        assertThat(controller.state().selector()).isEmpty();
        assertThat(controller.state().recoverableError()).contains("CAPABILITY_NOT_IMPLEMENTED");

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/trust"));
        assertThat(controller.state().selector()).isEmpty();
        assertThat(controller.state().recoverableError()).contains("CAPABILITY_NOT_IMPLEMENTED");
    }

    @Test
    void tabOpensVisibleCommandCandidatesAndInsertsTheSelection() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        var controller = controller(client);

        controller.accept(new TerminalInput(TerminalInput.Kind.COMPLETION_REQUESTED, "/", 1));

        assertThat(controller.state().selector()).isPresent();
        assertThat(controller.state().selector().orElseThrow().options())
                .containsExactlyElementsOf(io.haifa.agent.application.coding.terminal.jline.JLineCompleter.COMMANDS);
        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(controller.state().selector()).isEmpty();
        assertThat(controller.state().editorBuffer()).isEqualTo("/resume");
        assertThat(controller.state().editorCursor()).isEqualTo("/resume".length());
    }

    @Test
    void tabCompletesAWorkspaceFileInPlaceAndPreservesTheRestOfTheMessage() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.logicalPaths = List.of("README.md", "src/main/App.java", "src/test/AppTest.java");
        var controller = controller(client);
        String message = "inspect @src/ma after";
        int cursor = message.indexOf(" after");

        controller.accept(new TerminalInput(TerminalInput.Kind.COMPLETION_REQUESTED, message, cursor));

        assertThat(controller.state().selector().orElseThrow().options()).containsExactly("@src/main/App.java");
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(controller.state().editorBuffer()).isEqualTo("inspect @src/main/App.java after");
        assertThat(controller.state().editorCursor()).isEqualTo("inspect @src/main/App.java".length());
    }

    @Test
    void commandAliasOpensTheSameVisibleCommandPalette() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        var controller = controller(client);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/command"));

        assertThat(controller.state().selector()).isPresent();
        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("completion");
        assertThat(controller.state().selector().orElseThrow().title()).isEqualTo("Commands");
    }

    @Test
    void secondTurnReconcilesAndRetriesWhenTheRunSettlesDuringSubmission() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.submitFailure = new ProjectProductException("CODING_SESSION_ACTIVE", "Run became active");
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "second turn"));

        assertThat(client.submitAttempts).isEqualTo(2);
        assertThat(client.submittedMessages).containsExactly("second turn");
        assertThat(controller.state().editorBuffer()).isEmpty();
        assertThat(controller.state().transcript()).anyMatch(item -> item.body().equals("second turn"));
    }

    @Test
    void productFailureStaysInTheTerminalAndPreservesTheDraft() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.submitFailure = new ProjectProductException("SESSION_NOT_FOUND", "Session unavailable");
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "keep this draft"));

        assertThat(controller.state().recoverableError()).contains("SESSION_NOT_FOUND");
        assertThat(controller.state().editorBuffer()).isEqualTo("keep this draft");
        assertThat(controller.state().editorCursor()).isEqualTo("keep this draft".length());
    }

    @Test
    void escapeCancelsAnActiveRunEvenWhenASelectorIsOpen() {
        FakeClient client = new FakeClient(activeView());
        TerminalUiReducer reducer = new TerminalUiReducer();
        TerminalUiState activeState = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.SessionLoaded(activeView(), List.of("Loaded resources: project")));
        activeState = reducer.reduce(activeState, new TerminalUiAction.EditorChanged("draft", 5));
        activeState = reducer.reduce(
                activeState,
                new TerminalUiAction.SelectorOpened(
                        new io.haifa.agent.application.coding.terminal.state.TerminalSelector(
                                "completion", "Commands", List.of("/resume"), 0)));
        var controller =
                new CodingTerminalController(PROJECT_ID, client, new TerminalEventPump(32), reducer, activeState);

        controller.accept(input(TerminalInput.Kind.CANCEL_OR_CLOSE, "draft"));

        assertThat(client.cancelledSessions).containsExactly(SESSION_ID);
        assertThat(controller.state().status()).isEqualTo("Cancelling");
        assertThat(controller.state().selector()).isEmpty();
        assertThat(controller.state().editorBuffer()).isEqualTo("draft");
    }

    @Test
    void escapeReconcilesAStaleIdleViewBeforeCancellingTheActiveRun() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.reconciledView = activeView();
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.CANCEL_OR_CLOSE, ""));

        assertThat(client.cancelledSessions).containsExactly(SESSION_ID);
        assertThat(controller.state().status()).isEqualTo("Cancelling");
    }

    private static CodingTerminalController controller(CodingSessionClient client) {
        return new CodingTerminalController(
                PROJECT_ID,
                client,
                new TerminalEventPump(32),
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40));
    }

    private static CodingSessionView view(Optional<InteractionView> interaction) {
        return new CodingSessionView(
                new CodingSessionSummary(
                        SESSION_ID, PROJECT_ID, "session", Optional.empty(), Optional.empty(), 0, Instant.EPOCH, 0),
                Optional.empty(),
                interaction,
                Optional.empty(),
                "sha256:configuration",
                "cli-coding@1.0.0");
    }

    private static CodingSessionView activeView() {
        AgentRunId runId = new AgentRunId("run-1");
        return new CodingSessionView(
                new CodingSessionSummary(
                        SESSION_ID,
                        PROJECT_ID,
                        "session",
                        Optional.of(runId),
                        Optional.of(AgentRunStatus.RUNNING),
                        0,
                        Instant.EPOCH,
                        0),
                Optional.of(new AgentRunSnapshot(
                        runId,
                        AgentRunStatus.RUNNING,
                        1,
                        Instant.EPOCH,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())),
                Optional.empty(),
                Optional.empty(),
                "sha256:configuration",
                "cli-coding@1.0.0");
    }

    private static TerminalInput input(TerminalInput.Kind kind, String text) {
        return new TerminalInput(kind, text);
    }

    private static final class FakeClient implements CodingSessionClient {
        private CodingSessionView view;
        private CodingSessionView reconciledView;
        private List<CodingSessionSummary> summaries = List.of();
        private List<CodingQueuedMessage> restorable = List.of();
        private List<String> logicalPaths = List.of();
        private ProjectProductException submitFailure;
        private int submitAttempts;
        private final List<String> submittedMessages = new ArrayList<>();
        private final List<AgentSessionId> opened = new ArrayList<>();
        private final List<String> restored = new ArrayList<>();
        private final List<InteractionAction> respondedActions = new ArrayList<>();
        private final List<AgentSessionId> cancelledSessions = new ArrayList<>();

        private FakeClient(CodingSessionView view) {
            this.view = view;
            this.reconciledView = view;
        }

        @Override
        public CodingSessionView create(ProjectId projectId, String firstTurn, String idempotencyKey) {
            return view;
        }

        @Override
        public List<CodingSessionSummary> list(ProjectId projectId, int limit) {
            return summaries;
        }

        @Override
        public CodingSessionView open(AgentSessionId sessionId) {
            opened.add(sessionId);
            return view;
        }

        @Override
        public CodingSessionView reconcile(AgentSessionId sessionId) {
            view = reconciledView;
            return reconciledView;
        }

        @Override
        public void submit(AgentSessionId sessionId, String message, String idempotencyKey) {
            submitAttempts++;
            if (submitFailure != null) {
                ProjectProductException failure = submitFailure;
                submitFailure = null;
                throw failure;
            }
            submittedMessages.add(message);
        }

        @Override
        public void steer(AgentSessionId sessionId, AgentRunId activeRunId, String message, String idempotencyKey) {}

        @Override
        public void enqueueFollowUp(
                AgentSessionId sessionId, AgentRunId activeRunId, String message, String idempotencyKey) {}

        @Override
        public List<CodingQueuedMessage> restorableMessages(AgentSessionId sessionId, int limit) {
            return restorable;
        }

        @Override
        public CodingRestoredMessage restore(AgentSessionId sessionId, String followUpId, long revision) {
            restored.add(followUpId);
            restorable = List.of();
            return new CodingRestoredMessage(followUpId, sessionId, "queued task", List.of(), revision + 1);
        }

        @Override
        public InteractionResponseReceipt respond(
                InteractionView interaction, InteractionAction action, String idempotencyKey) {
            respondedActions.add(action);
            return null;
        }

        @Override
        public void cancel(AgentSessionId sessionId, String idempotencyKey) {
            cancelledSessions.add(sessionId);
        }

        @Override
        public RunEventPage events(AgentRunId runId, RunEventCursor after, int limit) {
            return new RunEventPage(List.of(), after, after, false);
        }

        @Override
        public RunEventCursor acknowledgeCursor(AgentSessionId sessionId, RunEventCursor cursor) {
            return cursor;
        }

        @Override
        public RunEventSubscription subscribe(AgentRunId runId, RunEventCursor after, AgentRunEventListener listener) {
            return new RunEventSubscription() {
                private boolean closed;

                @Override
                public boolean closed() {
                    return closed;
                }

                @Override
                public void close() {
                    closed = true;
                }
            };
        }

        @Override
        public List<String> logicalPaths() {
            return logicalPaths;
        }
    }
}
