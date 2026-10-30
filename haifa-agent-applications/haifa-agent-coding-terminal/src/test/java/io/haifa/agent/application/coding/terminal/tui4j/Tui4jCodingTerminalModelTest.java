package io.haifa.agent.application.coding.terminal.tui4j;

import static io.haifa.agent.application.coding.terminal.application.CodingTerminalTestSupport.view;
import static org.assertj.core.api.Assertions.assertThat;

import com.williamcallahan.tui4j.compat.bubbletea.BatchMessage;
import com.williamcallahan.tui4j.compat.bubbletea.ClearScreenMessage;
import com.williamcallahan.tui4j.compat.bubbletea.Command;
import com.williamcallahan.tui4j.compat.bubbletea.KeyPressMessage;
import com.williamcallahan.tui4j.compat.bubbletea.Message;
import com.williamcallahan.tui4j.compat.bubbletea.PasteMessage;
import com.williamcallahan.tui4j.compat.bubbletea.QuitMessage;
import com.williamcallahan.tui4j.compat.bubbletea.ReadClipboardMessage;
import com.williamcallahan.tui4j.compat.bubbletea.UpdateResult;
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
import io.haifa.agent.core.session.AgentSessionStatus;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
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
    void enablesMouseAllMotionAtStartupUnlessDisabled() {
        assertThat(requestsMouseAllMotion(fixture().model.init())).isTrue();
        assertThat(requestsMouseAllMotion(modelWithMouseReporting(false).init()))
                .isFalse();
    }

    @Test
    void dropsIdleMouseMotionButKeepsDragMotion() {
        var fixture = fixture();
        seedSelectableTranscript(fixture);

        assertThat(fixture.model.filterMessage(mouse(1, 0, MouseAction.MouseActionMotion, MouseButton.MouseButtonLeft)))
                .isNull();

        fixture.model.update(mouse(1, 0, MouseAction.MouseActionPress, MouseButton.MouseButtonLeft));
        assertThat(fixture.model.filterMessage(
                        mouse(1, 20, MouseAction.MouseActionMotion, MouseButton.MouseButtonLeft)))
                .isNotNull();
    }

    @Test
    void disablesMouseReportingWhenTheControllerTurnsItOff() {
        var fixture = fixture();
        fixture.model.init();
        fixture.controller.initializeMouseReporting(false);

        var updated = fixture.model.update(new Tui4jCodingTerminalModel.PollMessage());

        assertThat(requestsDisableMouse(updated.command())).isTrue();
    }

    @Test
    void rechecksTheRealWindowSizeEveryBoundedNumberOfPolls() {
        var fixture = fixture();

        assertThat(requestsWindowSizeCheck(fixture.model.init())).isTrue();

        int polls = 0;
        boolean requested = false;
        while (polls < Tui4jCodingTerminalModel.WINDOW_SIZE_POLL_TICKS && !requested) {
            polls++;
            var polled = fixture.model.update(new Tui4jCodingTerminalModel.PollMessage());
            requested = requestsWindowSizeCheck(polled.command());
        }

        assertThat(requested).isTrue();
        assertThat(polls).isEqualTo(Tui4jCodingTerminalModel.WINDOW_SIZE_POLL_TICKS);
    }

    @Test
    void doesNotPollWindowSizeWhenTheHostReportsResizeSignals() {
        TerminalHostInfo linux = TerminalHostInfo.detect(
                Map.of(
                        "os.name", "Linux",
                        "os.version", "6.0",
                        "os.arch", "amd64",
                        "java.version", "21"),
                List.of());
        var fixture = fixture(linux);

        for (int poll = 0; poll < Tui4jCodingTerminalModel.WINDOW_SIZE_POLL_TICKS; poll++) {
            var polled = fixture.model.update(new Tui4jCodingTerminalModel.PollMessage());
            assertThat(requestsWindowSizeCheck(polled.command())).isFalse();
        }
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
    void opensCommandAndWorkspacePathCompletionOnceTheTypedTriggerGoesIdle() {
        var command = fixture();

        // The trigger opens the popup only after the typed run settles, so a `/` inside a streamed paste never
        // swallows the text that follows it.
        typeRunes(command, "/");
        settleIdleInput(command);

        assertThat(command.controller.state().selector())
                .get()
                .extracting(TerminalSelector::title)
                .isEqualTo("Commands");
        assertThat(command.controller.state().editorBuffer()).isEqualTo("/");

        var path = fixture();
        typeRunes(path, "@");
        settleIdleInput(path);

        assertThat(path.controller.state().selector())
                .get()
                .extracting(TerminalSelector::title)
                .isEqualTo("Workspace paths");
        assertThat(path.controller.state().selector().orElseThrow().options()).contains("@README.md", "@src/");

        path.update(runes('s'));
        assertThat(path.controller.state().editorBuffer()).isEqualTo("@s");
        assertThat(path.controller.state().selector().orElseThrow().options()).containsExactly("@src/");
    }

    @Test
    void keepsFilteringAnOpenCompletionAndSubmitsAnExactSlashCommandWithOneEnter() {
        var fixture = fixture();

        typeRunes(fixture, "/quit");
        settleIdleInput(fixture);

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("/quit");
        assertThat(fixture.controller.state().selector().orElseThrow().options())
                .containsExactly("/quit");

        fixture.update(key(KeyType.keyCR));

        assertThat(fixture.controller.state().exitRequested()).isTrue();
    }

    @Test
    void keepsTheSpaceTypedAfterACommandWhileCompletionIsOpen() {
        var fixture = fixture();

        typeRunes(fixture, "/rename");
        settleIdleInput(fixture);
        assertThat(fixture.controller.state().selector()).isPresent();

        fixture.update(key(KeyType.KeySpace));
        typeRunes(fixture, "next");
        settleIdleInput(fixture);

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("/rename next");
    }

    @Test
    void highlightsSelectionWhileTheMouseIsStillDragging() {
        var fixture = fixture();
        seedSelectableTranscript(fixture);

        fixture.model.update(mouse(1, 0, MouseAction.MouseActionPress, MouseButton.MouseButtonLeft));
        String pressed = fixture.model.view();
        fixture.model.update(mouse(1, 20, MouseAction.MouseActionMotion, MouseButton.MouseButtonLeft));
        String dragged = fixture.model.view();

        assertThat(dragged).isNotEqualTo(pressed);
        assertThat(dragged).contains("\u001B[7m");
    }

    @Test
    void treatsRepeatedPressEventsDuringDragAsMotion() {
        var clipboard = new RecordingClipboard(true);
        var fixture = fixtureWithClipboard(clipboard);
        seedSelectableTranscript(fixture);

        fixture.model.update(mouse(1, 0, MouseAction.MouseActionPress, MouseButton.MouseButtonLeft));
        fixture.model.update(mouse(1, 20, MouseAction.MouseActionPress, MouseButton.MouseButtonLeft));
        fixture.model.update(mouse(1, 20, MouseAction.MouseActionRelease, MouseButton.MouseButtonLeft));

        assertThat(clipboard.text).isNull();
        fixture.model.update(key(KeyType.keyETX)).command().execute();
        assertThat(clipboard.text).isNotNull();
        assertThat(clipboard.text.length()).isGreaterThan(1);
    }

    @Test
    void copiesSelectedTranscriptTextThroughTheClipboardPort() {
        var clipboard = new RecordingClipboard(true);
        var fixture = fixtureWithClipboard(clipboard);
        seedSelectableTranscript(fixture);

        fixture.model.update(mouse(1, 0, MouseAction.MouseActionPress, MouseButton.MouseButtonLeft));
        fixture.model.update(mouse(1, 30, MouseAction.MouseActionMotion, MouseButton.MouseButtonLeft));
        fixture.model.update(mouse(1, 30, MouseAction.MouseActionRelease, MouseButton.MouseButtonLeft));

        assertThat(clipboard.text).isNull();
        assertThat(fixture.model.view()).contains("\u001B[7m");
        fixture.model.update(key(KeyType.keyETX)).command().execute();

        assertThat(clipboard.text).isNotNull().isNotBlank();
    }

    @Test
    void clickTogglesACollapsedTranscriptItemWithoutCopying() {
        var clipboard = new RecordingClipboard(true);
        var fixture = fixtureWithClipboard(clipboard);
        seedSingleToolTranscript(fixture);

        fixture.model.update(mouse(0, 2, MouseAction.MouseActionPress, MouseButton.MouseButtonLeft));
        fixture.model.update(mouse(0, 2, MouseAction.MouseActionRelease, MouseButton.MouseButtonLeft));

        var item = fixture.controller.state().transcript().stream()
                .filter(value -> value.id().equals("tool-tool-1"))
                .findFirst()
                .orElseThrow();
        assertThat(item.expanded()).isTrue();
        assertThat(clipboard.text).isNull();
    }

    @Test
    void clickBelowTheLastTranscriptLineDoesNotToggleAnItem() {
        var clipboard = new RecordingClipboard(true);
        var fixture = fixtureWithClipboard(clipboard);
        seedSingleToolTranscript(fixture);

        fixture.model.update(mouse(5, 2, MouseAction.MouseActionPress, MouseButton.MouseButtonLeft));
        fixture.model.update(mouse(5, 2, MouseAction.MouseActionRelease, MouseButton.MouseButtonLeft));

        var item = fixture.controller.state().transcript().stream()
                .filter(value -> value.id().equals("tool-tool-1"))
                .findFirst()
                .orElseThrow();
        assertThat(item.expanded()).isFalse();
        assertThat(clipboard.text).isNull();
    }

    @Test
    void finishingATextSelectionDoesNotToggleTheTranscriptItem() {
        var clipboard = new RecordingClipboard(true);
        var fixture = fixtureWithClipboard(clipboard);
        seedSingleToolTranscript(fixture);

        fixture.model.update(mouse(0, 1, MouseAction.MouseActionPress, MouseButton.MouseButtonLeft));
        fixture.model.update(mouse(0, 20, MouseAction.MouseActionMotion, MouseButton.MouseButtonLeft));
        fixture.model.update(mouse(0, 20, MouseAction.MouseActionRelease, MouseButton.MouseButtonLeft));

        var item = fixture.controller.state().transcript().stream()
                .filter(value -> value.id().equals("tool-tool-1"))
                .findFirst()
                .orElseThrow();
        assertThat(item.expanded()).isFalse();
        assertThat(clipboard.text).isNull();

        fixture.model.update(key(KeyType.keyETX)).command().execute();
        assertThat(clipboard.text).isNotNull().isNotBlank();
    }

    @Test
    void fallsBackToOsc52WhenTheLocalClipboardFails() {
        var clipboard = new RecordingClipboard(false);
        var fixture = fixtureWithClipboard(clipboard);
        fixture.model.init();
        for (int index = 1; index <= 6; index++) {
            fixture.pump.offer(new TerminalUiAction.UserMessageCommitted("m", "line-" + index));
        }
        fixture.model.update(new WindowSizeMessage(120, 30));
        fixture.model.view();
        fixture.model.update(mouse(1, 0, MouseAction.MouseActionPress, MouseButton.MouseButtonLeft));
        fixture.model.update(mouse(1, 30, MouseAction.MouseActionMotion, MouseButton.MouseButtonLeft));
        fixture.model.update(mouse(1, 30, MouseAction.MouseActionRelease, MouseButton.MouseButtonLeft));

        assertThat(clipboard.text).isNull();
        var copied = (Tui4jCodingTerminalModel.ClipboardCopyResultMessage)
                fixture.model.update(key(KeyType.keyETX)).command().execute();

        var followUp = fixture.model.update(copied);
        fixture.controller.drainEvents();

        assertThat(copied.success()).isFalse();
        assertThat(followUp.command().execute()).isNotNull();
        assertThat(fixture.controller.state().status()).contains("OSC 52");
    }

    @Test
    void escapeClearsTheSelectionWithoutCopying() {
        var clipboard = new RecordingClipboard(true);
        var fixture = fixtureWithClipboard(clipboard);
        fixture.model.init();
        for (int index = 1; index <= 6; index++) {
            fixture.pump.offer(new TerminalUiAction.UserMessageCommitted("m", "line-" + index));
        }
        fixture.model.update(new WindowSizeMessage(120, 30));
        fixture.model.view();
        fixture.model.update(mouse(1, 0, MouseAction.MouseActionPress, MouseButton.MouseButtonLeft));
        fixture.model.update(mouse(1, 30, MouseAction.MouseActionMotion, MouseButton.MouseButtonLeft));

        fixture.model.update(key(KeyType.keyESC));

        assertThat(clipboard.text).isNull();
    }

    @Test
    void doesNotCopyWhileSecureInputIsActive() {
        var clipboard = new RecordingClipboard(true);
        var fixture = fixtureWithClipboard(
                new CapturingAuthenticationClient(new java.util.concurrent.atomic.AtomicReference<>()), clipboard);
        seedSelectableTranscript(fixture);
        fixture.model.update(mouse(1, 0, MouseAction.MouseActionPress, MouseButton.MouseButtonLeft));
        fixture.model.update(mouse(1, 30, MouseAction.MouseActionMotion, MouseButton.MouseButtonLeft));

        fixture.model.update(new PasteMessage("/connect api deepseek"));
        commitPlainEnter(fixture);
        fixture.model.update(new PasteMessage("  private-value  "));
        assertThat(fixture.controller.secureInputRequested()).isTrue();

        assertThat(fixture.model.filterMessage(new QuitMessage())).isNull();
        assertThat(fixture.model.filterMessage(key(KeyType.keyETX))).isNotNull();

        fixture.model.update(mouse(1, 30, MouseAction.MouseActionRelease, MouseButton.MouseButtonLeft));

        assertThat(clipboard.text).isNull();
    }

    @Test
    void copiesSelectionInsteadOfQuittingWhenSignalQuitArrivesDuringSelection() {
        var clipboard = new RecordingClipboard(true);
        var fixture = fixtureWithClipboard(clipboard);
        seedSelectableTranscript(fixture);

        fixture.model.update(mouse(1, 0, MouseAction.MouseActionPress, MouseButton.MouseButtonLeft));
        fixture.model.update(mouse(1, 20, MouseAction.MouseActionMotion, MouseButton.MouseButtonLeft));
        fixture.model.update(mouse(1, 20, MouseAction.MouseActionRelease, MouseButton.MouseButtonLeft));

        QuitMessage quit = new QuitMessage();
        Message filtered = fixture.model.filterMessage(quit);
        assertThat(filtered).isNotNull().isNotSameAs(quit);

        fixture.model.update(filtered).command().execute();

        assertThat(clipboard.text).isNotNull().isNotBlank();
        assertThat(fixture.controller.state().exitRequested()).isFalse();
    }

    @Test
    void signalQuitWithoutSelectionKeepsTheExistingExitSemantics() {
        var fixture = fixture();
        seedSelectableTranscript(fixture);

        QuitMessage quit = new QuitMessage();
        Message filtered = fixture.model.filterMessage(quit);
        assertThat(filtered).isNotNull().isNotSameAs(quit);

        fixture.model.update(filtered);

        assertThat(fixture.controller.state().exitRequested()).isTrue();
    }

    @Test
    void explicitQuitStillQuitsWhileASelectionIsHighlighted() {
        var fixture = fixture();
        seedSelectableTranscript(fixture);
        fixture.model.update(mouse(1, 0, MouseAction.MouseActionPress, MouseButton.MouseButtonLeft));
        fixture.model.update(mouse(1, 20, MouseAction.MouseActionMotion, MouseButton.MouseButtonLeft));

        fixture.controller.accept(new TerminalInput(TerminalInput.Kind.EXECUTE_COMMAND, "/quit", 5));

        assertThat(fixture.controller.state().exitRequested()).isTrue();
        QuitMessage quit = new QuitMessage();
        assertThat(fixture.model.filterMessage(quit)).isSameAs(quit);
    }

    @Test
    void directCtrlCKeyIsDroppedSoTheSignalHandlerOwnsTheInterrupt() {
        var fixture = fixture();
        seedSelectableTranscript(fixture);

        assertThat(fixture.model.filterMessage(key(KeyType.keyETX))).isNull();
    }

    @Test
    void pressOutsideTheTranscriptRegionClearsTheSelection() {
        var clipboard = new RecordingClipboard(true);
        var fixture = fixtureWithClipboard(clipboard);
        seedSelectableTranscript(fixture);
        fixture.model.update(mouse(1, 0, MouseAction.MouseActionPress, MouseButton.MouseButtonLeft));
        fixture.model.update(mouse(1, 30, MouseAction.MouseActionMotion, MouseButton.MouseButtonLeft));

        fixture.model.update(mouse(1, 500, MouseAction.MouseActionPress, MouseButton.MouseButtonLeft));
        fixture.model.update(mouse(1, 500, MouseAction.MouseActionRelease, MouseButton.MouseButtonLeft));

        assertThat(clipboard.text).isNull();
    }

    @Test
    void sidebarColumnDoesNotSelectTranscriptText() {
        var clipboard = new RecordingClipboard(true);
        var fixture = fixtureWithClipboard(clipboard);
        seedSelectableTranscript(fixture);

        fixture.model.update(mouse(1, 110, MouseAction.MouseActionPress, MouseButton.MouseButtonLeft));
        fixture.model.update(mouse(1, 118, MouseAction.MouseActionMotion, MouseButton.MouseButtonLeft));
        fixture.model.update(mouse(1, 118, MouseAction.MouseActionRelease, MouseButton.MouseButtonLeft));

        assertThat(clipboard.text).isNull();
    }

    @Test
    void resizingClearsTheSelection() {
        var clipboard = new RecordingClipboard(true);
        var fixture = fixtureWithClipboard(clipboard);
        seedSelectableTranscript(fixture);
        fixture.model.update(mouse(1, 0, MouseAction.MouseActionPress, MouseButton.MouseButtonLeft));
        fixture.model.update(mouse(1, 30, MouseAction.MouseActionMotion, MouseButton.MouseButtonLeft));

        fixture.model.update(new WindowSizeMessage(110, 30));
        fixture.model.update(mouse(1, 30, MouseAction.MouseActionRelease, MouseButton.MouseButtonLeft));

        assertThat(clipboard.text).isNull();
    }

    @Test
    void togglingTheSidebarClearsTheSelection() {
        var clipboard = new RecordingClipboard(true);
        var fixture = fixtureWithClipboard(clipboard);
        seedSelectableTranscript(fixture);
        fixture.model.update(mouse(1, 0, MouseAction.MouseActionPress, MouseButton.MouseButtonLeft));
        fixture.model.update(mouse(1, 30, MouseAction.MouseActionMotion, MouseButton.MouseButtonLeft));

        fixture.pump.offer(new TerminalUiAction.SidebarToggled());
        fixture.model.update(new WindowSizeMessage(120, 30));
        fixture.model.update(mouse(1, 30, MouseAction.MouseActionRelease, MouseButton.MouseButtonLeft));

        assertThat(clipboard.text).isNull();
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
    void summarizesLargeBracketedPasteAndDeletesItInOneStroke() {
        var fixture = fixture();

        fixture.model.update(new PasteMessage("alpha\nbeta\ngamma"));

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasted #1 ~3 lines]");

        fixture.model.update(key(KeyType.keyBS));

        assertThat(fixture.controller.state().editorBuffer()).isEmpty();
    }

    @Test
    void deletesThePasteSummaryWhenBackspaceArrivesAsARepeatedDeleteRune() {
        var fixture = fixture();
        fixture.model.update(new PasteMessage("alpha\nbeta\ngamma"));
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasted #1 ~3 lines]");

        // mintty and ConPTY can deliver Backspace as a rune burst instead of the dedicated keyBS type.
        fixture.model.update(new KeyPressMessage(new Key(KeyType.KeyRunes, new char[] {0x7f, 0x7f})));

        assertThat(fixture.controller.state().editorBuffer()).isEmpty();
    }

    @Test
    void deletesThePasteSummaryWhenBackspaceArrivesWithPaddingControlRunes() {
        var fixture = fixture();
        fixture.model.update(new PasteMessage("alpha\nbeta\ngamma"));

        fixture.model.update(new KeyPressMessage(new Key(KeyType.KeyRunes, new char[] {'\b', 0x01})));

        assertThat(fixture.controller.state().editorBuffer()).isEmpty();
        assertThat(fixture.controller.state().editorCursor()).isZero();
    }

    @Test
    void neverKeepsAnUndecodableControlRuneInTheDraft() {
        var fixture = fixture();
        fixture.model.update(new PasteMessage("draft"));

        fixture.model.update(new KeyPressMessage(new Key(KeyType.KeyRunes, new char[] {'a', 0x1f, 'b'})));

        assertThat(fixture.controller.state().editorBuffer())
                .startsWith("draft")
                .doesNotContain("\u001f");
    }

    @Test
    void treatsACrLfPairInsideAStreamedPasteAsOneBreak() {
        var fixture = fixture();

        streamUnbracketedPasteWithCrLf(fixture, "alpha", " ", "gamma");
        settleIdleInput(fixture);

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasted #1 ~3 lines]");

        commitPlainEnter(fixture);

        assertThat(fixture.controller.state().transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .isEqualTo("alpha\n \ngamma"));
    }

    @Test
    void endsAStreamedPasteWithATrailingBreakWithoutSubmitting() {
        var fixture = fixture();

        fixture.update(keyWithRunes("alpha"));
        fixture.update(key(KeyType.keyCR));
        fixture.update(keyWithRunes("beta"));
        fixture.update(key(KeyType.keyCR));
        fixture.update(keyWithRunes("gamma"));
        fixture.update(key(KeyType.keyCR));
        settleIdleInput(fixture);

        assertThat(fixture.controller.state().editorBuffer()).startsWith("[Pasted #1 ~");
        assertThat(fixture.controller.state().transcript()).isEmpty();

        commitPlainEnter(fixture);

        assertThat(fixture.controller.state().transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .isEqualTo("alpha\nbeta\ngamma\n"));
    }

    @Test
    void keepsTheFirstLineInsideAStreamedPasteSummary() {
        var fixture = fixture();

        streamPerCharacterPaste(fixture, "1  a b c", "2", "3");
        settleIdleInput(fixture);

        String token = "[Pasted #1 ~3 lines]";
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo(token);
        assertThat(fixture.controller.state().editorCursor()).isEqualTo(token.length());

        commitPlainEnter(fixture);

        assertThat(fixture.controller.state().transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .isEqualTo("1  a b c\n2\n3"));
    }

    @Test
    void summarizesAStreamedCommandBlockOfFourLines() {
        var fixture = fixture();

        String first = "if errorlevel 1 exit /b %ERRORLEVEL%";
        String second = "if not exist \"%HAIFA_DATA_ROOT%\\logs\" mkdir \"%HAIFA_DATA_ROOT%\\logs\"";
        String fourth =
                "if not defined HAIFA_SQLITE_DATABASE_PATH set \"HAIFA_SQLITE_DATABASE_PATH=%HAIFA_DATA_ROOT%\\data\\runtime.db\"";
        streamPerCharacterPaste(fixture, first, second, first, fourth);
        settleIdleInput(fixture);

        // The pasted ` /b` must not open the completion popup and swallow the rest of the block.
        assertThat(fixture.controller.state().selector()).isEmpty();
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasted #1 ~4 lines]");

        commitPlainEnter(fixture);

        assertThat(fixture.controller.state().transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .isEqualTo(String.join("\n", first, second, first, fourth)));
    }

    @Test
    void keepsASmallStreamedPasteInline() {
        var fixture = fixture();

        streamUnbracketedPaste(fixture, "alpha", "beta");
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasting ~2 lines]");

        settleIdleInput(fixture);

        // Below the fold threshold the receiving block gives way to the normalized text itself.
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("alpha\nbeta");
        assertThat(fixture.controller.state().editorCursor()).isEqualTo("alpha\nbeta".length());
    }

    @Test
    void keepsShortBracketedPasteVerbatim() {
        var fixture = fixture();

        fixture.model.update(new PasteMessage("single line snippet"));

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("single line snippet");
    }

    @Test
    void keepsTwoLineBracketedPasteVerbatimBelowOpenCodeThreshold() {
        var fixture = fixture();

        fixture.model.update(new PasteMessage("one\ntwo"));

        // OpenCode summarizes at 3+ lines; two-line pastes stay editable inline.
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("one\ntwo");
    }

    @Test
    void summarizesCtrlVClipboardPasteLikeBracketedPaste() {
        var clipboard = new RecordingClipboard(true);
        clipboard.pasteText = "alpha\nbeta\ngamma\ndelta";
        var fixture = fixtureWithClipboard(clipboard);

        var pending = fixture.model.update(key(KeyType.keySYN));
        assertThat(Command.isNone(pending.command())).isFalse();
        fixture.model.update(pending.command().execute());

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasted #1 ~4 lines]");
    }

    @Test
    void summarizesReadClipboardMessageFromTextareaPasteBinding() {
        var clipboard = new RecordingClipboard(true);
        clipboard.pasteText = "one\ntwo\nthree";
        var fixture = fixtureWithClipboard(clipboard);

        var pending = fixture.model.update(new ReadClipboardMessage());
        assertThat(Command.isNone(pending.command())).isFalse();
        fixture.model.update(pending.command().execute());

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasted #1 ~3 lines]");
    }

    @Test
    void summarizesSingleLinePasteBeyondTheCharacterThreshold() {
        var fixture = fixture();

        fixture.model.update(new PasteMessage("x".repeat(151)));

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasted #1 ~1 lines]");
    }

    @Test
    void submitsTheFullLongPasteWhileKeepingItsTranscriptPreviewBounded() {
        var client = new UnusedClient();
        client.currentSession = sessionView("Long paste");
        var fixture = activeFixture(client);
        String pasted = ("abcdefghijklmnopqrstuvwx\n").repeat(1_000);
        fixture.model.update(new PasteMessage(pasted));

        var enter = fixture.model.update(key(KeyType.keyCR));
        var submitted = fixture.model.update(enter.command().execute());
        fixture.model.update(submitted.command().execute());

        assertThat(client.submittedMessages).containsExactly(pasted);
        assertThat(fixture.controller.state().editorBuffer()).isEmpty();
        assertThat(fixture.controller.state().transcript()).anySatisfy(item -> {
            assertThat(item.title()).isEqualTo("You");
            assertThat(item.body()).endsWith("[Preview truncated; full message submitted]");
        });
    }

    @Test
    void expandsSummarizedPasteBackToTheOriginalTextOnSubmit() {
        var fixture = fixture();
        String pasted = "alpha\nbeta\ngamma";
        fixture.model.update(new PasteMessage(pasted));

        var guarded = fixture.model.update(key(KeyType.keyCR));
        fixture.model.update(guarded.command().execute());

        assertThat(fixture.controller.state().editorBuffer()).isEmpty();
        assertThat(fixture.controller.state().transcript()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("You");
            assertThat(item.body()).isEqualTo(pasted);
        });
    }

    @Test
    void movesTheCursorAcrossASummarizedPasteAsOneUnit() {
        var fixture = fixture();
        String token = "[Pasted #1 ~3 lines]";
        fixture.model.update(new PasteMessage("alpha\nbeta\ngamma"));
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo(token);

        fixture.model.update(key(KeyType.KeyLeft));
        assertThat(fixture.controller.state().editorCursor()).isZero();

        fixture.model.update(key(KeyType.KeyRight));
        assertThat(fixture.controller.state().editorCursor()).isEqualTo(token.length());
    }

    @Test
    void expandsConsecutiveSummarizedPastesWithoutInjectingContent() {
        var fixture = fixture();
        fixture.model.update(new PasteMessage("a1\na2\na3"));
        fixture.model.update(new PasteMessage("b1\nb2\nb3"));

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasted #1 ~3 lines][Pasted #2 ~3 lines]");

        var guarded = fixture.model.update(key(KeyType.keyCR));
        fixture.model.update(guarded.command().execute());

        assertThat(fixture.controller.state().transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .isEqualTo("a1\na2\na3b1\nb2\nb3"));
    }

    @Test
    void deletesASummarizedPasteForwardInOneStroke() {
        var fixture = fixture();
        fixture.model.update(new PasteMessage("alpha\nbeta\ngamma"));

        fixture.model.update(key(KeyType.KeyLeft));
        fixture.model.update(key(KeyType.KeyDelete));

        assertThat(fixture.controller.state().editorBuffer()).isEmpty();
    }

    @Test
    void treatsASummarizedPasteStartingWithASlashAsAMessage() {
        var fixture = fixture();
        String pasted = "/usr/local/bin/tool\nsecond line\nthird line";
        fixture.model.update(new PasteMessage(pasted));

        var guarded = fixture.model.update(key(KeyType.keyCR));
        fixture.model.update(guarded.command().execute());

        assertThat(fixture.controller.state().transcript()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("You");
            assertThat(item.body()).isEqualTo(pasted);
        });
    }

    @Test
    void preservesLeadingIndentationInASummarizedPaste() {
        var fixture = fixture();
        String pasted = "    indented\nplain\nthird";
        fixture.model.update(new PasteMessage(pasted));

        var guarded = fixture.model.update(key(KeyType.keyCR));
        fixture.model.update(guarded.command().execute());

        assertThat(fixture.controller.state().transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .isEqualTo(pasted));
    }

    @Test
    void doesNotInjectASeparatorAfterExistingDraftText() {
        var fixture = fixture();
        fixture.model.update(new PasteMessage("--data="));
        fixture.model.update(new PasteMessage("alpha\nbeta\ngamma"));

        var guarded = fixture.model.update(key(KeyType.keyCR));
        fixture.model.update(guarded.command().execute());

        assertThat(fixture.controller.state().transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .isEqualTo("--data=alpha\nbeta\ngamma"));
    }

    @Test
    void doesNotExpandAPlaceholderLiteralInsideAnotherPaste() {
        var fixture = fixture();
        String first = "keep\n[Pasted #2 ~3 lines]\ntail";
        fixture.model.update(new PasteMessage(first));
        fixture.model.update(new PasteMessage("b1\nb2\nb3"));

        var guarded = fixture.model.update(key(KeyType.keyCR));
        fixture.model.update(guarded.command().execute());

        assertThat(fixture.controller.state().transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .isEqualTo(first + "b1\nb2\nb3"));
    }

    @Test
    void protectsASummarizedPasteFromPartialEditing() {
        var fixture = fixture();
        fixture.update(new PasteMessage("alpha\nbeta\ngamma"));
        fixture.update(key(KeyType.keyLF));
        fixture.update(runes('x'));
        fixture.update(runes('y'));
        settleIdleInput(fixture);
        String draft = fixture.controller.state().editorBuffer();

        fixture.update(key(KeyType.KeyUp));
        fixture.update(runes('Z'));

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo(draft);
        assertThat(fixture.controller.state().recoverableError()).contains("PASTE_PLACEHOLDER_ATOMIC");
    }

    @Test
    void typesARuneEIntoTheEditorInsteadOfEditingTheWaitingDraft() {
        var fixture = waitingFixture();

        fixture.model.update(runes('e'));

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("e");
        assertThat(fixture.controller.state().waiting()).hasValueSatisfying(waiting -> assertThat(waiting.phase())
                .isEqualTo(io.haifa.agent.application.coding.terminal.state.WaitingMessage.Phase.WAITING));
    }

    @Test
    void altEStartsEditingTheWaitingDraft() {
        var fixture = waitingFixture();

        fixture.model.update(new KeyPressMessage(new Key(KeyType.KeyRunes, new char[] {'e'}, true)));

        assertThat(fixture.controller.state().waiting()).hasValueSatisfying(waiting -> assertThat(waiting.phase())
                .isEqualTo(io.haifa.agent.application.coding.terminal.state.WaitingMessage.Phase.EDITING));
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("queued draft");
    }

    private Fixture waitingFixture() {
        var pump = new TerminalEventPump(64);
        var controller = new CodingTerminalController(
                new ProjectId("project-1"),
                new UnusedClient(),
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(80, 24)
                        .withWaiting(Optional.of(new io.haifa.agent.application.coding.terminal.state.WaitingMessage(
                                "wait-1",
                                "queued draft",
                                io.haifa.agent.application.coding.terminal.state.WaitingMessage.Phase.WAITING))),
                Runnable::run);
        return new Fixture(controller, pump, new Tui4jCodingTerminalModel(controller, pump));
    }

    @Test
    void enterDuringAnActiveRunQueuesWaitingInsteadOfSteering() {
        var pump = new TerminalEventPump(64);
        TerminalUiState idle = TerminalUiState.initial(80, 24);
        TerminalUiState active = new TerminalUiState(
                idle.header(),
                idle.loadedResources(),
                idle.transcript(),
                idle.pending(),
                "WORKING",
                "",
                0,
                idle.selector(),
                idle.footer(),
                idle.columns(),
                idle.rows(),
                idle.session(),
                Optional.of(new AgentRunId("run-1")),
                idle.appliedCursor(),
                idle.seenEventIds(),
                idle.recoverableError(),
                idle.activity(),
                false,
                idle.sidebarVisible(),
                idle.sidebarExplicitlyToggled(),
                idle.themeName(),
                false);
        var controller = new CodingTerminalController(
                new ProjectId("project-1"), new UnusedClient(), pump, new TerminalUiReducer(), active, Runnable::run);
        var model = new Tui4jCodingTerminalModel(controller, pump);
        var fixture = new Fixture(controller, pump, model);
        model.update(new PasteMessage("queue me"));

        commitPlainEnter(fixture);

        assertThat(controller.state().waiting()).hasValueSatisfying(waiting -> {
            assertThat(waiting.phase())
                    .isEqualTo(io.haifa.agent.application.coding.terminal.state.WaitingMessage.Phase.WAITING);
            assertThat(waiting.text()).isEqualTo("queue me");
        });
    }

    @Test
    void showsAReceivingBlockWhileTheBurstStreamsAndFinalizesItInPlace() {
        var fixture = fixture();

        streamUnbracketedPaste(fixture, "first line", "second line", "third line");

        // The block is visible in the next view without delivering the idle message, so reception has feedback.
        assertThat(TerminalScreenCells.plainText(fixture.model.view())).contains("[Pasting ~3 lines]");
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasting ~3 lines]");
        assertThat(fixture.controller.state().status()).isEqualTo("Idle");

        settleIdleInput(fixture);

        // The block finalizes at its own position instead of opening the summary somewhere else.
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasted #1 ~3 lines]");
        assertThat(fixture.controller.state().transcript()).isEmpty();

        var explicitEnter = fixture.update(key(KeyType.keyCR));
        var submitted = fixture.update(explicitEnter.command().execute());

        assertThat(Command.isNone(submitted.command())).isFalse();
        assertThat(fixture.controller.state().editorBuffer()).isEmpty();
        assertThat(fixture.controller.state().status()).isEqualTo("Submitting");
    }

    @Test
    void buildsTheSameSummaryForBothSpaceKeyTypes() {
        var hostTyped = fixture();
        streamPerCharacterPaste(hostTyped, "1  a b c", "2", "3");
        settleIdleInput(hostTyped);

        var runeTyped = fixture();
        streamPerCharacterPasteAsRunes(runeTyped, "1  a b c", "2", "3");
        settleIdleInput(runeTyped);

        assertThat(hostTyped.controller.state().editorBuffer()).isEqualTo("[Pasted #1 ~3 lines]");
        assertThat(runeTyped.controller.state().editorBuffer())
                .isEqualTo(hostTyped.controller.state().editorBuffer());
    }

    @Test
    void keepsTheDraftAroundAReceivingBlockWhileTheStreamArrives() {
        var fixture = fixture();
        fixture.update(new PasteMessage("keep me "));

        // The painted first line is taken back behind a receiving block, and the draft the user already had keeps its
        // own text and position.
        streamPerCharacterPaste(fixture, "first line", "second line", "third line");

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("keep me [Pasting ~3 lines]");
        assertThat(fixture.controller.state().editorCursor()).isEqualTo("keep me [Pasting ~3 lines]".length());

        settleIdleInput(fixture);

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("keep me [Pasted #1 ~3 lines]");
        assertThat(fixture.controller.state().editorCursor()).isEqualTo("keep me [Pasted #1 ~3 lines]".length());
    }

    @Test
    void keepsOneReceivingBlockWhileTheStreamKeepsArriving() {
        var fixture = fixture();

        streamPerCharacterPaste(fixture, "alpha", "beta");
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasting ~2 lines]");

        fixture.update(key(KeyType.keyCR));
        fixture.update(keyWithRunes("gamma"));
        fixture.update(key(KeyType.keyCR));
        fixture.update(keyWithRunes("delta"));

        // Later lines stay in the same block and raise its tentative count instead of opening another one.
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasting ~4 lines]");
        assertThat(fixture.controller.state().editorCursor()).isEqualTo("[Pasting ~4 lines]".length());

        settleIdleInput(fixture);

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasted #1 ~4 lines]");

        commitPlainEnter(fixture);

        assertThat(fixture.controller.state().transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .isEqualTo("alpha\nbeta\ngamma\ndelta"));
    }

    @Test
    void reusesTheFrameForHeldTextButRefreshesOnResizeAndInterrupt() {
        var fixture = fixture(() -> 0);
        streamPerCharacterPaste(fixture, "first", "second", "third");
        String frame = fixture.model.view();

        fixture.update(keyWithRunes(" more text"));
        assertThat(fixture.model.view()).isSameAs(frame);

        fixture.pump.offer(new TerminalUiAction.UserMessageCommitted("received-event", "runtime update"));
        fixture.update(new Tui4jCodingTerminalModel.PollMessage());
        assertThat(TerminalScreenCells.plainText(fixture.model.view())).contains("runtime update");

        fixture.update(new WindowSizeMessage(120, 40));
        String resized = fixture.model.view();
        assertThat(resized).isNotSameAs(frame);
        assertThat(TerminalScreenCells.plainText(resized)).contains("[Pasting ~3 lines]");

        fixture.update(key(KeyType.keyETX));
        assertThat(fixture.controller.state().editorBuffer()).isEmpty();
        assertThat(TerminalScreenCells.plainText(fixture.model.view())).doesNotContain("[Pasting");
    }

    @Test
    void anEnterCancelledByInterruptCannotCaptureNewTyping() {
        var fixture = fixture();
        fixture.update(runes('a'));
        var oldEnter = fixture.update(key(KeyType.keyCR)).command();
        fixture.update(key(KeyType.keyETX));
        fixture.update(runes('b'));

        fixture.update(oldEnter.execute());

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("b");
        assertThat(fixture.controller.state().transcript()).isEmpty();
    }

    @Test
    void keepsFastTypedInputAsTextAndSubmitsItWithASingleEnter() {
        AtomicLong clock = new AtomicLong();
        var fixture = fixture(clock::get);

        fixture.model.update(runes('a'));
        clock.addAndGet(Duration.ofMillis(10).toNanos());
        fixture.model.update(runes('b'));

        // Two keys 10ms apart are typing, not a paste: the run stays visible as text, and the Enter after it submits
        // the run instead of becoming a newline inside it.
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("ab");

        var enter = fixture.model.update(key(KeyType.keyCR));
        clock.addAndGet(StreamedInputBatch.IDLE_INTERVAL.toNanos());
        fixture.model.update(enter.command().execute());

        assertThat(fixture.controller.state().editorBuffer()).isEmpty();
        assertThat(fixture.controller.state().transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .isEqualTo("ab"));
    }

    @Test
    void keepsAnExistingSummaryWhenASecondStreamedPasteFollowsIt() {
        var fixture = fixture();
        fixture.update(new PasteMessage("one\ntwo\nthree"));
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasted #1 ~3 lines]");

        streamPerCharacterPaste(fixture, "a", "b", "c");
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasted #1 ~3 lines][Pasting ~3 lines]");

        settleIdleInput(fixture);

        // The settled stream keeps its own number next to the earlier summary instead of nesting or dropping it.
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasted #1 ~3 lines][Pasted #2 ~3 lines]");

        commitPlainEnter(fixture);

        assertThat(fixture.controller.state().transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .isEqualTo("one\ntwo\nthreea\nb\nc"));
    }

    @Test
    void keepsLineOrderWhenAStreamMixesCrAndLf() {
        var fixture = fixture();

        fixture.update(keyWithRunes("a"));
        fixture.update(key(KeyType.keyCR));
        fixture.update(keyWithRunes("b"));
        fixture.update(key(KeyType.keyLF));
        fixture.update(keyWithRunes("c"));
        settleIdleInput(fixture);

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasted #1 ~3 lines]");

        commitPlainEnter(fixture);

        assertThat(fixture.controller.state().transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .isEqualTo("a\nb\nc"));
    }

    @Test
    void summarizesAStreamThatOnlyUsesLf() {
        var fixture = fixture();

        fixture.update(keyWithRunes("alpha"));
        fixture.update(key(KeyType.keyLF));
        fixture.update(keyWithRunes("beta"));
        fixture.update(key(KeyType.keyLF));
        fixture.update(keyWithRunes("gamma"));
        settleIdleInput(fixture);

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasted #1 ~3 lines]");

        commitPlainEnter(fixture);

        assertThat(fixture.controller.state().transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .isEqualTo("alpha\nbeta\ngamma"));
    }

    @Test
    void keepsTheHeldStreamBeforeAnExplicitNewline() {
        var fixture = fixture();

        fixture.update(keyWithRunes("a"));
        fixture.update(key(KeyType.keyCR));
        fixture.update(keyWithRunes("b"));
        fixture.update(new EnterKeyModifierMessage(EnterKeyModifier.Shift));
        settleIdleInput(fixture);

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("a\nb\n");
    }

    @Test
    void keepsTheDraftAfterTheCaretOutsideAStreamedPaste() {
        var fixture = fixture();
        fixture.update(new PasteMessage("LEFT RIGHT"));
        fixture.controller.accept(new TerminalInput(TerminalInput.Kind.EDITOR_CHANGED, "LEFT RIGHT", 5));
        fixture.update(new WindowSizeMessage(120, 40));

        streamPerCharacterPaste(fixture, "a", "b", "c");
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("LEFT [Pasting ~3 lines]RIGHT");

        settleIdleInput(fixture);

        String token = "[Pasted #1 ~3 lines]";
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("LEFT " + token + "RIGHT");
        assertThat(fixture.controller.state().editorCursor()).isEqualTo(("LEFT " + token).length());

        commitPlainEnter(fixture);

        assertThat(fixture.controller.state().transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .isEqualTo("LEFT a\nb\ncRIGHT"));
    }

    @Test
    void keepsTextTypedBeforeTheStreamOutsideThePasteSummary() {
        AtomicLong clock = new AtomicLong();
        var fixture = fixture(clock::get);
        for (char value : "old".toCharArray()) {
            fixture.update(runes(value));
        }

        clock.addAndGet(Duration.ofSeconds(5).toNanos());
        fixture.update(new Tui4jCodingTerminalModel.PollMessage());

        streamPerCharacterPaste(fixture, "a", "b", "c");
        clock.addAndGet(StreamedInputBatch.IDLE_INTERVAL.toNanos());
        settleIdleInput(fixture);

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("old[Pasted #1 ~3 lines]");
        assertThat(fixture.controller.state().editorCursor()).isEqualTo("old[Pasted #1 ~3 lines]".length());

        commitPlainEnter(fixture);

        assertThat(fixture.controller.state().transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .isEqualTo("olda\nb\nc"));
    }

    @Test
    void doesNotExecuteTheCommandOnTheFirstLineOfAStreamedPaste() {
        var fixture = fixture();

        streamPerCharacterPaste(fixture, "/quit", "still a message", "third line");
        settleIdleInput(fixture);

        assertThat(fixture.controller.state().exitRequested()).isFalse();
        assertThat(fixture.controller.state().selector()).isEmpty();
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasted #1 ~3 lines]");

        commitPlainEnter(fixture);

        assertThat(fixture.controller.state().exitRequested()).isFalse();
        assertThat(fixture.controller.state().transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .isEqualTo("/quit\nstill a message\nthird line"));
    }

    @Test
    void doesNotOpenPathCompletionForAStreamThatStartsWithAPathMention() {
        var fixture = fixture();

        streamPerCharacterPaste(fixture, "@src/main/Main.java", "@src/main/Other.java", "@src/main/Third.java");
        settleIdleInput(fixture);

        assertThat(fixture.controller.state().selector()).isEmpty();
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasted #1 ~3 lines]");
    }

    @Test
    void summarizesALongStreamedSingleLineWithoutAnyBreak() {
        var fixture = fixture();
        String longLine = "x".repeat(151);

        streamPerCharacterPaste(fixture, longLine);
        settleIdleInput(fixture);

        String token = "[Pasted #1 ~1 lines]";
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo(token);
        assertThat(fixture.controller.state().editorCursor()).isEqualTo(token.length());

        commitPlainEnter(fixture);

        assertThat(fixture.controller.state().transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .isEqualTo(longLine));
    }

    @Test
    void keepsTabIndentationInsideAStreamedPaste() {
        var fixture = fixture();

        streamPerCharacterPaste(fixture, "if (ready) {", "\treturn 1;", "}");
        settleIdleInput(fixture);

        assertThat(fixture.controller.state().selector()).isEmpty();
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasted #1 ~3 lines]");

        commitPlainEnter(fixture);

        assertThat(fixture.controller.state().transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .isEqualTo("if (ready) {\n    return 1;\n}"));
    }

    @Test
    void clearsAStreamedPasteOnInterruptWithoutLettingItsIdleTimerWriteBack() {
        var fixture = fixture();

        streamPerCharacterPaste(fixture, "panic one", "panic two", "panic three");
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasting ~3 lines]");

        fixture.update(key(KeyType.keyETX));
        settleIdleInput(fixture);

        assertThat(fixture.controller.state().editorBuffer()).isEmpty();
    }

    @Test
    void writesTheHeldStreamBackBeforeABackspaceEditsIt() {
        var fixture = fixture();

        fixture.update(keyWithRunes("alpha"));
        fixture.update(key(KeyType.keyCR));
        fixture.update(keyWithRunes("beta"));
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasting ~2 lines]");

        fixture.update(key(KeyType.keyBS));
        settleIdleInput(fixture);

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("alpha\nbet");
        assertThat(fixture.controller.state().transcript()).isEmpty();
    }

    @Test
    void keepsAStreamedPasteInOrderWhilePollsAndResizesInterleave() {
        var fixture = fixture();

        fixture.update(keyWithRunes("alpha"));
        fixture.update(key(KeyType.keyCR));
        fixture.update(keyWithRunes("beta"));
        fixture.update(new Tui4jCodingTerminalModel.PollMessage());
        fixture.update(new WindowSizeMessage(120, 40));
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasting ~2 lines]");

        fixture.update(key(KeyType.keyCR));
        fixture.update(keyWithRunes("gamma"));
        settleIdleInput(fixture);

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("[Pasted #1 ~3 lines]");

        commitPlainEnter(fixture);

        assertThat(fixture.controller.state().transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .isEqualTo("alpha\nbeta\ngamma"));
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
    void ctrlOExpandsTranscriptWhileApprovalSelectorOwnsInput() {
        var fixture = fixture();
        fixture.pump.offer(new TerminalUiAction.ShellCompleted("!pwd", "Command exited\nD:/workspace", "EXITED"));
        fixture.pump.offer(new TerminalUiAction.SelectorOpened(
                new TerminalSelector("interaction:approval-1", "Approval", List.of("reject", "approve"), 0)));
        fixture.model.update(new WindowSizeMessage(100, 30));

        assertThat(fixture.controller.state().selector()).isPresent();
        assertThat(fixture.controller.state().transcript().getLast().expanded()).isTrue();

        fixture.model.update(key(KeyType.keySI));

        assertThat(fixture.controller.state().selector()).isPresent();
        assertThat(fixture.controller.state().transcript().getLast().expanded()).isFalse();
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
        assertThat(fixture.model.view()).contains("RUNNING (1s) · Run Command");
        clock.set(Duration.ofSeconds(14).toNanos());
        assertThat(fixture.model.view()).contains("RUNNING (5s) · Run Command");

        fixture.pump.offer(new TerminalUiAction.RunEventReceived(event(
                3,
                new RunEventPayloads.ToolLifecycle("tool-1", "execution_run", "SUCCEEDED", "NONE", "git status", ""))));
        fixture.model.update(new WindowSizeMessage(80, 24));
        assertThat(fixture.model.view()).contains("THINKING (1s)").doesNotContain("RUNNING (");
    }

    @Test
    void collapsesOrdinaryToolFailureButKeepsUnknownResultDetailsVisible() {
        var fixture = fixture();
        fixture.pump.offer(new TerminalUiAction.RunEventReceived(
                event(1, new RunEventPayloads.RunLifecycle("RUNNING", 1, "NONE"))));
        fixture.model.update(new WindowSizeMessage(120, 40));

        fixture.pump.offer(new TerminalUiAction.RunEventReceived(event(
                2,
                new RunEventPayloads.ToolLifecycle(
                        "tool-1",
                        "Apply Patch",
                        "FAILED",
                        "PATCH_AMBIGUOUS_MATCH",
                        "README.md",
                        "",
                        Optional.empty(),
                        "",
                        "file_patch"))));
        fixture.model.update(new WindowSizeMessage(120, 40));
        String failed = fixture.model.view();
        assertThat(failed).contains("apply patch", "Not completed: Multiple matches");
        assertThat(failed).doesNotContain("Reason: PATCH_AMBIGUOUS_MATCH");

        fixture.pump.offer(new TerminalUiAction.RunEventReceived(event(
                3,
                new RunEventPayloads.ToolLifecycle(
                        "tool-2",
                        "Run Command",
                        "OUTCOME_UNKNOWN",
                        "TOOL_OUTCOME_UNKNOWN",
                        "rm -rf",
                        "",
                        Optional.empty(),
                        "",
                        "execution_run"))));
        fixture.model.update(new WindowSizeMessage(120, 40));
        assertThat(fixture.model.view()).contains("Outcome: UNKNOWN", "Target: rm -rf");
    }

    @Test
    void keyboardFallbackKeepsFollowingNewToolsWithoutCreatingAnExplicitSelection() {
        var fixture = fixture();
        fixture.model.update(new PasteMessage("preserved draft"));
        for (int index = 1; index <= 3; index++) {
            fixture.pump.offer(new TerminalUiAction.RunEventReceived(event(
                    index,
                    new RunEventPayloads.ToolLifecycle(
                            "tool-" + index, "Read File", "SUCCEEDED", "NONE", "README.md", "result-" + index))));
            fixture.model.update(new WindowSizeMessage(120, 40));
            if (index == 2) {
                fixture.model.update(key(KeyType.keyCAN));
                fixture.model.update(runes('t'));
            } else {
                fixture.model.update(key(KeyType.keySI));
            }
            assertThat(fixture.controller.state().transcript().getLast())
                    .satisfies(item -> assertThat(item.expanded()).isTrue());
        }
        assertThat(fixture.controller.state().transcript())
                .allSatisfy(item -> assertThat(item.expanded()).isTrue());
        assertThat(fixture.controller.state().transcript().get(1).toolDetails())
                .hasValueSatisfying(details -> assertThat(details.expanded()).isTrue());
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("preserved draft");
    }

    @Test
    void technicalShortcutFollowsTheSelectedToolAndPreservesDraft() {
        var fixture = fixture();
        for (int index = 1; index <= 2; index++) {
            fixture.pump.offer(new TerminalUiAction.RunEventReceived(event(
                    index,
                    new RunEventPayloads.ToolLifecycle(
                            "tool-" + index,
                            "Read File",
                            "SUCCEEDED",
                            "NONE",
                            "README.md",
                            "result-" + index,
                            Optional.empty(),
                            "读取说明",
                            "file_read"))));
        }
        fixture.model.update(new WindowSizeMessage(120, 40));
        fixture.model.update(new PasteMessage("保留草稿"));
        fixture.controller.toggleTranscriptItem("tool-tool-1");
        fixture.model.update(key(KeyType.keyCAN));
        fixture.model.update(runes('t'));

        var selected = fixture.controller.state().transcript().getFirst();
        assertThat(selected.toolDetails())
                .hasValueSatisfying(details -> assertThat(details.expanded()).isTrue());
        assertThat(fixture.controller.state().transcript().getLast().toolDetails())
                .hasValueSatisfying(details -> assertThat(details.expanded()).isFalse());
        fixture.model.update(key(KeyType.keySI));
        assertThat(fixture.controller.state().transcript().getFirst().expanded())
                .isFalse();
        assertThat(fixture.controller.state().transcript().getLast().expanded()).isFalse();
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("保留草稿");
    }

    @Test
    void finalResponseShortcutCopiesThroughTheBackgroundClipboardCommandWithoutChangingDraft() {
        var clipboard = new RecordingClipboard(true);
        var fixture = fixtureWithClipboard(clipboard);
        fixture.pump.offer(new TerminalUiAction.RunEventReceived(
                event(1, new RunEventPayloads.AssistantTextDelta("generation-1", "**完成**"))));
        fixture.pump.offer(new TerminalUiAction.RunEventReceived(
                event(2, new RunEventPayloads.RunLifecycle("COMPLETED", 1, "NONE"))));
        fixture.model.update(new WindowSizeMessage(120, 40));
        fixture.model.update(new PasteMessage("next instruction"));
        fixture.model.update(key(KeyType.keyCAN));
        var copy = fixture.model.update(runes('y')).command();

        assertThat(clipboard.text).isNull();
        fixture.model.update(copy.execute());
        assertThat(clipboard.text).contains("完成").doesNotContain("**", "Assistant / Final response");
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("next instruction");
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
    void editsEmojiCombiningTextAtGraphemeBoundaries() {
        var fixture = fixture();
        String family = "👨‍👩‍👧‍👦";

        fixture.model.update(new PasteMessage("A" + family + "e\u0301-中x\u001B"));
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("A" + family + "e\u0301-中x");
        int afterCombining = 1 + family.length() + "e\u0301".length();
        fixture.controller.accept(new TerminalInput(
                TerminalInput.Kind.EDITOR_CHANGED, fixture.controller.state().editorBuffer(), afterCombining));
        fixture.model.update(new WindowSizeMessage(80, 24));
        fixture.model.update(key(KeyType.keyBS));

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("A" + family + "-中x");
        assertThat(fixture.controller.state().editorCursor()).isEqualTo(1 + family.length());

        fixture.model.update(key(KeyType.KeyLeft));
        fixture.model.update(key(KeyType.KeyDelete));

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("A-中x");
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

        fixture.model.update(new PasteMessage("/connect api deepseek"));
        commitPlainEnter(fixture);
        fixture.model.update(new PasteMessage("  private-value  "));

        assertThat(fixture.controller.secureInputRequested()).isTrue();
        assertThat(fixture.controller.secureInputIsUpdate()).isFalse();
        assertThat(fixture.controller.state().editorBuffer()).isEmpty();
        assertThat(fixture.controller.state().status()).contains("Enter the DeepSeek API key");
        assertThat(fixture.model.view())
                .doesNotContain("private-value", "stored as plaintext", "enter send")
                .contains("API Key ┃ •••••••••••••", "Enter submit · Esc cancel");

        fixture.model.update(key(KeyType.keyCR));

        assertThat(fixture.controller.secureInputRequested()).isFalse();
        assertThat(saved.get()).containsExactly("private-value".toCharArray());
        assertThat(fixture.controller.state().status()).contains("Connected to DeepSeek");
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

        fixture.model.update(new PasteMessage("/connect api deepseek"));
        commitPlainEnter(fixture);

        assertThat(fixture.controller.secureInputRequested()).isTrue();
        assertThat(fixture.controller.secureInputIsUpdate()).isTrue();
        assertThat(fixture.controller.state().status()).contains("Update the DeepSeek API key");
        assertThat(fixture.model.view())
                .contains(
                        "New key ┃ Paste or enter a new API key (Esc keeps the existing key)",
                        "Enter update · Esc cancel");

        fixture.model.update(new PasteMessage("updated-key"));
        fixture.model.update(key(KeyType.keyCR));

        assertThat(fixture.controller.secureInputRequested()).isFalse();
        assertThat(saved.get()).containsExactly("updated-key".toCharArray());
    }

    @Test
    void rejectsApiKeyLoginForUnsupportedProvider() {
        var fixture = fixture();

        fixture.model.update(new PasteMessage("/connect api openai-codex"));
        commitPlainEnter(fixture);

        assertThat(fixture.controller.secureInputRequested()).isFalse();
        assertThat(fixture.controller.state().recoverableError()).contains("AUTH_METHOD_UNSUPPORTED");
    }

    @Test
    void requiresWorkspaceForFirstAliyunBailianSetup() {
        AtomicReference<char[]> saved = new AtomicReference<>();
        AtomicReference<Map<String, String>> savedAttrs = new AtomicReference<>();
        var fixture = fixture(new CapturingAuthenticationClient(saved, savedAttrs, List.of(), Map.of()));

        fixture.model.update(new PasteMessage("/connect api aliyun-bailian"));
        commitPlainEnter(fixture);

        assertThat(fixture.controller.secureInputRequested()).isTrue();
        assertThat(fixture.controller.secureInputIsMasked()).isTrue();
        assertThat(fixture.controller.secureInputPrompt()).isEqualTo("API Key ┃ ");
        assertThat(fixture.controller.state().status()).contains("Step 1/3");
        assertThat(fixture.model.view()).contains("API Key ┃ ", "Enter next · Esc cancel");

        fixture.model.update(new PasteMessage("sk-bailian-test-key"));
        fixture.model.update(key(KeyType.keyCR));

        assertThat(fixture.controller.secureInputRequested()).isTrue();
        assertThat(fixture.controller.bailianConfigStep())
                .isEqualTo(CodingTerminalController.BailianConfigStep.WORKSPACE_ID);
        assertThat(fixture.controller.secureInputIsMasked()).isFalse();
        assertThat(fixture.controller.secureInputPrompt()).isEqualTo("Workspace ┃ ");
        assertThat(fixture.controller.state().status()).contains("Step 2/3");
        assertThat(saved.get()).isNull();
        assertThat(savedAttrs.get()).isNull();

        fixture.model.update(new PasteMessage("ws-default-01"));
        fixture.model.update(key(KeyType.keyCR));

        assertThat(fixture.controller.bailianConfigStep()).isEqualTo(CodingTerminalController.BailianConfigStep.REGION);
        assertThat(fixture.controller.secureInputPrompt()).isEqualTo("Region ┃ ");
        assertThat(fixture.controller.state().status()).contains("Step 3/3");

        fixture.model.update(key(KeyType.keyCR));

        assertThat(fixture.controller.secureInputRequested()).isFalse();
        assertThat(saved.get()).containsExactly("sk-bailian-test-key".toCharArray());
        assertThat(savedAttrs.get())
                .containsEntry("workspace_id", "ws-default-01")
                .containsEntry("region", "cn-beijing");
        assertThat(fixture.controller.state().status()).contains("Connected to Aliyun Bailian");
    }

    @Test
    void configuresAliyunBailianInThreeStepsWithCustomWorkspaceAndRegion() {
        AtomicReference<char[]> saved = new AtomicReference<>();
        AtomicReference<Map<String, String>> savedAttrs = new AtomicReference<>();
        var fixture = fixture(new CapturingAuthenticationClient(saved, savedAttrs, List.of(), Map.of()));

        fixture.model.update(new PasteMessage("/connect api aliyun-bailian"));
        commitPlainEnter(fixture);

        fixture.model.update(new PasteMessage("sk-bailian-custom-key"));
        fixture.model.update(key(KeyType.keyCR));
        fixture.model.update(new PasteMessage("ws-custom-88"));
        fixture.model.update(key(KeyType.keyCR));
        fixture.model.update(new PasteMessage("cn-shanghai"));
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

        fixture.model.update(new PasteMessage("/connect api aliyun-bailian ws-quick-99 cn-shanghai"));
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
    void rejectsInvalidWorkspaceIdInTheWorkspaceStep() {
        AtomicReference<char[]> saved = new AtomicReference<>();
        AtomicReference<Map<String, String>> savedAttrs = new AtomicReference<>();
        var fixture = fixture(new CapturingAuthenticationClient(saved, savedAttrs, List.of(), Map.of()));

        fixture.model.update(new PasteMessage("/connect api aliyun-bailian"));
        commitPlainEnter(fixture);
        fixture.model.update(new PasteMessage("sk-test-key"));
        fixture.model.update(key(KeyType.keyCR));

        fixture.model.update(new PasteMessage("INVALID_WS"));
        fixture.model.update(key(KeyType.keyCR));

        assertThat(fixture.controller.secureInputRequested()).isTrue();
        assertThat(fixture.controller.state().status()).contains("Invalid workspace ID");

        fixture.model.update(new PasteMessage("ws-valid-1"));
        fixture.model.update(key(KeyType.keyCR));
        fixture.model.update(key(KeyType.keyCR));

        assertThat(fixture.controller.secureInputRequested()).isFalse();
        assertThat(savedAttrs.get()).containsEntry("workspace_id", "ws-valid-1");
    }

    @Test
    void rejectsPositionalWorkspaceArgumentsInTheApiKeyStep() {
        AtomicReference<char[]> saved = new AtomicReference<>();
        AtomicReference<Map<String, String>> savedAttrs = new AtomicReference<>();
        var fixture = fixture(new CapturingAuthenticationClient(saved, savedAttrs, List.of(), Map.of()));

        fixture.model.update(new PasteMessage("/connect api aliyun-bailian"));
        commitPlainEnter(fixture);
        fixture.model.update(new PasteMessage("sk-test-key ws-valid-1 cn-beijing unexpected"));
        fixture.model.update(key(KeyType.keyCR));

        assertThat(fixture.controller.secureInputRequested()).isTrue();
        assertThat(fixture.controller.state().status()).contains("The API key cannot contain spaces");
        assertThat(saved.get()).isNull();
        assertThat(savedAttrs.get()).isNull();
    }

    @Test
    void cancelsAliyunBailianOnEscape() {
        var fixture = fixture();

        fixture.model.update(new PasteMessage("/connect api aliyun-bailian"));
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

        fixture.model.update(new PasteMessage("/connect api aliyun-bailian"));
        commitPlainEnter(fixture);

        assertThat(fixture.controller.secureInputRequested()).isFalse();
        assertThat(fixture.controller.bailianConfigStep()).isEqualTo(CodingTerminalController.BailianConfigStep.NONE);
        assertThat(fixture.controller.state().recoverableError()).isPresent();

        authentication.attributeLookupFailure = null;
        fixture.model.update(new PasteMessage("/connect api aliyun-bailian"));
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

        fixture.model.update(new PasteMessage("/connect api aliyun-bailian"));
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

    @Test
    void opensCommandPaletteWhenTriggeredByShortcut() {
        var fixture = fixture();

        fixture.model.update(key(KeyType.keyDLE));

        assertThat(fixture.controller.state().selector()).isPresent();
        assertThat(fixture.controller.state().selector().get().title()).isEqualTo("Commands");
    }

    @Test
    void reflowsTheDraftWithinTheMainColumnWhenTheSidebarOrWindowChanges() {
        var fixture = fixture();
        String draft = "中🙂ab".repeat(18);
        fixture.model.update(new WindowSizeMessage(120, 30));
        fixture.model.update(new PasteMessage(draft));

        for (int columns : List.of(120, 99, 100, 160)) {
            fixture.model.update(new WindowSizeMessage(columns, 30));
            for (int toggle = 0; toggle < 2; toggle++) {
                var state = fixture.controller.state();
                boolean sidebar = state.sidebarVisible() && columns >= 100;
                List<String> lines = TerminalScreenCells.plainText(fixture.model.view())
                        .lines()
                        .toList();
                int inputStart = java.util.stream.IntStream.range(0, lines.size())
                        .filter(row -> lines.get(row).startsWith("┃"))
                        .findFirst()
                        .orElseThrow();
                StringBuilder visibleDraft = new StringBuilder();
                for (String line : lines.subList(inputStart, lines.size() - 2)) {
                    String input = line;
                    if (sidebar) {
                        int divider = line.lastIndexOf('│');
                        assertThat(divider).isGreaterThanOrEqualTo(0);
                        input = line.substring(0, divider);
                        assertThat(com.williamcallahan.tui4j.ansi.TextWidth.measureCellWidth(input))
                                .isEqualTo(columns - 43);
                        assertThat(line.substring(divider + 1)).doesNotContain("中", "🙂", "ab");
                    }
                    visibleDraft.append(input.replace("┃", "").replace(" ", ""));
                }
                assertThat(visibleDraft.toString()).contains(draft);
                assertThat(state.editorBuffer()).isEqualTo(draft);
                assertThat(state.editorCursor()).isEqualTo(draft.length());
                fixture.model.update(key(KeyType.keyCAN));
                fixture.model.update(runes('b'));
            }
        }
        fixture.model.update(runes('Z'));
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo(draft + "Z");
    }

    @Test
    void togglesSidebarUsingLeaderKeyCombination() {
        var fixture = fixture();
        boolean initialVisible = fixture.controller.state().sidebarVisible();

        fixture.model.update(key(KeyType.keyCAN));
        fixture.model.update(runes('b'));
        fixture.controller.drainEvents();

        assertThat(fixture.controller.state().sidebarVisible()).isEqualTo(!initialVisible);

        fixture.model.update(key(KeyType.keyCAN));
        fixture.model.update(runes('b'));
        fixture.controller.drainEvents();

        assertThat(fixture.controller.state().sidebarVisible()).isEqualTo(initialVisible);
    }

    @Test
    void leaderEInformsUserThatExternalEditorIsDeferred() {
        var fixture = fixture();
        fixture.model.update(new PasteMessage("draft-code"));

        fixture.model.update(key(KeyType.keyCAN));
        fixture.model.update(runes('e'));
        fixture.controller.drainEvents();

        assertThat(fixture.controller.state().status()).isEqualTo("External editor deferred in this version");
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("draft-code");
    }

    @Test
    void approvalDialogRejectsLeaderKeysAndDoesNotAccidentallySubmit() {
        var fixture = fixture();
        fixture.pump.offer(new TerminalUiAction.SelectorOpened(
                new TerminalSelector("interaction:approval-1", "Approval", List.of("reject", "approve"), 1)));
        fixture.controller.drainEvents();

        assertThat(fixture.controller.state().selector()).isPresent();
        assertThat(fixture.controller.state().selector().orElseThrow().selected())
                .isEqualTo(1);

        fixture.model.update(key(KeyType.keyCAN));
        fixture.model.update(runes('m'));
        fixture.controller.drainEvents();

        assertThat(fixture.controller.state().selector()).isPresent();
        assertThat(fixture.controller.state().selector().orElseThrow().title()).isEqualTo("Approval");
        assertThat(fixture.controller.state().selector().orElseThrow().selected())
                .isEqualTo(1);
    }

    @Test
    void commandPaletteAndLeaderActionsPreserveDraftAndPastedContent() {
        var fixture = fixture();
        String largeContent = "line 1\nline 2\nline 3\nline 4\nline 5\nline 6\nline 7";
        fixture.model.update(new PasteMessage(largeContent));
        String originalBuffer = fixture.controller.state().editorBuffer();
        assertThat(originalBuffer).startsWith("[Pasted #1 ~7 lines]");

        fixture.model.update(key(KeyType.keyDLE));
        assertThat(fixture.controller.state().selector()).isPresent();
        assertThat(fixture.controller.state().selector().orElseThrow().kind()).isEqualTo("command-palette");

        fixture.model.update(key(KeyType.keyESC));
        assertThat(fixture.controller.state().selector()).isEmpty();
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo(originalBuffer);

        fixture.model.update(key(KeyType.keyCAN));
        fixture.model.update(runes('s'));
        fixture.controller.drainEvents();

        assertThat(fixture.controller.state().editorBuffer()).isEqualTo(originalBuffer);
        fixture.model.update(key(KeyType.keyESC));
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo(originalBuffer);
    }

    @Test
    void leaderRenamePromptPreservesDraftAndCancelsOnEsc() {
        var fixture = fixture();
        fixture.model.update(new PasteMessage("my draft before rename"));

        fixture.model.update(key(KeyType.keyCAN));
        fixture.model.update(runes('r'));
        fixture.controller.drainEvents();

        assertThat(fixture.controller.state().status()).isEqualTo("No active session to rename");
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("my draft before rename");
    }

    @Test
    void leaderRenameWithActiveSessionConfirmsOnEnterWithoutSubmittingMessage() {
        var client = new UnusedClient();
        client.currentSession = sessionView("Original Name");
        var fixture = activeFixture(client);

        fixture.model.update(new PasteMessage("important draft message"));
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("important draft message");

        fixture.model.update(key(KeyType.keyCAN));
        fixture.model.update(runes('r'));
        fixture.controller.drainEvents();

        assertThat(fixture.controller.isRenamingSession()).isTrue();
        assertThat(fixture.controller.state().status()).contains("Rename session");
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("Original Name");

        // Type updated name
        fixture.model.update(runes('2'));
        fixture.controller.drainEvents();
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("Original Name2");

        // Hit Enter to confirm
        commitPlainEnter(fixture);
        fixture.controller.drainEvents();

        assertThat(client.renamedTitle).isEqualTo("Original Name2");
        assertThat(client.submittedMessages).isEmpty();
        assertThat(fixture.controller.isRenamingSession()).isFalse();
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("important draft message");
    }

    @Test
    void leaderRenameWithActiveSessionCancelsOnEscRestoringDraft() {
        var client = new UnusedClient();
        client.currentSession = sessionView("Original Name");
        var fixture = activeFixture(client);

        fixture.model.update(new PasteMessage("important draft message"));

        fixture.model.update(key(KeyType.keyCAN));
        fixture.model.update(runes('r'));
        fixture.controller.drainEvents();

        assertThat(fixture.controller.isRenamingSession()).isTrue();
        fixture.model.update(runes('2'));

        fixture.model.update(key(KeyType.keyESC));
        fixture.controller.drainEvents();

        assertThat(fixture.controller.isRenamingSession()).isFalse();
        assertThat(fixture.controller.state().status()).isEqualTo("Rename cancelled");
        assertThat(fixture.controller.state().editorBuffer()).isEqualTo("important draft message");
        assertThat(client.renamedTitle).isNull();
        assertThat(client.submittedMessages).isEmpty();
    }

    @Test
    void f2ModelCyclingBlockedDuringActiveRun() {
        var fixture = fixture();
        fixture.pump.offer(new TerminalUiAction.RunEventReceived(
                event(1, new RunEventPayloads.RunLifecycle("RUNNING", 1, "NONE"))));
        fixture.controller.drainEvents();

        fixture.model.update(key(KeyType.KeyF2));
        fixture.controller.drainEvents();

        assertThat(fixture.controller.state().status()).isEqualTo("Cannot switch model during active run");
    }

    @Test
    void leaderKeyTimesOutAfterInactivity() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        var fixture = fixture(clock::get);
        boolean initialVisible = fixture.controller.state().sidebarVisible();

        fixture.model.update(key(KeyType.keyCAN));
        clock.addAndGet(2_000_000_000L); // 2 seconds > 1500ms timeout
        fixture.model.update(runes('b'));
        fixture.controller.drainEvents();

        assertThat(fixture.controller.state().sidebarVisible()).isEqualTo(initialVisible);
    }

    @Test
    void themeSwitchPreservesScrollPositionAndDoesNotTriggerPhantomNewOutput() {
        var fixture = fixture();
        for (int i = 0; i < 30; i++) {
            fixture.pump.offer(new TerminalUiAction.UserMessageCommitted("user-" + i, "Line " + i));
        }
        fixture.controller.drainEvents();
        fixture.model.init();

        // Scroll up
        fixture.update(key(KeyType.KeyPgUp));

        // Now switch theme via action
        fixture.pump.offer(new TerminalUiAction.ThemeChanged("nord"));
        fixture.controller.drainEvents();

        // Trigger update to sync components
        fixture.update(new Tui4jCodingTerminalModel.PollMessage());
        String viewAfterTheme = fixture.model.view();

        // The view must not show the phantom "new output below" badge
        assertThat(viewAfterTheme).doesNotContain("new output below");
        assertThat(fixture.controller.state().themeName()).isEqualTo("nord");
    }

    private Fixture fixture() {
        return fixture(System::nanoTime);
    }

    private Fixture activeFixture(UnusedClient client) {
        var pump = new TerminalEventPump(64);
        var controller = new CodingTerminalController(
                new ProjectId("project-1"),
                client,
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(80, 24),
                Runnable::run);
        if (client.currentSession != null) {
            controller.open(client.currentSession.summary().sessionId());
            controller.drainEvents();
        }
        return new Fixture(controller, pump, new Tui4jCodingTerminalModel(controller, pump));
    }

    private static CodingSessionView sessionView(String displayName) {
        return view(Optional.empty(), 0, displayName);
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

    private Fixture fixture(TerminalHostInfo hostInfo) {
        var pump = new TerminalEventPump(64);
        var controller = new CodingTerminalController(
                new ProjectId("project-1"),
                new UnusedClient(),
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(80, 24),
                Runnable::run);
        return new Fixture(
                controller, pump, new Tui4jCodingTerminalModel(controller, pump, System::nanoTime, hostInfo));
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

    private KeyPressMessage keyWithRunes(String runes) {
        return new KeyPressMessage(new Key(KeyType.KeyRunes, runes.toCharArray()));
    }

    /** Streams a paste the way ConPTY delivers it: one key message per character, in the host's own key types. */
    private void streamPerCharacterPaste(Fixture fixture, String... lines) {
        streamPerCharacterPaste(fixture, false, lines);
    }

    /** The same stream with every character as KeyRunes, the decoding some hosts produce for a space. */
    private void streamPerCharacterPasteAsRunes(Fixture fixture, String... lines) {
        streamPerCharacterPaste(fixture, true, lines);
    }

    private void streamPerCharacterPaste(Fixture fixture, boolean runesOnly, String... lines) {
        for (int index = 0; index < lines.length; index++) {
            for (char value : lines[index].toCharArray()) {
                KeyPressMessage pressed = runesOnly ? keyWithRunes(String.valueOf(value)) : keyForHost(value);
                fixture.update(pressed);
            }
            if (index < lines.length - 1) {
                fixture.update(key(KeyType.keyCR));
            }
        }
    }

    /** The key tui4j 0.3.3 actually decodes for one character of a paste: Space and Tab are their own key types. */
    private KeyPressMessage keyForHost(char value) {
        if (value == ' ') {
            return key(KeyType.KeySpace);
        }
        if (value == '\t') {
            return key(KeyType.keyHT);
        }
        return keyWithRunes(String.valueOf(value));
    }

    /** Types one rune at a time like a user, so each key carries its own timestamp. */
    private void typeRunes(Fixture fixture, String text) {
        for (char value : text.toCharArray()) {
            fixture.update(runes(value));
        }
    }

    private void streamUnbracketedPaste(Fixture fixture, String... lines) {
        streamUnbracketedPaste(fixture, false, lines);
    }

    private void streamUnbracketedPasteWithCrLf(Fixture fixture, String... lines) {
        streamUnbracketedPaste(fixture, true, lines);
    }

    /** Streams one paste the way a host without bracketed paste delivers it: runes, break, runes, ... */
    private void streamUnbracketedPaste(Fixture fixture, boolean crlf, String... lines) {
        for (int index = 0; index < lines.length; index++) {
            if (!lines[index].isEmpty()) {
                fixture.update(keyWithRunes(lines[index]));
            }
            if (index < lines.length - 1 || lines[index].isEmpty()) {
                fixture.update(key(KeyType.keyCR));
                if (crlf) {
                    fixture.update(key(KeyType.keyLF));
                }
            }
        }
    }

    /** Delivers the one scheduled check; later keys only move its deadline. */
    private void settleIdleInput(Fixture fixture) {
        while (!fixture.idleChecks.isEmpty()) {
            Command check = fixture.idleChecks.removeFirst();
            fixture.update(check.execute());
        }
    }

    private void commitPlainEnter(Fixture fixture) {
        var guarded = fixture.update(key(KeyType.keyCR));
        fixture.update(guarded.command().execute());
    }

    private Tui4jCodingTerminalModel modelWithMouseReporting(boolean enabled) {
        var pump = new TerminalEventPump(64);
        var controller = new CodingTerminalController(
                new ProjectId("project-1"),
                new UnusedClient(),
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(80, 24),
                Runnable::run);
        return new Tui4jCodingTerminalModel(controller, pump, System::nanoTime, null, enabled);
    }

    private boolean requestsDisableMouse(Command command) {
        if (Command.isNone(command)) {
            return false;
        }
        Message message = command.execute();
        if (message instanceof com.williamcallahan.tui4j.compat.bubbletea.DisableMouseMessage) {
            return true;
        }
        if (message instanceof BatchMessage batch) {
            for (Command child : batch.commands()) {
                if (requestsDisableMouse(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean requestsMouseAllMotion(Command command) {
        if (Command.isNone(command)) {
            return false;
        }
        Message message = command.execute();
        if (message instanceof com.williamcallahan.tui4j.compat.bubbletea.EnableMouseAllMotionMessage) {
            return true;
        }
        if (message instanceof BatchMessage batch) {
            for (Command child : batch.commands()) {
                if (requestsMouseAllMotion(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean requestsWindowSizeCheck(Command command) {
        if (Command.isNone(command)) {
            return false;
        }
        Message message = command.execute();
        if (message instanceof com.williamcallahan.tui4j.message.CheckWindowSizeMessage) {
            return true;
        }
        if (message instanceof BatchMessage batch) {
            for (Command child : batch.commands()) {
                if (requestsWindowSizeCheck(child)) {
                    return true;
                }
            }
        }
        return false;
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

    private MouseMessage mouse(int row, int column, MouseAction action, MouseButton button) {
        return new MouseMessage(column, row, false, false, false, action, button);
    }

    private Fixture fixtureWithClipboard(CodingAuthenticationClient authentication, TerminalClipboard clipboard) {
        var pump = new TerminalEventPump(64);
        var controller = new CodingTerminalController(
                new ProjectId("project-1"),
                new UnusedClient(),
                authentication,
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 30),
                Runnable::run);
        return new Fixture(
                controller,
                pump,
                new Tui4jCodingTerminalModel(controller, pump, System::nanoTime, null, true, clipboard));
    }

    private void seedSelectableTranscript(Fixture fixture) {
        fixture.model.init();
        for (int index = 1; index <= 6; index++) {
            fixture.pump.offer(new TerminalUiAction.UserMessageCommitted("m", "line-" + index));
        }
        fixture.update(new WindowSizeMessage(120, 30));
        fixture.model.view();
    }

    private void seedSingleToolTranscript(Fixture fixture) {
        fixture.model.init();
        fixture.pump.offer(new TerminalUiAction.RunEventReceived(event(
                1,
                new RunEventPayloads.ToolLifecycle("tool-1", "execution_run", "SUCCEEDED", "NONE", "git status", ""))));
        fixture.update(new WindowSizeMessage(120, 30));
        fixture.model.view();
    }

    private Fixture fixtureWithClipboard(TerminalClipboard clipboard) {
        var pump = new TerminalEventPump(64);
        var controller = new CodingTerminalController(
                new ProjectId("project-1"),
                new UnusedClient(),
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 30),
                Runnable::run);
        return new Fixture(
                controller,
                pump,
                new Tui4jCodingTerminalModel(controller, pump, System::nanoTime, null, true, clipboard));
    }

    private static final class RecordingClipboard implements TerminalClipboard {
        private final boolean succeed;
        private String text;
        private String pasteText;

        RecordingClipboard(boolean succeed) {
            this.succeed = succeed;
        }

        @Override
        public boolean copy(String value) {
            this.text = value;
            return succeed;
        }

        @Override
        public java.util.Optional<String> read() {
            return java.util.Optional.ofNullable(pasteText).filter(value -> !value.isEmpty());
        }
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

    private static final class Fixture {
        final CodingTerminalController controller;
        final TerminalEventPump pump;
        final Tui4jCodingTerminalModel model;
        final ArrayDeque<Command> idleChecks = new ArrayDeque<>();

        Fixture(CodingTerminalController controller, TerminalEventPump pump, Tui4jCodingTerminalModel model) {
            this.controller = controller;
            this.pump = pump;
            this.model = model;
        }

        UpdateResult<Tui4jCodingTerminalModel> update(Message message) {
            var result = model.update(message);
            if (result.command() instanceof ComposerInput.IdleCheck) {
                idleChecks.addLast(result.command());
            }
            return result;
        }
    }

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

        CodingSessionView currentSession;
        String renamedTitle;
        final List<String> submittedMessages = new ArrayList<>();

        @Override
        public List<CodingSessionSummary> list(ProjectId projectId, int limit) {
            return List.of();
        }

        @Override
        public CodingSessionView open(AgentSessionId sessionId) {
            if (currentSession != null) return currentSession;
            throw new UnsupportedOperationException();
        }

        @Override
        public CodingSessionView reconcile(AgentSessionId sessionId) {
            if (currentSession != null) return currentSession;
            throw new UnsupportedOperationException();
        }

        @Override
        public CodingSessionSummary rename(AgentSessionId sessionId, String displayName, long expectedRevision) {
            renamedTitle = displayName;
            CodingSessionSummary updated = new CodingSessionSummary(
                    sessionId,
                    new ProjectId("project-1"),
                    displayName,
                    AgentSessionStatus.ACTIVE,
                    Optional.empty(),
                    Optional.empty(),
                    0,
                    Instant.EPOCH,
                    expectedRevision + 1);
            if (currentSession != null) {
                currentSession = new CodingSessionView(
                        updated,
                        currentSession.activeRun(),
                        currentSession.pendingInteraction(),
                        currentSession.eventCursor(),
                        currentSession.configurationDigest(),
                        currentSession.productProfileRef(),
                        currentSession.model());
            }
            return updated;
        }

        @Override
        public Optional<AgentRunSnapshot> findRun(AgentRunId runId) {
            return Optional.empty();
        }

        @Override
        public void submit(AgentSessionId sessionId, String message, String idempotencyKey) {
            submittedMessages.add(message);
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
