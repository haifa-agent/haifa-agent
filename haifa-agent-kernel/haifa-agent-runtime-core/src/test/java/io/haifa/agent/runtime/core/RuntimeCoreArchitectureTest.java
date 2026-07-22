package io.haifa.agent.runtime.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
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
                        "io.haifa.agent.tool.core..",
                        "io.haifa.agent.model.openai..",
                        "com.openai..",
                        "dev.langchain4j..",
                        "org.testcontainers..",
                        "io.haifa.agent.admin..")
                .check(new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("io.haifa.agent.runtime.core"));
    }

    @Test
    void runtimeCoreDoesNotLaunchHostProcessesDirectly() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.ProcessBuilder")
                .check(new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("io.haifa.agent.runtime.core"));
    }
}
