package io.haifa.agent.model.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ModelCoreArchitectureTest {
    @Test
    void modelCoreDoesNotDependOnProviderAdaptersOrFrameworks() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.model.openai..",
                        "com.fasterxml.jackson..",
                        "org.springframework..",
                        "com.openai..",
                        "com.deepseek..")
                .check(new ClassFileImporter().importPackages("io.haifa.agent.model.core"));
    }
}
