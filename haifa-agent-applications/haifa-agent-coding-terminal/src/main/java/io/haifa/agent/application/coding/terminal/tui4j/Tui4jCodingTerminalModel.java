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
import com.williamcallahan.tui4j.compat.bubbletea.input.key.Key;
import com.williamcallahan.tui4j.compat.bubbletea.input.key.KeyType;
import com.williamcallahan.tui4j.message.EnterKeyModifier;
import com.williamcallahan.tui4j.message.EnterKeyModifierMessage;
import io.haifa.agent.application.coding.terminal.application.CodingTerminalController;
import io.haifa.agent.application.coding.terminal.event.TerminalEventPump;
import io.haifa.agent.application.coding.terminal.event.TerminalInput;
import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Production tui4j adapter around the authoritative terminal Controller and Reducer state. */
final class Tui4jCodingTerminalModel implements Model {
    private static final Duration EVENT_POLL_INTERVAL = Duration.ofMillis(50);

    private final CodingTerminalController controller;
    private final TerminalEventPump pump;
    private final Textarea editor = new Textarea();
    private final Viewport transcript;
    private final Tui4jTerminalView view = new Tui4jTerminalView();
    private final List<String> history = new ArrayList<>();

    private String transcriptContent = "";
    private int transcriptRows;
    private String historyDraft = "";
    private int editorCursor;
    private int historyIndex;
    private boolean browsingHistory;
    private boolean newOutputPending;
    private boolean fullRepaintRequested;

    Tui4jCodingTerminalModel(CodingTerminalController controller, TerminalEventPump pump) {
        this.controller = controller;
        this.pump = pump;
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
        syncComponents();
        fullRepaintRequested = false;
    }

    @Override
    public Command init() {
        return nextTick();
    }

