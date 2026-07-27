package io.haifa.agent.application.project.tool.web;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class WebToolArchitectureTest {
    private static final String WEB_PACKAGE = "io.haifa.agent.application.project.tool.web";

    @Test
    void providerNeutralWebContractsDoNotDependOnHttpImplementationDetails() {
        var classes = new ClassFileImporter().importPackages(WEB_PACKAGE);

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
        var classes = new ClassFileImporter().importPackages(WEB_PACKAGE);

        noClasses()
                .that()
                .resideInAnyPackage(WEB_PACKAGE, WEB_PACKAGE + "..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.haifa.agent.runtime..", "io.haifa.agent.cli..", "org.springframework..")
                .check(classes);
    }
}
