package io.haifa.agent.application.coding.terminal;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class CodingTerminalArchitectureTest {
    @Test
    void productionCodeUsesOnlyStableBoundaries() {
        var classes = new ClassFileImporter().importPackages("io.haifa.agent.application.coding.terminal");

        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.runtime.core..",
                        "io.haifa.agent.store.sqlite..",
                        "io.haifa.agent.sandbox..",
                        "java.sql..")
                .check(classes);
    }
}
