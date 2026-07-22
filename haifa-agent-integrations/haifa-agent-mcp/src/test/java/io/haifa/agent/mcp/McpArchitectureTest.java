package io.haifa.agent.mcp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class McpArchitectureTest {
    @Test
    void platformFacingMcpTypesDoNotLeakSdkJacksonOrReactor() {
        var classes = new ClassFileImporter()
                .importPackages("io.haifa.agent.mcp.config", "io.haifa.agent.mcp.protocol", "io.haifa.agent.mcp.tool");
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.modelcontextprotocol..", "com.fasterxml.jackson..", "reactor..", "org.springframework..")
                .check(classes);
    }

    @Test
    void mcpIntegrationDoesNotDependOnRuntimeOrApplicationAndDoesNotLaunchProcesses() {
        var classes = new ClassFileImporter().importPackages("io.haifa.agent.mcp");
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.runtime..", "io.haifa.agent.application..", "java.lang.ProcessBuilder")
                .check(classes);
    }
}
