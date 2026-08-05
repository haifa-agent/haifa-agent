package io.haifa.agent.application.coding.terminal.application;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Bounded completion over supported commands and product-provided logical paths only. */
public final class TerminalCompletionProvider {
    public static final List<String> COMMANDS = List.of(
            "/model",
            "/new",
            "/resume",
            "/compact",
            "/session",
            "/reload",
            "/rename",
            "/export",
            "/archive",
            "/delete",
            "/commands",
            "/help",
            "/quit");
    private static final int MAX_CANDIDATES = 16;

    private final Supplier<List<String>> logicalPaths;

    public TerminalCompletionProvider(Supplier<List<String>> logicalPaths) {
        this.logicalPaths = Objects.requireNonNull(logicalPaths, "logicalPaths must not be null");
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
                    .map(TerminalCompletionProvider::safeLogicalPath)
                    .filter(TerminalCompletionProvider::isVisibleLogicalPath)
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

    private static boolean isVisibleLogicalPath(String value) {
        return java.util.Arrays.stream(value.split("/"))
                .filter(segment -> !segment.isEmpty())
                .noneMatch(segment -> segment.startsWith("."));
    }
}
