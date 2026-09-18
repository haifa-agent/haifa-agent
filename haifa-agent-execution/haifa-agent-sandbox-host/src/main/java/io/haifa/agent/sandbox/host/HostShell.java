package io.haifa.agent.sandbox.host;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
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
        launch.add(usesPowerShellCommand() ? wrapPowerShellCommand(value) : value);
        return List.copyOf(launch);
    }

    private boolean usesPowerShellCommand() {
        return displayName.equals("PowerShell")
                && invocationPrefix.get(invocationPrefix.size() - 1).equalsIgnoreCase("-Command");
    }

    private static String wrapPowerShellCommand(String command) {
        String encodedCommand = Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_8));
        return "$__haifaUtf8 = [Text.UTF8Encoding]::new($false)\n"
                + "[Console]::InputEncoding = $__haifaUtf8\n"
                + "[Console]::OutputEncoding = $__haifaUtf8\n"
                + "$OutputEncoding = $__haifaUtf8\n"
                + "$ErrorActionPreference = 'Stop'\n"
                + "$global:LASTEXITCODE = $null\n"
                + "$__haifaCommandSource = [Text.Encoding]::UTF8.GetString("
                + "[Convert]::FromBase64String('" + encodedCommand + "'))\n"
                + "try {\n"
                + "  & ([ScriptBlock]::Create($__haifaCommandSource))\n"
                + "  $__haifaPowerShellSucceeded = $?\n"
                + "  $__haifaNativeExitCode = $LASTEXITCODE\n"
                + "  if ($null -ne $__haifaNativeExitCode -and $__haifaNativeExitCode -ne 0) {\n"
                + "    exit $__haifaNativeExitCode\n"
                + "  }\n"
                + "  if (-not $__haifaPowerShellSucceeded) { exit 1 }\n"
                + "  exit 0\n"
                + "} catch {\n"
                + "  [Console]::Error.WriteLine($_.Exception.Message)\n"
                + "  exit 1\n"
                + "}\n";
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
