package io.haifa.agent.project;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ProjectArchitectureTest {
    @Test
    void projectIsFrameworkRuntimeAndProviderIndependent() {
        var classes = productionClasses();
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "com.fasterxml.jackson..",
                        "jakarta.persistence..",
                        "io.haifa.agent.runtime..",
                        "io.haifa.agent.model..",
                        "io.haifa.agent.product..")
                .check(classes);
        noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.ProcessBuilder")
                .check(classes);
    }

    @Test
    void onlyLocalProviderUsesHostFileApis() {
        noClasses()
                .that()
                .resideOutsideOfPackage("io.haifa.agent.project.provider.local..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.nio.file..")
                .check(productionClasses());
    }

    private static com.tngtech.archunit.core.domain.JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent.project");
    }
}
