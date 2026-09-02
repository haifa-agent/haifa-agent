package io.haifa.agent.model.anthropic;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class AnthropicArchitectureTest {
    @Test
    void integrationDoesNotDependOnProductsOrOtherProviderAdapters() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.personalassistant..",
                        "io.haifa.agent.coding..",
                        "io.haifa.agent.cli..",
                        "io.haifa.agent.model.openai..",
                        "io.haifa.agent.model.gemini..")
                .check(new ClassFileImporter().importPackages("io.haifa.agent.model.anthropic"));
    }

    @Test
    void dialectTypesArePackagePrivate() {
        classes()
                .that()
                .haveSimpleNameEndingWith("Dialect")
                .and()
                .doNotHaveSimpleName("AnthropicMessagesDialects")
                .and()
                .doNotHaveSimpleName("Dialect")
                .or()
                .haveSimpleName("DialectErrorMapping")
                .or()
                .haveSimpleNameEndingWith("DialectSupport")
                .should()
                .bePackagePrivate()
                .check(new ClassFileImporter().importPackages("io.haifa.agent.model.anthropic"));
    }
}
