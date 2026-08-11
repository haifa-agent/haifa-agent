package io.haifa.agent.personalassistant.application;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class PersonalAssistantArchitectureTest {
    private final com.tngtech.archunit.core.domain.JavaClasses classes =
            new ClassFileImporter().importPackages("io.haifa.agent.personalassistant.application");

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
