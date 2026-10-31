package io.haifa.agent.cli.remote;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class RemoteCodingClientArchitectureTest {
    @Test
    void remoteAdapterDependsOnlyOnPublicApis() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent");

        noClasses()
                .that()
                .resideInAPackage("io.haifa.agent.cli.remote..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.runtime.core..",
                        "io.haifa.agent.application.project.persistence..",
                        "io.haifa.agent.execution..",
                        "io.haifa.agent.sandbox..",
                        "io.haifa.agent.credential..",
                        "io.haifa.agent.store..",
                        "io.haifa.agent.local.model.auth..",
                        "io.haifa.agent.model.openai..",
                        "io.haifa.agent.model.anthropic..",
                        "io.haifa.agent.google..")
                .check(classes);

        noClasses()
                .that()
                .resideInAPackage("io.haifa.agent.cli.remote..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(
                        "io.haifa.agent.application.project.product.coding.client.LocalCodingSessionClient")
                .check(classes);

        noClasses()
                .that()
                .resideInAPackage("io.haifa.agent.cli.remote..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("io.haifa.agent.cli")
                .check(classes);
    }
}
