package io.haifa.agent.orchestration.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class OrchestrationApiArchitectureTest {
    @Test
    void apiRemainsProviderFrameworkRuntimeAndProductIndependent() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "com.alibaba.cloud.ai..",
                        "org.bsc.langgraph4j..",
                        "com.fasterxml.jackson..",
                        "reactor..",
                        "jakarta.persistence..",
                        "io.haifa.agent.runtime..",
                        "io.haifa.agent.store..",
                        "io.haifa.agent.sdk..",
                        "io.haifa.agent.product..",
                        "io.haifa.agent.integration..")
                .check(new ClassFileImporter().importPackages("io.haifa.agent.orchestration.api"));
    }
}
