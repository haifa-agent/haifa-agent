package io.haifa.agent.contract;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class ContractArchitectureTest {

    private static final ArchRule CONTRACT_IS_SEPARATE_FROM_DOMAIN_AND_FRAMEWORKS = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "io.haifa.agent.core..",
                    "io.haifa.agent.runtime..",
                    "io.haifa.agent.policy..",
                    "io.haifa.agent.tool..",
                    "com.fasterxml.jackson..",
                    "reactor..",
                    "org.springframework..",
                    "jakarta.persistence..");

    @Test
    void contractIsSeparateFromDomainAndFrameworks() {
        CONTRACT_IS_SEPARATE_FROM_DOMAIN_AND_FRAMEWORKS.check(
                new ClassFileImporter().importPackages("io.haifa.agent.contract"));
    }
}
