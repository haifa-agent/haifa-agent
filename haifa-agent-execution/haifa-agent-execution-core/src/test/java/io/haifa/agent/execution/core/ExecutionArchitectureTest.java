package io.haifa.agent.execution.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ExecutionArchitectureTest {
    @Test
    void onlyHostSandboxCreatesProcesses() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent");
        noClasses()
                .that()
                .resideOutsideOfPackage("io.haifa.agent.sandbox.host..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.ProcessBuilder")
                .check(classes);
        noClasses()
                .that()
                .resideOutsideOfPackage("io.haifa.agent.sandbox.host..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.Runtime")
                .check(classes);
    }
}
