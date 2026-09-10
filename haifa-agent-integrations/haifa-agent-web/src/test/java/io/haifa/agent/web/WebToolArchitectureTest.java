package io.haifa.agent.web;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class WebToolArchitectureTest {
    private static final String WEB_PACKAGE = "io.haifa.agent.web";
    private static final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(WEB_PACKAGE);

    @Test
    void providerNeutralWebContractsDoNotDependOnHttpImplementationDetails() {
        noClasses()
                .that()
                .resideInAPackage(WEB_PACKAGE)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        WEB_PACKAGE + ".provider..",
                        "com.fasterxml.jackson..",
                        "java.net.http..",
                        "org.springframework..")
                .check(classes);
    }

    @Test
    void webToolImplementationDoesNotDependOnRuntimeOrCli() {
        noClasses()
                .that()
                .resideInAnyPackage(WEB_PACKAGE, WEB_PACKAGE + "..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.haifa.agent.runtime..", "io.haifa.agent.cli..", "org.springframework..")
                .check(classes);
    }
}
