package io.haifa.coding.host.internal;

import io.haifa.agent.sandbox.host.HostExecutionEnvironmentResolver;
import io.haifa.agent.sandbox.host.ResolvedHostEnvironment;
import java.nio.file.Path;
import java.util.Map;

/** Coding product adapter for the shared trusted execution-environment resolver. */
public final class CliExecutionEnvironment {
    private CliExecutionEnvironment() {}

    public static ResolvedHostEnvironment resolve(
            CliConfiguration.Execution configuration, Path applicationDataRoot, Path workspaceRoot, Path scratchRoot) {
        return resolve(
                configuration,
                System.getenv(),
                System.getProperty("os.name", ""),
                Path.of(System.getProperty("user.home", ".")),
                applicationDataRoot,
                workspaceRoot,
                scratchRoot);
    }

    public static ResolvedHostEnvironment resolve(
            CliConfiguration.Execution configuration,
            Map<String, String> hostEnvironment,
            String operatingSystem,
            Path jvmUserHome,
            Path applicationDataRoot,
            Path workspaceRoot,
            Path scratchRoot) {
        return resolve(
                configuration,
                hostEnvironment,
                operatingSystem,
                jvmUserHome,
                applicationDataRoot,
                workspaceRoot,
                scratchRoot,
                java.util.Set.of());
    }

    public static ResolvedHostEnvironment resolve(
            CliConfiguration.Execution configuration,
            Map<String, String> hostEnvironment,
            String operatingSystem,
            Path jvmUserHome,
            Path applicationDataRoot,
            Path workspaceRoot,
            Path scratchRoot,
            java.util.Set<String> deniedEnvironmentNames) {
        return HostExecutionEnvironmentResolver.resolveHostUser(
                hostEnvironment,
                operatingSystem,
                jvmUserHome,
                applicationDataRoot,
                workspaceRoot,
                scratchRoot,
                configuration.inheritEnvironment(),
                deniedEnvironmentNames);
    }
}
