package io.haifa.agent.sandbox.host;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Trusted host configuration that compiles shell text into platform process argv. */
public record HostShell(String displayName, List<String> invocationPrefix) {
    public HostShell {
        displayName = requireText(displayName, "displayName");
        invocationPrefix = List.copyOf(Objects.requireNonNull(invocationPrefix, "invocationPrefix must not be null"));
        if (invocationPrefix.isEmpty()) throw new IllegalArgumentException("invocationPrefix must not be empty");
        if (invocationPrefix.stream().anyMatch(value -> value == null || value.isBlank() || value.indexOf('\0') >= 0)) {
            throw new IllegalArgumentException("invocationPrefix contains an invalid argument");
        }
    }

    public static HostShell auto() {
        if (isWindows()) {
            return new HostShell(
                    "PowerShell", List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command"));
        }
        Path bash = Path.of("/bin/bash");
        if (Files.isExecutable(bash)) return new HostShell("Bash", List.of(bash.toString(), "-lc"));
        Path shell = Path.of("/bin/sh");
        if (Files.isExecutable(shell)) return new HostShell("POSIX shell", List.of(shell.toString(), "-c"));
        throw new IllegalStateException("no supported host shell is available");
    }

    public static HostShell bash(Path executable) {
        return configured("Bash", executable, List.of("-lc"));
    }

    public static HostShell powerShell(Path executable) {
        return configured("PowerShell", executable, List.of("-NoLogo", "-NoProfile", "-NonInteractive", "-Command"));
    }

    public List<String> launch(String command) {
        String value = requireText(command, "command");
        var launch = new ArrayList<>(invocationPrefix);
        launch.add(value);
        return List.copyOf(launch);
    }

    private static HostShell configured(String displayName, Path executable, List<String> arguments) {
        Path path = Objects.requireNonNull(executable, "executable must not be null")
                .toAbsolutePath()
                .normalize();
        if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
            throw new IllegalArgumentException("configured shell is not an executable file");
        }
        var prefix = new ArrayList<String>();
        prefix.add(path.toString());
        prefix.addAll(arguments);
        return new HostShell(displayName, prefix);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
