package io.haifa.agent.model.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class ModelCoreArchitectureTest {
    @Test
    void modelCoreDoesNotDependOnProviderAdaptersOrFrameworks() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.model.openai..",
                        "org.springframework..",
                        "com.openai..",
                        "com.deepseek..",
                        "io.haifa.agent.personalassistant..",
                        "io.haifa.agent.coding..")
                .check(new ClassFileImporter().importPackages("io.haifa.agent.model.core"));
    }
}
