package io.haifa.agent.policy.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class PolicyCoreArchitectureTest {
    @Test
    void coreDoesNotDependOnRuntimeToolExecutionStoresProductsOrFrameworks() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.runtime..",
                        "io.haifa.agent.tool..",
                        "io.haifa.agent.execution..",
                        "io.haifa.agent.store..",
                        "io.haifa.agent.application..",
                        "org.springframework..",
                        "jakarta.persistence..",
                        "com.fasterxml.jackson..",
                        "org.apache.ibatis..",
                        "java.sql..")
                .check(new ClassFileImporter().importPackages("io.haifa.agent.policy.core"));
    }
}
