package io.haifa.agent.tool.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class ToolCoreArchitectureTest {
    @Test
    void coreDoesNotDependOnSpringMcpPersistenceOrApplications() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent.tool.core");
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "io.modelcontextprotocol..",
                        "jakarta.persistence..",
                        "io.haifa.agent.project..",
                        "io.haifa.agent.runtime..")
                .check(classes);
    }
}
