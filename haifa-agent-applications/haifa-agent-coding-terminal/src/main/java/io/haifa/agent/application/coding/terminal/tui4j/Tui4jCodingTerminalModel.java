package io.haifa.agent.application.coding.terminal.tui4j;

import com.williamcallahan.tui4j.compat.bubbles.textarea.Textarea;
import com.williamcallahan.tui4j.compat.bubbles.viewport.Viewport;
import com.williamcallahan.tui4j.compat.bubbletea.Command;
import com.williamcallahan.tui4j.compat.bubbletea.KeyPressMessage;
import com.williamcallahan.tui4j.compat.bubbletea.Message;
import com.williamcallahan.tui4j.compat.bubbletea.Model;
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
import com.williamcallahan.tui4j.message.EnterKeyModifier;
import com.williamcallahan.tui4j.message.EnterKeyModifierMessage;
import io.haifa.agent.application.coding.terminal.application.CodingTerminalController;
import io.haifa.agent.application.coding.terminal.application.CodingTerminalController.MessageSubmissionResult;
import io.haifa.agent.application.coding.terminal.application.CodingTerminalController.PreparedMessageSubmission;
import io.haifa.agent.application.coding.terminal.event.TerminalEventPump;
import io.haifa.agent.application.coding.terminal.event.TerminalInput;
import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.coding.terminal.state.TranscriptItem;
import io.haifa.agent.core.run.AgentRunId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/** Production tui4j adapter around the authoritative terminal Controller and Reducer state. */
final class Tui4jCodingTerminalModel implements Model {
    private static final Duration EVENT_POLL_INTERVAL = Duration.ofMillis(50);
    private static final Duration UNBRACKETED_PASTE_GUARD_INTERVAL = Duration.ofMillis(100);
    /** Matches OpenCode defaults: summarize pastes of 3+ lines or more than 150 characters. */
    private static final int PASTE_SUMMARY_MIN_LINES = 3;

    private static final int PASTE_SUMMARY_MAX_CHARACTERS = 150;
    static final int WINDOW_SIZE_POLL_TICKS = 10;
    private static final int MAX_SECRET_CHARACTERS = 65_536;
    private static final long LEADER_TIMEOUT_NANOS = Duration.ofMillis(1500).toNanos();
    private static final Duration SELECTION_AUTO_SCROLL_INTERVAL = Duration.ofMillis(75);

    private final CodingTerminalController controller;
    private final TerminalEventPump pump;
    private final Textarea editor = new Textarea();
    private final Viewport transcript;
    private final TerminalShortcutProfile shortcuts;
    private final Tui4jTerminalView view;
    private final List<String> history = new ArrayList<>();
    private final StringBuilder secretBuffer = new StringBuilder();
    private final Map<String, String> pastedContent = new LinkedHashMap<>();
    private final LongSupplier monotonicNanos;
    private final boolean pollWindowSize;
    private boolean mouseReportingEnabled;
    private final TerminalClipboard clipboard;
    private final TerminalTextSelection selection = new TerminalTextSelection();

    private String transcriptContent = "";
    private List<TranscriptItem> renderedTranscript = List.of();
    private int renderedTranscriptColumns = -1;
    private String historyDraft = "";
    private int editorCursor;
    private int historyIndex;
    private boolean browsingHistory;
    private boolean followTranscript = true;
    private boolean newOutputPending;
    private int pendingTranscriptScrollRows;
    private String displayedTranscriptContent = "";
    private long selectionAutoScrollSequence;
    private int selectionAutoScrollDirection;
    private int selectionAutoScrollColumn;
    private AgentRunId timedRunId;
    private long timedActivityRevision = -1;
    private long activityStartedNanos;
    private PreparedMessageSubmission pendingSubmission;
    private long deferredEnterSequence;
    private DeferredEnter pendingEnter;
    /** Start offset of an open unbracketed-paste burst, or {@code -1} when the composer is not inside one. */
    private int unbracketedPasteStart = -1;
    /** Sequence of the pending burst-end timer, so every later keystroke invalidates the earlier timer. */
    private long unbracketedPasteSequence;
    /** Start offset of the rune insertion that immediately preceded the current key, used to anchor a burst. */
    private int lastRuneInsertStart = -1;
    /** Keys of an open burst, held back so a streamed paste never paints one character at a time. */
    private final StringBuilder burstTail = new StringBuilder();
    /** Whether the burst tail ends with the CR half of a CRLF pair. */
    private boolean burstTailEndsWithCr;

    private boolean secretPresentation;
    private int pasteSequence;
    private int windowSizePollTicks;
    private long leaderActivatedNanos;
    private String currentThemeName = Tui4jTerminalTheme.DEFAULT_THEME;

    Tui4jCodingTerminalModel(CodingTerminalController controller, TerminalEventPump pump) {
        this(controller, pump, System::nanoTime, null);
    }

    Tui4jCodingTerminalModel(CodingTerminalController controller, TerminalEventPump pump, LongSupplier monotonicNanos) {
        this(controller, pump, monotonicNanos, null);
    }

    Tui4jCodingTerminalModel(
            CodingTerminalController controller,
            TerminalEventPump pump,
            LongSupplier monotonicNanos,
            TerminalHostInfo hostInfo) {
        this(controller, pump, monotonicNanos, hostInfo, true);
    }

    Tui4jCodingTerminalModel(
            CodingTerminalController controller,
            TerminalEventPump pump,
            LongSupplier monotonicNanos,
            TerminalHostInfo hostInfo,
            boolean mouseReportingEnabled) {
        this(controller, pump, monotonicNanos, hostInfo, mouseReportingEnabled, TerminalClipboard.system());
    }

    Tui4jCodingTerminalModel(
            CodingTerminalController controller,
            TerminalEventPump pump,
            LongSupplier monotonicNanos,
            TerminalHostInfo hostInfo,
            boolean mouseReportingEnabled,
            TerminalClipboard clipboard) {
        this.controller = controller;
        this.pump = pump;
        this.monotonicNanos = monotonicNanos;
        this.mouseReportingEnabled = mouseReportingEnabled;
        this.clipboard = clipboard;
        this.pollWindowSize =
                hostInfo == null || hostInfo.operatingSystem() == TerminalHostInfo.OperatingSystem.WINDOWS;
        this.shortcuts =
                hostInfo == null ? TerminalShortcutProfile.standard() : TerminalShortcutProfile.forHost(hostInfo);
        this.view = new Tui4jTerminalView(shortcuts);
        TerminalUiState state = controller.state();
        this.currentThemeName = state.themeName();
        this.view.setTheme(this.currentThemeName);
        this.editorCursor = state.editorCursor();
        this.transcript = Viewport.create(state.columns(), 2);
        editor.setWidth(Tui4jTerminalView.editorColumns(state));
        editor.setHeight(3);
        editor.setMaxHeight(3);
        editor.setShowLineNumbers(false);
        editor.setPrompt("┃ ");
        editor.setPlaceholder("Type a message, /command, @file, !command, or !!command");
        editor.focus();
    }

    @Override
    public Command init() {
        syncComponents();
        // tui4j only reports the size when explicitly requested, and its automatic resize path
        // relies on the POSIX WINCH signal, which does not exist on Windows. On Windows the 50ms
        // poll carries a bounded size re-check; Tui4jTerminalIo drops unchanged sizes before the
        // renderer, so an unchanged poll does not reset the renderer cache and repaint every row.
        Command polling = Command.batch(
                Command.printf(Tui4jTerminalIo.DISABLE_ALTERNATE_SCREEN_SCROLL), Command.checkWindowSize(), nextTick());
        return mouseReportingEnabled ? Command.batch(Command.enableMouseAllMotion(), polling) : polling;
    }

