package io.haifa.agent.mcp.config;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record StdioDefinition(
        String executable,
        List<String> fixedArguments,
        String logicalWorkingDirectory,
        Set<String> environmentNameAllowlist,
        Duration startupTimeout,
        Duration requestTimeout,
        Duration idleTimeout,
        Duration shutdownTimeout,
        int maxFrameBytes,
        int maxStderrBytes)
        implements McpTransportDefinition {
    public StdioDefinition {
        executable = text(executable, "executable");
        if (executable.contains("/") || executable.contains("\\") || executable.contains(":")) {
            throw new IllegalArgumentException("stdio executable must be an approved logical name");
        }
        fixedArguments = List.copyOf(Objects.requireNonNull(fixedArguments, "fixedArguments"));
        fixedArguments.forEach(value -> text(value, "fixed argument"));
        logicalWorkingDirectory = text(logicalWorkingDirectory, "logicalWorkingDirectory");
        if (logicalWorkingDirectory.matches("^[A-Za-z]:.*")
                || logicalWorkingDirectory.startsWith("/")
                || logicalWorkingDirectory.startsWith("\\")) {
            throw new IllegalArgumentException("stdio working directory must be logical, not a host path");
        }
        environmentNameAllowlist =
                Set.copyOf(Objects.requireNonNull(environmentNameAllowlist, "environmentNameAllowlist"));
        environmentNameAllowlist.forEach(name -> {
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("invalid stdio environment name");
            }
        });
        startupTimeout = positive(startupTimeout, "startupTimeout");
        requestTimeout = positive(requestTimeout, "requestTimeout");
        idleTimeout = positive(idleTimeout, "idleTimeout");
        shutdownTimeout = positive(shutdownTimeout, "shutdownTimeout");
        if (maxFrameBytes < 1024
                || maxFrameBytes > 16 * 1024 * 1024
                || maxStderrBytes < 0
                || maxStderrBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("stdio output budget is out of range");
        }
    }

    @Override
    public String identityReference() {
        return "stdio:" + executable + ":" + String.join("\u001f", fixedArguments);
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
