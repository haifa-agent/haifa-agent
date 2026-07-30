package io.haifa.agent.execution.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ExecutionArchitectureTest {
    @Test
    void onlyConcreteLocalSandboxProvidersCreateProcesses() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent");
        noClasses()
                .that()
                .resideOutsideOfPackages("io.haifa.agent.sandbox.host..", "io.haifa.agent.sandbox.localnative..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.ProcessBuilder")
                .check(classes);
        noClasses()
                .that()
                .resideInAPackage("io.haifa.agent.execution.core..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.policy.core..",
                        "io.haifa.agent.runtime.core..",
                        "io.haifa.agent.tool.core..",
                        "io.haifa.agent.personalassistant..")
                .check(classes);
        noClasses()
                .that()
                .resideOutsideOfPackages("io.haifa.agent.sandbox.host..", "io.haifa.agent.sandbox.localnative..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.Runtime")
                .check(classes);
    }

    @Test
    void publicTrustRuntimeSourcesRemainProductNeutral() throws IOException {
        Path root = repositoryRoot();
        List<Path> sourceRoots = List.of(
                root.resolve("haifa-agent-capabilities/haifa-agent-skill-api/src"),
                root.resolve("haifa-agent-capabilities/haifa-agent-skill-core/src"),
                root.resolve("haifa-agent-kernel/haifa-agent-runtime-core/src"),
                root.resolve("haifa-agent-execution/haifa-agent-execution-core/src"));
        List<String> forbidden =
                List.of("personalassistant", "finance", "stocks", "dcf", "excel-author", "hermes-agent");

        for (Path sourceRoot : sourceRoots) {
            try (var files = Files.walk(sourceRoot)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !path.getFileName().toString().endsWith("ArchitectureTest.java"))
                        .toList()) {
                    String source = Files.readString(file).toLowerCase(Locale.ROOT);
                    for (String identifier : forbidden) {
                        org.assertj.core.api.Assertions.assertThat(source)
                                .as("%s must not reference product identifier %s", root.relativize(file), identifier)
                                .doesNotContain(identifier);
                    }
                }
            }
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("haifa-agent-execution"))
                    && Files.isDirectory(current.resolve("haifa-agent-kernel"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Haifa Agent repository root was not found");
    }
}
