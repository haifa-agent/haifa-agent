package io.haifa.agent.cli;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class CliAuthenticationArchitectureTest {
    @Test
    void localAuthenticationEntryCannotAssembleRuntimePersistenceExecutionOrRemoteClients() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent.cli", "io.haifa.coding.host");
        noClasses()
                .that()
                .haveNameMatching(".*(CodingAuthCommand.*|CliLocalModelAuth).*")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.runtime..",
                        "io.haifa.agent.store..",
                        "io.haifa.agent.execution..",
                        "io.haifa.agent.sandbox..",
                        "io.haifa.agent.application.project.persistence..",
                        "io.haifa.agent.cli.remote..")
                .check(classes);
    }
}
