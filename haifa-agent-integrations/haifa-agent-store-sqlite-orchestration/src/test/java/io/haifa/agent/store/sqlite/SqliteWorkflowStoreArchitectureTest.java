package io.haifa.agent.store.sqlite;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class SqliteWorkflowStoreArchitectureTest {
    @Test
    void workflowStoreDoesNotDependOnGraphProvidersSpringOrProductCode() {
        noClasses()
                .that()
                .resideInAnyPackage("io.haifa.agent.store.sqlite..", "io.haifa.agent.store.sqlite.orchestration..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.bsc.langgraph4j..",
                        "com.alibaba.cloud.ai.graph..",
                        "org.springframework..",
                        "io.haifa.agent.application..",
                        "io.haifa.agent.product..")
                .check(new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("io.haifa.agent.store.sqlite"));
    }
}
