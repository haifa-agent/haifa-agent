package io.haifa.agent.application.coding.terminal.jline;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.reader.Buffer;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;

/**
 * Non-blocking JLine editor. The application owns the only Display, while this class reuses JLine's
 * Unicode-aware input reader, Buffer, History, Parser and Completer.
 */
public final class JLineEditor {
    private static final long INPUT_POLL_MILLIS = 50L;

    private final LineReader reader;
    private final JLineCompleter completer;
    private final TerminalBindingReader bindingReader;
    private final org.jline.keymap.KeyMap<JLineKeyBindings.Binding> keys;
    private String historyDraft = "";
    private boolean browsingHistory;

    public JLineEditor(Terminal terminal, Supplier<List<String>> logicalPaths) {
        Terminal requiredTerminal = Objects.requireNonNull(terminal, "terminal must not be null");
        completer = new JLineCompleter(logicalPaths);
        reader = LineReaderBuilder.builder()
                .terminal(requiredTerminal)
                .completer(completer)
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .build();
        bindingReader = new TerminalBindingReader(requiredTerminal.reader());
        keys = JLineKeyBindings.create(requiredTerminal);
    }

    public TerminalInput read(String initialBuffer) {
        return read(initialBuffer, initialBuffer.length(), false);
    }

    public TerminalInput read(String initialBuffer, boolean selector) {
        return read(initialBuffer, initialBuffer.length(), selector);
    }

    public TerminalInput read(String initialBuffer, int initialCursor, boolean selector) {
        synchronizeBuffer(initialBuffer, initialCursor);
        int next = bindingReader.peekCharacter(INPUT_POLL_MILLIS);
        if (next == NonBlockingReader.READ_EXPIRED) {
            return new TerminalInput(TerminalInput.Kind.TICK, "", 0);
        }
        if (next < 0) {
            return new TerminalInput(TerminalInput.Kind.EOF, "", 0);
        }

        JLineKeyBindings.Binding binding = bindingReader.readBinding(keys);
        if (binding == null) {
            if (KeyMap.esc().equals(bindingReader.getCurrentBuffer())) {
                Buffer buffer = reader.getBuffer();
                bindingReader.clearCurrentBuffer();
                return new TerminalInput(TerminalInput.Kind.CANCEL_OR_CLOSE, buffer.toString(), buffer.cursor());
            }
            return new TerminalInput(TerminalInput.Kind.EOF, "", 0);
        }
        return apply(binding, selector);
    }

    public LineReader lineReader() {
        return reader;
    }

