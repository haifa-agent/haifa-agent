package io.haifa.agent.contract;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class ContractArchitectureTest {

    private static final ArchRule CONTRACT_IS_SEPARATE_FROM_DOMAIN_AND_FRAMEWORKS = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "io.haifa.agent.core..",
                    "io.haifa.agent.runtime..",
                    "org.springframework..",
                    "jakarta.persistence..");

    @Test
    void contractIsSeparateFromDomainAndFrameworks() {
        CONTRACT_IS_SEPARATE_FROM_DOMAIN_AND_FRAMEWORKS.check(
                new ClassFileImporter().importPackages("io.haifa.agent.contract"));
    }
}
