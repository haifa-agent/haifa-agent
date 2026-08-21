package io.haifa.agent.execution.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionInput;
import io.haifa.agent.execution.api.ExecutionOutput;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.api.ResourceUsageSummary;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillAlias;
import io.haifa.agent.skill.api.SkillContent;
import io.haifa.agent.skill.api.SkillContentDigest;
import io.haifa.agent.skill.api.SkillDeclaredVersion;
import io.haifa.agent.skill.api.SkillMetadata;
import io.haifa.agent.skill.api.SkillName;
import io.haifa.agent.skill.api.SkillPackageIndex;
import io.haifa.agent.skill.api.SkillResourceKind;
import io.haifa.agent.skill.api.SkillResourceRef;
import io.haifa.agent.skill.api.SkillScopeRef;
import io.haifa.agent.skill.api.SkillSourceRef;
import io.haifa.agent.skill.api.SkillTrustDigests;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolCoordinate;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolDefinitionHash;
import io.haifa.agent.tool.api.ToolDispatchEvidence;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolProviderId;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TrustedScriptExecutionToolProviderTest {
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @Test
    void fixedScriptUsesBrokerWithHostOwnedContentRuntimeAndValidatedWorkspaceInput() {
        AtomicReference<ExecutionRequest> captured = new AtomicReference<>();
        AtomicReference<List<ProjectPath>> validated = new AtomicReference<>();
        var provider = provider(captured, Set.of("execution.run"), (workspaceId, paths) -> {
            assertThat(workspaceId).isEqualTo(new WorkspaceId("workspace"));
            validated.set(paths);
        });

        var result = provider.invokeTrustedScript(
                invocation(),
                "fixture-runtime",
                "print('FROZEN_SCRIPT')",
                List.of("MODEL_VALUE"),
                "fixed transform",
                ".",
                Duration.ofSeconds(5),
                Set.of("execution.run"),
                List.of(ProjectPath.of("input/value.txt")));

        assertThat(result.successful()).isTrue();
        assertThat(validated.get()).containsExactly(ProjectPath.of("input/value.txt"));
        ExecutionRequest execution = captured.get();
        assertThat(execution.command().argv()).containsExactly("trusted-runtime", "MODEL_VALUE");
        assertThat(new String(execution.input().bytes(), StandardCharsets.UTF_8))
                .isEqualTo("print('FROZEN_SCRIPT')\n")
                .doesNotContain("MODEL_VALUE");
        assertThat(execution.context().policyDecisionRef()).isEqualTo("policy-decision");
        assertThat(execution.limits().timeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void missingCapabilityAndUnconfiguredWorkspaceValidationFailBeforeBrokerDispatch() {
        AtomicReference<ExecutionRequest> captured = new AtomicReference<>();
        var missingCapability = provider(captured, Set.of(), TrustedWorkspacePathValidator.rejectWorkspaceInputs());

        assertThatThrownBy(() -> missingCapability.invokeTrustedScript(
                        invocation(),
                        "fixture-runtime",
                        "safe",
                        List.of(),
                        "fixed transform",
                        ".",
                        Duration.ofSeconds(5),
                        Set.of("execution.run"),
                        List.of()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("capabilities");
        assertThat(captured).hasValue(null);

        var noPathValidator =
                provider(captured, Set.of("execution.run"), TrustedWorkspacePathValidator.rejectWorkspaceInputs());
        assertThatThrownBy(() -> noPathValidator.invokeTrustedScript(
                        invocation(),
                        "fixture-runtime",
                        "safe",
                        List.of(),
                        "fixed transform",
                        ".",
                        Duration.ofSeconds(5),
                        Set.of("execution.run"),
                        List.of(ProjectPath.of("input/value.txt"))))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("validation is not configured");
        assertThat(captured).hasValue(null);
    }

    @Test
    void preservesSafeBrokerFailureCodeWithoutClaimingDispatch() {
        ExecutionBroker broker = new ExecutionBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request) {
                throw new io.haifa.agent.execution.core.ExecutionRejectedException(
                        "POLICY_RESOURCE_MISMATCH", "policy decision digest does not match execution");
            }

            @Override
            public boolean cancel(ExecutionId id) {
                return false;
            }

            @Override
            public Optional<ExecutionResult> find(ExecutionId id) {
                return Optional.empty();
            }
        };
        ExecutionToolProvider provider =
                provider(broker, Set.of("execution.run"), TrustedWorkspacePathValidator.rejectWorkspaceInputs());

        assertThatThrownBy(() -> provider.invokeTrustedScript(
                        invocation(),
                        "fixture-runtime",
                        "safe",
                        List.of(),
                        "fixed transform",
                        ".",
                        Duration.ofSeconds(5),
                        Set.of("execution.run"),
                        List.of()))
                .isInstanceOfSatisfying(io.haifa.agent.tool.api.ToolInvocationException.class, exception -> {
                    assertThat(exception.failureCode()).isEqualTo("POLICY_RESOURCE_MISMATCH");
                    assertThat(exception.dispatchState())
                            .isEqualTo(io.haifa.agent.tool.api.ToolDispatchState.NOT_DISPATCHED);
                });
    }

    @Test
    void publishesExecutionIdentityAndSafeWorkingDirectoryDigestAtDispatch() {
        AtomicReference<ToolDispatchEvidence> dispatched = new AtomicReference<>();
        ExecutionBroker broker = new ExecutionBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request) {
                throw new AssertionError("streaming execution path must be used");
            }

            @Override
            public ExecutionResult execute(
                    ExecutionRequest request, io.haifa.agent.execution.api.ExecutionOutputObserver observer) {
                observer.onStarted(new io.haifa.agent.execution.api.ExecutionProcessIdentity(811));
                return success(request.id());
            }

            @Override
            public boolean cancel(ExecutionId id) {
                return false;
            }

            @Override
            public Optional<ExecutionResult> find(ExecutionId id) {
                return Optional.empty();
            }
        };
        ToolInvocationObserver observer = new ToolInvocationObserver() {
            @Override
            public void dispatched() {}

            @Override
            public void dispatched(ToolDispatchEvidence evidence) {
                dispatched.set(evidence);
            }

            @Override
            public void acknowledged() {}
        };
        ExecutionToolProvider provider =
                provider(broker, Set.of("execution.run"), TrustedWorkspacePathValidator.rejectWorkspaceInputs());

        provider.invokeTrustedScript(
                invocation(binding(), observer),
                "fixture-runtime",
                "safe",
                List.of(),
                "fixed transform",
                ".",
                Duration.ofSeconds(5),
                Set.of("execution.run"),
                List.of());

        assertThat(dispatched.get())
                .isEqualTo(new ToolDispatchEvidence(
                        "execution-id",
                        java.util.OptionalLong.of(811),
                        PolicyDigest.sha256Fields(List.of("execution-working-directory-v1", "workspace", "."))));
    }

    @Test
    void fixedToolProviderLoadsAndRehashesTheFrozenSkillResource() {
        AtomicReference<ExecutionRequest> captured = new AtomicReference<>();
        ExecutionToolProvider execution = provider(captured, Set.of("execution.run"), (workspaceId, paths) -> {});
        FrozenSkillBinding skill = skill();
        String script = "print('FROZEN_RESOURCE')";
        ToolDefinition definition = trustedDefinition(
                execution.configurationIdentity(), SkillTrustDigests.sandbox(execution.sandboxProfileIdentity()));
        TrustedSkillScriptToolSpec spec = new TrustedSkillScriptToolSpec(
                definition,
                skill,
                "scripts/transform",
                SkillTrustDigests.content(script),
                "fixture-runtime",
                execution.configurationIdentity(),
                SkillTrustDigests.sandbox(execution.sandboxProfileIdentity()),
                Set.of("execution.run"),
                Duration.ofSeconds(5),
                "fixed transform",
                arguments -> TrustedScriptArguments.atWorkspaceRoot(List.of(String.valueOf(arguments.get("value")))));
        var provider = new TrustedSkillScriptToolProvider(
                execution,
                (binding, visibility) ->
                        new SkillContent(binding, "# fixture", Map.of("scripts/transform", script), 10),
                List.of(spec));

        provider.invoke(invocation(binding(definition)));

        assertThat(new String(captured.get().input().bytes(), StandardCharsets.UTF_8))
                .isEqualTo(script + "\n")
                .doesNotContain("MODEL_VALUE");
        assertThat(captured.get().command().argv()).endsWith("MODEL_VALUE");

        var drifted = new TrustedSkillScriptToolProvider(
                execution,
                (binding, visibility) ->
                        new SkillContent(binding, "# fixture", Map.of("scripts/transform", script + " "), 10),
                List.of(spec));
        assertThatThrownBy(() -> drifted.invoke(invocation(binding(definition))))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("content has drifted");
    }

    private static ExecutionToolProvider provider(
            AtomicReference<ExecutionRequest> captured,
            Set<String> capabilities,
            TrustedWorkspacePathValidator validator) {
        ExecutionBroker broker = new ExecutionBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request) {
                captured.set(request);
                return success(request.id());
            }

            @Override
            public boolean cancel(ExecutionId id) {
                return true;
            }

            @Override
            public Optional<ExecutionResult> find(ExecutionId id) {
                return Optional.empty();
            }
        };
        return provider(broker, capabilities, validator);
    }

    private static ExecutionToolProvider provider(
            ExecutionBroker broker, Set<String> capabilities, TrustedWorkspacePathValidator validator) {
        var runtimes = new ScriptRuntimeResolver(ExecutionOperatingSystem.LINUX, List.of(new ScriptRuntimeAdapter() {
            @Override
            public String language() {
                return "fixture-runtime";
            }

            @Override
            public String executable() {
                return "trusted-runtime";
            }

            @Override
            public PreparedScript prepare(String content, List<String> arguments) {
                var argv = new java.util.ArrayList<String>();
                argv.add(executable());
                argv.addAll(arguments);
                return new PreparedScript(
                        ExecutionCommand.direct(argv),
                        ExecutionInput.utf8(content.endsWith("\n") ? content : content + "\n"));
            }
        }));
        var configuration = new ExecutionToolConfiguration(
                ExecutionEnvironmentRef.empty(),
                new SandboxProfileRef("sandbox", "1"),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                4096,
                100,
                1,
                false,
                runtimes,
                ignored -> {},
                value -> value);
        return new ExecutionToolProvider(
                broker,
                () -> "execution-id",
                () -> NOW,
                ignored -> new ExecutionInvocationScopeResolver.ExecutionInvocationScope(
                        new WorkspaceId("workspace"), capabilities),
                configuration,
                validator);
    }

    private static ToolInvocationRequest invocation() {
        return invocation(binding());
    }

    private static ToolInvocationRequest invocation(FrozenToolBinding binding) {
        return invocation(binding, ToolInvocationObserver.noop());
    }

    private static ToolInvocationRequest invocation(FrozenToolBinding binding, ToolInvocationObserver observer) {
        return new ToolInvocationRequest(
                binding,
                new ToolCallId("call"),
                new AgentRunId("run"),
                new TenantRef("tenant"),
                new PrincipalRef("principal", "human"),
                new ToolArguments("fixture.input", "1.0.0", Map.of("value", "MODEL_VALUE")),
                NOW.plusSeconds(30),
                Optional.of("idempotency"),
                Optional.of("policy-decision"),
                () -> false,
                List.of(),
                observer);
    }

    private static FrozenToolBinding binding() {
        ToolName name = new ToolName("trusted.transform");
        SemanticVersion version = new SemanticVersion("1.0.0");
        ToolProviderId provider = new ToolProviderId("fixed-script");
        Map<String, Object> schema =
                Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", false);
        ToolDefinition definition = new ToolDefinition(
                name,
                version,
                provider,
                "Fixed transform",
                "Transforms one fixed value",
                new ToolSchema("fixture.input", "1.0.0", schema),
                new ToolSchema("fixture.output", "1.0.0", schema),
                ToolExecutionMode.HOST_PROCESS,
                true,
                Duration.ofSeconds(5),
                "fixture",
                ToolIdempotency.IDEMPOTENT,
                ToolRisk.HIGH,
                Set.of(ToolSideEffect.PROCESS_EXECUTION),
                ToolResourceRequirements.none(),
                List.of(),
                ToolApprovalRequirement.ALWAYS,
                "test",
                false,
                Set.of());
        return new FrozenToolBinding(
                new ToolAlias("trusted_transform"),
                new ToolCoordinate(name, version, provider, new ToolDefinitionHash("0".repeat(64))),
                definition,
                "fixture",
                "catalog");
    }

    private static FrozenToolBinding binding(ToolDefinition definition) {
        return new FrozenToolBinding(
                new ToolAlias("trusted_transform"),
                new ToolCoordinate(
                        definition.name(),
                        definition.version(),
                        definition.providerId(),
                        new ToolDefinitionHash("1".repeat(64))),
                definition,
                "fixture",
                "catalog");
    }

    private static ToolDefinition trustedDefinition(String executionDigest, String sandboxDigest) {
        ToolName name = new ToolName("trusted.transform");
        Map<String, Object> input = Map.of(
                "$schema",
                ToolSchema.DRAFT_2020_12,
                "type",
                "object",
                "additionalProperties",
                false,
                "properties",
                Map.of("value", Map.of("type", "string")));
        Map<String, Object> output =
                Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", false);
        return new ToolDefinition(
                name,
                new SemanticVersion("1.0.0"),
                TrustedSkillScriptToolProvider.PROVIDER_ID,
                "Fixed transform",
                "Transforms one fixed value",
                new ToolSchema("fixture.input", "1.0.0", input),
                new ToolSchema("fixture.output", "1.0.0", output),
                ToolExecutionMode.HOST_PROCESS,
                true,
                Duration.ofSeconds(5),
                "fixture",
                ToolIdempotency.IDEMPOTENT,
                ToolRisk.HIGH,
                Set.of(ToolSideEffect.PROCESS_EXECUTION),
                new ToolResourceRequirements(
                        Set.of("execution.run"), Set.of(), Set.of(executionDigest, "sandbox@" + sandboxDigest)),
                List.of(),
                ToolApprovalRequirement.ALWAYS,
                "test",
                false,
                Set.of());
    }

    private static FrozenSkillBinding skill() {
        SkillContentDigest packageDigest = new SkillContentDigest("sha256:" + "a".repeat(64));
        SkillName name = new SkillName("trusted-text-transform");
        SkillDeclaredVersion version = new SkillDeclaredVersion("1.0.0");
        SkillPackageIndex index = new SkillPackageIndex(
                packageDigest,
                List.of(
                        new SkillResourceRef(
                                "SKILL.md",
                                SkillResourceKind.INSTRUCTION,
                                "text/markdown",
                                new SkillContentDigest("sha256:" + "b".repeat(64)),
                                10,
                                true),
                        new SkillResourceRef(
                                "scripts/transform",
                                SkillResourceKind.SCRIPT,
                                "text/plain",
                                SkillTrustDigests.content("print('FROZEN_RESOURCE')"),
                                24,
                                true)));
        var coordinate = new io.haifa.agent.skill.api.SkillCoordinate(
                SkillScopeRef.product(), new SkillSourceRef("fixture", "1"), name, Optional.of(version), packageDigest);
        return new FrozenSkillBinding(
                new SkillAlias("trusted-text-transform"),
                coordinate,
                new SkillMetadata(
                        name,
                        "Transforms bounded text",
                        Optional.of(version),
                        Optional.empty(),
                        Optional.empty(),
                        Map.of(),
                        Set.of()),
                index,
                packageDigest,
                new SkillContentDigest("sha256:" + "c".repeat(64)),
                "fixture",
                Optional.of("package-grant"));
    }

    private static ExecutionResult success(ExecutionId id) {
        var output = new ExecutionOutput("ok", null, 2, "0".repeat(64), false, false);
        var empty = new ExecutionOutput("", null, 0, "0".repeat(64), false, false);
        return new ExecutionResult(
                id,
                ExecutionStatus.SUCCEEDED,
                0,
                NOW,
                NOW.plusMillis(10),
                output,
                empty,
                null,
                "sandbox-session",
                new ResourceUsageSummary(Duration.ofMillis(10), 1),
                null,
                false);
    }
}
