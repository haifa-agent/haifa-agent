package io.haifa.agent.sandbox.localnative;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class LocalNativeArchitectureTest {
    @Test
    void localNativeDependsOnlyOnItsPublishedLowerLevelBoundaries() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent.sandbox.localnative");

        noClasses()
                .that()
                .resideInAPackage("io.haifa.agent.sandbox.localnative..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.sandbox.host..",
                        "io.haifa.agent.execution.core..",
                        "io.haifa.agent.runtime..",
                        "io.haifa.agent.tool..",
                        "io.haifa.agent.policy.core..",
                        "io.haifa.agent.project.application..",
                        "org.springframework..",
                        "com.fasterxml.jackson..")
                .check(classes);
    }
}
