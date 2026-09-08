package io.haifa.agent.personalassistant.application.execution;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunSpec;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionInput;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.execution.api.ExecutionOrigin;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.api.TrustedExecutionContext;
import io.haifa.agent.execution.core.ExecutionPolicyEntryPoint;
import io.haifa.agent.execution.core.ExecutionRejectedException;
import io.haifa.agent.execution.core.tool.ExecutionOperatingSystem;
import io.haifa.agent.execution.core.tool.ExecutionToolConfiguration;
import io.haifa.agent.execution.core.tool.ScriptRuntimeResolver;
import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.runtime.core.tool.RuntimeToolExecutionVerifier;
import io.haifa.agent.skill.api.SkillContentDigest;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolArgumentsDigest;
import io.haifa.agent.tool.api.ToolCoordinate;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolDefinitionHash;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolProviderId;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PersonalAssistantExecutionPolicyTest {
    private static final TenantRef TENANT = new TenantRef("tenant");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("actor", "user");
    private static final WorkspaceId WORKSPACE = new WorkspaceId("personal-execution");
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void rejectsDirectManagedAndForgedRuntimeSourcesWithoutCaWorkspaceAuthority() {
        PersonalAssistantExecutionPolicy policy = policy();

        assertThatThrownBy(() -> policy.authorize(
                        request(ExecutionOrigin.PRODUCT_USER_COMMAND, Optional.empty()),
                        ExecutionPolicyEntryPoint.FIRST_EXECUTION))
                .isInstanceOf(ExecutionRejectedException.class)
                .hasMessageContaining("Runtime Tool");
        assertThatThrownBy(() -> policy.authorize(
                        request(ExecutionOrigin.RUNTIME_TOOL, Optional.of(new ToolCallId("forged"))),
                        ExecutionPolicyEntryPoint.MANAGED_SESSION))
                .isInstanceOf(ExecutionRejectedException.class)
                .hasMessageContaining("managed");
        assertThatThrownBy(() -> policy.authorize(
                        request(ExecutionOrigin.RUNTIME_TOOL, Optional.of(new ToolCallId("forged"))),
                        ExecutionPolicyEntryPoint.FIRST_EXECUTION))
                .isInstanceOf(ExecutionRejectedException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void allowsOnlyTheRequestReconstructedFromThePersistedPaToolCall() {
        ValidFixture fixture = validFixture();

        assertThatCode(() -> fixture.policy().authorize(fixture.request(), ExecutionPolicyEntryPoint.FIRST_EXECUTION))
                .doesNotThrowAnyException();

        ExecutionRequest expanded = new ExecutionRequest(
                new ExecutionId("expanded"),
                fixture.request().idempotencyKey(),
                fixture.request().context(),
                fixture.request().workspaceId(),
                fixture.request().workingDirectory(),
                ExecutionCommand.shell("echo unsafe"),
                fixture.request().environmentRef(),
                fixture.request().limits(),
                fixture.request().sandboxProfileRef(),
                fixture.request().input(),
                fixture.request().invocationDigest(),
                fixture.request().scratchSpace());
        assertThatThrownBy(() -> fixture.policy().authorize(expanded, ExecutionPolicyEntryPoint.IDEMPOTENT_REPLAY))
                .isInstanceOf(ExecutionRejectedException.class)
                .hasMessageContaining("drifted");
    }

    private static PersonalAssistantExecutionPolicy policy() {
        RuntimePersistencePorts ports = RuntimePersistencePorts.inMemory();
        var verifier = new RuntimeToolExecutionVerifier(
                ports.runs(),
                ports.state(),
                ports.interactions(),
                io.haifa.agent.runtime.core.tool.ToolRequestCanonicalizer.identity(),
                (run, binding, request) ->
                        new PolicyDecision(PolicyEffect.ALLOW, Optional.empty(), "ALLOW", "Allowed", "requirement"));
        var runtimes = new ScriptRuntimeResolver(
                ExecutionOperatingSystem.LINUX, List.of(ScriptRuntimeResolver.bash("/bin/bash")));
        var configuration = new ExecutionToolConfiguration(
                ExecutionEnvironmentRef.empty(),
                new SandboxProfileRef("test", "1"),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                4096,
                100,
                1,
                false,
                runtimes,
                ExecutionOutputObserver.noop(),
                value -> value);
        return new PersonalAssistantExecutionPolicy(verifier, configuration, TENANT, PRINCIPAL, WORKSPACE);
    }

    private static ValidFixture validFixture() {
        ExecutionToolConfiguration configuration = configuration();
        ToolArguments arguments = new ToolArguments(
                "haifa.execution.run.input",
                "2.0.0",
                Map.of("mode", "COMMAND", "content", "echo safe", "purpose", "test"));
        FrozenToolBinding binding = binding();
        RunConfigurationSnapshotRef reference =
                new RunConfigurationSnapshotRef("pa-configuration", "sha256:" + "2".repeat(64));
        var snapshot = new RuntimeConfigurationSnapshot(
                reference,
                new AgentDefinitionId("pa-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                "personal-assistant",
                "1",
                AgentRunType.CHAT,
                budget(),
                limits(),
                List.of(binding),
                List.of(),
                new SkillContentDigest("sha256:" + "3".repeat(64)),
                "skill-policy",
                Set.of(),
                "Test instruction",
                io.haifa.agent.runtime.api.RuntimeOverrides.NONE,
                List.of(),
                model());
        AgentRun run = AgentRun.createRoot(
                new AgentRunId("pa-run"),
                new AgentRunSpec(
                        new AgentSessionId("pa-session"),
                        new ProjectRef("pa-project"),
                        TENANT,
                        PRINCIPAL,
                        new AgentDefinitionId("pa-agent"),
                        new AgentDefinitionVersion(1, 0, 0),
                        "personal-assistant",
                        "1",
                        AgentRunType.CHAT,
                        "test",
                        budget(),
                        limits(),
                        reference),
                NOW);
        ToolCallId sourceId = new ToolCallId("pa-source");
        var call = new ToolCall(
                sourceId,
                run.id(),
                new AgentStepId("pa-step"),
                new ProviderToolCallCorrelationId("pa-provider-call"),
                new RuntimeIdempotencyKey("pa-tool-key"),
                binding.alias().value(),
                binding.definition().version().value(),
                arguments,
                NOW);
        call.beginValidation();
        call.beginPolicyCheck();
        call.start(NOW);
        var store = new InMemoryRuntimeStore();
        store.saveConfiguration(snapshot);
        store.insert(run);
        store.appendToolCall(call);
        var interactions = new io.haifa.agent.runtime.core.interaction.InMemoryInteractionPort();
        var verifier = new RuntimeToolExecutionVerifier(
                store,
                store,
                interactions,
                io.haifa.agent.runtime.core.tool.ToolRequestCanonicalizer.identity(),
                (ignoredRun, ignoredBinding, ignoredRequest) ->
                        new PolicyDecision(PolicyEffect.ALLOW, Optional.empty(), "ALLOW", "Allowed", "requirement"));
        var policy = new PersonalAssistantExecutionPolicy(verifier, configuration, TENANT, PRINCIPAL, WORKSPACE);
        var request = new ExecutionRequest(
                new ExecutionId("pa-execution"),
                "pa-execution-key",
                new TrustedExecutionContext(
                        TENANT,
                        run.id().value(),
                        PRINCIPAL,
                        Set.of("execution.run"),
                        ExecutionOrigin.RUNTIME_TOOL,
                        Optional.of(sourceId)),
                WORKSPACE,
                WorkspacePath.root(WORKSPACE),
                ExecutionCommand.shell("echo safe"),
                configuration.environmentRef(),
                new ExecutionLimits(Duration.ofSeconds(5), 16 * 1024 * 1024, 16 * 1024 * 1024, 1),
                configuration.sandboxProfileRef(),
                ExecutionInput.none(),
                ExecutionRequest.digestWithScratch(ToolArgumentsDigest.sha256(arguments), configuration.scratchSpace()),
                configuration.scratchSpace());
        return new ValidFixture(policy, request);
    }

    private static ExecutionToolConfiguration configuration() {
        var runtimes = new ScriptRuntimeResolver(
                ExecutionOperatingSystem.LINUX, List.of(ScriptRuntimeResolver.bash("/bin/bash")));
        return new ExecutionToolConfiguration(
                ExecutionEnvironmentRef.empty(),
                new SandboxProfileRef("test", "1"),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                4096,
                100,
                1,
                false,
                runtimes,
                ExecutionOutputObserver.noop(),
                value -> value);
    }

    private static FrozenToolBinding binding() {
        Map<String, Object> schema =
                Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", true);
        var definition = new ToolDefinition(
                new ToolName("execution.run"),
                new SemanticVersion("2.0.0"),
                new ToolProviderId("haifa-execution"),
                "Run execution",
                "Run a bounded execution",
                new ToolSchema("haifa.execution.run.input", "2.0.0", schema),
                new ToolSchema("haifa.execution.run.output", "2.0.0", schema),
                ToolExecutionMode.HOST_PROCESS,
                true,
                Duration.ofSeconds(30),
                "execution",
                ToolIdempotency.NON_IDEMPOTENT,
                ToolRisk.HIGH,
                Set.of(ToolSideEffect.PROCESS_EXECUTION),
                new ToolResourceRequirements(Set.of("execution.run"), Set.of(), Set.of("test@1")),
                List.of(),
                ToolApprovalRequirement.NEVER,
                "test",
                false,
                Set.of());
        return new FrozenToolBinding(
                new ToolAlias("execution_run"),
                new ToolCoordinate(
                        definition.name(),
                        definition.version(),
                        definition.providerId(),
                        new ToolDefinitionHash("1".repeat(64))),
                definition,
                "provider-binding",
                "catalog");
    }

    private static AgentRunBudget budget() {
        return new AgentRunBudget(100, 100, 100, 10, 10, 2, "USD", 100);
    }

    private static AgentRunLimits limits() {
        return new AgentRunLimits(10, 2, 1, 60_000, 10_000);
    }

    private static ResolvedModelSnapshot model() {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("provider"),
                "1",
                new ModelDefinitionId("model"),
                "1",
                "model",
                "adapter",
                "1",
                new ApiStyleId("style"),
                "standard",
                URI.create("https://example.test"),
                new CredentialRef("credential"),
                true,
                Set.of(ModelCapability.TEXT_CHAT),
                4096,
                1024,
                Map.of(),
                Map.of());
    }

    private static ExecutionRequest request(ExecutionOrigin origin, Optional<ToolCallId> source) {
        return new ExecutionRequest(
                new ExecutionId("execution"),
                "idempotency",
                new TrustedExecutionContext(TENANT, "run", PRINCIPAL, Set.of("execution.run"), origin, source),
                WORKSPACE,
                WorkspacePath.root(WORKSPACE),
                ExecutionCommand.shell("echo safe"),
                ExecutionEnvironmentRef.empty(),
                new ExecutionLimits(Duration.ofSeconds(5), 4096, 4096, 1),
                new SandboxProfileRef("test", "1"));
    }

    private record ValidFixture(PersonalAssistantExecutionPolicy policy, ExecutionRequest request) {}
}
