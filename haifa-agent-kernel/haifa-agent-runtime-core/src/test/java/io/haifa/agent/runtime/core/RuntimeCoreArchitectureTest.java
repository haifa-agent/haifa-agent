package io.haifa.agent.runtime.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class RuntimeCoreArchitectureTest {
    @Test
    void runtimeCoreIsFrameworkProviderPersistenceAndProductIndependent() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "org.springframework.ai..",
                        "com.alibaba.cloud.ai..",
                        "jakarta.persistence..",
                        "io.haifa.agent.product..",
                        "io.haifa.agent.integration..",
                        "io.haifa.agent.model.openai..",
                        "com.openai..",
                        "dev.langchain4j..",
                        "org.testcontainers..",
                        "io.haifa.agent.admin..")
                .check(new ClassFileImporter().importPackages("io.haifa.agent.runtime.core"));
    }

    @Test
    void runtimeCoreDoesNotLaunchHostProcessesDirectly() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.ProcessBuilder")
                .check(new ClassFileImporter().importPackages("io.haifa.agent.runtime.core"));
    }
}
