package io.haifa.agent.mcp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class McpArchitectureTest {
    private static final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.haifa.agent.mcp");

    @Test
    void platformFacingMcpTypesDoNotLeakSdkJacksonOrReactor() {
        noClasses()
                .that()
                .resideInAnyPackage("..mcp.config..", "..mcp.protocol..", "..mcp.tool..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.modelcontextprotocol..", "com.fasterxml.jackson..", "reactor..", "org.springframework..")
                .check(classes);
    }

    @Test
    void mcpIntegrationDoesNotDependOnRuntimeOrApplicationAndDoesNotLaunchProcesses() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.runtime..", "io.haifa.agent.application..", "java.lang.ProcessBuilder")
                .check(classes);
    }
}
