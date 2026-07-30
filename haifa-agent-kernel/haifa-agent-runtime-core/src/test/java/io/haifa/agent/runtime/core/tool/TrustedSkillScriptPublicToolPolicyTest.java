package io.haifa.agent.runtime.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyAction;
import io.haifa.agent.policy.api.PolicyChallenge;
import io.haifa.agent.policy.api.PolicyContext;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyDecisionId;
import io.haifa.agent.policy.api.PolicyDecisionStore;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyResource;
import io.haifa.agent.policy.api.PolicyRisk;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import io.haifa.agent.policy.api.PolicySideEffect;
import io.haifa.agent.policy.api.PolicySnapshotRef;
import io.haifa.agent.policy.api.PolicySubject;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillAlias;
import io.haifa.agent.skill.api.SkillContentDigest;
import io.haifa.agent.skill.api.SkillDeclaredVersion;
import io.haifa.agent.skill.api.SkillMetadata;
import io.haifa.agent.skill.api.SkillName;
import io.haifa.agent.skill.api.SkillOrigin;
import io.haifa.agent.skill.api.SkillPackageIndex;
import io.haifa.agent.skill.api.SkillPackageReviewGrant;
import io.haifa.agent.skill.api.SkillResourceKind;
import io.haifa.agent.skill.api.SkillResourceRef;
import io.haifa.agent.skill.api.SkillScopeRef;
import io.haifa.agent.skill.api.SkillScriptExecutionGrant;
import io.haifa.agent.skill.api.SkillSourceRef;
import io.haifa.agent.skill.api.SkillTrustDigests;
import io.haifa.agent.skill.api.SkillTrustGrantState;
import io.haifa.agent.skill.api.SkillTrustScope;
import io.haifa.agent.skill.api.SkillTrustSnapshot;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TrustedSkillScriptPublicToolPolicyTest {
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");
    private static final TenantRef TENANT = new TenantRef("tenant");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("principal", "human");
    private static final String PRODUCT = "test-product";
    private static final String PACKAGE_DIGEST = digest('a');
    private static final String REGISTRATION_DIGEST = digest('b');
    private static final String SCRIPT_DIGEST = digest('c');
    private static final String SANDBOX_DIGEST = digest('d');
    private static final String EXECUTION_DIGEST = "e".repeat(64);

    @Test
    void exactFrozenEvidenceProducesAuditedAllowWithoutDelegating() {
        Fixture fixture = fixture(SANDBOX_DIGEST, "trusted.transform");

        PolicyDecision decision =
                fixture.policy().evaluate(fixture.run(), fixture.tool(), request("trusted_transform"));

        assertThat(decision.effect()).isEqualTo(PolicyEffect.ALLOW);
        assertThat(decision.reasonCode()).isEqualTo(TrustedSkillScriptPublicToolPolicy.REASON_CODE);
        assertThat(decision.challenge()).isEmpty();
        assertThat(decision.matchedRule()).hasValueSatisfying(rule -> {
            assertThat(rule.ruleId()).isEqualTo("script-grant");
            assertThat(rule.version()).isEqualTo("package-grant");
        });
        assertThat(fixture.delegateCalls()).hasValue(0);
        assertThat(fixture.decisions().find(decision.id())).contains(decision);
    }

    @Test
    void sandboxDigestDriftFallsBackToOrdinaryApproval() {
        Fixture fixture = fixture(digest('f'), "trusted.transform");

        PolicyDecision decision =
                fixture.policy().evaluate(fixture.run(), fixture.tool(), request("trusted_transform"));

        assertThat(decision.effect()).isEqualTo(PolicyEffect.ASK);
        assertThat(decision.reasonCode()).isEqualTo("ORDINARY_APPROVAL");
        assertThat(fixture.delegateCalls()).hasValue(1);
    }

    @Test
    void forgedToolNameAndArgumentsCannotReuseAnotherFixedToolGrant() {
        Fixture fixture = fixture(SANDBOX_DIGEST, "trusted.transform");
        FrozenToolBinding forged = tool(
                "forged.transform",
                Map.of(
                        "trusted", Map.of("type", "boolean"),
                        "skill", Map.of("type", "string"),
                        "scriptPath", Map.of("type", "string")));

        PolicyDecision decision = fixture.policy().evaluate(fixture.run(), forged, request("forged_transform"));

        assertThat(decision.effect()).isEqualTo(PolicyEffect.ASK);
        assertThat(fixture.delegateCalls()).hasValue(1);
    }

    @Test
    void genericExecutionToolAlwaysUsesTheOrdinaryApprovalPath() {
        Fixture fixture = fixture(SANDBOX_DIGEST, "trusted.transform");
        FrozenToolBinding generic = tool("execution.run", Map.of("content", Map.of("type", "string")));

        PolicyDecision decision = fixture.policy().evaluate(fixture.run(), generic, request("execution_run"));

        assertThat(decision.effect()).isEqualTo(PolicyEffect.ASK);
        assertThat(fixture.delegateCalls()).hasValue(1);
    }

    private static Fixture fixture(String grantSandboxDigest, String toolName) {
        FrozenSkillBinding skill = skill();
        FrozenToolBinding tool = tool(toolName, Map.of("value", Map.of("type", "string")));
        SkillPackageReviewGrant packageGrant = new SkillPackageReviewGrant(
                "package-grant",
                1,
                1,
                TENANT,
                PRINCIPAL,
                PRODUCT,
                SkillTrustScope.PRODUCT,
                Optional.empty(),
                skill.coordinate(),
                skill.registrationDigest(),
                skill.resourceIndexDigest(),
                NOW.minusSeconds(60),
                Optional.of(NOW.plusSeconds(600)),
                Optional.empty(),
                SkillTrustGrantState.ACTIVE,
                "reviewer",
                "fixture",
                "SKILL_PACKAGE_REVIEWED");
        SkillScriptExecutionGrant scriptGrant = new SkillScriptExecutionGrant(
                "script-grant",
                1,
                1,
                packageGrant.id(),
                TENANT,
                PRINCIPAL,
                PRODUCT,
                SkillTrustScope.PRODUCT,
                Optional.empty(),
                skill.coordinate(),
                skill.registrationDigest(),
                skill.resourceIndexDigest(),
                "scripts/transform",
                new SkillContentDigest(SCRIPT_DIGEST),
                tool.coordinate(),
                tool.providerBindingReference(),
                tool.catalogDigest(),
                SkillTrustDigests.argumentPolicy(tool.coordinate()),
                "runtime",
                SkillTrustDigests.executionProfile(
                        "runtime",
                        tool.definition().resources().executionProfiles().stream()
                                .sorted()
                                .toList()),
                grantSandboxDigest,
                List.of("execution.run"),
                List.of(),
                NOW.minusSeconds(60),
                Optional.of(NOW.plusSeconds(600)),
                Optional.empty(),
                SkillTrustGrantState.ACTIVE,
                "reviewer",
                "fixture",
                "TRUSTED_SKILL_SCRIPT_REVIEWED");
        var trust = new SkillTrustSnapshot(digest('9'), List.of(packageGrant), List.of(scriptGrant));
        RunConfigurationSnapshotRef reference = new RunConfigurationSnapshotRef("configuration", digest('8'));
        var configuration = new RuntimeConfigurationSnapshot(
                reference,
                new AgentDefinitionId("test-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                PRODUCT,
                "1",
                AgentRunType.CHAT,
                budget(),
                limits(),
                List.of(tool),
                List.of(skill),
                new SkillContentDigest(PACKAGE_DIGEST),
                "skill-policy",
                trust,
                Set.of(),
                "Test instruction",
                RuntimeOverrides.NONE,
                List.of(),
                model());
        var state = new InMemoryRuntimeStore();
        state.saveConfiguration(configuration);
        AgentRun run = AgentRun.createRoot(
                new AgentRunId("run-1"),
                new AgentRunSpec(
                        new AgentSessionId("session-1"),
                        new ProjectRef("project-1"),
                        TENANT,
                        PRINCIPAL,
                        new AgentDefinitionId("test-agent"),
                        new AgentDefinitionVersion(1, 0, 0),
                        PRODUCT,
                        "1",
                        AgentRunType.CHAT,
                        "test",
                        budget(),
                        limits(),
                        reference),
                NOW);
        AtomicInteger delegateCalls = new AtomicInteger();
        MemoryDecisionStore decisions = new MemoryDecisionStore();
        PublicToolPolicy delegate = (ignoredRun, ignoredTool, ignoredRequest) -> {
            delegateCalls.incrementAndGet();
            return new PolicyDecision(
                    new PolicyDecisionId("ordinary-decision"),
                    PolicyEffect.ASK,
                    Optional.of(PolicyChallenge.APPROVAL),
                    "ORDINARY_APPROVAL",
                    "Ordinary approval remains required",
                    new PolicySnapshotRef("ordinary"),
                    Optional.empty(),
                    NOW);
        };
        ToolPolicyRequestAdapter adapter = (ignoredRun, binding, ignoredRequest) -> new PolicyRequest(
                new PolicySubject(TENANT, PRINCIPAL, PRODUCT),
                PolicyContext.run(run.id().value(), ApprovalMode.ASK),
                new PolicyAction("tool", "invoke"),
                new PolicyResource(
                        "tool",
                        binding.definition().name().value(),
                        Optional.of(binding.coordinate().definitionHash().value()),
                        "Fixed test tool"),
                new PolicyRisk(
                        PolicyRiskLevel.HIGH, Set.of(PolicySideEffect.PROCESS_EXECUTION), false, Optional.empty()));
        var policy = new TrustedSkillScriptPublicToolPolicy(
                delegate, state, adapter, () -> "trusted-decision", () -> NOW, decisions);
        return new Fixture(policy, run, tool, delegateCalls, decisions);
    }

    private static FrozenSkillBinding skill() {
        SkillContentDigest packageDigest = new SkillContentDigest(PACKAGE_DIGEST);
        SkillContentDigest registrationDigest = new SkillContentDigest(REGISTRATION_DIGEST);
        SkillName name = new SkillName("trusted-text-transform");
        SkillDeclaredVersion version = new SkillDeclaredVersion("1.0.0");
        SkillPackageIndex index = new SkillPackageIndex(
                packageDigest,
                List.of(
                        new SkillResourceRef(
                                "SKILL.md",
                                SkillResourceKind.INSTRUCTION,
                                "text/markdown",
                                new SkillContentDigest(digest('1')),
                                10,
                                true),
                        new SkillResourceRef(
                                "scripts/transform",
                                SkillResourceKind.SCRIPT,
                                "text/plain",
                                new SkillContentDigest(SCRIPT_DIGEST),
                                20,
                                true)));
        var coordinate = new io.haifa.agent.skill.api.SkillCoordinate(
                SkillScopeRef.product(), new SkillSourceRef("fixture", "1"), name, Optional.of(version), packageDigest);
        var metadata = new SkillMetadata(
                name,
                "Transforms bounded text for a test",
                Optional.of(version),
                Optional.empty(),
                Optional.empty(),
                Map.of("origin", SkillOrigin.BUNDLED.name()),
                Set.of());
        return new FrozenSkillBinding(
                new SkillAlias("trusted-text-transform"),
                coordinate,
                metadata,
                index,
                packageDigest,
                registrationDigest,
                "skill-policy",
                Optional.of("package-grant"));
    }

    private static FrozenToolBinding tool(String nameValue, Map<String, Object> properties) {
        ToolName name = new ToolName(nameValue);
        SemanticVersion version = new SemanticVersion("1.0.0");
        ToolProviderId provider = new ToolProviderId("fixed-script-fixture");
        Map<String, Object> input = Map.of(
                "$schema",
                ToolSchema.DRAFT_2020_12,
                "type",
                "object",
                "additionalProperties",
                false,
                "properties",
                properties);
        Map<String, Object> output =
                Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", false);
        ToolDefinition definition = new ToolDefinition(
                name,
                version,
                provider,
                "Fixed transform",
                "Transforms a bounded value",
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
                        Set.of("execution.run"), Set.of(), Set.of(EXECUTION_DIGEST, "sandbox@" + SANDBOX_DIGEST)),
                List.of(),
                ToolApprovalRequirement.ALWAYS,
                PRODUCT,
                false,
                Set.of());
        ToolCoordinate coordinate = new ToolCoordinate(name, version, provider, new ToolDefinitionHash("0".repeat(64)));
        return new FrozenToolBinding(
                new ToolAlias(nameValue.replace('.', '_')), coordinate, definition, "fixture", "catalog");
    }

    private static ToolRequest request(String alias) {
        return new ToolRequest(
                new ToolCallId("call-1"),
                new ProviderToolCallCorrelationId("provider-call-1"),
                new RuntimeIdempotencyKey("idempotency-1"),
                alias,
                "1.0.0",
                new ToolArguments("fixture.input", "1.0.0", Map.of("value", "hello")));
    }

    private static AgentRunBudget budget() {
        return new AgentRunBudget(100, 100, 100, 10, 10, 2, "USD", 100);
    }

    private static AgentRunLimits limits() {
        return new AgentRunLimits(10, 2, 1, 60_000, 10_000);
    }

    private static ResolvedModelSnapshot model() {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("test-provider"),
                "1",
                new ModelDefinitionId("test-model"),
                "1",
                "test-model",
                "test-adapter",
                "1",
                URI.create("https://example.test"),
                new CredentialRef("test-credential"),
                Set.of(ModelCapability.TEXT_CHAT),
                4096,
                1024,
                Map.of(),
                Map.of());
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record Fixture(
            TrustedSkillScriptPublicToolPolicy policy,
            AgentRun run,
            FrozenToolBinding tool,
            AtomicInteger delegateCalls,
            MemoryDecisionStore decisions) {}

    private static final class MemoryDecisionStore implements PolicyDecisionStore {
        private final Map<PolicyDecisionId, PolicyDecision> values = new HashMap<>();

        @Override
        public void save(PolicyDecision decision) {
            values.put(decision.id(), decision);
        }

        @Override
        public Optional<PolicyDecision> find(PolicyDecisionId id) {
            return Optional.ofNullable(values.get(id));
        }
    }
}
