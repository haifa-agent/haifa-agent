package io.haifa.agent.sandbox.localnative;

import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionScratchSpaceSpec;
import io.haifa.agent.sandbox.api.SandboxProfile;
import java.nio.file.Path;
import java.util.List;

final class UnsupportedLocalNativeAdapter implements LocalNativeAdapter {
    private final String platform;

    UnsupportedLocalNativeAdapter(String platform) {
        this.platform = platform;
    }

    @Override
    public String adapterId() {
        return "unsupported";
    }

    @Override
    public void preflight(LocalNativeSandboxConfiguration configuration) {
        throw new LocalNativeSandboxException(
                "SANDBOX_ADAPTER_UNAVAILABLE", "local-native sandbox is unavailable on this platform");
    }

    @Override
    public LocalNativeLaunchPlan prepare(
            LocalNativeSandboxConfiguration configuration,
            SandboxProfile profile,
            Path workspaceRoot,
            Path workingDirectory,
            Path controlDirectory,
            List<LocalNativePathGrant> additionalPaths,
            ExecutionScratchSpaceSpec scratchSpace,
            ExecutionCommand command) {
        throw new LocalNativeSandboxException(
                "SANDBOX_ADAPTER_UNAVAILABLE", "local-native sandbox is unavailable on this platform");
    }

    @Override
    public String toString() {
        return "UnsupportedLocalNativeAdapter[" + platform + "]";
    }
}
