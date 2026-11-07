package io.haifa.coding.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class CodingApiArchitectureTest {
    @Test
    void productContractDependsOnlyOnJdkAndItsOwnTypes() {
        var imported = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.coding.api", "io.haifa.agent.application.project.product.coding.client");
        classes()
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.coding.api..", "io.haifa.agent.application.project.product.coding.client", "java..")
                .check(imported);
    }
}
