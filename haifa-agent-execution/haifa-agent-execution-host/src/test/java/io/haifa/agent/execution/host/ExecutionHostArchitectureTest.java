package io.haifa.agent.execution.host;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class ExecutionHostArchitectureTest {
    @Test
    void hostAdapterDoesNotDependOnProductsFrameworksOrConcreteSandboxes() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent.execution.host");
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.application..",
                        "io.haifa.agent.personalassistant..",
                        "io.haifa.agent.sandbox.host..",
                        "io.haifa.agent.sandbox.localnative..",
                        "org.springframework..",
                        "jakarta.persistence..")
                .check(classes);
    }
}
