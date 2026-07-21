package io.haifa.agent.memory.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class MemoryCoreArchitectureTest {
    @Test
    void memoryCoreDoesNotDependOnRuntimeContextProvidersOrFrameworks() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.runtime..",
                        "io.haifa.agent.context..",
                        "io.haifa.agent.model..",
                        "org.springframework..",
                        "jakarta.persistence..",
                        "com.fasterxml.jackson..")
                .check(new ClassFileImporter().importPackages("io.haifa.agent.memory.core"));
    }
}
