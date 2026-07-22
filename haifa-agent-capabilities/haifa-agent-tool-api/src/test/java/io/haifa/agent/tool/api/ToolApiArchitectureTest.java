package io.haifa.agent.tool.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ToolApiArchitectureTest {
    @Test
    void apiDoesNotDependOnFrameworkProtocolJsonOrPersistenceTypes() {
        var classes = new ClassFileImporter().importPackages("io.haifa.agent.tool.api");
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
