package io.haifa.agent.personalassistant.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class PersonalAssistantServerArchitectureTest {
    private final com.tngtech.archunit.core.domain.JavaClasses classes =
            new ClassFileImporter().importPackages("io.haifa.agent.personalassistant.server");

    @Test
    void dtoDoesNotExposeDomainSdkOrProviderTypes() {
        noClasses()
                .that()
                .resideInAPackage("..web.v1.dto..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.core..",
                        "io.haifa.agent.runtime..",
                        "io.haifa.agent.sdk..",
                        "io.haifa.agent.store..",
                        "io.modelcontextprotocol..")
                .check(classes);
    }

    @Test
    void controllersDoNotQueryPersistenceOrInternalMappers() {
        noClasses()
                .that()
                .resideInAPackage("..web.v1.controller..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.haifa.agent.store..", "..mybatis..")
                .check(classes);
    }

    @Test
    void serverDoesNotUseServletMvcOrTestingProductionTypes() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.web.servlet..", "jakarta.servlet..", "io.haifa.agent.testing..")
                .check(classes);
    }
}
