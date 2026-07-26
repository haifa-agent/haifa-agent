package io.haifa.agent.sandbox.localnative;

import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.sandbox.api.SandboxProfile;
import java.nio.file.Path;
import java.util.List;

interface LocalNativeAdapter {
    String adapterId();

    void preflight(LocalNativeSandboxConfiguration configuration);

    LocalNativeLaunchPlan prepare(
            LocalNativeSandboxConfiguration configuration,
            SandboxProfile profile,
            Path workspaceRoot,
            Path workingDirectory,
            Path controlDirectory,
            List<LocalNativePathGrant> additionalPaths,
            ExecutionCommand command);
}
