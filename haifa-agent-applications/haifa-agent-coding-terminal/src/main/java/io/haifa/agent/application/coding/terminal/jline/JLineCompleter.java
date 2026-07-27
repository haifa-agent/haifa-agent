package io.haifa.agent.application.coding.terminal.jline;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

/** Bounded completion over supported commands and product-provided logical paths only. */
public final class JLineCompleter implements Completer {
    public static final List<String> COMMANDS = List.of("/new", "/resume", "/settings", "/trust", "/session", "/quit");

    private final Supplier<List<String>> logicalPaths;

    public JLineCompleter(Supplier<List<String>> logicalPaths) {
        this.logicalPaths = Objects.requireNonNull(logicalPaths, "logicalPaths must not be null");
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String word = line.word();
        if (word.startsWith("/")) {
            COMMANDS.stream()
                    .filter(value -> value.startsWith(word))
                    .map(Candidate::new)
                    .forEach(candidates::add);
            return;
        }
        if (word.startsWith("@")) {
            logicalPaths.get().stream()
                    .limit(200)
                    .map(JLineCompleter::safeLogicalPath)
                    .filter(value -> value.startsWith(word.substring(1)))
                    .map(value -> new Candidate("@" + value))
                    .forEach(candidates::add);
        }
    }

    private static String safeLogicalPath(String value) {
        String safe =
                Objects.requireNonNull(value, "logical path must not be null").replace('\\', '/');
        if (safe.startsWith("/") || safe.contains("../") || safe.equals("..")) {
            throw new IllegalArgumentException("logical path must stay relative");
        }
        return safe;
    }
}
