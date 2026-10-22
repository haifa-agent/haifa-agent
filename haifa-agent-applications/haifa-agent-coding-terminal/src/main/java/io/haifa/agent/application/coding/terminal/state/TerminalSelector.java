package io.haifa.agent.application.coding.terminal.state;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record TerminalSelector(
        String kind, String title, List<String> options, int selected, List<TerminalSelectorDetail> details) {
    public TerminalSelector(String kind, String title, List<String> options, int selected) {
        this(kind, title, options, selected, List.of());
    }

    public TerminalSelector {
        kind = Objects.requireNonNull(kind, "kind must not be null");
        title = Objects.requireNonNull(title, "title must not be null");
        options = List.copyOf(Objects.requireNonNull(options, "options must not be null"));
        details = List.copyOf(Objects.requireNonNull(details, "details must not be null"));
        if (options.size() > 100) throw new IllegalArgumentException("selector has too many options");
        if (selected < 0 || (!options.isEmpty() && selected >= options.size())) {
            throw new IllegalArgumentException("selector selection is out of range");
        }
        if (!details.isEmpty() && details.size() != options.size()) {
            throw new IllegalArgumentException("selector details must align with options");
        }
    }

    public TerminalSelector move(int delta) {
        if (options.isEmpty()) return this;
        int next = Math.floorMod(selected + delta, options.size());
        return new TerminalSelector(kind, title, options, next, details);
    }

    public Optional<TerminalSelectorDetail> detail(int index) {
        return index >= 0 && index < details.size() ? Optional.of(details.get(index)) : Optional.empty();
    }
}
