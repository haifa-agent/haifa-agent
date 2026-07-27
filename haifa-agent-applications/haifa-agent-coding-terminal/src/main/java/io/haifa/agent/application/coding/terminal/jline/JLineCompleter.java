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
    public static final List<String> COMMANDS = List.of("/new", "/resume", "/session", "/commands", "/quit");
    private static final int MAX_CANDIDATES = 12;

    private final Supplier<List<String>> logicalPaths;

    public JLineCompleter(Supplier<List<String>> logicalPaths) {
        this.logicalPaths = Objects.requireNonNull(logicalPaths, "logicalPaths must not be null");
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        suggestions(line.word()).stream().map(Candidate::new).forEach(candidates::add);
    }

    public List<String> suggestions(String word) {
        Objects.requireNonNull(word, "word must not be null");
        if (word.startsWith("/")) {
            return COMMANDS.stream()
                    .filter(value -> value.startsWith(word))
                    .limit(MAX_CANDIDATES)
                    .toList();
        }
        if (word.startsWith("@")) {
            return logicalPaths.get().stream()
                    .map(JLineCompleter::safeLogicalPath)
                    .filter(value -> value.startsWith(word.substring(1)))
                    .map(value -> "@" + value)
                    .limit(MAX_CANDIDATES)
                    .toList();
        }
        return List.of();
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
