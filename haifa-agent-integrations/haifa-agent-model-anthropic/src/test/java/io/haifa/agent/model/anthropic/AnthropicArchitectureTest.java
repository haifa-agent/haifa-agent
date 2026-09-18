package io.haifa.agent.model.anthropic;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class AnthropicArchitectureTest {
    private static final JavaClasses PACKAGE_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.haifa.agent.model.anthropic");

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
                .check(PACKAGE_CLASSES);
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
                .check(PACKAGE_CLASSES);
    }
}
