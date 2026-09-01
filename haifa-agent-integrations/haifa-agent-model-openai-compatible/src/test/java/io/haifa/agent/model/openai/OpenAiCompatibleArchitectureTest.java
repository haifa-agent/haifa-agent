package io.haifa.agent.model.openai;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleArchitectureTest {
    @Test
    void integrationDoesNotDependOnProductsOrOtherProviderAdapters() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.personalassistant..",
                        "io.haifa.agent.coding..",
                        "io.haifa.agent.cli..",
                        "io.haifa.agent.model.anthropic..",
                        "io.haifa.agent.model.gemini..")
                .check(new ClassFileImporter().importPackages("io.haifa.agent.model.openai"));
    }

    @Test
    void responsesDialectTypesArePackagePrivate() {
        classes()
                .that()
                .haveSimpleNameEndingWith("Dialect")
                .and()
                .doNotHaveSimpleName("OpenAiResponsesDialects")
                .or()
                .haveSimpleName("DialectErrorMapping")
                .or()
                .haveSimpleNameEndingWith("DialectSupport")
                .or()
                .haveSimpleName("DialectAuthenticationException")
                .should()
                .bePackagePrivate()
                .check(new ClassFileImporter().importPackages("io.haifa.agent.model.openai.responses"));
    }
}
