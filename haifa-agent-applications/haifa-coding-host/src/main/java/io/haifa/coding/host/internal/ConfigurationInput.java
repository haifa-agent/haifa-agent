package io.haifa.coding.host.internal;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/** Narrow trusted configuration overrides; CLI argument parsing stays in the launcher. */
public interface ConfigurationInput {
    Optional<Path> config();

    Optional<String> model();

    Optional<ApprovalMode> approval();

    Optional<Duration> timeout();

    static ConfigurationInput of(Optional<Path> config) {
        return new Overrides(config, Optional.empty(), Optional.empty(), Optional.empty());
    }

    record Overrides(
            Optional<Path> config, Optional<String> model, Optional<ApprovalMode> approval, Optional<Duration> timeout)
            implements ConfigurationInput {}
}
