package io.haifa.agent.application.coding.terminal.state;

import java.util.List;
import java.util.Objects;

public record TerminalSelector(String kind, String title, List<String> options, int selected) {
    public TerminalSelector {
        kind = Objects.requireNonNull(kind, "kind must not be null");
        title = Objects.requireNonNull(title, "title must not be null");
        options = List.copyOf(Objects.requireNonNull(options, "options must not be null"));
        if (options.size() > 100) throw new IllegalArgumentException("selector has too many options");
        if (selected < 0 || (!options.isEmpty() && selected >= options.size())) {
            throw new IllegalArgumentException("selector selection is out of range");
        }
    }

    public TerminalSelector move(int delta) {
        if (options.isEmpty()) return this;
        int next = Math.floorMod(selected + delta, options.size());
        return new TerminalSelector(kind, title, options, next);
    }
}
