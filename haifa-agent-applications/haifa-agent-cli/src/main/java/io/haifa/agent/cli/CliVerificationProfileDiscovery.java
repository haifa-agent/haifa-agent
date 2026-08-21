package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.verification.CodingVerificationCandidate;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationCost;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationProfile;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationProfileResolver;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationSource;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationTrigger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Finds a small set of safe build-entry candidates from root markers without parsing task intent or shell output. */
final class CliVerificationProfileDiscovery {
    private CliVerificationProfileDiscovery() {}

    static CodingVerificationProfile discover(Path workspaceRoot, String operatingSystem) {
        Path root = Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null")
                .toAbsolutePath()
                .normalize();
        boolean windows = Objects.requireNonNull(operatingSystem, "operatingSystem must not be null")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        List<CodingVerificationCandidate> build = new ArrayList<>();
        if (exists(root, "pom.xml")) {
            String command = exists(root, windows ? "mvnw.cmd" : "mvnw")
                    ? windows ? ".\\mvnw.cmd test" : "./mvnw test"
                    : "mvn test";
            add(build, command, "pom.xml");
        }
        if (exists(root, "build.gradle") || exists(root, "build.gradle.kts")) {
            String command = exists(root, windows ? "gradlew.bat" : "gradlew")
                    ? windows ? ".\\gradlew.bat test" : "./gradlew test"
                    : "gradle test";
            add(build, command, exists(root, "build.gradle.kts") ? "build.gradle.kts" : "build.gradle");
        }
        if (exists(root, "pyproject.toml") || exists(root, "pytest.ini")) {
            add(build, "python -m pytest", exists(root, "pyproject.toml") ? "pyproject.toml" : "pytest.ini");
        }
        if (exists(root, "package.json")) add(build, "npm test", "package.json");
        if (exists(root, "Cargo.toml")) add(build, "cargo test", "Cargo.toml");
        if (exists(root, "go.mod")) add(build, "go test ./...", "go.mod");
        try (var entries = Files.list(root)) {
            entries.filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".sln") || name.endsWith(".csproj");
                    })
                    .findFirst()
                    .ifPresent(
                            path -> add(build, "dotnet test", path.getFileName().toString()));
        } catch (java.io.IOException ignored) {
            // An unreadable root yields no .NET candidate; execution remains model-visible and policy governed.
        }
        return new CodingVerificationProfileResolver().resolve(List.of(), build, List.of(), List.of());
    }

    private static void add(List<CodingVerificationCandidate> target, String command, String sourceReference) {
        target.add(new CodingVerificationCandidate(
                command,
                CodingVerificationCost.HIGH,
                Duration.ofMinutes(10),
                CodingVerificationTrigger.FINAL_GATE,
                CodingVerificationSource.BUILD_CONFIGURATION,
                sourceReference));
    }

    private static boolean exists(Path root, String name) {
        return Files.isRegularFile(root.resolve(name));
    }
}
