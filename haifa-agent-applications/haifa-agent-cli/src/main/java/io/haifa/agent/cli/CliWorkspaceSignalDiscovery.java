package io.haifa.agent.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Finds a small set of safe root project markers without parsing task intent or shell output. */
final class CliWorkspaceSignalDiscovery {
    private static final int MAXIMUM_DOTNET_SIGNALS = 8;
    private static final List<String> FILE_SIGNALS = List.of(
            "pom.xml",
            "mvnw",
            "mvnw.cmd",
            "build.gradle",
            "build.gradle.kts",
            "gradlew",
            "gradlew.bat",
            "settings.gradle",
            "settings.gradle.kts",
            "package.json",
            "package-lock.json",
            "pnpm-lock.yaml",
            "yarn.lock",
            "pyproject.toml",
            "pytest.ini",
            "tox.ini",
            "requirements.txt",
            "Cargo.toml",
            "Cargo.lock",
            "go.mod",
            "go.work",
            "CMakeLists.txt",
            "Makefile",
            "meson.build",
            "verify.ps1",
            "verify.sh");
    private static final List<String> DIRECTORY_SIGNALS = List.of("src/test", "test", "tests", "__tests__");

    private CliWorkspaceSignalDiscovery() {}

    static DiscoveryResult discoverWithSignals(Path workspaceRoot, String operatingSystem) {
        Path root = Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null")
                .toAbsolutePath()
                .normalize();
        Objects.requireNonNull(operatingSystem, "operatingSystem must not be null");
        SignalScan scan = scan(root);
        return new DiscoveryResult(scan.projectSignals(), scan.diagnostics());
    }

    private static SignalScan scan(Path root) {
        List<String> signals = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        FILE_SIGNALS.forEach(name -> inspect(root, name, false, signals, diagnostics));
        DIRECTORY_SIGNALS.forEach(name -> inspect(root, name, true, signals, diagnostics));
        try (var entries = Files.list(root)) {
            List<Path> dotnet = entries.filter(
                            path -> isDotnetSignal(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path path : dotnet.stream().limit(MAXIMUM_DOTNET_SIGNALS).toList()) {
                String name = path.getFileName().toString();
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                    signals.add(name);
                } else {
                    diagnostics.add("dotnet-root-signal:INVALID");
                }
            }
            if (dotnet.size() > MAXIMUM_DOTNET_SIGNALS) diagnostics.add("dotnet-root-signals:TRUNCATED");
        } catch (java.io.IOException | SecurityException ignored) {
            diagnostics.add("workspace-root-listing:UNAVAILABLE");
        }
        signals.sort(String::compareTo);
        return new SignalScan(signals, diagnostics);
    }

    private static void inspect(
            Path root, String name, boolean directory, List<String> signals, List<String> diagnostics) {
        Path candidate = root.resolve(name);
        final BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException ignored) {
            return;
        } catch (IOException | SecurityException ignored) {
            diagnostics.add(name + ":UNKNOWN");
            return;
        }
        if (attributes.isSymbolicLink()) {
            diagnostics.add(name + ":INVALID");
            return;
        }
        boolean expected = directory ? attributes.isDirectory() : attributes.isRegularFile();
        if (expected) signals.add(name);
        else diagnostics.add(name + ":INVALID");
    }

    private static boolean isDotnetSignal(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".sln") || normalized.endsWith(".csproj");
    }

    record DiscoveryResult(List<String> projectSignals, List<String> diagnostics) {
        DiscoveryResult {
            projectSignals = List.copyOf(projectSignals);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private record SignalScan(List<String> projectSignals, List<String> diagnostics) {
        private SignalScan {
            projectSignals = List.copyOf(projectSignals);
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
