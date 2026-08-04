package io.haifa.agent.application.coding.terminal.tui4j;

import static org.assertj.core.api.Assertions.assertThat;

import com.williamcallahan.tui4j.compat.bubbletea.KeyPressMessage;
import com.williamcallahan.tui4j.compat.bubbletea.PasteMessage;
import com.williamcallahan.tui4j.compat.bubbletea.WindowSizeMessage;
import com.williamcallahan.tui4j.compat.bubbletea.input.MouseAction;
import com.williamcallahan.tui4j.compat.bubbletea.input.MouseButton;
import com.williamcallahan.tui4j.compat.bubbletea.input.MouseMessage;
import com.williamcallahan.tui4j.compat.bubbletea.input.key.Key;
import com.williamcallahan.tui4j.compat.bubbletea.input.key.KeyType;
import com.williamcallahan.tui4j.compat.lipgloss.color.NoColor;
import com.williamcallahan.tui4j.message.EnterKeyModifier;
import com.williamcallahan.tui4j.message.EnterKeyModifierMessage;
import com.williamcallahan.tui4j.term.TerminalInfo;
import io.haifa.agent.application.coding.terminal.application.CodingTerminalController;
import io.haifa.agent.application.coding.terminal.event.TerminalEventPump;
import io.haifa.agent.application.coding.terminal.event.TerminalInput;
import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.application.coding.terminal.session.CodingSessionClient;
import io.haifa.agent.application.coding.terminal.state.TerminalSelector;
import io.haifa.agent.application.coding.terminal.state.TerminalUiReducer;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.project.product.coding.CodingQueuedMessage;
import io.haifa.agent.application.project.product.coding.CodingRestoredMessage;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunEventListener;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionResponseReceipt;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.RunEventSubscription;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
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
        fixture.model.update(new EnterKeyModifierMessage(EnterKeyModifier.Ctrl));
        fixture.model.update(new PasteMessage("windows"));
        fixture.model.update(new WindowSizeMessage(120, 40));

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("draft\nnext\nwindows");
        assertThat(fixture.controller.state().editorCursor()).isEqualTo("draft\nnext\nwindows".length());
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
    void opensCommandAndWorkspacePathCompletionAsSoonAsTheTriggerIsTyped() {
        var command = fixture();

        command.model.update(runes('/'));

        assertThat(command.controller.state().selector())
                .get()
                .extracting(TerminalSelector::title)
                .isEqualTo("Commands");
        assertThat(command.controller.state().editorBuffer()).isEqualTo("/");

        var path = fixture();
        path.model.update(runes('@'));

        assertThat(path.controller.state().selector())
                .get()
                .extracting(TerminalSelector::title)
                .isEqualTo("Workspace paths");
        assertThat(path.controller.state().selector().orElseThrow().options()).contains("@README.md", "@src/");

        path.model.update(runes('s'));
        assertThat(path.controller.state().editorBuffer()).isEqualTo("@s");
        assertThat(path.controller.state().selector().orElseThrow().options()).containsExactly("@src/");
    }

    @Test
    void keepsFilteringAnOpenCompletionAndSubmitsAnExactSlashCommandWithOneEnter() {
        var fixture = fixture();

        "/quit".chars().forEach(value -> fixture.model.update(runes((char) value)));

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("/quit");
        assertThat(fixture.controller.state().selector().orElseThrow().options())
                .containsExactly("/quit");

        fixture.model.update(key(KeyType.keyCR));

        assertThat(fixture.controller.state().exitRequested()).isTrue();
    }

    @Test
    void mapsTraditionalCtrlOToExpansionWithoutEditingTheDraft() {
        var fixture = fixture();
        fixture.pump.offer(new TerminalUiAction.ShellCompleted("!pwd", "Command succeeded\nD:/workspace", "SUCCEEDED"));
        fixture.model.update(new WindowSizeMessage(100, 30));
        assertThat(fixture.controller.state().transcript().getLast().expanded()).isTrue();

        fixture.model.update(key(KeyType.keySI));

        assertThat(fixture.controller.state().transcript().getLast().expanded()).isFalse();
        assertThat(fixture.controller.state().editorBuffer()).isEmpty();
    }

    @Test
    void keepsFollowingNewOutputWhenActiveRunLayoutShrinksALongTranscriptViewport() {
        var fixture = fixture();
        fixture.model.init();
        fixture.model.view();
        for (int index = 1; index <= 30; index++) {
            fixture.pump.offer(new TerminalUiAction.UserMessageCommitted("message-" + index, "history-" + index));
        }
        fixture.model.update(new WindowSizeMessage(80, 24));
        assertThat(fixture.model.view()).contains("history-30");

        fixture.pump.offer(new TerminalUiAction.RunEventReceived(
                event(1, new RunEventPayloads.RunLifecycle("RUNNING", 1, "NONE"))));
        fixture.model.update(new WindowSizeMessage(80, 24));
        fixture.model.view();

        fixture.pump.offer(new TerminalUiAction.RunEventReceived(
                event(2, new RunEventPayloads.AssistantTextDelta("generation-1", "LATEST_ASSISTANT_OUTPUT"))));
        fixture.model.update(new WindowSizeMessage(80, 24));

        assertThat(fixture.model.view()).contains("LATEST_ASSISTANT_OUTPUT").doesNotContain("new output below");
    }

    @Test
    void routesMouseWheelToTranscriptWithoutBrowsingEditorHistory() {
        var fixture = fixture();
        fixture.model.init();
        for (int index = 1; index <= 30; index++) {
            fixture.pump.offer(new TerminalUiAction.UserMessageCommitted("message-" + index, "history-" + index));
        }
        fixture.model.update(new WindowSizeMessage(80, 24));
        fixture.model.update(new PasteMessage("current draft"));
        assertThat(fixture.model.view()).contains("history-30");

        fixture.model.update(wheel(MouseButton.MouseButtonWheelUp));
        fixture.model.update(wheel(MouseButton.MouseButtonWheelUp));

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("current draft");
        assertThat(fixture.model.view()).contains("history-28").doesNotContain("history-30");

        fixture.pump.offer(new TerminalUiAction.ShellCompleted("!pwd", "LATEST_OUTPUT", "SUCCEEDED"));
        fixture.model.update(new WindowSizeMessage(80, 24));
        assertThat(fixture.model.view()).contains("new output below").doesNotContain("LATEST_OUTPUT");

        for (int index = 0; index < 20; index++) {
            fixture.model.update(wheel(MouseButton.MouseButtonWheelDown));
        }
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("current draft");
        assertThat(fixture.model.view()).contains("LATEST_OUTPUT").doesNotContain("new output below");
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

    @Test
    void editsEmojiCombiningTextAndMultilinePasteOnlyAtGraphemeBoundaries() {
        var fixture = fixture();
        String family = "👨‍👩‍👧‍👦";

        fixture.model.update(new PasteMessage("A" + family + "e\u0301\r\n中x\u001B"));
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("A" + family + "e\u0301\n中x");
        int afterCombining = 1 + family.length() + "e\u0301".length();
        fixture.controller.accept(new TerminalInput(
                TerminalInput.Kind.EDITOR_CHANGED, fixture.controller.state().editorBuffer(), afterCombining));
        fixture.model.update(new WindowSizeMessage(80, 24));
        fixture.model.update(key(KeyType.keyBS));

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("A" + family + "\n中x");
        assertThat(fixture.controller.state().editorCursor()).isEqualTo(1 + family.length());

        fixture.model.update(key(KeyType.KeyLeft));
        fixture.model.update(key(KeyType.KeyDelete));

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("A\n中x");
        assertThat(fixture.controller.state().editorCursor()).isEqualTo(1);
    }

    @Test
    void preservesEditorCursorAndSelectorAcrossTheFullResizeSequence() {
        var fixture = fixture();
        fixture.model.update(new PasteMessage("/r"));
        fixture.model.update(key(KeyType.keyHT));
        int selected = fixture.controller.state().selector().orElseThrow().selected();

        List.of(
                        new WindowSizeMessage(120, 40),
                        new WindowSizeMessage(80, 24),
                        new WindowSizeMessage(60, 16),
                        new WindowSizeMessage(40, 10),
                        new WindowSizeMessage(120, 40),
                        new WindowSizeMessage(80, 24),
                        new WindowSizeMessage(60, 16),
                        new WindowSizeMessage(120, 40))
                .forEach(fixture.model::update);

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("/r");
        assertThat(fixture.controller.state().editorCursor()).isEqualTo(2);
        assertThat(fixture.controller.state().selector())
                .get()
                .extracting(TerminalSelector::selected)
                .isEqualTo(selected);
        assertThat(fixture.model.view()).contains("Haifa Coding Agent", "Commands");
    }

    private Fixture fixture() {
        var pump = new TerminalEventPump(64);
        var controller = new CodingTerminalController(
                new ProjectId("project-1"),
                new UnusedClient(),
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(80, 24));
        return new Fixture(controller, pump, new Tui4jCodingTerminalModel(controller, pump));
    }

    private KeyPressMessage key(KeyType type) {
        return new KeyPressMessage(new Key(type));
    }

    private KeyPressMessage runes(char value) {
        return new KeyPressMessage(new Key(KeyType.KeyRunes, new char[] {value}));
    }

    private MouseMessage wheel(MouseButton button) {
        return new MouseMessage(1, 1, false, false, false, MouseAction.MouseActionPress, button);
    }

    private AgentRunEvent event(long sequence, AgentRunEvent.Payload payload) {
        AgentRunId runId = new AgentRunId("run-1");
        return new AgentRunEvent(
                "event-" + sequence,
                "run.status.changed",
                "1",
                runId,
                new AgentSessionId("session-1"),
                sequence,
                new RunEventCursor(runId, "1", OptionalLong.of(sequence)),
                Instant.parse("2026-07-30T00:00:00Z"),
                Optional.empty(),
                Optional.empty(),
                payload);
    }

    private record Fixture(
            CodingTerminalController controller, TerminalEventPump pump, Tui4jCodingTerminalModel model) {}

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

        @Override
        public List<String> logicalPaths() {
            return List.of("README.md", "src/");
        }
    }
}
