package io.haifa.agent.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.io.Serializable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class CoreArchitectureTest {
    private static final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.haifa.agent.core");

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
        CORE_IS_FRAMEWORK_INDEPENDENT.check(classes);
    }

    @Test
    void coreDoesNotExposeJavaBeanSetters() {
        assertThat(classes.stream()
                        .flatMap(javaClass -> javaClass.getMethods().stream())
                        .filter(method -> method.getModifiers().contains(JavaModifier.PUBLIC))
                        .map(method -> method.getName())
                        .filter(name -> name.startsWith("set")))
                .isEmpty();
    }

    @Test
    void persistenceSnapshotsDoNotUseJavaNativeSerialization() {
        noClasses()
                .that()
                .haveSimpleNameEndingWith("PersistenceSnapshot")
                .should()
                .implement(Serializable.class)
                .check(classes);
    }
}
