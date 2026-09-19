package io.haifa.agent.application.coding.terminal.tui4j;

import static org.assertj.core.api.Assertions.assertThat;

import com.williamcallahan.tui4j.compat.bubbletea.BatchMessage;
import com.williamcallahan.tui4j.compat.bubbletea.ClearScreenMessage;
import com.williamcallahan.tui4j.compat.bubbletea.Command;
import com.williamcallahan.tui4j.compat.bubbletea.KeyPressMessage;
import com.williamcallahan.tui4j.compat.bubbletea.Message;
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
import io.haifa.agent.application.coding.terminal.state.TerminalSelector;
import io.haifa.agent.application.coding.terminal.state.TerminalUiReducer;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.project.product.coding.CodingQueuedMessage;
import io.haifa.agent.application.project.product.coding.CodingRestoredMessage;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationClient;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationView;
import io.haifa.agent.application.project.product.coding.client.CodingSessionClient;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunEventListener;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionResponseReceipt;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.RunEventSubscription;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
    void rendersConversationSubmissionBeforeClientIoRuns() {
        var fixture = fixture();
        fixture.model.update(new PasteMessage("inspect the repository"));

        var guarded = fixture.model.update(key(KeyType.keyCR));

        assertThat(Command.isNone(guarded.command())).isFalse();
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("inspect the repository");

        var updated = fixture.model.update(guarded.command().execute());

        assertThat(Command.isNone(updated.command())).isFalse();
        assertThat(fixture.controller.state().editorBuffer()).isEmpty();
        assertThat(fixture.controller.state().status()).isEqualTo("Submitting");
        assertThat(fixture.controller.state().transcript()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("You");
            assertThat(item.body()).isEqualTo("inspect the repository");
        });
    }

    @Test
    void keepsUnbracketedCrAndCrLfMultilinePasteInTheEditorUntilASeparateEnter() {
        var fixture = fixture();
        fixture.model.update(new PasteMessage("first line"));

        var firstCr = fixture.model.update(key(KeyType.keyCR));
        "second line".chars().forEach(value -> fixture.model.update(runes((char) value)));
        fixture.model.update(key(KeyType.keyCR));
        fixture.model.update(key(KeyType.keyLF));
        "third line".chars().forEach(value -> fixture.model.update(runes((char) value)));

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("first line\nsecond line\nthird line");
        assertThat(fixture.controller.state().status()).isEqualTo("Idle");

        fixture.model.update(firstCr.command().execute());

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("first line\nsecond line\nthird line");
        assertThat(fixture.controller.state().transcript()).isEmpty();

        var explicitEnter = fixture.model.update(key(KeyType.keyCR));
        var submitted = fixture.model.update(explicitEnter.command().execute());

        assertThat(Command.isNone(submitted.command())).isFalse();
        assertThat(fixture.controller.state().editorBuffer()).isEmpty();
        assertThat(fixture.controller.state().status()).isEqualTo("Submitting");
    }

    @Test
    void mapsTraditionalCtrlOToExpansionWithoutEditingTheDraft() {
        var fixture = fixture();
        fixture.pump.offer(new TerminalUiAction.ShellCompleted("!pwd", "Command exited\nD:/workspace", "EXITED"));
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
    void doesNotClearTheScreenWhenStreamingAddsAnExplicitLine() {
        var fixture = fixture();
        fixture.model.init();
        fixture.pump.offer(new TerminalUiAction.RunEventReceived(
                event(1, new RunEventPayloads.AssistantTextDelta("generation-1", "first line"))));
        fixture.model.update(new WindowSizeMessage(80, 24));

        fixture.pump.offer(new TerminalUiAction.RunEventReceived(
                event(2, new RunEventPayloads.AssistantTextDelta("generation-1", "\nsecond line"))));
        var streamed = fixture.model.update(new WindowSizeMessage(80, 24));

        assertThat(emitsClearScreen(streamed.command())).isFalse();
        assertThat(fixture.model.view()).contains("first line", "second line");
    }

    @Test
    void doesNotClearTheScreenWhenStreamingWrapsOntoAnotherVisualLine() {
        var fixture = fixture();
        fixture.model.init();
        fixture.model.update(new WindowSizeMessage(60, 24));
        fixture.pump.offer(new TerminalUiAction.RunEventReceived(
                event(1, new RunEventPayloads.AssistantTextDelta("generation-1", "short"))));
        fixture.model.update(new WindowSizeMessage(60, 24));

        fixture.pump.offer(new TerminalUiAction.RunEventReceived(event(
                2,
                new RunEventPayloads.AssistantTextDelta(
                        "generation-1",
                        " text that grows beyond the narrow terminal width while streaming continues"))));
        var streamed = fixture.model.update(new WindowSizeMessage(60, 24));

        assertThat(emitsClearScreen(streamed.command())).isFalse();
        assertThat(fixture.model.view()).contains("short text");
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

        fixture.pump.offer(new TerminalUiAction.ShellCompleted("!pwd", "LATEST_OUTPUT", "EXITED"));
        fixture.model.update(new WindowSizeMessage(80, 24));
        assertThat(fixture.model.view()).contains("new output below").doesNotContain("LATEST_OUTPUT");

        for (int index = 0; index < 20; index++) {
            fixture.model.update(wheel(MouseButton.MouseButtonWheelDown));
        }
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("current draft");
        assertThat(fixture.model.view()).contains("LATEST_OUTPUT").doesNotContain("new output below");
    }

    @Test
    void appliesWheelScrollAfterTheCurrentFrameRecalculatesViewportHeight() {
        var fixture = fixture();
        fixture.model.init();
        for (int index = 1; index <= 10; index++) {
            fixture.pump.offer(new TerminalUiAction.UserMessageCommitted("message-" + index, "history-" + index));
        }
        fixture.model.update(new WindowSizeMessage(80, 60));
        fixture.model.view();

        fixture.model.update(new WindowSizeMessage(80, 24));
        fixture.model.update(wheel(MouseButton.MouseButtonWheelUp));

        assertThat(fixture.model.view()).contains("history-5").doesNotContain("history-10");
    }

    @Test
    void pagesThroughTranscriptAndResumesFollowingAtTheBottom() {
        var fixture = fixture();
        fixture.model.init();
        for (int index = 1; index <= 30; index++) {
            fixture.pump.offer(new TerminalUiAction.UserMessageCommitted("message-" + index, "history-" + index));
        }
        fixture.model.update(new WindowSizeMessage(80, 24));
        assertThat(fixture.model.view()).contains("history-30");

        fixture.model.update(key(KeyType.KeyPgUp));
        assertThat(fixture.model.view()).contains("history-24").doesNotContain("history-30");

        fixture.pump.offer(new TerminalUiAction.ShellCompleted("!pwd", "LATEST_OUTPUT", "EXITED"));
        fixture.model.update(new WindowSizeMessage(80, 24));
        assertThat(fixture.model.view()).contains("new output below").doesNotContain("LATEST_OUTPUT");

        fixture.model.update(key(KeyType.KeyPgDown));
        fixture.model.view();
        fixture.model.update(key(KeyType.KeyPgDown));
        assertThat(fixture.model.view()).contains("LATEST_OUTPUT").doesNotContain("new output below");
    }

    @Test
    void resumesFollowingWhenTheUserSubmitsANewMessageAfterReviewingHistory() {
        var fixture = fixture();
        fixture.model.init();
        for (int index = 1; index <= 30; index++) {
            fixture.pump.offer(new TerminalUiAction.UserMessageCommitted("message-" + index, "history-" + index));
        }
        fixture.model.update(new WindowSizeMessage(80, 24));
        fixture.model.update(key(KeyType.KeyPgUp));

        fixture.pump.offer(new TerminalUiAction.ShellCompleted("!pwd", "HIDDEN_BEFORE_SUBMIT", "EXITED"));
        fixture.model.update(new WindowSizeMessage(80, 24));
        assertThat(fixture.model.view()).contains("new output below");

        fixture.model.update(new PasteMessage("start a new turn"));
        commitPlainEnter(fixture);
        fixture.pump.offer(new TerminalUiAction.ShellCompleted("!pwd", "VISIBLE_AFTER_SUBMIT", "EXITED"));
        fixture.model.update(new WindowSizeMessage(80, 24));

        assertThat(fixture.model.view()).contains("VISIBLE_AFTER_SUBMIT").doesNotContain("new output below");
    }

    @Test
    void restartsTheActivityTimerWhenToolsStartAndReturnControlToTheModel() {
        AtomicLong clock = new AtomicLong();
        var fixture = fixture(clock::get);

        fixture.pump.offer(new TerminalUiAction.RunEventReceived(
                event(1, new RunEventPayloads.RunLifecycle("RUNNING", 1, "NONE"))));
        fixture.model.update(new WindowSizeMessage(80, 24));
        fixture.model.view();
        clock.set(Duration.ofSeconds(9).toNanos());
        assertThat(fixture.model.view()).contains("THINKING (9s)");

        fixture.pump.offer(new TerminalUiAction.RunEventReceived(event(
                2,
                new RunEventPayloads.ToolLifecycle("tool-1", "execution_run", "STARTED", "NONE", "git status", ""))));
        fixture.model.update(new WindowSizeMessage(80, 24));
        assertThat(fixture.model.view()).contains("WORKING (1s) · execution_run");
        clock.set(Duration.ofSeconds(14).toNanos());
        assertThat(fixture.model.view()).contains("WORKING (5s) · execution_run");

        fixture.pump.offer(new TerminalUiAction.RunEventReceived(event(
                3,
                new RunEventPayloads.ToolLifecycle("tool-1", "execution_run", "SUCCEEDED", "NONE", "git status", ""))));
        fixture.model.update(new WindowSizeMessage(80, 24));
        assertThat(fixture.model.view()).contains("THINKING (1s)").doesNotContain("WORKING (");
    }

    @Test
    void routesParsedSgrMouseWheelInputToTheTranscript() {
        var fixture = fixture();
        for (int index = 1; index <= 30; index++) {
            fixture.pump.offer(new TerminalUiAction.UserMessageCommitted("message-" + index, "history-" + index));
        }
        fixture.model.update(new WindowSizeMessage(80, 24));
        assertThat(fixture.model.view()).contains("history-30");

        fixture.model.update(MouseMessage.parseSGRMouseEvent(64, 2, 5, false));
        fixture.model.update(MouseMessage.parseSGRMouseEvent(64, 2, 5, false));

        assertThat(fixture.model.view()).contains("history-23").doesNotContain("history-30");
    }

    @Test
    void preservesHistoryDraftAndSynchronizesTheAuthoritativeCursor() {
        var fixture = fixture();

        fixture.model.update(new PasteMessage("/unknown-one"));
        commitPlainEnter(fixture);
        fixture.model.update(new PasteMessage("/unknown-two"));
        commitPlainEnter(fixture);
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

    @Test
    void trimsOuterWhitespaceFromApiKeyBeforeSaving() {
        AtomicReference<char[]> saved = new AtomicReference<>();
        var fixture = fixture(new CapturingAuthenticationClient(saved));

        fixture.model.update(new PasteMessage("/login api deepseek"));
        commitPlainEnter(fixture);
        fixture.model.update(new PasteMessage("  private-value  "));

        assertThat(fixture.controller.secureInputRequested()).isTrue();
        assertThat(fixture.controller.secureInputIsUpdate()).isFalse();
        assertThat(fixture.controller.state().editorBuffer()).isEmpty();
        assertThat(fixture.controller.state().status())
                .isEqualTo("Enter API key for deepseek (first-time setup · saved to system credential store)");
        assertThat(fixture.model.view())
                .doesNotContain("private-value", "stored as plaintext", "enter send")
                .contains("API key ┃ •••••••••••••", "enter submit · escape cancel");

        fixture.model.update(key(KeyType.keyCR));

        assertThat(fixture.controller.secureInputRequested()).isFalse();
        assertThat(saved.get()).containsExactly("private-value".toCharArray());
        assertThat(fixture.controller.state().status()).isEqualTo("API key connected for deepseek account");
        assertThat(fixture.model.view()).doesNotContain("private-value");
    }

    @Test
    void switchesToUpdatePromptWhenApiKeyAlreadyExists() {
        AtomicReference<char[]> saved = new AtomicReference<>();
        CodingAuthenticationView existing = new CodingAuthenticationView(
                "model-auth://deepseek/default",
                "deepseek",
                CodingAuthenticationView.Method.API_KEY,
                CodingAuthenticationView.Status.AUTHENTICATED,
                "deepseek account",
                Optional.empty(),
                OptionalLong.empty(),
                false);
        var fixture = fixture(new CapturingAuthenticationClient(saved, List.of(existing)));

        fixture.model.update(new PasteMessage("/login api deepseek"));
        commitPlainEnter(fixture);

        assertThat(fixture.controller.secureInputRequested()).isTrue();
        assertThat(fixture.controller.secureInputIsUpdate()).isTrue();
        assertThat(fixture.controller.state().status())
                .isEqualTo(
                        "Update API key for deepseek (existing key found · will overwrite in system credential store)");
        assertThat(fixture.model.view())
                .contains(
                        "New key ┃ Paste or enter new API key (Esc to keep existing)", "enter update · escape cancel");

        fixture.model.update(new PasteMessage("updated-key"));
        fixture.model.update(key(KeyType.keyCR));

        assertThat(fixture.controller.secureInputRequested()).isFalse();
        assertThat(saved.get()).containsExactly("updated-key".toCharArray());
    }

    @Test
    void rejectsApiKeyLoginForUnsupportedProvider() {
        var fixture = fixture();

        fixture.model.update(new PasteMessage("/login api openai-codex"));
        commitPlainEnter(fixture);

        assertThat(fixture.controller.secureInputRequested()).isFalse();
        assertThat(fixture.controller.state().recoverableError()).contains("AUTH_METHOD_UNSUPPORTED");
    }

    @Test
    void requiresWorkspaceForFirstAliyunBailianSetup() {
        AtomicReference<char[]> saved = new AtomicReference<>();
        AtomicReference<Map<String, String>> savedAttrs = new AtomicReference<>();
        var fixture = fixture(new CapturingAuthenticationClient(saved, savedAttrs, List.of(), Map.of()));

        fixture.model.update(new PasteMessage("/login api aliyun-bailian"));
        commitPlainEnter(fixture);

        // API Key input (masked); workspace is required on first setup.
        assertThat(fixture.controller.secureInputRequested()).isTrue();
        assertThat(fixture.controller.secureInputIsMasked()).isTrue();
        assertThat(fixture.controller.secureInputPrompt()).isEqualTo("API key ┃ ");
        assertThat(fixture.controller.state().status())
                .contains("Enter DashScope API key for aliyun-bailian (workspace: required, region: cn-beijing");
        assertThat(fixture.model.view()).contains("API key ┃ ", "enter submit · escape cancel");

        fixture.model.update(new PasteMessage("sk-bailian-test-key"));
        fixture.model.update(key(KeyType.keyCR));

        assertThat(fixture.controller.secureInputRequested()).isTrue();
        assertThat(fixture.controller.state().status())
                .contains("Workspace ID is required for first-time aliyun-bailian setup");
        assertThat(saved.get()).isNull();
        assertThat(savedAttrs.get()).isNull();

        fixture.model.update(new PasteMessage("sk-bailian-test-key ws-default-01"));
        fixture.model.update(key(KeyType.keyCR));

        assertThat(fixture.controller.secureInputRequested()).isFalse();
        assertThat(saved.get()).containsExactly("sk-bailian-test-key".toCharArray());
        assertThat(savedAttrs.get())
                .containsEntry("workspace_id", "ws-default-01")
                .containsEntry("region", "cn-beijing");
        assertThat(fixture.controller.state().status())
                .contains(
                        "API key and endpoint connected for aliyun-bailian (workspace: ws-default-01, region: cn-beijing)");
    }

    @Test
    void configuresAliyunBailianInSingleStepWithCustomWorkspaceAndRegion() {
        AtomicReference<char[]> saved = new AtomicReference<>();
        AtomicReference<Map<String, String>> savedAttrs = new AtomicReference<>();
        var fixture = fixture(new CapturingAuthenticationClient(saved, savedAttrs, List.of(), Map.of()));

        fixture.model.update(new PasteMessage("/login api aliyun-bailian"));
        commitPlainEnter(fixture);

        fixture.model.update(new PasteMessage("sk-bailian-custom-key ws-custom-88 cn-shanghai"));
        fixture.model.update(key(KeyType.keyCR));

        assertThat(fixture.controller.secureInputRequested()).isFalse();
        assertThat(saved.get()).containsExactly("sk-bailian-custom-key".toCharArray());
        assertThat(savedAttrs.get())
                .containsEntry("workspace_id", "ws-custom-88")
                .containsEntry("region", "cn-shanghai");
    }

    @Test
    void fastPathsAliyunBailianWithCommandLineArguments() {
        AtomicReference<char[]> saved = new AtomicReference<>();
        AtomicReference<Map<String, String>> savedAttrs = new AtomicReference<>();
        var fixture = fixture(new CapturingAuthenticationClient(saved, savedAttrs, List.of(), Map.of()));

        fixture.model.update(new PasteMessage("/login api aliyun-bailian ws-quick-99 cn-shanghai"));
        commitPlainEnter(fixture);

        // Directly at API Key step (skipping workspace and region questions)
        assertThat(fixture.controller.secureInputRequested()).isTrue();
        assertThat(fixture.controller.secureInputIsMasked()).isTrue();

        fixture.model.update(new PasteMessage("sk-fast-key"));
        fixture.model.update(key(KeyType.keyCR));

        // Immediately completes
        assertThat(fixture.controller.secureInputRequested()).isFalse();
        assertThat(saved.get()).containsExactly("sk-fast-key".toCharArray());
        assertThat(savedAttrs.get())
                .containsEntry("workspace_id", "ws-quick-99")
                .containsEntry("region", "cn-shanghai");
    }

    @Test
    void rejectsInvalidWorkspaceIdInSingleStep() {
        AtomicReference<char[]> saved = new AtomicReference<>();
        AtomicReference<Map<String, String>> savedAttrs = new AtomicReference<>();
        var fixture = fixture(new CapturingAuthenticationClient(saved, savedAttrs, List.of(), Map.of()));

        fixture.model.update(new PasteMessage("/login api aliyun-bailian"));
        commitPlainEnter(fixture);

        // Invalid workspace ID in single step
        fixture.model.update(new PasteMessage("sk-test-key INVALID_WS"));
        fixture.model.update(key(KeyType.keyCR));

        // Validation error shown, still in secure input
        assertThat(fixture.controller.secureInputRequested()).isTrue();
        assertThat(fixture.controller.state().status())
                .contains("Invalid workspace ID: must be lowercase DNS label ([a-z0-9-], 1-63 chars)");

        // Now enter valid
        fixture.model.update(new PasteMessage("sk-test-key ws-valid-1"));
        fixture.model.update(key(KeyType.keyCR));

        assertThat(fixture.controller.secureInputRequested()).isFalse();
        assertThat(savedAttrs.get()).containsEntry("workspace_id", "ws-valid-1");
    }

    @Test
    void rejectsExtraAliyunBailianArgumentsInsteadOfIgnoringThem() {
        AtomicReference<char[]> saved = new AtomicReference<>();
        AtomicReference<Map<String, String>> savedAttrs = new AtomicReference<>();
        var fixture = fixture(new CapturingAuthenticationClient(saved, savedAttrs, List.of(), Map.of()));

        fixture.model.update(new PasteMessage("/login api aliyun-bailian"));
        commitPlainEnter(fixture);
        fixture.model.update(new PasteMessage("sk-test-key ws-valid-1 cn-beijing unexpected"));
        fixture.model.update(key(KeyType.keyCR));

        assertThat(fixture.controller.secureInputRequested()).isTrue();
        assertThat(fixture.controller.state().status())
                .contains("Too many arguments: expected API key [workspace] [region] for aliyun-bailian");
        assertThat(saved.get()).isNull();
        assertThat(savedAttrs.get()).isNull();
    }

    @Test
    void cancelsAliyunBailianOnEscape() {
        var fixture = fixture();

        fixture.model.update(new PasteMessage("/login api aliyun-bailian"));
        commitPlainEnter(fixture);

        assertThat(fixture.controller.secureInputRequested()).isTrue();
        fixture.model.update(key(KeyType.keyESC));

        assertThat(fixture.controller.secureInputRequested()).isFalse();
        assertThat(fixture.controller.state().status()).isEqualTo("API key entry cancelled");
    }

    @Test
    void leavesNoPendingSecureInputWhenStoredAttributeLookupFails() {
        var authentication = new CapturingAuthenticationClient(
                new AtomicReference<>(), new AtomicReference<>(), List.of(), Map.of());
        authentication.attributeLookupFailure = new IllegalStateException("AUTH_STORE_UNAVAILABLE");
        var fixture = fixture(authentication);

        fixture.model.update(new PasteMessage("/login api aliyun-bailian"));
        commitPlainEnter(fixture);

        assertThat(fixture.controller.secureInputRequested()).isFalse();
        assertThat(fixture.controller.bailianConfigStep()).isEqualTo(CodingTerminalController.BailianConfigStep.NONE);
        assertThat(fixture.controller.state().recoverableError()).isPresent();

        authentication.attributeLookupFailure = null;
        fixture.model.update(new PasteMessage("/login api aliyun-bailian"));
        commitPlainEnter(fixture);

        assertThat(fixture.controller.secureInputRequested()).isTrue();
        assertThat(fixture.controller.bailianConfigStep())
                .isEqualTo(CodingTerminalController.BailianConfigStep.API_KEY);
    }

    @Test
    void updatesExistingAliyunBailianConfigurationRetainingValuesOnEmptyEnter() {
        AtomicReference<char[]> saved = new AtomicReference<>();
        AtomicReference<Map<String, String>> savedAttrs = new AtomicReference<>();
        CodingAuthenticationView existing = new CodingAuthenticationView(
                "model-auth://aliyun-bailian/default",
                "aliyun-bailian",
                CodingAuthenticationView.Method.API_KEY,
                CodingAuthenticationView.Status.AUTHENTICATED,
                "aliyun-bailian account",
                Optional.empty(),
                OptionalLong.empty(),
                false);
        Map<String, String> existingAttrs = Map.of("workspace_id", "ws-existing-01", "region", "cn-beijing");
        var fixture = fixture(new CapturingAuthenticationClient(saved, savedAttrs, List.of(existing), existingAttrs));

        fixture.model.update(new PasteMessage("/login api aliyun-bailian"));
        commitPlainEnter(fixture);

        // Update API Key -> press Enter directly to keep existing key & attributes
        assertThat(fixture.controller.secureInputIsUpdate()).isTrue();
        assertThat(fixture.controller.secureInputPrompt()).isEqualTo("New key ┃ ");
        fixture.model.update(key(KeyType.keyCR));

        // Saved in single step!
        assertThat(fixture.controller.secureInputRequested()).isFalse();
        assertThat(savedAttrs.get())
                .containsEntry("workspace_id", "ws-existing-01")
                .containsEntry("region", "cn-beijing");
    }

    @Test
    void routesLeftArrowInModelSelectorToNavigateBackToProviderList() {
        var models = List.of(
                new io.haifa.agent.application.project.product.coding.CodingModelOption(
                        "m1", "Model 1", "p1", "Provider 1", Set.of("TEXT_CHAT"), 128_000),
                new io.haifa.agent.application.project.product.coding.CodingModelOption(
                        "m2", "Model 2", "p2", "Provider 2", Set.of("TEXT_CHAT"), 128_000));
        var client = new UnusedClient(models);
        var pump = new TerminalEventPump(64);
        var controller = new CodingTerminalController(
                new ProjectId("project-1"),
                client,
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(80, 24),
                Runnable::run);
        var fixture = new Fixture(controller, pump, new Tui4jCodingTerminalModel(controller, pump, System::nanoTime));

        fixture.model.update(new PasteMessage("/model"));
        commitPlainEnter(fixture);

        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("model-provider");

        commitPlainEnter(fixture);
        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("model");

        fixture.model.update(key(KeyType.KeyLeft));
        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("model-provider");
    }

    private Fixture fixture() {
        return fixture(System::nanoTime);
    }

    private Fixture fixture(java.util.function.LongSupplier monotonicNanos) {
        var pump = new TerminalEventPump(64);
        var controller = new CodingTerminalController(
                new ProjectId("project-1"),
                new UnusedClient(),
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(80, 24),
                Runnable::run);
        return new Fixture(controller, pump, new Tui4jCodingTerminalModel(controller, pump, monotonicNanos));
    }

    private Fixture fixture(CodingAuthenticationClient authentication) {
        var pump = new TerminalEventPump(64);
        var controller = new CodingTerminalController(
                new ProjectId("project-1"),
                new UnusedClient(),
                authentication,
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(80, 24),
                Runnable::run);
        return new Fixture(controller, pump, new Tui4jCodingTerminalModel(controller, pump));
    }

    private KeyPressMessage key(KeyType type) {
        return new KeyPressMessage(new Key(type));
    }

    private void commitPlainEnter(Fixture fixture) {
        var guarded = fixture.model.update(key(KeyType.keyCR));
        fixture.model.update(guarded.command().execute());
    }

    private boolean emitsClearScreen(Command command) {
        if (Command.isNone(command)) {
            return false;
        }
        return emitsClearScreen(command.execute());
    }

    private boolean emitsClearScreen(Message message) {
        if (message instanceof ClearScreenMessage) {
            return true;
        }
        if (message instanceof BatchMessage batch) {
            for (Command command : batch.commands()) {
                if (emitsClearScreen(command)) {
                    return true;
                }
            }
        }
        return false;
    }

    private KeyPressMessage runes(char value) {
        return new KeyPressMessage(new Key(KeyType.KeyRunes, new char[] {value}));
    }

    private MouseMessage wheel(MouseButton button) {
        return new MouseMessage(1, 4, false, false, false, MouseAction.MouseActionPress, button);
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
        private final List<io.haifa.agent.application.project.product.coding.CodingModelOption> models;

        UnusedClient() {
            this(List.of());
        }

        UnusedClient(List<io.haifa.agent.application.project.product.coding.CodingModelOption> models) {
            this.models = Objects.requireNonNull(models, "models must not be null");
        }

        @Override
        public List<io.haifa.agent.application.project.product.coding.CodingModelOption> models() {
            return models;
        }

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
        public Optional<AgentRunSnapshot> findRun(AgentRunId runId) {
            return Optional.empty();
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
        public Optional<InteractionView> pendingInteraction(AgentRunId runId) {
            return Optional.empty();
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

    private static final class CapturingAuthenticationClient implements CodingAuthenticationClient {
        private final AtomicReference<char[]> saved;
        private final AtomicReference<Map<String, String>> savedAttributes;
        private final List<CodingAuthenticationView> existingConnections;
        private final Map<String, String> existingAttributes;
        private RuntimeException attributeLookupFailure;

        private CapturingAuthenticationClient(AtomicReference<char[]> saved) {
            this(saved, new AtomicReference<>(), List.of(), Map.of());
        }

        private CapturingAuthenticationClient(
                AtomicReference<char[]> saved, List<CodingAuthenticationView> existingConnections) {
            this(saved, new AtomicReference<>(), existingConnections, Map.of());
        }

        private CapturingAuthenticationClient(
                AtomicReference<char[]> saved,
                AtomicReference<Map<String, String>> savedAttributes,
                List<CodingAuthenticationView> existingConnections,
                Map<String, String> existingAttributes) {
            this.saved = saved;
            this.savedAttributes = savedAttributes;
            this.existingConnections = List.copyOf(existingConnections);
            this.existingAttributes = Map.copyOf(existingAttributes);
        }

        @Override
        public List<CodingAuthenticationView> connections() {
            return existingConnections;
        }

        @Override
        public CodingAuthenticationView loginCodexBrowser() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CodingAuthenticationView saveApiKey(String providerId, char[] apiKey) {
            return saveApiKey(providerId, apiKey, Map.of());
        }

        @Override
        public CodingAuthenticationView saveApiKey(String providerId, char[] apiKey, Map<String, String> attributes) {
            saved.set(apiKey.clone());
            savedAttributes.set(attributes);
            return new CodingAuthenticationView(
                    providerId + "/default",
                    providerId,
                    CodingAuthenticationView.Method.API_KEY,
                    CodingAuthenticationView.Status.AUTHENTICATED,
                    providerId + " account",
                    Optional.empty(),
                    OptionalLong.empty(),
                    false);
        }

        @Override
        public Optional<Map<String, String>> providerAttributes(String providerId) {
            if (attributeLookupFailure != null) {
                throw attributeLookupFailure;
            }
            return Optional.of(existingAttributes);
        }

        @Override
        public boolean logout(String connectionId) {
            return false;
        }
    }
}