    private TerminalInput apply(JLineKeyBindings.Binding binding, boolean selector) {
        Buffer buffer = reader.getBuffer();
        return switch (binding) {
            case SELF_INSERT -> {
                if (!selector) {
                    buffer.write(bindingReader.getLastBinding());
                }
                yield changed();
            }
            case SUBMIT ->
                selector
                        ? new TerminalInput(TerminalInput.Kind.SUBMIT, buffer.toString(), buffer.cursor())
                        : submit(TerminalInput.Kind.SUBMIT);
            case FOLLOW_UP ->
                selector
                        ? new TerminalInput(TerminalInput.Kind.FOLLOW_UP, buffer.toString(), buffer.cursor())
                        : submit(TerminalInput.Kind.FOLLOW_UP);
            case CANCEL_OR_CLOSE ->
                new TerminalInput(TerminalInput.Kind.CANCEL_OR_CLOSE, buffer.toString(), buffer.cursor());
            case RESTORE -> new TerminalInput(TerminalInput.Kind.RESTORE, buffer.toString(), buffer.cursor());
            case TOGGLE -> new TerminalInput(TerminalInput.Kind.TOGGLE_EXPANSION, buffer.toString(), buffer.cursor());
            case INTERRUPT -> {
                String partial = buffer.toString();
                buffer.clear();
                resetHistoryNavigation();
                yield new TerminalInput(TerminalInput.Kind.INTERRUPT, partial, partial.length());
            }
            case EOF -> {
                if (!selector && buffer.length() > 0) {
                    buffer.delete();
                    yield changed();
                }
                yield new TerminalInput(TerminalInput.Kind.EOF, "", 0);
            }
            case NEWLINE -> {
                if (!selector) {
                    buffer.write('\n');
                }
                yield changed();
            }
            case BACKSPACE -> {
                if (!selector) {
                    buffer.backspace();
                }
                yield changed();
            }
            case DELETE -> {
                if (!selector) {
                    buffer.delete();
                }
                yield changed();
            }
            case LEFT -> {
                if (!selector) {
                    buffer.move(-1);
                }
                yield changed();
            }
            case RIGHT -> {
                if (!selector) {
                    buffer.move(1);
                }
                yield changed();
            }
            case UP ->
                selector
                        ? new TerminalInput(TerminalInput.Kind.SELECT_PREVIOUS, buffer.toString(), buffer.cursor())
                        : history(-1);
            case DOWN ->
                selector
                        ? new TerminalInput(TerminalInput.Kind.SELECT_NEXT, buffer.toString(), buffer.cursor())
                        : history(1);
            case HOME -> {
                if (!selector) {
                    buffer.cursor(0);
                }
                yield changed();
            }
            case END -> {
                if (!selector) {
                    buffer.cursor(buffer.length());
                }
                yield changed();
            }
            case KILL_TO_START -> {
                if (!selector) {
                    buffer.backspace(buffer.cursor());
                }
                yield changed();
            }
            case KILL_TO_END -> {
                if (!selector) {
                    buffer.delete(buffer.length() - buffer.cursor());
                }
                yield changed();
            }
            case KILL_PREVIOUS_WORD -> {
                if (!selector) {
                    killPreviousWord(buffer);
                }
                yield changed();
            }
            case COMPLETE -> {
                yield selector
                        ? changed()
                        : new TerminalInput(
                                TerminalInput.Kind.COMPLETION_REQUESTED, buffer.toString(), buffer.cursor());
            }
            case IGNORE -> changed();
        };
    }

    private TerminalInput submit(TerminalInput.Kind kind) {
        Buffer buffer = reader.getBuffer();
        String text = buffer.toString();
        if (!text.isBlank()) {
            reader.getHistory().add(text);
        }
        buffer.clear();
        resetHistoryNavigation();
        return new TerminalInput(kind, text, text.length());
    }

    private TerminalInput history(int direction) {
        History history = reader.getHistory();
        if (history.isEmpty()) {
            return changed();
        }
        if (!browsingHistory) {
            historyDraft = reader.getBuffer().toString();
            browsingHistory = true;
            history.moveToEnd();
        }
        boolean moved = direction < 0 ? history.previous() : history.next();
        if (moved) {
            replaceBuffer(history.current());
        } else if (direction > 0) {
            replaceBuffer(historyDraft);
            resetHistoryNavigation();
        }
        return changed();
    }

    private static void killPreviousWord(Buffer buffer) {
        while (buffer.cursor() > 0 && Character.isWhitespace(buffer.prevChar())) {
            buffer.backspace();
        }
        while (buffer.cursor() > 0 && !Character.isWhitespace(buffer.prevChar())) {
            buffer.backspace();
        }
    }

    private void synchronizeBuffer(String value, int cursor) {
        Objects.requireNonNull(value, "initialBuffer must not be null");
        if (cursor < 0 || cursor > value.length()) {
            throw new IllegalArgumentException("initial cursor is out of range");
        }
        Buffer buffer = reader.getBuffer();
        if (!buffer.toString().equals(value)) {
            replaceBuffer(value);
            resetHistoryNavigation();
        }
        buffer.cursor(cursor);
    }

    private void replaceBuffer(String value) {
        Buffer buffer = reader.getBuffer();
        buffer.clear();
        buffer.write(value);
    }

    private TerminalInput changed() {
        Buffer buffer = reader.getBuffer();
        return new TerminalInput(TerminalInput.Kind.EDITOR_CHANGED, buffer.toString(), buffer.cursor());
    }

    private void resetHistoryNavigation() {
        browsingHistory = false;
        historyDraft = "";
        reader.getHistory().moveToEnd();
    }

    private static final class TerminalBindingReader extends BindingReader {
        private TerminalBindingReader(NonBlockingReader reader) {
            super(reader);
        }

        private void clearCurrentBuffer() {
            opBuffer.setLength(0);
        }
    }
}
