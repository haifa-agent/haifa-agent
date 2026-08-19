package io.haifa.agent.orchestration.langgraph4j;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.lang.reflect.Executable;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class LangGraph4jArchitectureTest {
    @Test
    void adapterDoesNotDependOnFrameworkStoreRuntimeCoreOrProductPackages() {
        noClasses()
                .that()
                .resideInAnyPackage("io.haifa.agent.orchestration.langgraph4j..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "com.alibaba.cloud.ai..",
                        "io.haifa.agent.runtime.core..",
                        "io.haifa.agent.store..",
                        "io.haifa.agent.sdk..",
                        "io.haifa.agent.product..")
                .check(new ClassFileImporter().importPackages("io.haifa.agent.orchestration.langgraph4j"));
    }

    @Test
    void publicAdapterSignaturesDoNotExposeProviderTypes() {
        assertThat(Arrays.stream(LangGraph4jWorkflowRuntime.class.getConstructors())
                        .map(Executable::toGenericString))
                .noneMatch(signature -> signature.contains("org.bsc.langgraph4j"));
        assertThat(Arrays.stream(LangGraph4jWorkflowRuntime.class.getMethods()).map(Executable::toGenericString))
                .noneMatch(signature -> signature.contains("org.bsc.langgraph4j"));
    }
}
