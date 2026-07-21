package io.haifa.agent.runtime.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class RuntimeApiArchitectureTest {

    private static final ArchRule RUNTIME_API_IS_FRAMEWORK_AND_PRODUCT_INDEPENDENT = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "org.springframework.ai..",
                    "com.alibaba.cloud.ai..",
                    "jakarta.persistence..",
                    "io.haifa.agent.product..",
                    "io.haifa.agent.integration..",
                    "io.haifa.agent.runtime.core..");

    @Test
    void runtimeApiIsFrameworkAndProductIndependent() {
        RUNTIME_API_IS_FRAMEWORK_AND_PRODUCT_INDEPENDENT.check(
                new ClassFileImporter().importPackages("io.haifa.agent.runtime.api"));
    }
}
