package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.delivery.CodingValidationScope;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationCandidate;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationCost;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationProfile;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationProfileResolver;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationSource;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationTrigger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Finds a small set of safe build-entry candidates from root markers without parsing task intent or shell output. */
final class CliVerificationProfileDiscovery {
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
            "meson.build");
    private static final List<String> DIRECTORY_SIGNALS = List.of("src/test", "test", "tests", "__tests__");

    private CliVerificationProfileDiscovery() {}

    static DiscoveryResult discoverWithSignals(Path workspaceRoot, String operatingSystem) {
        Path root = Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null")
                .toAbsolutePath()
                .normalize();
        boolean windows = Objects.requireNonNull(operatingSystem, "operatingSystem must not be null")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        SignalScan scan = scan(root);
        List<String> signals = scan.projectSignals();
        List<CodingVerificationCandidate> build = new ArrayList<>();
        if (signals.contains("pom.xml")) {
            String command = signals.contains(windows ? "mvnw.cmd" : "mvnw")
                    ? windows ? ".\\mvnw.cmd test" : "./mvnw test"
                    : "mvn test";
            add(build, command, "pom.xml");
        }
        if (signals.contains("build.gradle") || signals.contains("build.gradle.kts")) {
            String command = signals.contains(windows ? "gradlew.bat" : "gradlew")
                    ? windows ? ".\\gradlew.bat test" : "./gradlew test"
                    : "gradle test";
            add(build, command, signals.contains("build.gradle.kts") ? "build.gradle.kts" : "build.gradle");
        }
        if (signals.contains("pyproject.toml") || signals.contains("pytest.ini")) {
            add(build, "python -m pytest", signals.contains("pyproject.toml") ? "pyproject.toml" : "pytest.ini");
        }
        if (signals.contains("package.json")) add(build, "npm test", "package.json");
        if (signals.contains("Cargo.toml")) add(build, "cargo test", "Cargo.toml");
        if (signals.contains("go.mod")) add(build, "go test ./...", "go.mod");
        signals.stream()
                .filter(CliVerificationProfileDiscovery::isDotnetSignal)
                .findFirst()
                .ifPresent(name -> add(build, "dotnet test", name));
        CodingVerificationProfile profile =
                new CodingVerificationProfileResolver().resolve(List.of(), build, List.of(), List.of());
        return new DiscoveryResult(profile, signals, scan.diagnostics());
    }

    private static void add(List<CodingVerificationCandidate> target, String command, String sourceReference) {
        target.add(new CodingVerificationCandidate(
                command,
                CodingVerificationCost.HIGH,
                Duration.ofMinutes(10),
                CodingVerificationTrigger.FINAL_GATE,
                CodingVerificationSource.BUILD_CONFIGURATION,
                sourceReference,
                CodingValidationScope.FULL));
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

    record DiscoveryResult(CodingVerificationProfile profile, List<String> projectSignals, List<String> diagnostics) {
        DiscoveryResult {
            profile = Objects.requireNonNull(profile, "profile must not be null");
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
