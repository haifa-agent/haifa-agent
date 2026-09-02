package io.haifa.agent.store.sqlite;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class SqliteStoreArchitectureTest {
    @Test
    void jdbcMyBatisAndSqliteStayInsideTheSqliteIntegration() {
        noClasses()
                .that()
                .resideOutsideOfPackage("io.haifa.agent.store.sqlite..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.sql..", "javax.sql..", "org.sqlite..", "org.apache.ibatis..")
                .check(new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("io.haifa.agent"));
    }

    @Test
    void sqliteIntegrationDoesNotDependOnSpringOrmOrProductCode() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "com.baomidou..",
                        "org.mybatis.spring..",
                        "io.haifa.agent.application..",
                        "io.haifa.agent.product..")
                .check(new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("io.haifa.agent.store.sqlite"));
    }
}
