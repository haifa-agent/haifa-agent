package io.haifa.agent.git;

import java.util.List;
import java.util.Objects;

public record GitStatus(List<GitStatusEntry> entries, boolean truncated) {
    public GitStatus {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
    }
}
