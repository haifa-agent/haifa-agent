package io.haifa.agent.runtime.api;

import io.haifa.agent.core.run.AgentRun;
import java.util.Objects;
import java.util.Optional;

/** Runtime result containing the latest run snapshot and optional user-facing output. */
public record AgentRunResult(AgentRun run, Optional<String> output) {

    public AgentRunResult {
        run = Objects.requireNonNull(run, "run must not be null");
        output = Objects.requireNonNull(output, "output must not be null")
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }

    public static AgentRunResult withoutOutput(AgentRun run) {
        return new AgentRunResult(run, Optional.empty());
    }

    public static AgentRunResult withOutput(AgentRun run, String output) {
        return new AgentRunResult(run, Optional.of(output));
    }
}
