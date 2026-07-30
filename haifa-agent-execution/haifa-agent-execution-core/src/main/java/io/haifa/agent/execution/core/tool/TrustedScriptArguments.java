package io.haifa.agent.execution.core.tool;

import io.haifa.agent.project.path.ProjectPath;
import java.util.List;
import java.util.Objects;

public record TrustedScriptArguments(
        List<String> argv, String workingDirectory, List<ProjectPath> workspaceInputPaths) {
    public TrustedScriptArguments {
        argv = List.copyOf(Objects.requireNonNull(argv, "argv must not be null"));
        if (argv.size() > 16) throw new IllegalArgumentException("argv exceeds the trusted script limit");
        for (String item : argv) {
            if (item == null || item.length() > 1024 || item.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("argv contains an invalid item");
            }
        }
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory must not be null")
                .trim()
                .replace('\\', '/');
        if (!workingDirectory.equals(".")) {
            throw new IllegalArgumentException("trusted script working directory must be the Workspace root");
        }
        workspaceInputPaths =
                List.copyOf(Objects.requireNonNull(workspaceInputPaths, "workspaceInputPaths must not be null"));
        if (workspaceInputPaths.size() > 16) {
            throw new IllegalArgumentException("workspaceInputPaths exceeds the trusted script limit");
        }
    }

    public static TrustedScriptArguments atWorkspaceRoot(List<String> argv) {
        return new TrustedScriptArguments(argv, ".", List.of());
    }

    public static TrustedScriptArguments atWorkspaceRootWithInputs(
            List<String> argv, List<ProjectPath> workspaceInputPaths) {
        return new TrustedScriptArguments(argv, ".", workspaceInputPaths);
    }
}