    @Override
    public UpdateResult<Tui4jCodingTerminalModel> update(Message message) {
        Command command = Command.none();
        if (message instanceof CtrlCRequestedMessage) {
            // SIGINT (Ctrl-C) must not terminate the program: with a transcript
            // selection it copies, without one it keeps the existing interrupt
            // semantics (clear editor / cancel run).
            if (selection.range().isPresent()) {
                command = copySelectedText();
            } else {
                accept(TerminalInput.Kind.INTERRUPT);
            }
        } else if (message instanceof SelectionAutoScrollMessage scrolling) {
            command = continueSelectionAutoScroll(scrolling);
        } else if (message instanceof ClipboardCopyResultMessage copied) {
            command = completeCopy(copied);
        } else if (message instanceof PollMessage) {
            controller.drainEvents();
            syncComponents();
            command = nextTick();
            if (pollWindowSize && ++windowSizePollTicks >= WINDOW_SIZE_POLL_TICKS) {
                windowSizePollTicks = 0;
                // checkWindowSize first so observers can detect the request without running the tick.
                command = Command.batch(Command.checkWindowSize(), command);
            }
        } else if (message instanceof DeferredEnterMessage deferred) {
            command = commitDeferredEnter(deferred);
        } else if (message instanceof UnbracketedPasteEndMessage ended) {
            endUnbracketedPaste(ended.sequence());
        } else if (message instanceof SubmissionCompletedMessage completed) {
            if (completed.result().submission().equals(pendingSubmission)) {
                controller.completeMessageSubmission(completed.result());
                pendingSubmission = null;
                syncComponents();
            }
        } else if (message instanceof WindowSizeMessage resized) {
            TerminalUiState state = controller.state();
            if (resized.width() != state.columns() || resized.height() != state.rows()) {
                pump.offer(new TerminalUiAction.TerminalResized(resized.width(), resized.height()));
            }
            controller.drainEvents();
            syncComponents();
        } else if (message instanceof ClipboardPasteResultMessage pasted) {
            command = applyClipboardPaste(pasted.content());
        } else if (message instanceof ReadClipboardMessage) {
            // tui4j Textarea maps ctrl+v to Command.paste() → ReadClipboardMessage, but the
            // library never materializes clipboard text. Read via TerminalClipboard instead.
            command = pasteFromClipboard();
        } else if (controller.secureInputRequested() && message instanceof KeyPressMessage key) {
            command = isPasteShortcut(key) ? pasteFromClipboard() : secretKey(key);
        } else if (controller.secureInputRequested() && message instanceof PasteMessage paste) {
            appendSecret(paste.content());
            syncComponents();
        } else if (message instanceof EnterKeyModifierMessage modified
                && (modified.modifier() == EnterKeyModifier.Shift
                        || modified.modifier() == EnterKeyModifier.Ctrl
                        || modified.modifier() == EnterKeyModifier.CtrlShift)) {
            resolvePendingEnterAsNewline();
            edit(new PasteMessage("\n"));
        } else if (message instanceof MouseMessage mouse) {
            command = mouse(mouse);
        } else if (message instanceof KeyPressMessage key) {
            command = key(key);
        } else if (message instanceof PasteMessage paste) {
            resolvePendingEnterAsNewline();
            pasteContent(paste.content());
        } else {
            editor.update(message);
        }
        if (controller.mouseReportingEnabled() != mouseReportingEnabled) {
            mouseReportingEnabled = controller.mouseReportingEnabled();
            command = Command.batch(
                    mouseReportingEnabled ? Command.enableMouseAllMotion() : Command.disableMouse(), command);
        }
        if (controller.state().exitRequested()) {
            clearSelection();
            return UpdateResult.from(this, Command.quit());
        }
        return UpdateResult.from(this, command);
    }

    private Command mouse(MouseMessage mouse) {
        if (!mouseReportingEnabled) {
            return Command.none();
        }
        Tui4jTerminalView.TranscriptRegion region = view.transcriptRegion();
        if (mouse.isWheel()) {
            if (!region.contains(mouse.row(), mouse.column())) {
                return Command.none();
            }
            if (mouse.getButton() == MouseButton.MouseButtonWheelUp) {
                requestTranscriptScroll(-transcript.getMouseWheelDelta());
            } else if (mouse.getButton() == MouseButton.MouseButtonWheelDown) {
                requestTranscriptScroll(transcript.getMouseWheelDelta());
            }
            return Command.none();
        }
        if (controller.secureInputRequested()) {
            clearSelection();
            return Command.none();
        }
        if (mouse.getAction() == MouseAction.MouseActionPress && mouse.getButton() == MouseButton.MouseButtonLeft) {
            Optional<TerminalTextSelection.Point> point = transcriptPoint(mouse, false);
            if (point.isEmpty()) {
                clearSelection();
                return Command.none();
            }
            if (selection.selecting()) {
                // Some Windows terminal backends encode drag motion as another press event.
                // Keep the original anchor so the drag grows instead of restarting at the cursor.
                selection.extend(point.orElseThrow());
                return updateSelectionAutoScroll(mouse, region);
            }
            stopSelectionAutoScroll();
            selection.start(point.orElseThrow());
            return Command.none();
        }
        if (mouse.getAction() == MouseAction.MouseActionMotion && selection.selecting()) {
            transcriptPoint(mouse, true).ifPresent(selection::extend);
            return updateSelectionAutoScroll(mouse, region);
        }
        if (mouse.getAction() == MouseAction.MouseActionRelease && selection.selecting()) {
            transcriptPoint(mouse, true).ifPresent(selection::finish);
            stopSelectionAutoScroll();
            Optional<Integer> clickedRow =
                    selection.range().map(range -> range.start().row());
            if (selection.isClick()) {
                clearSelection();
                if (isTranscriptContentRow(mouse)) {
                    clickedRow.ifPresent(row -> view.toggleableItemAt(row).ifPresent(itemId -> {
                        controller.toggleTranscriptItem(itemId);
                        syncComponents();
                    }));
                }
                return Command.none();
            }
            return Command.none();
        }
        return Command.none();
    }

    private Command copySelectedText() {
        Optional<String> text = selection
                .range()
                .flatMap(range -> TerminalScreenCells.selectedText(
                        transcriptContent, range, TerminalScreenCells.DEFAULT_MAX_COPY_CHARACTERS));
        if (text.isEmpty()) {
            applyCopyFeedback("selection too large to copy");
            return Command.none();
        }
        clearSelection();
        syncComponents();
        return copySelection(text.orElseThrow());
    }

    private Command copySelection(String text) {
        return () -> new ClipboardCopyResultMessage(clipboard.copy(text), text.codePointCount(0, text.length()), text);
    }