    @Override
    public UpdateResult<Tui4jCodingTerminalModel> update(Message message) {
        Command command = Command.none();
        if (message instanceof PollMessage) {
            controller.drainEvents();
            syncComponents();
            command = nextTick();
        } else if (message instanceof WindowSizeMessage resized) {
            pump.offer(new TerminalUiAction.TerminalResized(resized.width(), resized.height()));
            controller.drainEvents();
            syncComponents();
        } else if (message instanceof EnterKeyModifierMessage modified
                && modified.modifier() == EnterKeyModifier.Shift) {
            edit(new PasteMessage("\n"));
        } else if (message instanceof KeyPressMessage key) {
            command = key(key);
        } else if (message instanceof PasteMessage) {
            edit(message);
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

    @Override
    public String view() {
        TerminalUiState state = controller.state();
        return view.render(state, transcript, editor, transcript.atBottom(), newOutputPending);
    }

    private Command key(KeyPressMessage key) {
        TerminalUiState state = controller.state();
        if (state.selector().isPresent()) {
            selectorKey(key);
            return Command.none();
        }
        if (key.alt() && key.type() == KeyType.KeyUp) {
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
        if (key.type() == KeyType.keySI) {
            accept(TerminalInput.Kind.TOGGLE_EXPANSION);
            return Command.none();
        }
        if (key.type() == KeyType.keyHT) {
            accept(TerminalInput.Kind.COMPLETION_REQUESTED);
            return Command.none();
        }
        if (key.type() == KeyType.keyCR) {
            submit(key.alt() ? TerminalInput.Kind.FOLLOW_UP : TerminalInput.Kind.SUBMIT);
            return Command.none();
        }
        if (key.type() == KeyType.keyLF) {
            edit(new PasteMessage("\n"));
            return Command.none();
        }
        if (key.type() == KeyType.KeyUp) {
            navigateHistory(-1);
            return Command.none();
        }
        if (key.type() == KeyType.KeyDown) {
            navigateHistory(1);
            return Command.none();
        }
        if (key.type() == KeyType.KeyPgUp) {
            transcript.scrollUp(Math.max(1, transcript.getHeight() - 1));
            newOutputPending = true;
            return Command.none();
        }
        if (key.type() == KeyType.KeyPgDown) {
            transcript.scrollDown(Math.max(1, transcript.getHeight() - 1));
            if (transcript.atBottom()) {
                newOutputPending = false;
            }
            return Command.none();
        }
        return edit(key);
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
        syncComponents();
        return result.command();
    }

    private void accept(TerminalInput.Kind kind) {
        TerminalUiState state = controller.state();
        controller.accept(new TerminalInput(kind, state.editorBuffer(), state.editorCursor()));
        if (!state.editorBuffer().equals(controller.state().editorBuffer())) {
            resetHistoryNavigation();
        }
        syncComponents();
    }

    private void submit(TerminalInput.Kind kind) {
        TerminalUiState state = controller.state();
        if (!state.editorBuffer().isBlank()) {
            history.add(state.editorBuffer());
        }
        resetHistoryNavigation();
        controller.accept(new TerminalInput(kind, state.editorBuffer(), state.editorCursor()));
        syncComponents();
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
        controller.accept(new TerminalInput(TerminalInput.Kind.EDITOR_CHANGED, value, value.length()));
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
            boolean follow = transcriptContent.isEmpty() || transcript.atBottom();
            transcript.setContent(nextTranscript);
            transcriptContent = nextTranscript;
            transcriptRows = nextTranscriptRows;
            if (follow) {
                transcript.gotoBottom();
                newOutputPending = false;
            } else {
                newOutputPending = true;
            }
        }
    }

    private void synchronizeEditor(String value, int cursor) {
        boolean wasFocused = editor.focused();
        editor.focus();
        editor.setValue(value);
        int leftMoves = value.codePointCount(cursor, value.length());
        var left = new KeyPressMessage(new Key(KeyType.KeyLeft));
        for (int index = 0; index < leftMoves; index++) {
            editor.update(left);
        }
        if (!wasFocused) {
            editor.blur();
        }
        editorCursor = cursor;
    }

    private int cursorAfter(Message message, String before, int beforeCursor, String after) {
        if (message instanceof PasteMessage paste) {
            return clamp(beforeCursor + paste.content().length(), after.length());
        }
        if (!(message instanceof KeyPressMessage key)) {
            return clamp(beforeCursor, after.length());
        }
        return switch (key.type()) {
            case KeyRunes -> clamp(beforeCursor + new String(key.runes()).length(), after.length());
            case keyBS, keyDEL -> previousCodePoint(before, beforeCursor);
            case KeyDelete -> clamp(beforeCursor, after.length());
            case KeyLeft -> previousCodePoint(before, beforeCursor);
            case KeyRight -> nextCodePoint(before, beforeCursor);
            case KeyHome -> lineStart(before, beforeCursor);
            case KeyEnd -> lineEnd(before, beforeCursor);
            case KeyUp -> vertical(before, beforeCursor, -1);
            case KeyDown -> vertical(before, beforeCursor, 1);
            default -> clamp(beforeCursor + (after.length() - before.length()), after.length());
        };
    }

    private int vertical(String value, int cursor, int delta) {
        int start = lineStart(value, cursor);
        int column = cursor - start;
        if (delta < 0) {
            if (start == 0) return cursor;
            int previousEnd = start - 1;
            int previousStart = lineStart(value, previousEnd);
            return Math.min(previousStart + column, previousEnd);
        }
        int end = lineEnd(value, cursor);
        if (end == value.length()) return cursor;
        int nextStart = end + 1;
        return Math.min(nextStart + column, lineEnd(value, nextStart));
    }

    private int lineStart(String value, int cursor) {
        int newline = value.lastIndexOf('\n', Math.max(0, cursor - 1));
        return newline < 0 ? 0 : newline + 1;
    }

    private int lineEnd(String value, int cursor) {
        int newline = value.indexOf('\n', cursor);
        return newline < 0 ? value.length() : newline;
    }

    private int previousCodePoint(String value, int cursor) {
        return cursor <= 0 ? 0 : value.offsetByCodePoints(cursor, -1);
    }

    private int nextCodePoint(String value, int cursor) {
        return cursor >= value.length() ? value.length() : value.offsetByCodePoints(cursor, 1);
    }

    private int clamp(int value, int maximum) {
        return Math.max(0, Math.min(value, maximum));
    }

    private Command nextTick() {
        return Command.tick(EVENT_POLL_INTERVAL, ignored -> new PollMessage());
    }

    private record PollMessage() implements Message {}
}
