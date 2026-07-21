package io.haifa.agent.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaModifier;
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
                    "jakarta.persistence..",
                    "io.haifa.agent.contract..",
                    "io.haifa.agent.runtime..",
                    "io.haifa.agent.product..",
                    "io.haifa.agent.integration..",
                    "io.haifa.agent.store..");

    @Test
    void coreIsFrameworkIndependent() {
        CORE_IS_FRAMEWORK_INDEPENDENT.check(new ClassFileImporter().importPackages("io.haifa.agent.core"));
    }

    @Test
    void coreDoesNotExposeJavaBeanSetters() {
        var coreClasses = new ClassFileImporter().importPackages("io.haifa.agent.core");

        assertThat(coreClasses.stream()
                        .flatMap(javaClass -> javaClass.getMethods().stream())
                        .filter(method -> method.getModifiers().contains(JavaModifier.PUBLIC))
                        .map(method -> method.getName())
                        .filter(name -> name.startsWith("set")))
                .isEmpty();
    }
}
