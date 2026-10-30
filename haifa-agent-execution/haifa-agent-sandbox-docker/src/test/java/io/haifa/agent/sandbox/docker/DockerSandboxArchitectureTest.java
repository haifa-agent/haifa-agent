package io.haifa.agent.sandbox.docker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class DockerSandboxArchitectureTest {
    @Test
    void dockerProviderStaysOutsideProductAndProcessOwnership() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent.sandbox.docker");

        noClasses()
                .that()
                .resideInAPackage("io.haifa.agent.sandbox.docker..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.cli..",
                        "io.haifa.agent.personalassistant..",
                        "io.haifa.agent.sdk.starter..",
                        "org.springframework..")
                .check(classes);

        noClasses()
                .that()
                .resideInAPackage("io.haifa.agent.sandbox.docker..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.ProcessBuilder")
                .check(classes);

        noClasses()
                .that()
                .resideInAPackage("io.haifa.agent.sandbox.docker..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.Runtime")
                .check(classes);
    }
}
