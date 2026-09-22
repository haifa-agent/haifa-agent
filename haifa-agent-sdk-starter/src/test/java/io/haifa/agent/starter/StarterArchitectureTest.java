package io.haifa.agent.starter;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class StarterArchitectureTest {
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.haifa.agent.starter");

    @Test
    void remainsPureJavaAndFrameworkIndependent() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "org.springframework.ai..",
                        "com.alibaba.cloud.ai..",
                        "jakarta.persistence..",
                        "org.mybatis..",
                        "io.modelcontextprotocol..")
                .check(CLASSES);
    }

    @Test
    void consumesMcpOnlyThroughTheStableIntegrationBoundary() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.haifa.agent.mcp.protocol..", "io.haifa.agent.mcp.transport..")
                .check(CLASSES);
    }

    /**
     * The Starter's public surface is the entry point plus the MCP Client declaration types. Assembly
     * details such as {@code NativeMcpToolPlatform} stay package-private so they never become an
     * accidental extension point with a compatibility cost.
     */
    @Test
    void keepsThePublicSurfaceToTheEntryPointAndTheMcpDeclarationTypes() {
        var publicTypes = CLASSES.stream()
                .filter(javaClass -> !javaClass.isNestedClass())
                .filter(javaClass -> Modifier.isPublic(javaClass.reflect().getModifiers()))
                .map(javaClass -> javaClass.reflect().getSimpleName())
                .toList();

        assertThat(publicTypes)
                .containsExactlyInAnyOrder(
                        "HaifaAgentStarter", "HaifaAgentStarterBuilder", "McpServerSpec", "McpServerRequirement");
    }
}
