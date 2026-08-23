package io.haifa.agent.auth.localmodel;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class LocalModelAuthArchitectureTest {
    @Test
    void localModelAuthDoesNotDependOnProductsRuntimeSpringOrSqlite() {
        var classes = new ClassFileImporter().importPackages("io.haifa.agent.auth.localmodel");

        noClasses()
                .that()
                .resideInAPackage("io.haifa.agent.auth.localmodel..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.cli..",
                        "io.haifa.agent.application..",
                        "io.haifa.agent.personalassistant..",
                        "io.haifa.agent.runtime..",
                        "io.haifa.agent.store.sqlite..",
                        "org.springframework..")
                .check(classes);
    }
}
