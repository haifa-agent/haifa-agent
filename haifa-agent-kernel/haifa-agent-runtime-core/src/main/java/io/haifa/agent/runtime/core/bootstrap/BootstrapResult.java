package io.haifa.agent.runtime.core.bootstrap;

import io.haifa.agent.core.run.AgentRun;
import java.util.Objects;

public record BootstrapResult(
        AgentRun run,
        ResolvedDefinition definition,
        ResolvedProfile profile,
        RuntimeConfigurationSnapshot configuration) {
    public BootstrapResult {
        run = Objects.requireNonNull(run, "run must not be null");
        definition = Objects.requireNonNull(definition, "definition must not be null");
        profile = Objects.requireNonNull(profile, "profile must not be null");
        configuration = Objects.requireNonNull(configuration, "configuration must not be null");
    }
}
