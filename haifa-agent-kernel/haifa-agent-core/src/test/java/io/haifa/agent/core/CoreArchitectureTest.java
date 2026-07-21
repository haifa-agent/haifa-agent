package io.haifa.agent.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class CoreArchitectureTest {

    private static final ArchRule CORE_IS_FRAMEWORK_INDEPENDENT = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "org.springframework.ai..",
                    "com.alibaba.cloud.ai..",
                    "io.haifa.agent.contract..",
                    "io.haifa.agent.runtime..",
                    "io.haifa.agent.product..");

    @Test
    void coreIsFrameworkIndependent() {
        CORE_IS_FRAMEWORK_INDEPENDENT.check(new ClassFileImporter().importPackages("io.haifa.agent.core"));
    }
}
