package io.haifa.agent.application.coding.terminal.jline;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;

/** The terminal's single JLine editor instance. */
public final class JLineEditor {
    private final LineReader reader;
    private final AtomicReference<TerminalInput.Kind> acceptedKind = new AtomicReference<>(TerminalInput.Kind.SUBMIT);
    private final AtomicBoolean selectorActive = new AtomicBoolean();

    public JLineEditor(Terminal terminal, Supplier<List<String>> logicalPaths) {
        reader = LineReaderBuilder.builder()
                .terminal(Objects.requireNonNull(terminal, "terminal must not be null"))
                .completer(new JLineCompleter(logicalPaths))
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .build();
        JLineKeyBindings.install(reader, acceptedKind, selectorActive);
    }

    public TerminalInput read(String initialBuffer) {
        return read(initialBuffer, false);
    }

    public TerminalInput read(String initialBuffer, boolean selector) {
        acceptedKind.set(TerminalInput.Kind.SUBMIT);
        selectorActive.set(selector);
        try {
            String line = reader.readLine("", null, selector ? "" : initialBuffer);
            return new TerminalInput(acceptedKind.get(), line);
        } catch (UserInterruptException exception) {
            return new TerminalInput(TerminalInput.Kind.INTERRUPT, exception.getPartialLine());
        } catch (EndOfFileException exception) {
            return new TerminalInput(TerminalInput.Kind.EOF, "");
        } finally {
            selectorActive.set(false);
        }
    }

    public LineReader lineReader() {
        return reader;
    }
}
