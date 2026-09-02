package io.haifa.agent.store.jsonl;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.haifa.agent.runtime.core.storage.RunStateRepository;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import io.haifa.agent.runtime.core.storage.RuntimeUnitOfWork;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class JsonlStoreArchitectureTest {
    @Test
    void mainCodeHasNoSqliteJdbcFrameworkOrProductDependency() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.store.sqlite..",
                        "java.sql..",
                        "javax.sql..",
                        "org.sqlite..",
                        "org.apache.ibatis..",
                        "org.springframework..",
                        "io.haifa.agent.application..",
                        "io.haifa.agent.product..")
                .check(new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("io.haifa.agent.store.jsonl"));
    }

    @Test
    void projectionClassesDoNotImplementRuntimePersistencePorts() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent.store.jsonl");

        assertThat(classes.stream()
                        .filter(type -> type.isAssignableTo(RuntimeUnitOfWork.class)
                                || type.isAssignableTo(RuntimeEventAppender.class)
                                || type.isAssignableTo(RunStateRepository.class)))
                .isEmpty();
    }
}
