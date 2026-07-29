package io.haifa.agent.application.project.tool;

import io.haifa.agent.execution.api.ExecutionScratchBinding;
import io.haifa.agent.execution.api.ExecutionScratchSpaceSpec;
import java.util.List;
import java.util.Set;

/** Coding-product scratch bindings; Execution and Sandbox remain language-neutral. */
public final class CodingToolchainEnvironmentProfile {
    private static final ExecutionScratchSpaceSpec DEFAULT = new ExecutionScratchSpaceSpec(
            true,
            Set.of("TMPDIR", "TMP", "TEMP", "GOTMPDIR"),
            List.of(new ExecutionScratchBinding("GOCACHE", "go-build")));

    private CodingToolchainEnvironmentProfile() {}

    public static ExecutionScratchSpaceSpec defaultScratchSpace() {
        return DEFAULT;
    }
}
