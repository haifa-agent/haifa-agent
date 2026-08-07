package io.haifa.agent.sandbox.host;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class HostSandboxArchitectureTest {
    @Test
    void sharedHostEnvironmentResolutionRemainsProductNeutral() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent.sandbox.host");

        noClasses()
                .that()
                .resideInAPackage("io.haifa.agent.sandbox.host..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.haifa.agent.cli..", "io.haifa.agent.personalassistant..")
                .check(classes);
    }
}
