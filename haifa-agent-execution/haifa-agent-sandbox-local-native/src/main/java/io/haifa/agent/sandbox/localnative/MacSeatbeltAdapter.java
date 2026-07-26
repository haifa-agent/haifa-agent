package io.haifa.agent.sandbox.localnative;

import io.haifa.agent.common.io.SecureFilePermissions;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.sandbox.api.NetworkPolicy;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.api.SandboxWorkspaceAccess;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class MacSeatbeltAdapter implements LocalNativeAdapter {
    @Override
    public String adapterId() {
        return "mac-seatbelt";
    }

    @Override
    public void preflight(LocalNativeSandboxConfiguration configuration) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if ((!os.contains("mac") && !os.contains("darwin"))
                || !Files.isRegularFile(configuration.seatbeltExecutable())
                || !Files.isExecutable(configuration.seatbeltExecutable())) {
            throw unavailable();
        }
        LocalNativeProcessSupport.runProbe(List.of(
                configuration.seatbeltExecutable().toString(), "-p", "(version 1) (allow default)", "/usr/bin/true"));
    }

    @Override
    public LocalNativeLaunchPlan prepare(
            LocalNativeSandboxConfiguration configuration,
            SandboxProfile profile,
            Path workspaceRoot,
            Path workingDirectory,
            Path controlDirectory,
            List<LocalNativePathGrant> additionalPaths,
            ExecutionCommand command) {
        Path policyFile = controlDirectory.resolve("seatbelt.sb");
        try {
            Files.writeString(
                    policyFile,
                    policy(configuration, profile, workspaceRoot, controlDirectory, additionalPaths),
                    StandardCharsets.UTF_8);
            SecureFilePermissions.secureFile(policyFile);
        } catch (IOException exception) {
            throw new LocalNativeSandboxException(
                    "SANDBOX_PROVISION_FAILED", "local-native sandbox policy could not be created");
        }
        List<String> argv = new ArrayList<>();
        argv.add(configuration.seatbeltExecutable().toString());
        argv.add("-f");
        argv.add(policyFile.toString());
        argv.addAll(command(configuration, command));
        return new LocalNativeLaunchPlan(argv);
    }

    static String policy(
            LocalNativeSandboxConfiguration configuration,
            SandboxProfile profile,
            Path workspaceRoot,
            Path controlDirectory,
            List<LocalNativePathGrant> additionalPaths) {
        StringBuilder policy = new StringBuilder();
        policy.append("(version 1)\n");
        policy.append("(deny default)\n");
        policy.append("(allow process*)\n");
        policy.append("(allow signal (target self))\n");
        policy.append("(allow sysctl-read)\n");
        policy.append("(allow mach-lookup)\n");
        policy.append("(allow file-read-metadata)\n");
        for (String systemPath : List.of("/System", "/usr", "/bin", "/sbin", "/Library", "/private/etc")) {
            policy.append("(allow file-read* (subpath \"")
                    .append(escape(Path.of(systemPath).toString()))
                    .append("\"))\n");
        }
        allowPath(policy, workspaceRoot, true);
        if (profile.filesystemPolicy().workspaceAccess() == SandboxWorkspaceAccess.READ_WRITE) {
            allowPath(policy, workspaceRoot, false);
        }
        allowPath(policy, controlDirectory, true);
        allowPath(policy, controlDirectory, false);
        for (LocalNativePathGrant grant : additionalPaths) {
            allowPath(policy, grant.path(), true);
            if (!grant.readOnly()) allowPath(policy, grant.path(), false);
        }
        if (profile.networkPolicy() == NetworkPolicy.ALLOW) {
            policy.append("(allow network*)\n");
        }
        for (Path sensitive : configuration.sensitivePaths()) {
            policy.append("(deny file-read* file-write* (subpath \"")
                    .append(escape(sensitive.toString()))
                    .append("\"))\n");
        }
        return policy.toString();
    }

    private static void allowPath(StringBuilder policy, Path path, boolean read) {
        policy.append("(allow ")
                .append(read ? "file-read*" : "file-write*")
                .append(" (subpath \"")
                .append(escape(path.toString()))
                .append("\"))\n");
    }

    private static List<String> command(LocalNativeSandboxConfiguration configuration, ExecutionCommand command) {
        if (command.mode() == ExecutionCommandMode.DIRECT) return command.argv();
        List<String> result = new ArrayList<>(configuration.shellInvocationPrefix());
        result.add(command.shellCommand());
        return List.copyOf(result);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static LocalNativeSandboxException unavailable() {
        return new LocalNativeSandboxException("SANDBOX_ADAPTER_UNAVAILABLE", "macOS Seatbelt is unavailable");
    }
}
