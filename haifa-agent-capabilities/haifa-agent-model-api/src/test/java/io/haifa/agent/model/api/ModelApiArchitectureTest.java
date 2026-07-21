package io.haifa.agent.model.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ModelApiArchitectureTest {
    @Test
    void apiDoesNotDependOnFrameworkOrProviderProtocols() {
        var classes = new ClassFileImporter().importPackages("io.haifa.agent.model.api");
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.fasterxml.jackson..", "org.springframework..", "com.openai..", "com.deepseek..")
                .check(classes);
    }
}
