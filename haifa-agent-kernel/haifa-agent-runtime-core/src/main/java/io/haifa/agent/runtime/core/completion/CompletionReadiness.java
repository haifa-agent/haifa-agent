package io.haifa.agent.runtime.core.completion;

import java.util.List;
import java.util.Objects;

public record CompletionReadiness(boolean ready, List<String> blockers) {
    public CompletionReadiness {
        blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers must not be null"));
        if (ready && !blockers.isEmpty()) throw new IllegalArgumentException("ready completion cannot have blockers");
    }
}
