package io.haifa.agent.application.coding.terminal.tui4j;

import static org.assertj.core.api.Assertions.assertThat;

import com.williamcallahan.tui4j.compat.bubbletea.KeyPressMessage;
import com.williamcallahan.tui4j.compat.bubbletea.PasteMessage;
import com.williamcallahan.tui4j.compat.bubbletea.WindowSizeMessage;
import com.williamcallahan.tui4j.compat.bubbletea.input.key.Key;
import com.williamcallahan.tui4j.compat.bubbletea.input.key.KeyType;
import com.williamcallahan.tui4j.compat.lipgloss.color.NoColor;
import com.williamcallahan.tui4j.message.EnterKeyModifier;
import com.williamcallahan.tui4j.message.EnterKeyModifierMessage;
import com.williamcallahan.tui4j.term.TerminalInfo;
import io.haifa.agent.application.coding.terminal.application.CodingTerminalController;
import io.haifa.agent.application.coding.terminal.event.TerminalEventPump;
import io.haifa.agent.application.coding.terminal.event.TerminalInput;
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
import io.haifa.agent.runtime.api.InteractionResponseReceipt;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.runtime.api.RunEventSubscription;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Tui4jCodingTerminalModelTest {
    @BeforeAll
    static void configureHeadlessTerminalInfo() {
        TerminalInfo.provide(() -> new TerminalInfo(false, new NoColor()));
    }

    @Test
    void mapsEditorNewlineResizeAndInterruptKeysIntoAuthoritativeState() {
        var fixture = fixture();

        fixture.model.update(new PasteMessage("draft"));
        fixture.model.update(new EnterKeyModifierMessage(EnterKeyModifier.Shift));
        fixture.model.update(new PasteMessage("next"));
        fixture.model.update(new WindowSizeMessage(120, 40));

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("draft\nnext");
        assertThat(fixture.controller.state().editorCursor()).isEqualTo("draft\nnext".length());
        assertThat(fixture.controller.state().columns()).isEqualTo(120);
        assertThat(fixture.controller.state().rows()).isEqualTo(40);

        fixture.model.update(key(KeyType.keyETX));
        assertThat(fixture.controller.state().editorBuffer()).isEmpty();
        assertThat(fixture.controller.state().exitRequested()).isFalse();

        fixture.model.update(key(KeyType.keyETX));
        assertThat(fixture.controller.state().exitRequested()).isTrue();
    }

    @Test
    void preservesTheEditorWhileCompletionSelectorIsOpenedAndClosed() {
        var fixture = fixture();

        fixture.model.update(new PasteMessage("/r"));
        fixture.model.update(key(KeyType.keyHT));

        assertThat(fixture.controller.state().selector()).isPresent();
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("/r");

        fixture.model.update(key(KeyType.KeyDown));
        fixture.model.update(key(KeyType.keyESC));

        assertThat(fixture.controller.state().selector()).isEmpty();
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("/r");
    }

    @Test
    void preservesHistoryDraftAndSynchronizesTheAuthoritativeCursor() {
        var fixture = fixture();

        fixture.model.update(new PasteMessage("/unknown-one"));
        fixture.model.update(key(KeyType.keyCR));
        fixture.model.update(new PasteMessage("/unknown-two"));
        fixture.model.update(key(KeyType.keyCR));
        fixture.model.update(new PasteMessage("current draft"));

        fixture.model.update(key(KeyType.KeyUp));
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("/unknown-two");
        fixture.model.update(key(KeyType.KeyUp));
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("/unknown-one");
        fixture.model.update(key(KeyType.KeyDown));
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("/unknown-two");
        fixture.model.update(key(KeyType.KeyDown));
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("current draft");

        fixture.controller.accept(new TerminalInput(TerminalInput.Kind.EDITOR_CHANGED, "abcdef", 2));
        fixture.model.update(new WindowSizeMessage(80, 24));
        fixture.model.update(new PasteMessage("X"));

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("abXcdef");
        assertThat(fixture.controller.state().editorCursor()).isEqualTo(3);
    }

    private Fixture fixture() {
        var pump = new TerminalEventPump(64);
        var controller = new CodingTerminalController(
                new ProjectId("project-1"),
                new UnusedClient(),
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(80, 24));
        return new Fixture(controller, new Tui4jCodingTerminalModel(controller, pump));
    }

    private KeyPressMessage key(KeyType type) {
        return new KeyPressMessage(new Key(type));
    }

    private record Fixture(CodingTerminalController controller, Tui4jCodingTerminalModel model) {}

    private static final class UnusedClient implements CodingSessionClient {
        @Override
        public CodingSessionView create(ProjectId projectId, String firstTurn, String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CodingSessionSummary> list(ProjectId projectId, int limit) {
            return List.of();
        }

        @Override
        public CodingSessionView open(AgentSessionId sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CodingSessionView reconcile(AgentSessionId sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void submit(AgentSessionId sessionId, String message, String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void steer(AgentSessionId sessionId, AgentRunId activeRunId, String message, String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void enqueueFollowUp(
                AgentSessionId sessionId, AgentRunId activeRunId, String message, String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CodingQueuedMessage> restorableMessages(AgentSessionId sessionId, int limit) {
            return List.of();
        }

        @Override
        public CodingRestoredMessage restore(AgentSessionId sessionId, String followUpId, long revision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InteractionResponseReceipt respond(
                InteractionView interaction, InteractionAction action, String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancel(AgentSessionId sessionId, String idempotencyKey) {
            throw new UnsupportedOperationException();
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
            throw new UnsupportedOperationException();
        }
    }
}
