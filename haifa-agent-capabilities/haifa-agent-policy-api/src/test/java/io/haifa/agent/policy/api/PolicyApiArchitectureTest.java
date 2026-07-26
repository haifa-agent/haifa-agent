package io.haifa.agent.policy.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class PolicyApiArchitectureTest {
    @Test
    void apiIsFrameworkProviderRuntimeToolAndExecutionIndependent() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "com.fasterxml.jackson..",
                        "org.apache.ibatis..",
                        "java.sql..",
                        "io.haifa.agent.runtime..",
                        "io.haifa.agent.tool..",
                        "io.haifa.agent.execution..",
                        "io.haifa.agent.store..",
                        "io.haifa.agent.application..")
                .check(new ClassFileImporter().importPackages("io.haifa.agent.policy.api"));
    }
}
