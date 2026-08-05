package io.haifa.agent.application.coding.terminal.tui4j;

import com.williamcallahan.tui4j.compat.bubbles.textarea.Textarea;
import com.williamcallahan.tui4j.compat.bubbles.viewport.Viewport;
import com.williamcallahan.tui4j.compat.bubbletea.Command;
import com.williamcallahan.tui4j.compat.bubbletea.KeyPressMessage;
import com.williamcallahan.tui4j.compat.bubbletea.Message;
import com.williamcallahan.tui4j.compat.bubbletea.Model;
import com.williamcallahan.tui4j.compat.bubbletea.PasteMessage;
import com.williamcallahan.tui4j.compat.bubbletea.UpdateResult;
import com.williamcallahan.tui4j.compat.bubbletea.WindowSizeMessage;
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
import io.haifa.agent.core.run.AgentRunId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** Production tui4j adapter around the authoritative terminal Controller and Reducer state. */
final class Tui4jCodingTerminalModel implements Model {
    private static final Duration EVENT_POLL_INTERVAL = Duration.ofMillis(50);

    private final CodingTerminalController controller;
    private final TerminalEventPump pump;
    private final Textarea editor = new Textarea();
    private final Viewport transcript;
    private final TerminalShortcutProfile shortcuts;
    private final Tui4jTerminalView view;
    private final List<String> history = new ArrayList<>();
    private final LongSupplier monotonicNanos;

