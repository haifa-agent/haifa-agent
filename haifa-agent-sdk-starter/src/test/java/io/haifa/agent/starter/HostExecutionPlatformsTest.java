package io.haifa.agent.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.execution.core.tool.ExecutionToolProvider;
import io.haifa.agent.runtime.api.ApprovalPrompt;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolCoordinate;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolDefinitionHash;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolProviderId;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSideEffect;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import io.haifa.agent.tool.core.ToolCatalogBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
                        1_000))
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
        // Bound assertions are independent of the production soft deadline; use a generous budget so
        // shared CI runners do not fail this check on GC or scheduling jitter.
        var validator = new JsonSchema202012Validator(20, 10_000, Duration.ofSeconds(5));

        try (var platform = HostExecutionPlatforms.hostGuarded(
                spec(workspace, Duration.ofSeconds(120), MAXIMUM_TIMEOUT), TENANT, PRINCIPAL)) {
            var schema = platform.registration().definition().inputSchema();

            assertThat(validator.validate(schema, timeoutArguments(600_000)).errors())
                    .isEmpty();
            assertThat(validator.validate(schema, timeoutArguments(600_001)).errors())
                    .isNotEmpty();
        }
    }

    /**
     * The frozen Tool identity carries the declared host workspace: another directory, or the
     * same path holding a replaced directory, produces another binding, so a resumed Run cannot
     * replay an approved command in a different workspace.
     */
    @Test
    void bindsTheFrozenToolIdentityToTheDeclaredHostWorkspace() throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path otherWorkspace = Files.createDirectories(temporary.resolve("other-workspace"));
        String binding;

        try (var platform = HostExecutionPlatforms.hostGuarded(
                spec(workspace, Duration.ofSeconds(120), MAXIMUM_TIMEOUT), TENANT, PRINCIPAL)) {
            binding = platform.registration().providerBindingReference();
            assertThat(binding).startsWith("host:execution:v1:sha256:");
            assertThat(platform.registration().definition().sideEffects())
                    .contains(ToolSideEffect.PROCESS_EXECUTION, ToolSideEffect.NETWORK_ACCESS);
            assertThat(platform.securitySummary()).contains("network=host");
        }

        try (var declared = HostExecutionPlatforms.hostGuarded(
                        spec(workspace, Duration.ofSeconds(120), MAXIMUM_TIMEOUT), TENANT, PRINCIPAL);
                var other = HostExecutionPlatforms.hostGuarded(
                        spec(otherWorkspace, Duration.ofSeconds(120), MAXIMUM_TIMEOUT), TENANT, PRINCIPAL)) {
            assertThat(declared.registration().providerBindingReference())
                    .isNotEqualTo(other.registration().providerBindingReference());
            assertThat(declared.registration().definition())
                    .isNotEqualTo(other.registration().definition());
        }

        deleteRecursively(workspace);
        Files.createDirectories(workspace);

        try (var replaced = HostExecutionPlatforms.hostGuarded(
                spec(workspace, Duration.ofSeconds(120), MAXIMUM_TIMEOUT), TENANT, PRINCIPAL)) {
            assertThat(replaced.registration().providerBindingReference()).isNotEqualTo(binding);
        }
    }

    /** One Approval shows what the invocation would run as the current OS user. */
    @Test
    void presentsTheExecutionFactsInTheApprovalPrompt() throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));

        try (var platform = HostExecutionPlatforms.hostGuarded(
                spec(workspace, Duration.ofSeconds(120), MAXIMUM_TIMEOUT), TENANT, PRINCIPAL)) {
            var registration = platform.registration();
            var catalog = new ToolCatalogBuilder();
            catalog.register(
                    registration.alias(),
                    registration.definition(),
                    registration.providerBindingReference(),
                    registration.provider());
            FrozenToolBinding binding =
                    catalog.freeze().snapshot().bindings().iterator().next();

            ApprovalPrompt prompt = platform.approvalPromptFormatter()
                    .format(
                            binding,
                            call(Map.<String, Object>of(
                                    "mode", "SCRIPT",
                                    "language", "powershell",
                                    "content", "Remove-Item -Recurse build",
                                    "args", List.of("-NoProfile"),
                                    "purpose", "clean the build output",
                                    "timeoutMillis", 60_000)),
                            false);

            assertThat(prompt.prompt())
                    .contains("Approve host execution")
                    .contains("Script: Remove-Item -Recurse build")
                    .contains("Args: -NoProfile")
                    .contains("Purpose: clean the build output")
                    .contains("Timeout: 60000 ms")
                    .contains("PROCESS_EXECUTION")
                    .contains("NETWORK_ACCESS")
                    .contains("Invocation digest: sha256:");
            assertThat(prompt.presentation()).isEmpty();
        }
    }

    /** Every other Tool keeps the SDK default Approval prompt. */
    @Test
    void keepsTheDefaultApprovalPromptForOtherTools() throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));

        try (var platform = HostExecutionPlatforms.hostGuarded(
                spec(workspace, Duration.ofSeconds(120), MAXIMUM_TIMEOUT), TENANT, PRINCIPAL)) {
            ApprovalPrompt prompt = platform.approvalPromptFormatter()
                    .format(otherBinding(), call(Map.<String, Object>of("mode", "COMMAND", "content", "true")), false);

            assertThat(prompt.prompt()).contains("Approve tool other_tool");
        }
    }

    private static ToolCall call(Map<String, Object> arguments) {
        return new ToolCall(
                new ToolCallId("host-execution-approval-call-1"),
                new AgentRunId("host-execution-approval-run-1"),
                new AgentStepId("host-execution-approval-step-1"),
                new ProviderToolCallCorrelationId("host-execution-approval-provider-1"),
                new RuntimeIdempotencyKey("host-execution-approval-key-1"),
                "execution_run",
                "3.0.0",
                new ToolArguments("haifa.execution.run.input", "3.0.0", arguments),
                Instant.parse("2026-07-28T00:00:00Z"));
    }

    private static FrozenToolBinding otherBinding() {
        Map<String, Object> schema =
                Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", true);
        ToolDefinition definition = new ToolDefinition(
                new ToolName("other_tool"),
                new SemanticVersion("1.0.0"),
                new ToolProviderId("test-provider"),
                "Other tool",
                "Another Tool of the same product",
                new ToolSchema("test.other.input", "1.0.0", schema),
                new ToolSchema("test.other.output", "1.0.0", schema),
                ToolExecutionMode.HOST_PROCESS,
                true,
                Duration.ofSeconds(30),
                "execution",
                ToolIdempotency.NON_IDEMPOTENT,
                ToolRisk.LOW,
                Set.of(),
                new ToolResourceRequirements(Set.of("other_tool"), Set.of(), Set.of()),
                List.of(),
                ToolApprovalRequirement.NEVER,
                "test",
                false,
                Set.of());
        return new FrozenToolBinding(
                new ToolAlias("other_tool"),
                new ToolCoordinate(
                        definition.name(),
                        definition.version(),
                        definition.providerId(),
                        new ToolDefinitionHash("2".repeat(64))),
                definition,
                "provider-binding",
                "catalog");
    }

    private static void deleteRecursively(Path directory) throws IOException {
        List<Path> paths;
        try (var walk = Files.walk(directory)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.delete(path);
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
                workspace, temporary.resolve("data"), defaultTimeout, maximumTimeout, 65_536, 1_000);
    }
}
