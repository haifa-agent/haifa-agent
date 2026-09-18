package io.haifa.agent.execution.host.tool;

import io.haifa.agent.execution.core.tool.ExecutionOperatingSystem;
import io.haifa.agent.execution.core.tool.ScriptRuntimeAdapter;
import io.haifa.agent.execution.core.tool.ScriptRuntimeResolver;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Resolves the current host OS and its trusted, application-configured script runtimes. */
public final class HostScriptRuntimeResolver {
    private HostScriptRuntimeResolver() {}

    public static ScriptRuntimeResolver currentHost(Optional<Path> python, Optional<Path> powerShell) {
        ExecutionOperatingSystem operatingSystem = currentOperatingSystem();
        List<ScriptRuntimeAdapter> adapters = new ArrayList<>();
        if (operatingSystem == ExecutionOperatingSystem.WINDOWS) {
            Path executable = powerShell.orElse(Path.of("powershell.exe"));
            adapters.add(ScriptRuntimeResolver.powerShell(executable.toString()));
        } else {
            adapters.add(ScriptRuntimeResolver.bash("/bin/bash"));
            powerShell.ifPresent(path -> adapters.add(ScriptRuntimeResolver.powerShell(path.toString())));
        }
        python.ifPresent(path -> adapters.add(ScriptRuntimeResolver.python(path.toString())));
        return new ScriptRuntimeResolver(operatingSystem, adapters);
    }

    public static ExecutionOperatingSystem currentOperatingSystem() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) return ExecutionOperatingSystem.WINDOWS;
        if (name.contains("mac") || name.contains("darwin")) return ExecutionOperatingSystem.MACOS;
        return ExecutionOperatingSystem.LINUX;
    }
}
