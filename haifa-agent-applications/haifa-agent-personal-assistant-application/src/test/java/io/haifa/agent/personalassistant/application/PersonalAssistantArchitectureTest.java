package io.haifa.agent.personalassistant.application;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class PersonalAssistantArchitectureTest {
    private static final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.haifa.agent.personalassistant.application");

    @Test
    void applicationRemainsPureJavaAndDoesNotDependOnServerOrStoreImplementations() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta..",
                        "io.haifa.agent.personalassistant.server..",
                        "io.haifa.agent.store.sqlite..",
                        "io.haifa.agent.testing..")
                .check(classes);
    }

    @Test
    void personalExecutionDoesNotDependOnCodingWorkspaceAccess() {
        noClasses()
                .that()
                .resideInAnyPackage("..application.execution..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.haifa.agent.application.project.workspace..")
                .check(classes);
    }

    @Test
    void missionRemainsProductLocalWithoutDeferredDomainTypes() {
        noClasses()
                .that()
                .resideInAnyPackage("..application.mission..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.fasterxml.jackson..", "java.sql..", "org.springframework..")
                .check(classes);
        assertThat(classes.stream()
                        .map(value -> value.getSimpleName())
                        .filter(name -> name.contains("ResearchAgent")
                                || name.contains("ResearchRun")
                                || name.contains("ResearchBackend")
                                || name.contains("Verifier")
                                || name.contains("Repair")
                                || name.contains("MissionInput")
                                || name.contains("Pause"))
                        .toList())
                .isEmpty();
    }
}
