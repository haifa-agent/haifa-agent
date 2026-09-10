package io.haifa.agent.auth.localmodel.codex;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class CodexLocalCompatArchitectureTest {
    @Test
    void codexLocalCompatDoesNotDependOnAntigravityAdaptersProductsRuntimeSpringOrSqlite() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent.auth.localmodel.codex");

        noClasses()
                .that()
                .resideInAPackage("io.haifa.agent.auth.localmodel.codex..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.auth.localmodel.antigravity..",
                        "io.haifa.agent.model.openai..",
                        "io.haifa.agent.model.anthropic..",
                        "io.haifa.agent.model.gemini..",
                        "io.haifa.agent.cli..",
                        "io.haifa.agent.application..",
                        "io.haifa.agent.personalassistant..",
                        "io.haifa.agent.runtime..",
                        "io.haifa.agent.store.sqlite..",
                        "org.springframework..")
                .check(classes);
    }
}
