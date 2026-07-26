package io.haifa.agent.transport.http;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class HttpTransportArchitectureTest {
    @Test
    void transportDoesNotReachRuntimeCoreStoreOrSpringBoot() {
        var classes = new ClassFileImporter().importPackages("io.haifa.agent.transport.http");
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
