package io.haifa.agent.cli;

import java.util.List;
import java.util.Objects;

/** Host-owned configuration provenance; never includes file contents, paths or credential values. */
record CodingHostConfiguration(CliConfiguration configuration, Source source) {
    CodingHostConfiguration {
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(source, "source must not be null");
    }

    @Override
    public String toString() {
        return "CodingHostConfiguration[source=" + source + "]";
    }

    List<String> summary() {
        return List.of(
                "Host config source: " + source.label,
                "Workspace config overlay: ignored; use explicit --config for a trusted file",
                "Host approval for new Runs: " + configuration.approval().name() + "; threshold: "
                        + configuration.approvalThreshold().name(),
                "Configured built-in tools: " + configuration.enabledTools().size(),
                "Configured models: " + configuration.availableModels().size());
    }

    enum Source {
        EXPLICIT_CONFIG("explicit --config file"),
        USER_CONFIG("user ~/.haifa-agent/config.yaml"),
        DEFAULTS("built-in defaults");

        private final String label;

        Source(String label) {
            this.label = label;
        }
    }
}
