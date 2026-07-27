package io.haifa.agent.application.coding.terminal.jline;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.keymap.KeyMap;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.Widget;

/** Fixed Phase 2 key semantics from the reviewed terminal prototype. */
public final class JLineKeyBindings {
    static final String FOLLOW_UP = "haifa-follow-up";
    static final String CANCEL_OR_CLOSE = "haifa-cancel-or-close";
    static final String RESTORE = "haifa-restore";
    static final String TOGGLE = "haifa-toggle";
    static final String SELECT_PREVIOUS = "haifa-selector-previous";
    static final String SELECT_NEXT = "haifa-selector-next";

    private JLineKeyBindings() {}

    public static void install(
            LineReader reader, AtomicReference<TerminalInput.Kind> acceptedKind, AtomicBoolean selectorActive) {
        Objects.requireNonNull(reader, "reader must not be null");
        Objects.requireNonNull(acceptedKind, "acceptedKind must not be null");
        Objects.requireNonNull(selectorActive, "selectorActive must not be null");
        bindAccept(reader, acceptedKind, FOLLOW_UP, TerminalInput.Kind.FOLLOW_UP, "\033\r");
        bindAccept(reader, acceptedKind, CANCEL_OR_CLOSE, TerminalInput.Kind.CANCEL_OR_CLOSE, "\033");
        bindAccept(reader, acceptedKind, RESTORE, TerminalInput.Kind.RESTORE, "\033[1;3A");
        bindAccept(reader, acceptedKind, TOGGLE, TerminalInput.Kind.TOGGLE_EXPANSION, "\017");
        bindSelectorNavigation(
                reader,
                acceptedKind,
                selectorActive,
                SELECT_PREVIOUS,
                TerminalInput.Kind.SELECT_PREVIOUS,
                LineReader.UP_LINE_OR_HISTORY,
                "\033[A");
        bindSelectorNavigation(
                reader,
                acceptedKind,
                selectorActive,
                SELECT_NEXT,
                TerminalInput.Kind.SELECT_NEXT,
                LineReader.DOWN_LINE_OR_HISTORY,
                "\033[B");

        Widget insertNewline = () -> {
            reader.getBuffer().write('\n');
            return true;
        };
        reader.getWidgets().put("haifa-newline", insertNewline);
        reader.getKeyMaps().get(LineReader.MAIN).bind(new Reference("haifa-newline"), "\033[13;2u");
    }

    private static void bindSelectorNavigation(
            LineReader reader,
            AtomicReference<TerminalInput.Kind> acceptedKind,
            AtomicBoolean selectorActive,
            String widgetName,
            TerminalInput.Kind kind,
            String fallbackWidget,
            String sequence) {
        reader.getWidgets().put(widgetName, () -> {
            if (!selectorActive.get()) {
                reader.callWidget(fallbackWidget);
                return true;
            }
            acceptedKind.set(kind);
            reader.callWidget(LineReader.ACCEPT_LINE);
            return true;
        });
        reader.getKeyMaps().get(LineReader.MAIN).bind(new Reference(widgetName), sequence);
    }

    private static void bindAccept(
            LineReader reader,
            AtomicReference<TerminalInput.Kind> acceptedKind,
            String widgetName,
            TerminalInput.Kind kind,
            String sequence) {
        reader.getWidgets().put(widgetName, () -> {
            acceptedKind.set(kind);
            reader.callWidget(LineReader.ACCEPT_LINE);
            return true;
        });
        KeyMap<org.jline.reader.Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
        main.bind(new Reference(widgetName), sequence);
    }
}
