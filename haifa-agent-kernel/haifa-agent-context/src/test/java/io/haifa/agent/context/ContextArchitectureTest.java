package io.haifa.agent.context;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ContextArchitectureTest {
    @Test
    void contextIsProviderFrameworkAndRuntimeIndependent() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "com.fasterxml.jackson..",
                        "io.haifa.agent.runtime..",
                        "io.haifa.agent.model.openai..",
                        "com.openai..")
                .check(new ClassFileImporter().importPackages("io.haifa.agent.context"));
    }
}
