package io.haifa.agent.cli;

import io.haifa.agent.sandbox.host.HostExecutionEnvironmentResolver;
import io.haifa.agent.sandbox.host.ResolvedHostEnvironment;
import io.haifa.agent.sandbox.localnative.LocalNativeSandboxProvider;
import java.nio.file.Path;
import java.util.Map;

/** Coding product adapter for the shared trusted execution-environment resolver. */
final class CliExecutionEnvironment {
    private CliExecutionEnvironment() {}

    static ResolvedHostEnvironment resolve(
            CliConfiguration.Execution configuration,
            String providerId,
            Path applicationDataRoot,
            Path workspaceRoot,
            Path scratchRoot) {
        return resolve(
                configuration,
                providerId,
                System.getenv(),
                System.getProperty("os.name", ""),
                Path.of(System.getProperty("user.home", ".")),
                applicationDataRoot,
                workspaceRoot,
                scratchRoot);
    }

    static ResolvedHostEnvironment resolve(
            CliConfiguration.Execution configuration,
            String providerId,
            Map<String, String> hostEnvironment,
            String operatingSystem,
            Path jvmUserHome,
            Path applicationDataRoot,
            Path workspaceRoot,
            Path scratchRoot) {
        if (LocalNativeSandboxProvider.PROVIDER_ID.equals(providerId)) {
            return HostExecutionEnvironmentResolver.resolveProviderIsolated(
                    hostEnvironment, operatingSystem, configuration.inheritEnvironment());
        }
        return HostExecutionEnvironmentResolver.resolveHostUser(
                hostEnvironment,
                operatingSystem,
                jvmUserHome,
                applicationDataRoot,
                workspaceRoot,
                scratchRoot,
                configuration.inheritEnvironment());
    }
}