    private Command completeCopy(ClipboardCopyResultMessage copied) {
        if (copied.success()) {
            applyCopyFeedback("copied " + copied.characters() + " chars");
            return Command.none();
        }
        String encoded =
                Base64.getEncoder().encodeToString(copied.text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        applyCopyFeedback("copied via terminal (OSC 52)");
        return Command.printf("\u001B]52;c;" + encoded + "\u0007");
    }

    private void applyCopyFeedback(String message) {
        pump.offer(new TerminalUiAction.StatusChanged(message));
        syncComponents();
    }

    private Optional<TerminalTextSelection.Point> transcriptPoint(MouseMessage mouse, boolean clampToRegion) {
        Tui4jTerminalView.TranscriptRegion region = view.transcriptRegion();
        if (region.height() < 1 || region.width() < 1) {
            return Optional.empty();
        }
        if (!clampToRegion && !region.contains(mouse.row(), mouse.column())) {
            return Optional.empty();
        }
        int lastRow = TerminalScreenCells.lineCount(transcriptContent) - 1;
        if (lastRow < 0) {
            return Optional.empty();
        }
        int visibleRow = region.relativeRow(region.clampRow(mouse.row()));
        int logicalRow = Math.max(0, Math.min(lastRow, transcript.getYOffset() + visibleRow));
        int column = region.clampColumn(mouse.column()) - region.leftColumn();
        return Optional.of(new TerminalTextSelection.Point(logicalRow, column));
    }

    private boolean isTranscriptContentRow(MouseMessage mouse) {
        Tui4jTerminalView.TranscriptRegion region = view.transcriptRegion();
        if (!region.contains(mouse.row(), mouse.column())) {
            return false;
        }
        int logicalRow = transcript.getYOffset() + region.relativeRow(mouse.row());
        return logicalRow >= 0 && logicalRow < TerminalScreenCells.lineCount(transcriptContent);
    }

    private Command updateSelectionAutoScroll(MouseMessage mouse, Tui4jTerminalView.TranscriptRegion region) {
        int direction = mouse.row() <= region.topRow() ? -1 : mouse.row() >= region.bottomRow() ? 1 : 0;
        selectionAutoScrollColumn = Math.max(0, region.clampColumn(mouse.column()) - region.leftColumn());
        if (direction == 0) {
            stopSelectionAutoScroll();
            return Command.none();
        }
        if (selectionAutoScrollDirection == direction) {
            return Command.none();
        }
        selectionAutoScrollDirection = direction;
        long sequence = ++selectionAutoScrollSequence;
        return selectionAutoScrollTick(sequence);
    }

    private Command continueSelectionAutoScroll(SelectionAutoScrollMessage scrolling) {
        if (scrolling.sequence() != selectionAutoScrollSequence
                || selectionAutoScrollDirection == 0
                || !selection.selecting()) {
            return Command.none();
        }
        if ((selectionAutoScrollDirection < 0 && transcript.atTop())
                || (selectionAutoScrollDirection > 0 && transcript.atBottom())) {
            stopSelectionAutoScroll();
            return Command.none();
        }
        requestTranscriptScroll(selectionAutoScrollDirection);
        Tui4jTerminalView.TranscriptRegion region = view.transcriptRegion();
        int nextOffset = Math.max(
                0, Math.min(transcript.getMaxYOffset(), transcript.getYOffset() + selectionAutoScrollDirection));
        int visibleRow = selectionAutoScrollDirection < 0 ? 0 : Math.max(0, region.height() - 1);
        int row = Math.min(TerminalScreenCells.lineCount(transcriptContent) - 1, nextOffset + visibleRow);
        selection.extend(new TerminalTextSelection.Point(Math.max(0, row), selectionAutoScrollColumn));
        return selectionAutoScrollTick(scrolling.sequence());
    }

    private Command selectionAutoScrollTick(long sequence) {
        return Command.tick(SELECTION_AUTO_SCROLL_INTERVAL, ignored -> new SelectionAutoScrollMessage(sequence));
    }

    private void stopSelectionAutoScroll() {
        selectionAutoScrollDirection = 0;
        selectionAutoScrollSequence++;
    }

    void clearSelection() {
        selection.clear();
        stopSelectionAutoScroll();
    }

    @Override
    public String view() {
        TerminalUiState state = controller.state();
        String desiredTranscript = selection
                .range()
                .map(range -> TerminalScreenCells.highlight(transcriptContent, range))
                .orElse(transcriptContent);
        if (!desiredTranscript.equals(displayedTranscriptContent)) {
            transcript.setContent(desiredTranscript);
            displayedTranscriptContent = desiredTranscript;
        }
        int requestedScrollRows = pendingTranscriptScrollRows;
        pendingTranscriptScrollRows = 0;
        Duration elapsed = activityElapsed(state);
        boolean secureInput = controller.secureInputRequested();
        boolean isUpdate = controller.secureInputIsUpdate();
        String secureInputHint = controller.secureInputHint();
        String rendered = view.render(
                state,
                transcript,
                editor,
                followTranscript,
                newOutputPending,
                elapsed,
                requestedScrollRows,
                secureInput,
                isUpdate,
                secureInputHint,
                mouseReportingEnabled);
        if (requestedScrollRows > 0 && transcript.atBottom()) {
            followTranscript = true;
            newOutputPending = false;
            rendered = view.render(
                    state, transcript, editor, true, false, elapsed, 0, secureInput, isUpdate, secureInputHint);
        }
        return rendered;
    }

    private Duration activityElapsed(TerminalUiState state) {
        if (state.currentRunId().isEmpty()) {
            timedRunId = null;
            timedActivityRevision = -1;
            return Duration.ZERO;
        }
        AgentRunId runId = state.currentRunId().orElseThrow();
        long now = monotonicNanos.getAsLong();
        if (!runId.equals(timedRunId)
                || timedActivityRevision != state.activity().revision()) {
            timedRunId = runId;
            timedActivityRevision = state.activity().revision();
            activityStartedNanos = now;
        }
        return Duration.ofNanos(Math.max(0, now - activityStartedNanos));
    }

    private Command key(KeyPressMessage key) {
        if (key.type() == KeyType.keyESC && selection.range().isPresent()) {
            clearSelection();
            return Command.none();
        }
        // A key that arrives inside the guard window after a line break proves the host is streaming one paste as
        // ordinary keys, so the burst is tracked until it goes idle and then collapsed into a paste summary.
        boolean continuesUnbracketedPaste = pendingEnter != null && isUnbracketedPasteContinuation(key);
        if (continuesUnbracketedPaste) {
            openUnbracketedPaste(pendingEnter.start());
            resolvePendingEnterAsNewline();
            // The resolved break came from a CR, so an LF that follows it is the other half of the same CRLF pair.
            burstTailEndsWithCr = true;
        }
        if (unbracketedPasteStart >= 0 && !isBurstContentKey(key)) {
            // Anything other than pasted content ends the burst, so its text reaches the composer before the key.
            flushBurstTail();
        }
        TerminalUiState state = controller.state();
        if (state.selector().isPresent()) {
            leaderActivatedNanos = 0;
            if ("completion".equals(state.selector().orElseThrow().kind()) && editCompletion(key)) {
                return Command.none();
            }
            return selectorKey(key);
        }

        if (shortcuts.matchesCommandPalette(key)) {
            controller.openCommandSelector();
            return Command.none();
        }
        if (shortcuts.matchesModelCycleNext(key)) {
            controller.cycleModel(1);
            return Command.none();
        }
        if (shortcuts.matchesModelCyclePrevious(key)) {
            controller.cycleModel(-1);
            return Command.none();
        }

        long now = monotonicNanos.getAsLong();
        boolean leaderPending = leaderActivatedNanos > 0 && (now - leaderActivatedNanos) <= LEADER_TIMEOUT_NANOS;
        if (leaderActivatedNanos > 0 && !leaderPending) {
            leaderActivatedNanos = 0;
        }

        if (shortcuts.matchesLeader(key)) {
            leaderActivatedNanos = now;
            return Command.none();
        }

        if (leaderPending) {
            leaderActivatedNanos = 0;
            if (key.type() == KeyType.keyESC || key.type() == KeyType.keyETX) {
                return Command.none();
            }
            String keyChar = key.key();
            if (keyChar != null && !keyChar.isEmpty()) {
                char ch = Character.toLowerCase(keyChar.charAt(0));
                switch (ch) {
                    case 'y' -> {
                        var response = view.copyableFinalResponse(state);
                        if (response.isEmpty()) {
                            applyCopyFeedback("No final response available");
                            return Command.none();
                        }
                        clearSelection();
                        return copySelection(response.orElseThrow());
                    }
                    case 'a' -> {
                        var row = view.finalResponseActivityRow(state);
                        if (row.isEmpty()) {
                            applyCopyFeedback("No tool activity available for this response");
                        } else {
                            followTranscript = false;
                            transcript.gotoTop();
                            transcript.scrollDown(row.orElseThrow());
                            newOutputPending = false;
                        }
                        return Command.none();
                    }
                    case 't' -> {
                        accept(TerminalInput.Kind.TOGGLE_TECHNICAL_DETAILS);
                        return Command.none();
                    }
                    case 'b' -> {
                        pump.offer(new TerminalUiAction.SidebarToggled());
                        return Command.none();
                    }
                    case 's' -> {
                        controller.accept(new TerminalInput(TerminalInput.Kind.EXECUTE_COMMAND, "/session"));
                        return Command.none();
                    }
                    case 'e' -> {
                        pump.offer(new TerminalUiAction.StatusChanged("External editor deferred in this version"));
                        return Command.none();
                    }
                    case 'm' -> {
                        executeCommand("/model");
                        return Command.none();
                    }
                    case 'n' -> {
                        executeCommand("/new");
                        return Command.none();
                    }
                    case 'l' -> {
                        executeCommand("/resume");
                        return Command.none();
                    }
                    case 'c' -> {
                        executeCommand("/compact");
                        return Command.none();
                    }
                    case 'r' -> {
                        executeCommand("/rename");
                        return Command.none();
                    }
                    case 'q' -> {
                        executeCommand("/quit");
                        return Command.none();
                    }
                    default -> {
                        return Command.none();
                    }
                }
            }
        }

        if (shortcuts.matchesToggleExpansion(key)) {
            accept(TerminalInput.Kind.TOGGLE_EXPANSION);
            return Command.none();
        }
        if (shortcuts.matchesRestoreQueuedMessage(key)) {
            accept(TerminalInput.Kind.RESTORE);
            return Command.none();
        }
        if (isPasteShortcut(key)) {
            // Intercept before Textarea's ctrl+v binding returns a dead ReadClipboardMessage.
            return pasteFromClipboard();
        }
        if (key.type() == KeyType.keyESC) {
            accept(TerminalInput.Kind.CANCEL_OR_CLOSE);
            return Command.none();
        }
        if (key.type() == KeyType.keyETX) {
            if (selection.range().isPresent()) {
                return copySelectedText();
            }
            accept(TerminalInput.Kind.INTERRUPT);
            return Command.none();
        }
        if (key.type() == KeyType.keyEOT) {
            accept(TerminalInput.Kind.EOF);
            return Command.none();
        }
        if (key.type() == KeyType.keyHT) {
            if (unbracketedPasteStart >= 0) {
                burstTail.append("    ");
                burstTailEndsWithCr = false;
                return scheduleUnbracketedPasteEnd();
            }
            accept(TerminalInput.Kind.COMPLETION_REQUESTED);
            return Command.none();
        }
        if (key.type() == KeyType.keyCR) {
            if (unbracketedPasteStart >= 0) {
                burstTail.append('\n');
                burstTailEndsWithCr = true;
                return scheduleUnbracketedPasteEnd();
            }
            if (shortcuts.matchesFollowUp(key)) {
                return submit(TerminalInput.Kind.FOLLOW_UP);
            }
            return deferEnter(TerminalInput.Kind.SUBMIT);
        }
        if (key.type() == KeyType.keyLF) {
            if (unbracketedPasteStart >= 0 && burstTailEndsWithCr) {
                // A CRLF pair inside one burst is a single break, and the CR half already buffered it.
                burstTailEndsWithCr = false;
                return scheduleUnbracketedPasteEnd();
            }
            edit(new PasteMessage("\n"));
            return Command.none();
        }
        if (key.type() == KeyType.KeyUp) {
            if (state.editorBuffer().contains("\n")) {
                moveCursor(TerminalTextCursor.vertical(state.editorBuffer(), state.editorCursor(), -1));
            } else {
                navigateHistory(-1);
            }
            return Command.none();
        }
        if (key.type() == KeyType.KeyDown) {
            if (state.editorBuffer().contains("\n")) {
                moveCursor(TerminalTextCursor.vertical(state.editorBuffer(), state.editorCursor(), 1));
            } else {
                navigateHistory(1);
            }
            return Command.none();
        }
        if (key.type() == KeyType.KeyPgUp) {
            requestTranscriptScroll(-Math.max(1, transcript.getHeight() - 1));
            return Command.none();
        }
        if (key.type() == KeyType.KeyPgDown) {
            requestTranscriptScroll(Math.max(1, transcript.getHeight() - 1));
            return Command.none();
        }
        if (key.type() == KeyType.KeyLeft) {
            String token = pastedTokenBefore(state.editorBuffer(), state.editorCursor());
            if (token != null) {
                moveCursor(state.editorBuffer().indexOf(token));
            } else {
                moveCursor(TerminalTextCursor.previous(state.editorBuffer(), state.editorCursor()));
            }
            return Command.none();
        }
        if (key.type() == KeyType.KeyRight) {
            String token = pastedTokenAfter(state.editorBuffer(), state.editorCursor());
            if (token != null) {
                moveCursor(state.editorBuffer().indexOf(token) + token.length());
            } else {
                moveCursor(TerminalTextCursor.next(state.editorBuffer(), state.editorCursor()));
            }
            return Command.none();
        }
        if (key.type() == KeyType.KeyHome) {
            moveCursor(TerminalTextCursor.lineStart(state.editorBuffer(), state.editorCursor()));
            return Command.none();
        }
        if (key.type() == KeyType.KeyEnd) {
            moveCursor(TerminalTextCursor.lineEnd(state.editorBuffer(), state.editorCursor()));
            return Command.none();
        }
        if (isBackspaceKey(key)) {
            String buffer = state.editorBuffer();
            int cursor = state.editorCursor();
            String token = pastedTokenBefore(buffer, cursor);
            if (token != null) {
                replaceEditor(withoutPasteToken(buffer, token), buffer.indexOf(token));
                return Command.none();
            }
            int previous = TerminalTextCursor.previous(buffer, cursor);
            replaceEditor(TerminalTextCursor.backspace(buffer, cursor), previous);
            return Command.none();
        }
        if (key.type() == KeyType.KeyDelete) {
            String buffer = state.editorBuffer();
            int cursor = state.editorCursor();
            String token = pastedTokenAfter(buffer, cursor);
            if (token != null) {
                replaceEditor(withoutPasteToken(buffer, token), buffer.indexOf(token));
                return Command.none();
            }
            replaceEditor(TerminalTextCursor.delete(buffer, cursor), cursor);
            return Command.none();
        }
        if (shortcuts.matchesEditWaiting(key)
                && state.editorBuffer().isBlank()
                && state.waiting()
                        .filter(value -> value.phase()
                                == io.haifa.agent.application.coding.terminal.state.WaitingMessage.Phase.WAITING)
                        .isPresent()) {
            accept(TerminalInput.Kind.EDIT_WAITING);
            return Command.none();
        }
        if (continuesUnbracketedPaste || unbracketedPasteStart >= 0) {
            burstTail.append(key.runes());
            burstTailEndsWithCr = false;
            return scheduleUnbracketedPasteEnd();
        }
        return edit(key);
    }

    private Command secretKey(KeyPressMessage key) {
        if (key.type() == KeyType.keyCR) {
            char[] secret = new char[secretBuffer.length()];
            secretBuffer.getChars(0, secretBuffer.length(), secret, 0);
            clearSecretBuffer();
            controller.submitApiKey(secret);
            syncComponents();
            return Command.none();
        }
        if (key.type() == KeyType.keyESC || key.type() == KeyType.keyETX || key.type() == KeyType.keyEOT) {
            clearSecretBuffer();
            controller.cancelSecureInput();
            syncComponents();
            return Command.none();
        }
        if (key.type() == KeyType.keyBS || key.type() == KeyType.keyDEL || key.type() == KeyType.KeyDelete) {
            if (!secretBuffer.isEmpty()) {
                int previous = secretBuffer.offsetByCodePoints(secretBuffer.length(), -1);
                for (int index = previous; index < secretBuffer.length(); index++) {
                    secretBuffer.setCharAt(index, '\0');
                }
                secretBuffer.setLength(previous);
            }
            syncComponents();
            return Command.none();
        }
        if (key.type() == KeyType.KeyRunes) {
            appendSecret(key.runes());
            syncComponents();
        }
        return Command.none();
    }

    private void appendSecret(CharSequence value) {
        for (int index = 0; index < value.length() && secretBuffer.length() < MAX_SECRET_CHARACTERS; index++) {
            appendSecretCharacter(value.charAt(index));
        }
    }

    private void appendSecret(char[] value) {
        for (char character : value) {
            if (secretBuffer.length() >= MAX_SECRET_CHARACTERS) {
                break;
            }
            appendSecretCharacter(character);
        }
    }

    private void appendSecretCharacter(char character) {
        if (!Character.isISOControl(character) && (!Character.isWhitespace(character) || character == ' ')) {
            secretBuffer.append(character);
        }
    }

    private void clearSecretBuffer() {
        for (int index = 0; index < secretBuffer.length(); index++) {
            secretBuffer.setCharAt(index, '\0');
        }
        secretBuffer.setLength(0);
    }

    private boolean isUnbracketedPasteContinuation(KeyPressMessage key) {
        return key.type() == KeyType.KeyRunes
                || key.type() == KeyType.keyCR
                || key.type() == KeyType.keyLF
                || key.type() == KeyType.keyHT;
    }

    /** Keeps the burst anchored at the first line of the paste, which is the rune chunk before its first break. */
    private void openUnbracketedPaste(int start) {
        if (unbracketedPasteStart < 0) {
            unbracketedPasteStart = Math.max(0, start);
        }
    }

    private Command scheduleUnbracketedPasteEnd() {
        long sequence = ++unbracketedPasteSequence;
        return Command.tick(UNBRACKETED_PASTE_GUARD_INTERVAL, ignored -> new UnbracketedPasteEndMessage(sequence));
    }

    private void endUnbracketedPaste(long sequence) {
        if (sequence == unbracketedPasteSequence) {
            finishUnbracketedPaste();
        }
    }

    /**
     * Collapses the open burst into one paste summary when it is large enough, so a host that streams a paste as
     * ordinary keys produces the same draft as a host that brackets it. The region is anchored at the first pasted
     * line, so a small burst simply stays editable text.
     */
    private void finishUnbracketedPaste() {
        int start = unbracketedPasteStart;
        unbracketedPasteStart = -1;
        String buffer = controller.state().editorBuffer();
        if (start < 0 || start > buffer.length()) {
            flushBurstTail();
            return;
        }
        String region = buffer.substring(start) + burstTail;
        String trimmed = region.strip();
        if (!needsPasteSummary(trimmed)) {
            flushBurstTail();
            return;
        }
        String token = nextPasteToken(lineCount(trimmed));
        pastedContent.put(token, region);
        burstTail.setLength(0);
        burstTailEndsWithCr = false;
        replaceEditor(buffer.substring(0, start) + token, start + token.length());
    }

    /** Ends an open burst by appending its buffered text, so held-back keys are never dropped. */
    private void flushBurstTail() {
        String tail = burstTail.toString();
        burstTail.setLength(0);
        burstTailEndsWithCr = false;
        unbracketedPasteStart = -1;
        if (!tail.isEmpty()) {
            edit(new PasteMessage(tail));
        }
    }

    private static boolean isBurstContentKey(KeyPressMessage key) {
        return key.type() == KeyType.KeyRunes
                || key.type() == KeyType.keyCR
                || key.type() == KeyType.keyLF
                || key.type() == KeyType.keyHT;
    }

    private Command deferEnter(TerminalInput.Kind kind) {
        int start = lastRuneInsertStart >= 0 ? lastRuneInsertStart : editorCursor;
        lastRuneInsertStart = -1;
        long sequence = ++deferredEnterSequence;
        pendingEnter = new DeferredEnter(sequence, kind, start);
        return Command.tick(UNBRACKETED_PASTE_GUARD_INTERVAL, ignored -> new DeferredEnterMessage(sequence, kind));
    }

    /** The shared paste-summary thresholds: 3+ lines or more than 150 characters, matching OpenCode defaults. */
    private static boolean needsPasteSummary(String trimmed) {
        return !trimmed.isEmpty()
                && (lineCount(trimmed) >= PASTE_SUMMARY_MIN_LINES || trimmed.length() > PASTE_SUMMARY_MAX_CHARACTERS);
    }

    private static int lineCount(String value) {
        int lines = 1;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /** The OpenCode-style label for one summarized paste; the sequence keeps repeated pastes distinct. */
    private String nextPasteToken(int lines) {
        return "[Pasted #" + (++pasteSequence) + " ~" + lines + " lines]";
    }

    private Command commitDeferredEnter(DeferredEnterMessage deferred) {
        if (pendingEnter == null
                || pendingEnter.sequence() != deferred.sequence()
                || pendingEnter.kind() != deferred.kind()) {
            return Command.none();
        }
        pendingEnter = null;
        return submit(deferred.kind());
    }

    private void resolvePendingEnterAsNewline() {
        if (pendingEnter == null) {
            return;
        }
        pendingEnter = null;
        edit(new PasteMessage("\n"));
    }

    private void requestTranscriptScroll(int rows) {
        if (rows == 0) return;
        if (rows < 0) {
            followTranscript = false;
        }
        long requested = (long) pendingTranscriptScrollRows + rows;
        pendingTranscriptScrollRows = (int) Math.max(-1_000_000L, Math.min(1_000_000L, requested));
    }

    private boolean editCompletion(KeyPressMessage key) {
        TerminalUiState state = controller.state();
        String buffer = state.editorBuffer();
        int cursor = state.editorCursor();
        String updated;
        int updatedCursor;
        if (key.type() == KeyType.KeyRunes) {
            String inserted = sanitizeEditorInput(new String(key.runes()));
            if (inserted.isEmpty()) {
                return true;
            }
            updated = buffer.substring(0, cursor) + inserted + buffer.substring(cursor);
            updatedCursor = cursor + inserted.length();
        } else if (key.type() == KeyType.KeySpace) {
            // tui4j reports Space as KeySpace, not KeyRunes. Without this branch the completion
            // selector swallows the space, so "/rename <arg>" would become "/rename<arg>".
            updated = buffer.substring(0, cursor) + " " + buffer.substring(cursor);
            updatedCursor = cursor + 1;
        } else if (key.type() == KeyType.keyBS || key.type() == KeyType.keyDEL) {
            updatedCursor = TerminalTextCursor.previous(buffer, cursor);
            updated = TerminalTextCursor.backspace(buffer, cursor);
        } else if (key.type() == KeyType.KeyDelete) {
            updated = TerminalTextCursor.delete(buffer, cursor);
            updatedCursor = cursor;
        } else if (key.type() == KeyType.KeyLeft) {
            updated = buffer;
            updatedCursor = TerminalTextCursor.previous(buffer, cursor);
        } else if (key.type() == KeyType.KeyRight) {
            updated = buffer;
            updatedCursor = TerminalTextCursor.next(buffer, cursor);
        } else {
            return false;
        }
        if (lostPastedToken(buffer, updated)) {
            rejectProtectedPaste(buffer, cursor);
            return true;
        }
        controller.accept(new TerminalInput(TerminalInput.Kind.EDITOR_CHANGED, updated, updatedCursor));
        controller.accept(new TerminalInput(TerminalInput.Kind.COMPLETION_REQUESTED, updated, updatedCursor));
        syncComponents();
        return true;
    }

    private Command selectorKey(KeyPressMessage key) {
        boolean providerSearch = controller
                .state()
                .selector()
                .map(selector -> "auth-provider".equals(selector.kind()))
                .orElse(false);
        if (providerSearch) {
            if (key.type() == KeyType.KeyRunes) {
                char[] runes = key.runes();
                if (runes != null) {
                    controller.accept(new TerminalInput(TerminalInput.Kind.SELECTOR_FILTER, new String(runes)));
                    syncComponents();
                    return Command.none();
                }
            }
            if (key.type() == KeyType.keyBS || key.type() == KeyType.keyDEL) {
                accept(TerminalInput.Kind.SELECTOR_BACKSPACE);
                return Command.none();
            }
        }
        boolean authorizationWait = controller
                .state()
                .selector()
                .map(selector -> "auth-wait".equals(selector.kind()))
                .orElse(false);
        if (authorizationWait) {
            if (key.type() == KeyType.keyESC || key.type() == KeyType.keyETX) {
                controller.cancelAuthentication();
                return Command.none();
            }
            if (key.type() == KeyType.KeyRunes) {
                char[] runes = key.runes();
                if (runes != null && runes.length == 1) {
                    if (runes[0] == 'c' || runes[0] == 'C') {
                        return copyAuthorizationLink();
                    }
                    if (runes[0] == 'o' || runes[0] == 'O') {
                        controller.reopenAuthorization();
                        return Command.none();
                    }
                }
            }
            if (key.type() == KeyType.keyCR) {
                int selected = controller.state().selector().orElseThrow().selected();
                if (selected == 0) {
                    return copyAuthorizationLink();
                }
                if (selected == 1) {
                    controller.reopenAuthorization();
                    return Command.none();
                }
                controller.cancelAuthentication();
                return Command.none();
            }
        }
        boolean deviceWait = controller
                .state()
                .selector()
                .map(selector -> "auth-device".equals(selector.kind()))
                .orElse(false);
        if (deviceWait) {
            if (key.type() == KeyType.keyESC || key.type() == KeyType.keyETX) {
                controller.cancelAuthentication();
                return Command.none();
            }
            if (key.type() == KeyType.KeyRunes) {
                char[] runes = key.runes();
                if (runes != null && runes.length == 1) {
                    if (runes[0] == 'c' || runes[0] == 'C') {
                        return copyDeviceCode();
                    }
                    if (runes[0] == 'l' || runes[0] == 'L') {
                        return copyVerificationLink();
                    }
                    if (runes[0] == 'o' || runes[0] == 'O') {
                        controller.openVerificationPage();
                        return Command.none();
                    }
                }
            }
            if (key.type() == KeyType.keyCR) {
                int selected = controller.state().selector().orElseThrow().selected();
                if (selected == 0) {
                    return copyDeviceCode();
                }
                if (selected == 1) {
                    return copyVerificationLink();
                }
                controller.cancelAuthentication();
                return Command.none();
            }
        }
        if (shortcuts.matchesToggleExpansion(key)) {
            accept(TerminalInput.Kind.TOGGLE_EXPANSION);
            return Command.none();
        }
        if (key.type() == KeyType.KeyUp) {
            accept(TerminalInput.Kind.SELECT_PREVIOUS);
        } else if (key.type() == KeyType.KeyDown) {
            accept(TerminalInput.Kind.SELECT_NEXT);
        } else if (key.type() == KeyType.KeyLeft) {
            accept(TerminalInput.Kind.NAVIGATE_BACK);
        } else if (key.type() == KeyType.keyCR) {
            accept(TerminalInput.Kind.SUBMIT);
        } else if (key.type() == KeyType.KeySpace && clarificationSelectorOpen()) {
            accept(TerminalInput.Kind.TOGGLE_SELECTION);
        } else if (key.type() == KeyType.keyESC) {
            accept(TerminalInput.Kind.CANCEL_OR_CLOSE);
        } else if (key.type() == KeyType.keyETX) {
            accept(TerminalInput.Kind.INTERRUPT);
        }
        return Command.none();
    }

    private boolean clarificationSelectorOpen() {
        return controller
                .state()
                .selector()
                .map(selector -> selector.kind().startsWith("clarification:"))
                .orElse(false);
    }

    private Command copyAuthorizationLink() {
        return copyText(
                controller.authorizationUri(),
                "Authorization link has expired; reconnect",
                "Authorization link copied");
    }

    private Command copyDeviceCode() {
        return copyText(controller.deviceCode(), "Device code has expired; reconnect", "Device code copied");
    }

    private Command copyVerificationLink() {
        return copyText(controller.verificationUri(), "Browser link has expired; reconnect", "Browser link copied");
    }

    private Command copyText(Optional<String> value, String missingMessage, String successMessage) {
        if (value.isEmpty()) {
            applyCopyFeedback(missingMessage);
            return Command.none();
        }
        if (clipboard.copy(value.orElseThrow())) {
            applyCopyFeedback(successMessage);
            return Command.none();
        }
        applyCopyFeedback("Copied via terminal: " + successMessage);
        String encoded = Base64.getEncoder()
                .encodeToString(value.orElseThrow().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Command.printf("\u001B]52;c;" + encoded + "\u0007");
    }

    private Command edit(Message message) {
        resetHistoryNavigation();
        String before = editor.value();
        int beforeCursor = editorCursor;
        lastRuneInsertStart =
                message instanceof KeyPressMessage pressed && pressed.type() == KeyType.KeyRunes ? beforeCursor : -1;
        UpdateResult<Textarea> result = editor.update(message);
        String raw = editor.value();
        String after = sanitizeEditorInput(raw);
        if (!after.equals(raw)) {
            // A host may deliver a keystroke it cannot decode as a raw control rune. The textarea is the render source,
            // so the draft is corrected here instead of only on the paste boundary; an unsanitized rune would reach
            // EDITOR_CHANGED and paint as an unmapped glyph inside the composer.
            synchronizeEditor(after, Math.min(beforeCursor, after.length()));
        }
        int afterCursor = cursorAfter(before, beforeCursor, after);
        if (lostPastedToken(before, after)) {
            if (message instanceof KeyPressMessage key && isBackspaceKey(key)) {
                for (String token : List.copyOf(pastedContent.keySet())) {
                    if (before.contains(token) && !after.contains(token)) {
                        replaceEditor(withoutPasteToken(before, token), before.indexOf(token));
                        return Command.none();
                    }
                }
            }
            rejectProtectedPaste(before, beforeCursor);
            return Command.none();
        }
        editorCursor = afterCursor;
        controller.accept(new TerminalInput(TerminalInput.Kind.EDITOR_CHANGED, after, editorCursor));
        if (startsCompletion(message, before, beforeCursor, after, editorCursor)) {
            controller.accept(new TerminalInput(TerminalInput.Kind.COMPLETION_REQUESTED, after, editorCursor));
        }
        syncComponents();
        return result.command();
    }

    private boolean startsCompletion(Message message, String before, int beforeCursor, String after, int afterCursor) {
        if (!(message instanceof KeyPressMessage key) || key.type() != KeyType.KeyRunes) {
            return false;
        }
        String inserted = new String(key.runes());
        if (!(inserted.equals("/") || inserted.equals("@"))) {
            return false;
        }
        if (after.length() != before.length() + 1 || afterCursor != beforeCursor + 1) {
            return false;
        }
        return beforeCursor == 0 || Character.isWhitespace(before.charAt(beforeCursor - 1));
    }

    private void accept(TerminalInput.Kind kind) {
        TerminalUiState state = controller.state();
        controller.accept(new TerminalInput(kind, state.editorBuffer(), state.editorCursor()));
        if (!state.editorBuffer().equals(controller.state().editorBuffer())) {
            resetHistoryNavigation();
        }
        pruneStalePastes(controller.state().editorBuffer());
        syncComponents();
    }

    private void executeCommand(String command) {
        controller.accept(new TerminalInput(TerminalInput.Kind.EXECUTE_COMMAND, command));
        syncComponents();
        pruneStalePastes(controller.state().editorBuffer());
    }

    private Command submit(TerminalInput.Kind kind) {
        if (pendingSubmission != null) {
            return Command.none();
        }
        flushBurstTail();
        TerminalUiState state = controller.state();
        String expanded = expandPastedContent(state.editorBuffer());
        if (!expanded.isBlank() && !controller.isRenamingSession()) {
            resumeTranscriptFollowing();
            history.add(expanded);
        }
        resetHistoryNavigation();
        TerminalInput input =
                new TerminalInput(kind, expanded, TerminalTextCursor.clamp(expanded, state.editorCursor()));
        String routingText = state.editorBuffer();
        var prepared = controller.prepareMessageSubmission(input, routingText);
        if (prepared.isEmpty()) {
            controller.accept(input, routingText);
            syncComponents();
            pruneStalePastes(controller.state().editorBuffer());
            return Command.none();
        }
        pendingSubmission = prepared.orElseThrow();
        syncComponents();
        pruneStalePastes(controller.state().editorBuffer());
        PreparedMessageSubmission submission = pendingSubmission;
        return () -> new SubmissionCompletedMessage(controller.executeMessageSubmission(submission));
    }

    private void resumeTranscriptFollowing() {
        followTranscript = true;
        newOutputPending = false;
        pendingTranscriptScrollRows = 0;
        transcript.gotoBottom();
    }

    private void navigateHistory(int direction) {
        if (history.isEmpty()) {
            return;
        }
        TerminalUiState state = controller.state();
        if (!browsingHistory) {
            historyDraft = state.editorBuffer();
            historyIndex = history.size();
            browsingHistory = true;
        }
        if (direction < 0 && historyIndex > 0) {
            historyIndex--;
            replaceEditor(history.get(historyIndex));
        } else if (direction > 0 && historyIndex < history.size() - 1) {
            historyIndex++;
            replaceEditor(history.get(historyIndex));
        } else if (direction > 0 && historyIndex == history.size() - 1) {
            String draft = historyDraft;
            resetHistoryNavigation();
            replaceEditor(draft);
        }
    }

    private void replaceEditor(String value) {
        replaceEditor(value, value.length());
    }

    private void replaceEditor(String value, int cursor) {
        controller.accept(
                new TerminalInput(TerminalInput.Kind.EDITOR_CHANGED, value, TerminalTextCursor.clamp(value, cursor)));
        syncComponents();
    }

    private void moveCursor(int cursor) {
        TerminalUiState state = controller.state();
        controller.accept(new TerminalInput(
                TerminalInput.Kind.EDITOR_CHANGED,
                state.editorBuffer(),
                TerminalTextCursor.clamp(state.editorBuffer(), cursor)));
        syncComponents();
    }

    private Command pasteFromClipboard() {
        return () -> new ClipboardPasteResultMessage(clipboard.read().orElse(""));
    }

    private Command applyClipboardPaste(String content) {
        if (content == null || content.isEmpty()) {
            return Command.none();
        }
        if (controller.secureInputRequested()) {
            appendSecret(content);
            syncComponents();
            return Command.none();
        }
        resolvePendingEnterAsNewline();
        pasteContent(content);
        return Command.none();
    }

    private static boolean isPasteShortcut(KeyPressMessage key) {
        return key.type() == KeyType.keySYN;
    }

    private void pasteContent(String content) {
        String normalized = sanitizeEditorInput(content);
        flushBurstTail();
        pruneStalePastes(controller.state().editorBuffer());
        edit(new PasteMessage(summarizedPaste(normalized)));
    }

    /** The label that replaces one pasted block, or the block itself when it is small enough to stay inline. */
    private String summarizedPaste(String normalized) {
        String trimmed = normalized.strip();
        if (!needsPasteSummary(trimmed)) {
            return normalized;
        }
        String token = nextPasteToken(lineCount(trimmed));
        pastedContent.put(token, normalized);
        return token;
    }

    private String expandPastedContent(String buffer) {
        StringBuilder expanded = new StringBuilder(buffer.length());
        int index = 0;
        while (index < buffer.length()) {
            String token = tokenStartingAt(buffer, index);
            if (token != null) {
                expanded.append(pastedContent.get(token));
                index += token.length();
            } else {
                expanded.append(buffer.charAt(index));
                index++;
            }
        }
        return expanded.toString();
    }

    private String tokenStartingAt(String buffer, int index) {
        for (String token : pastedContent.keySet()) {
            if (buffer.startsWith(token, index)) {
                return token;
            }
        }
        return null;
    }

    private void pruneStalePastes(String buffer) {
        Optional<String> preserved = controller.preservedDraft();
        pastedContent
                .keySet()
                .removeIf(token -> !buffer.contains(token)
                        && preserved.map(p -> !p.contains(token)).orElse(true));
    }

    /**
     * Recognizes every encoding hosts use for Backspace: the dedicated key types, the key name, and a rune burst that
     * carries delete runes. A host may decode one keystroke into several runes or pad it with other control runes, and
     * an unrecognized burst would reach the textarea as text and paint as an unmapped glyph inside the composer.
     */
    private static boolean isBackspaceKey(KeyPressMessage key) {
        if (key.type() == KeyType.keyBS || key.type() == KeyType.keyDEL) {
            return true;
        }
        if ("backspace".equalsIgnoreCase(key.key())) {
            return true;
        }
        if (key.type() != KeyType.KeyRunes) {
            return false;
        }
        char[] runes = key.runes();
        if (runes == null || runes.length == 0) {
            return false;
        }
        boolean deletes = false;
        for (char rune : runes) {
            if (rune == '\b' || rune == 0x7f) {
                deletes = true;
            } else if (!Character.isISOControl(rune)) {
                return false;
            }
        }
        return deletes;
    }

    /** Drops one summarized paste token, and the space separating it from the text, from the draft. */
    private String withoutPasteToken(String buffer, String token) {
        pastedContent.remove(token);
        int start = buffer.indexOf(token);
        int end = start + token.length();
        if (end < buffer.length() && buffer.charAt(end) == ' ') {
            end++;
        }
        return buffer.substring(0, start) + buffer.substring(end);
    }

    private String pastedTokenBefore(String buffer, int cursor) {
        for (String token : pastedContent.keySet()) {
            int start = buffer.indexOf(token);
            if (start >= 0) {
                int end = start + token.length();
                if ((start < cursor && cursor <= end)
                        || (cursor == end + 1 && end < buffer.length() && buffer.charAt(end) == ' ')) {
                    return token;
                }
            }
        }
        return null;
    }

    private String pastedTokenAfter(String buffer, int cursor) {
        for (String token : pastedContent.keySet()) {
            int start = buffer.indexOf(token);
            if (start >= 0) {
                int end = start + token.length();
                if (start <= cursor && cursor < end) {
                    return token;
                }
            }
        }
        return null;
    }

    private boolean lostPastedToken(String before, String after) {
        for (String token : pastedContent.keySet()) {
            if (before.contains(token) && !after.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private void rejectProtectedPaste(String before, int cursor) {
        editorCursor = cursor;
        controller.accept(new TerminalInput(TerminalInput.Kind.EDITOR_CHANGED, before, cursor));
        pump.offer(new TerminalUiAction.RecoverableFailure("PASTE_PLACEHOLDER_ATOMIC"));
        controller.drainEvents();
        syncComponents();
    }

    private void resetHistoryNavigation() {
        browsingHistory = false;
        historyDraft = "";
        historyIndex = history.size();
    }

    private void syncComponents() {
        TerminalUiState state = controller.state();
        editor.setWidth(Tui4jTerminalView.editorColumns(state));
        boolean secureInput = controller.secureInputRequested();
        if (secureInput) {
            boolean masked = controller.secureInputIsMasked();
            String display = masked
                    ? "•".repeat(secretBuffer.codePointCount(0, secretBuffer.length()))
                    : secretBuffer.toString();
            if (!editor.value().equals(display) || editorCursor != display.length()) {
                synchronizeEditor(display, display.length());
            }
            editor.setHeight(1);
            editor.setMaxHeight(1);
            editor.setPrompt(controller.secureInputPrompt());
            editor.setPlaceholder(controller.secureInputPlaceholder());
            secretPresentation = true;
        } else {
            if (secretPresentation) {
                clearSecretBuffer();
                editor.setHeight(3);
                editor.setMaxHeight(3);
                editor.setPlaceholder("Type a message, /command, @file, !command, or !!command");
                secretPresentation = false;
            }
            boolean isShell =
                    state.editorBuffer().startsWith("!") || state.editorBuffer().startsWith("!!");
            editor.setPrompt(isShell ? "! " : "┃ ");
            if (!editor.value().equals(state.editorBuffer())) {
                synchronizeEditor(state.editorBuffer(), state.editorCursor());
            } else if (editorCursor != state.editorCursor()) {
                synchronizeEditor(state.editorBuffer(), state.editorCursor());
            }
        }
        if (state.selector().isPresent()) {
            editor.blur();
        } else {
            editor.focus();
        }

        boolean themeSwitched = false;
        if (!state.themeName().equals(currentThemeName)) {
            currentThemeName = state.themeName();
            view.setTheme(currentThemeName);
            renderedTranscript = List.of();
            themeSwitched = true;
        }

        int effectiveTranscriptColumns =
                (state.sidebarVisible() && state.columns() >= 100) ? state.columns() - 42 : state.columns();
        boolean columnsChanged = renderedTranscriptColumns != effectiveTranscriptColumns;
        boolean transcriptChanged = renderedTranscript != state.transcript() || columnsChanged;
        if (transcriptChanged) {
            if (columnsChanged) {
                clearSelection();
            }
            String nextTranscript = view.transcriptContent(state);
            if (!nextTranscript.equals(transcriptContent)) {
                transcript.setContent(nextTranscript);
                transcriptContent = nextTranscript;
                if (followTranscript) {
                    transcript.gotoBottom();
                    newOutputPending = false;
                } else if (!themeSwitched) {
                    newOutputPending = true;
                }
            }
            renderedTranscript = state.transcript();
            renderedTranscriptColumns = effectiveTranscriptColumns;
        }
    }

    private void synchronizeEditor(String value, int cursor) {
        int checkedCursor = TerminalTextCursor.clamp(value, cursor);
        boolean wasFocused = editor.focused();
        editor.focus();
        editor.setValue(value);
        int leftMoves = value.codePointCount(checkedCursor, value.length());
        var left = new KeyPressMessage(new Key(KeyType.KeyLeft));
        for (int index = 0; index < leftMoves; index++) {
            editor.update(left);
        }
        if (!wasFocused) {
            editor.blur();
        }
        editorCursor = checkedCursor;
    }

    /** The cursor after {@code editor.update} replaced {@code before} with {@code after}. */
    private static int cursorAfter(String before, int beforeCursor, String after) {
        return TerminalTextCursor.clamp(after, beforeCursor + (after.length() - before.length()));
    }

    private Command nextTick() {
        return Command.tick(EVENT_POLL_INTERVAL, ignored -> new PollMessage());
    }

    /**
     * Drops a window-size report that matches the current state so a periodic or signal-driven
     * resize with no change does not make the tui4j renderer clear its line cache and repaint.
     */
    Message filterMessage(Message message) {
        if (message instanceof KeyPressMessage key
                && key.type() == KeyType.keyETX
                && !controller.secureInputRequested()) {
            // Ctrl-C is handled through the terminal SIGINT handler below so the
            // signal-driven QuitMessage cannot race the copy and exit the process.
            return null;
        }
        if (message instanceof QuitMessage) {
            if (controller.secureInputRequested()) {
                return null;
            }
            if (!controller.state().exitRequested()) {
                return new CtrlCRequestedMessage();
            }
        }
        if (message instanceof MouseMessage mouse
                && mouse.getAction() == MouseAction.MouseActionMotion
                && !selection.selecting()) {
            return null;
        }
        if (message instanceof WindowSizeMessage size
                && size.width() == controller.state().columns()
                && size.height() == controller.state().rows()) {
            return null;
        }
        return message;
    }

    private String sanitizeEditorInput(String value) {
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder safe = new StringBuilder(normalized.length());
        normalized.codePoints().forEach(codePoint -> {
            if (codePoint == '\n') {
                safe.append('\n');
            } else if (codePoint == '\t') {
                safe.append("    ");
            } else if (!Character.isISOControl(codePoint)) {
                safe.appendCodePoint(codePoint);
            }
        });
        return safe.toString();
    }

    record PollMessage() implements Message {}

    private record CtrlCRequestedMessage() implements Message {}

    record SelectionAutoScrollMessage(long sequence) implements Message {}

    record ClipboardCopyResultMessage(boolean success, int characters, String text) implements Message {}

    record ClipboardPasteResultMessage(String content) implements Message {}

    private record DeferredEnter(long sequence, TerminalInput.Kind kind, int start) {}

    private record DeferredEnterMessage(long sequence, TerminalInput.Kind kind) implements Message {}

    private record UnbracketedPasteEndMessage(long sequence) implements Message {}

    private record SubmissionCompletedMessage(MessageSubmissionResult result) implements Message {}
}
