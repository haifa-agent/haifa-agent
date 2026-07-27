package io.haifa.agent.application.coding.terminal.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.coding.terminal.event.TerminalEventPump;
import io.haifa.agent.application.coding.terminal.jline.TerminalInput;
import io.haifa.agent.application.coding.terminal.session.CodingSessionClient;
import io.haifa.agent.application.coding.terminal.state.TerminalUiReducer;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.project.product.coding.CodingQueuedMessage;
import io.haifa.agent.application.project.product.coding.CodingRestoredMessage;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.api.AgentRunEventListener;
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

    private static TerminalInput input(TerminalInput.Kind kind, String text) {
        return new TerminalInput(kind, text);
    }

    private static final class FakeClient implements CodingSessionClient {
        private CodingSessionView view;
        private CodingSessionView reconciledView;
        private List<CodingSessionSummary> summaries = List.of();
        private List<CodingQueuedMessage> restorable = List.of();
        private final List<AgentSessionId> opened = new ArrayList<>();
        private final List<String> restored = new ArrayList<>();
        private final List<InteractionAction> respondedActions = new ArrayList<>();

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
        public void submit(AgentSessionId sessionId, String message, String idempotencyKey) {}

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
        public void cancel(AgentSessionId sessionId, String idempotencyKey) {}

        @Override
        public RunEventPage events(AgentRunId runId, RunEventCursor after, int limit) {
            throw new AssertionError("no active Run expected");
        }

        @Override
        public RunEventCursor acknowledgeCursor(AgentSessionId sessionId, RunEventCursor cursor) {
            return cursor;
        }

        @Override
        public RunEventSubscription subscribe(AgentRunId runId, RunEventCursor after, AgentRunEventListener listener) {
            throw new AssertionError("no active Run expected");
        }
    }
}
