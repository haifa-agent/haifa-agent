package io.haifa.agent.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.execution.core.tool.ExecutionToolProvider;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import io.haifa.agent.tool.core.ToolCatalogBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public host execution assembly facade: bounded declaration, single apply and a real host run. */
class HostExecutionPlatformsTest {
    private static final TenantRef TENANT = new TenantRef("public");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("default-public-user", "user");
    private static final Duration MAXIMUM_TIMEOUT = Duration.ofMinutes(10);

    @TempDir
    Path temporary;

    @Test
    void assemblesTheExecutionToolFromTheSharedSdkPath() throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));

        try (var platform = HostExecutionPlatforms.hostGuarded(
                spec(workspace, Duration.ofSeconds(120), MAXIMUM_TIMEOUT), TENANT, PRINCIPAL)) {
            assertThat(platform.toolNames()).containsExactly("execution_run");
            assertThat(platform.registration().alias().value()).isEqualTo("execution_run");
            assertThat(platform.registration().definition().timeout()).isEqualTo(MAXIMUM_TIMEOUT);
            assertThat(platform.registration().provider().id()).isEqualTo(ExecutionToolProvider.PROVIDER_ID);
            assertThat(platform.previewPublisher()).isNotNull();
            assertThat(platform.securitySummary()).contains("host-guarded", "no strong isolation");

            platform.applyTo(HaifaAgents.builder());
            assertThatThrownBy(() -> platform.applyTo(HaifaAgents.builder()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("applied");
            platform.close();
            platform.close();
        }
    }

    @Test
    void rejectsADeclarationTheHostAssemblyCannotServe() throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));

        assertThatThrownBy(() -> new HostExecutionPlatforms.HostExecutionSpec(
                        temporary.resolve("missing"),
                        temporary.resolve("data"),
                        Duration.ofSeconds(120),
                        MAXIMUM_TIMEOUT,
                        65_536,
                        1_000,
                        true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspaceRoot");
        assertThatThrownBy(() -> HostExecutionPlatforms.hostGuarded(
                        spec(workspace, Duration.ofMinutes(20), MAXIMUM_TIMEOUT), TENANT, PRINCIPAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout must be within");
        assertThatThrownBy(() -> HostExecutionPlatforms.hostGuarded(
                        spec(workspace, Duration.ofSeconds(120), MAXIMUM_TIMEOUT), TENANT, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void runsAHostCommandThroughTheAssembledPlatform() throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));

        try (var platform = HostExecutionPlatforms.hostGuarded(
                spec(workspace, Duration.ofSeconds(120), MAXIMUM_TIMEOUT), TENANT, PRINCIPAL)) {
            var registration = platform.registration();
            ToolDefinition definition = registration.definition();
            var catalog = new ToolCatalogBuilder();
            catalog.register(
                    registration.alias(), definition, registration.providerBindingReference(), registration.provider());
            FrozenToolBinding binding =
                    catalog.freeze().snapshot().bindings().iterator().next();

            var result = registration
                    .provider()
                    .invoke(new ToolInvocationRequest(
                            binding,
                            new ToolCallId("host-execution-call-1"),
                            new AgentRunId("host-execution-run-1"),
                            TENANT,
                            PRINCIPAL,
                            new ToolArguments(
                                    definition.inputSchema().id(),
                                    definition.inputSchema().version(),
                                    Map.of(
                                            "mode", "COMMAND",
                                            "content", "echo haifa host execution",
                                            "purpose", "verify the assembled host execution path")),
                            Instant.now().plus(Duration.ofMinutes(2)),
                            Optional.of("host-execution-invocation-1"),
                            () -> false,
                            Map.of()));

            assertThat(result.successful()).isTrue();
            assertThat(result.summary()).isNotBlank();
        }
    }

    /** The declared maximum is the model-visible bound of the registered execution Tool schema. */
    @Test
    void exposesTheDeclaredTimeoutBoundToTheModel() throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        var validator = new JsonSchema202012Validator();

        try (var platform = HostExecutionPlatforms.hostGuarded(
                spec(workspace, Duration.ofSeconds(120), MAXIMUM_TIMEOUT), TENANT, PRINCIPAL)) {
            var schema = platform.registration().definition().inputSchema();

            assertThat(validator.validate(schema, timeoutArguments(600_000)).errors())
                    .isEmpty();
            assertThat(validator.validate(schema, timeoutArguments(600_001)).errors())
                    .isNotEmpty();
        }
    }

    private static Map<String, Object> timeoutArguments(long timeoutMillis) {
        return Map.of(
                "mode", "COMMAND",
                "content", "true",
                "purpose", "timeout bound",
                "timeoutMillis", timeoutMillis);
    }

    private HostExecutionPlatforms.HostExecutionSpec spec(
            Path workspace, Duration defaultTimeout, Duration maximumTimeout) {
        return new HostExecutionPlatforms.HostExecutionSpec(
                workspace, temporary.resolve("data"), defaultTimeout, maximumTimeout, 65_536, 1_000, true);
    }
}