    private String transcriptContent = "";
    private int transcriptRows;
    private String historyDraft = "";
    private int editorCursor;
    private int historyIndex;
    private boolean browsingHistory;
    private boolean followTranscript = true;
    private boolean newOutputPending;
    private boolean fullRepaintRequested;
    private int pendingTranscriptScrollRows;
    private AgentRunId timedRunId;
    private long runStartedNanos;
    private PreparedMessageSubmission pendingSubmission;

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
        this.controller = controller;
        this.pump = pump;
        this.monotonicNanos = monotonicNanos;
        this.shortcuts =
                hostInfo == null ? TerminalShortcutProfile.standard() : TerminalShortcutProfile.forHost(hostInfo);
        this.view = new Tui4jTerminalView(shortcuts);
        TerminalUiState state = controller.state();
        this.editorCursor = state.editorCursor();
        this.transcript = Viewport.create(state.columns(), 2);
        editor.setWidth(state.columns());
        editor.setHeight(3);
        editor.setMaxHeight(3);
        editor.setShowLineNumbers(false);
        editor.setPrompt("┃ ");
        editor.setPlaceholder("Type a message, /command, @file, !command, or !!command");
        editor.focus();
        fullRepaintRequested = false;
    }

    @Override
    public Command init() {
        syncComponents();
        // tui4j does not emit the initial size until explicitly requested. Without this,
        // the production terminal stays at the 80x24 bootstrap size until the user resizes it.
        return Command.batch(Command.checkWindowSize(), nextTick());
    }

    @Override
    public UpdateResult<Tui4jCodingTerminalModel> update(Message message) {
        Command command = Command.none();
        if (message instanceof PollMessage) {
            controller.drainEvents();
            syncComponents();
            command = nextTick();
        } else if (message instanceof SubmissionCompletedMessage completed) {
            if (completed.result().submission().equals(pendingSubmission)) {
                controller.completeMessageSubmission(completed.result());
                pendingSubmission = null;
                syncComponents();
            }
        } else if (message instanceof WindowSizeMessage resized) {
            pump.offer(new TerminalUiAction.TerminalResized(resized.width(), resized.height()));
            controller.drainEvents();
            syncComponents();
        } else if (message instanceof EnterKeyModifierMessage modified
                && (modified.modifier() == EnterKeyModifier.Shift
                        || modified.modifier() == EnterKeyModifier.Ctrl
                        || modified.modifier() == EnterKeyModifier.CtrlShift)) {
            edit(new PasteMessage("\n"));
        } else if (message instanceof MouseMessage mouse) {
            command = mouse(mouse);
        } else if (message instanceof KeyPressMessage key) {
            command = key(key);
        } else if (message instanceof PasteMessage paste) {
            edit(new PasteMessage(sanitizeEditorInput(paste.content())));
        } else {
            editor.update(message);
        }
        if (controller.state().exitRequested()) {
            return UpdateResult.from(this, Command.quit());
        }
        if (fullRepaintRequested) {
            fullRepaintRequested = false;
            command = Command.batch(Command.clearScreen(), command);
        }
        return UpdateResult.from(this, command);
    }

    private Command mouse(MouseMessage mouse) {
        if (!mouse.isWheel()) {
            return Command.none();
        }
        if (mouse.getButton() == MouseButton.MouseButtonWheelUp) {
            requestTranscriptScroll(-transcript.getMouseWheelDelta());
        } else if (mouse.getButton() == MouseButton.MouseButtonWheelDown) {
            requestTranscriptScroll(transcript.getMouseWheelDelta());
        }
        return Command.none();
    }

    @Override
    public String view() {
        TerminalUiState state = controller.state();
        int requestedScrollRows = pendingTranscriptScrollRows;
        pendingTranscriptScrollRows = 0;
        Duration elapsed = workingElapsed(state);
        String rendered = view.render(
                state, transcript, editor, followTranscript, newOutputPending, elapsed, requestedScrollRows);
        if (requestedScrollRows > 0 && transcript.atBottom()) {
            followTranscript = true;
            newOutputPending = false;
            rendered = view.render(state, transcript, editor, true, false, elapsed, 0);
        }
        return rendered;
    }

    private Duration workingElapsed(TerminalUiState state) {
        if (state.currentRunId().isEmpty()) {
            timedRunId = null;
            return Duration.ZERO;
        }
        AgentRunId runId = state.currentRunId().orElseThrow();
        long now = monotonicNanos.getAsLong();
        if (!runId.equals(timedRunId)) {
            timedRunId = runId;
            runStartedNanos = now;
        }
        return Duration.ofNanos(Math.max(0, now - runStartedNanos));
    }

    private Command key(KeyPressMessage key) {
        TerminalUiState state = controller.state();
        if (state.selector().isPresent()) {
            if ("completion".equals(state.selector().orElseThrow().kind()) && editCompletion(key)) {
                return Command.none();
            }
            selectorKey(key);
            return Command.none();
        }
        if (shortcuts.matchesRestoreQueuedMessage(key)) {
            accept(TerminalInput.Kind.RESTORE);
            return Command.none();
        }
        if (key.type() == KeyType.keyESC) {
            accept(TerminalInput.Kind.CANCEL_OR_CLOSE);
            return Command.none();
        }
        if (key.type() == KeyType.keyETX) {
            accept(TerminalInput.Kind.INTERRUPT);
            return Command.none();
        }
        if (key.type() == KeyType.keyEOT) {
            accept(TerminalInput.Kind.EOF);
            return Command.none();
        }
        if (shortcuts.matchesToggleExpansion(key)) {
            accept(TerminalInput.Kind.TOGGLE_EXPANSION);
            return Command.none();
        }
        if (key.type() == KeyType.keyHT) {
            accept(TerminalInput.Kind.COMPLETION_REQUESTED);
            return Command.none();
        }
        if (key.type() == KeyType.keyCR) {
            return submit(shortcuts.matchesFollowUp(key) ? TerminalInput.Kind.FOLLOW_UP : TerminalInput.Kind.SUBMIT);
        }
        if (key.type() == KeyType.keyLF) {
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
            moveCursor(TerminalTextCursor.previous(state.editorBuffer(), state.editorCursor()));
            return Command.none();
        }
        if (key.type() == KeyType.KeyRight) {
            moveCursor(TerminalTextCursor.next(state.editorBuffer(), state.editorCursor()));
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
        if (key.type() == KeyType.keyBS || key.type() == KeyType.keyDEL) {
            int cursor = TerminalTextCursor.previous(state.editorBuffer(), state.editorCursor());
            replaceEditor(TerminalTextCursor.backspace(state.editorBuffer(), state.editorCursor()), cursor);
            return Command.none();
        }
        if (key.type() == KeyType.KeyDelete) {
            replaceEditor(TerminalTextCursor.delete(state.editorBuffer(), state.editorCursor()), state.editorCursor());
            return Command.none();
        }
        return edit(key);
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
        controller.accept(new TerminalInput(TerminalInput.Kind.EDITOR_CHANGED, updated, updatedCursor));
        controller.accept(new TerminalInput(TerminalInput.Kind.COMPLETION_REQUESTED, updated, updatedCursor));
        syncComponents();
        return true;
    }

    private void selectorKey(KeyPressMessage key) {
        if (key.type() == KeyType.KeyUp) {
            accept(TerminalInput.Kind.SELECT_PREVIOUS);
        } else if (key.type() == KeyType.KeyDown) {
            accept(TerminalInput.Kind.SELECT_NEXT);
        } else if (key.type() == KeyType.keyCR) {
            accept(TerminalInput.Kind.SUBMIT);
        } else if (key.type() == KeyType.keyESC) {
            accept(TerminalInput.Kind.CANCEL_OR_CLOSE);
        } else if (key.type() == KeyType.keyETX) {
            accept(TerminalInput.Kind.INTERRUPT);
        }
    }

    private Command edit(Message message) {
        resetHistoryNavigation();
        String before = editor.value();
        int beforeCursor = editorCursor;
        UpdateResult<Textarea> result = editor.update(message);
        String after = editor.value();
        editorCursor = cursorAfter(message, before, beforeCursor, after);
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
        syncComponents();
    }

    private Command submit(TerminalInput.Kind kind) {
        TerminalUiState state = controller.state();
        if (pendingSubmission != null) {
            return Command.none();
        }
        if (!state.editorBuffer().isBlank()) {
            history.add(state.editorBuffer());
        }
        resetHistoryNavigation();
        TerminalInput input = new TerminalInput(kind, state.editorBuffer(), state.editorCursor());
        var prepared = controller.prepareMessageSubmission(input);
        if (prepared.isEmpty()) {
            controller.accept(input);
            syncComponents();
            return Command.none();
        }
        pendingSubmission = prepared.orElseThrow();
        syncComponents();
        PreparedMessageSubmission submission = pendingSubmission;
        return () -> new SubmissionCompletedMessage(controller.executeMessageSubmission(submission));
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

    private void resetHistoryNavigation() {
        browsingHistory = false;
        historyDraft = "";
        historyIndex = history.size();
    }

    private void syncComponents() {
        TerminalUiState state = controller.state();
        if (!editor.value().equals(state.editorBuffer())) {
            synchronizeEditor(state.editorBuffer(), state.editorCursor());
        } else if (editorCursor != state.editorCursor()) {
            synchronizeEditor(state.editorBuffer(), state.editorCursor());
        }
        editor.setWidth(state.columns());
        if (state.selector().isPresent()) {
            editor.blur();
        } else {
            editor.focus();
        }

        String nextTranscript = view.transcriptContent(state);
        if (!nextTranscript.equals(transcriptContent)) {
            int nextTranscriptRows = (int) nextTranscript.lines().count();
            fullRepaintRequested = transcriptRows != 0 && transcriptRows != nextTranscriptRows;
            transcript.setContent(nextTranscript);
            transcriptContent = nextTranscript;
            transcriptRows = nextTranscriptRows;
            if (followTranscript) {
                transcript.gotoBottom();
                newOutputPending = false;
            } else {
                newOutputPending = true;
            }
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

    private int cursorAfter(Message message, String before, int beforeCursor, String after) {
        if (message instanceof PasteMessage) {
            return TerminalTextCursor.clamp(after, beforeCursor + (after.length() - before.length()));
        }
        if (!(message instanceof KeyPressMessage key)) {
            return TerminalTextCursor.clamp(after, beforeCursor);
        }
        return switch (key.type()) {
            case KeyRunes -> TerminalTextCursor.clamp(after, beforeCursor + (after.length() - before.length()));
            default -> TerminalTextCursor.clamp(after, beforeCursor + (after.length() - before.length()));
        };
    }

    private Command nextTick() {
        return Command.tick(EVENT_POLL_INTERVAL, ignored -> new PollMessage());
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

    private record PollMessage() implements Message {}

    private record SubmissionCompletedMessage(MessageSubmissionResult result) implements Message {}
}
