package io.haifa.agent.sandbox.localnative;

import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionScratchSpaceSpec;
import io.haifa.agent.sandbox.api.NetworkPolicy;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.api.SandboxWorkspaceAccess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class LinuxBubblewrapAdapter implements LocalNativeAdapter {
    private static final Path SANDBOX_WORKSPACE = Path.of("/workspace");
    private static final List<Path> SYSTEM_PATHS = List.of(
            Path.of("/usr"), Path.of("/bin"), Path.of("/sbin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc"));

    @Override
    public String adapterId() {
        return "linux-bubblewrap";
    }

    @Override
    public void preflight(LocalNativeSandboxConfiguration configuration) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux")
                || !Files.isRegularFile(configuration.bubblewrapExecutable())
                || !Files.isExecutable(configuration.bubblewrapExecutable())) {
            throw unavailable();
        }
        LocalNativeProcessSupport.runProbe(List.of(
                configuration.bubblewrapExecutable().toString(),
                "--unshare-all",
                "--die-with-parent",
                "--ro-bind",
                "/",
                "/",
                "--",
                "/bin/true"));
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
        List<String> argv = new ArrayList<>();
        argv.add(configuration.bubblewrapExecutable().toString());
        argv.add("--die-with-parent");
        argv.add("--new-session");
        argv.add("--unshare-all");
        if (profile.networkPolicy() == NetworkPolicy.ALLOW) argv.add("--share-net");

        for (Path systemPath : SYSTEM_PATHS) {
            if (Files.exists(systemPath)) bind(argv, "--ro-bind", systemPath, systemPath);
        }
        argv.add("--proc");
        argv.add("/proc");
        argv.add("--dev");
        argv.add("/dev");
        argv.add("--tmpfs");
        argv.add("/tmp");
        argv.add("--dir");
        argv.add("/tmp/haifa-home");
        argv.add("--setenv");
        argv.add("HOME");
        argv.add("/tmp/haifa-home");
        argv.add("--setenv");
        argv.add("TMP");
        argv.add("/tmp");
        argv.add("--setenv");
        argv.add("TMPDIR");
        argv.add("/tmp");
        argv.add("--setenv");
        argv.add("TEMP");
        argv.add("/tmp");
        for (var binding : scratchSpace.childBindings()) {
            addLogicalScratchDirectories(argv, binding.relativeDirectory());
            argv.add("--setenv");
            argv.add(binding.environmentName());
            argv.add("/tmp/" + binding.relativeDirectory());
        }
        for (String environmentName : scratchSpace.rootEnvironmentNames()) {
            argv.add("--setenv");
            argv.add(environmentName);
            argv.add("/tmp");
        }

        String workspaceBind = profile.filesystemPolicy().workspaceAccess() == SandboxWorkspaceAccess.READ_ONLY
                ? "--ro-bind"
                : "--bind";
        bind(argv, workspaceBind, workspaceRoot, SANDBOX_WORKSPACE);

        Set<Path> createdParents = new LinkedHashSet<>();
        for (LocalNativePathGrant grant : additionalPaths) {
            addParents(argv, grant.path(), createdParents);
            bind(argv, grant.readOnly() ? "--ro-bind" : "--bind", grant.path(), grant.path());
        }

        Path relative = workspaceRoot.relativize(workingDirectory);
        String sandboxCwd = relative.getNameCount() == 0
                ? SANDBOX_WORKSPACE.toString()
                : SANDBOX_WORKSPACE.resolve(relative).toString().replace('\\', '/');
        argv.add("--chdir");
        argv.add(sandboxCwd);
        argv.add("--");
        if (command.mode() == ExecutionCommandMode.DIRECT) {
            argv.addAll(command.argv());
        } else {
            argv.addAll(configuration.shellInvocationPrefix());
            argv.add(command.shellCommand());
        }
        return new LocalNativeLaunchPlan(argv);
    }

    private static void addParents(List<String> argv, Path path, Set<Path> created) {
        if (SYSTEM_PATHS.stream().anyMatch(path::startsWith)) return;
        List<Path> parents = new ArrayList<>();
        Path current = path.getParent();
        while (current != null && current.getParent() != null) {
            parents.add(current);
            current = current.getParent();
        }
        java.util.Collections.reverse(parents);
        for (Path parent : parents) {
            if (created.add(parent)) {
                argv.add("--dir");
                argv.add(parent.toString());
            }
        }
    }

    private static void addLogicalScratchDirectories(List<String> argv, String relativeDirectory) {
        Path current = Path.of("/tmp");
        for (String segment : relativeDirectory.split("/")) {
            current = current.resolve(segment);
            argv.add("--dir");
            argv.add(current.toString());
        }
    }

    private static void bind(List<String> argv, String option, Path source, Path target) {
        argv.add(option);
        argv.add(source.toString());
        argv.add(target.toString());
    }

    private static LocalNativeSandboxException unavailable() {
        return new LocalNativeSandboxException("SANDBOX_ADAPTER_UNAVAILABLE", "Linux bubblewrap is unavailable");
    }
}
