package io.haifa.agent.project.hostworkspace;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class ProjectHostArchitectureTest {
    @Test
    void hostAdapterDoesNotDependOnRuntimeExecutionProductsOrFrameworks() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent.project.hostworkspace");
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.runtime..",
                        "io.haifa.agent.execution..",
                        "io.haifa.agent.application..",
                        "io.haifa.agent.personalassistant..",
                        "org.springframework..",
                        "jakarta.persistence..")
                .check(classes);
    }
}
