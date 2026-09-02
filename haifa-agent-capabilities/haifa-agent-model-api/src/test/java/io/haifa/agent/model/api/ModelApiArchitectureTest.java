package io.haifa.agent.model.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class ModelApiArchitectureTest {
    @Test
    void apiDoesNotDependOnFrameworkOrProviderProtocols() {
        var classes = new ClassFileImporter().importPackages("io.haifa.agent.model.api");
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.fasterxml.jackson..",
                        "org.springframework..",
                        "com.openai..",
                        "com.deepseek..",
                        "io.haifa.agent.personalassistant..",
                        "io.haifa.agent.coding..",
                        "io.haifa.agent.model.openai..",
                        "io.haifa.agent.model.anthropic..",
                        "io.haifa.agent.model.gemini..")
                .check(classes);
    }
}
