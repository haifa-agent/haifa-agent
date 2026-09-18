package io.haifa.agent.transport.http;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class HttpTransportArchitectureTest {
    @Test
    void transportDoesNotReachRuntimeCoreStoreOrSpringBoot() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent.transport.http");
        noClasses()
                .that()
                .resideInAPackage("io.haifa.agent.transport.http..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.runtime.core..", "io.haifa.agent.store.sqlite..", "org.springframework.boot..")
                .check(classes);
    }
}
