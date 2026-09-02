package io.haifa.agent.cli;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class CliExecutionArchitectureTest {
    @Test
    void concreteLocalProvidersRemainInTheCliAssemblyBoundary() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent");

        noClasses()
                .that()
                .resideOutsideOfPackages(
                        "io.haifa.agent.cli..", "io.haifa.agent.sandbox.host..", "io.haifa.agent.sandbox.localnative..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.haifa.agent.sandbox.host..", "io.haifa.agent.sandbox.localnative..")
                .check(classes);
    }
}
