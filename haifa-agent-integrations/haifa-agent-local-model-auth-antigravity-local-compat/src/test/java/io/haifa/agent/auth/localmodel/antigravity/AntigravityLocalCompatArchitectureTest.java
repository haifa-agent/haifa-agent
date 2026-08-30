package io.haifa.agent.auth.localmodel.antigravity;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class AntigravityLocalCompatArchitectureTest {
    @Test
    void antigravityLocalCompatDoesNotDependOnCodexAdaptersProductsRuntimeSpringOrSqlite() {
        var classes = new ClassFileImporter().importPackages("io.haifa.agent.auth.localmodel.antigravity");

        noClasses()
                .that()
                .resideInAPackage("io.haifa.agent.auth.localmodel.antigravity..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.auth.localmodel.codex..",
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
