package io.haifa.coding.host;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.*;
import org.junit.jupiter.api.*;

@Tag("architecture")
class ProductArchitectureTest {
    @Test
    void moduleDoesNotImportForbiddenImplementations() {
        var imported = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.coding.host");
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.haifa.agent.cli..", "io.haifa.agent.application.coding.terminal..")
                .check(imported);
    }

    @Test
    void publicEntrypointsExposeOnlyJdkAndProductTypes() {
        for (Class<?> type : java.util.List.of(
                HostOptions.class,
                BrowserOptions.class,
                BrowserIngress.class,
                PairingChallenge.class,
                CodingHost.class,
                CodingHosts.class)) {
            for (var method : type.getDeclaredMethods())
                if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                    org.assertj.core.api.Assertions.assertThat(method.toGenericString())
                            .doesNotContain("io.haifa.agent.", ".internal.", "jackson", "sqlite", "spring");
                }
            for (var constructor : type.getConstructors())
                org.assertj.core.api.Assertions.assertThat(constructor.toGenericString())
                        .doesNotContain("io.haifa.agent.", ".internal.", "jackson", "sqlite", "spring");
        }
    }
}
