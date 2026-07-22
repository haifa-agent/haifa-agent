package io.haifa.agent.credential.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class CredentialApiArchitectureTest {
    @Test
    void apiDoesNotDependOnFrameworkProtocolOrPersistenceTypes() {
        var classes = new ClassFileImporter().importPackages("io.haifa.agent.credential.api");
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.fasterxml.jackson..",
                        "org.springframework..",
                        "io.modelcontextprotocol..",
                        "jakarta.persistence..",
                        "javax.persistence..")
                .check(classes);
    }
}
