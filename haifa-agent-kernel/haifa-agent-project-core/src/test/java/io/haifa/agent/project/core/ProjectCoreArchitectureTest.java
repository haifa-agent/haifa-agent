package io.haifa.agent.project.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class ProjectCoreArchitectureTest {
    @Test
    void implementationsUseCorePackagesAndRemainDeploymentIndependent() {
        var classes = productionClasses();
        assertThat(classes.stream().map(javaClass -> javaClass.getPackageName()))
                .allMatch(packageName -> packageName.startsWith("io.haifa.agent.project.core."));
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "java.nio.file..",
                        "io.haifa.agent.project.hostworkspace..",
                        "io.haifa.agent.runtime..",
                        "io.haifa.agent.execution..",
                        "io.haifa.agent.sandbox..",
                        "org.springframework..",
                        "jakarta.persistence..",
                        "com.fasterxml.jackson..")
                .check(classes);
        noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.ProcessBuilder")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.Runtime")
                .check(classes);
    }

    private static com.tngtech.archunit.core.domain.JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent.project.core");
    }
}
