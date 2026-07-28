package io.haifa.agent.personalassistant.application;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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
}
