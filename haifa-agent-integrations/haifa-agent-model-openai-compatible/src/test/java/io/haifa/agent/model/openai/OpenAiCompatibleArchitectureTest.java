package io.haifa.agent.model.openai;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleArchitectureTest {
    @Test
    void integrationDoesNotDependOnProductPackages() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.haifa.agent.personalassistant..", "io.haifa.agent.coding..")
                .check(new ClassFileImporter().importPackages("io.haifa.agent.model.openai"));
    }
}
