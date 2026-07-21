package io.haifa.agent.memory.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class MemoryApiArchitectureTest {
    @Test
    void apiIsFrameworkProviderAndRuntimeIndependent() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "com.fasterxml.jackson..",
                        "io.haifa.agent.runtime..",
                        "io.haifa.agent.context..",
                        "io.haifa.agent.model..")
                .check(new ClassFileImporter().importPackages("io.haifa.agent.memory.api"));
    }
}
