package io.haifa.agent.runtime.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.credential.api.CredentialDefinitionId;
import io.haifa.agent.credential.api.CredentialExposureMode;
import io.haifa.agent.credential.api.CredentialRequirement;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultToolPolicyTest {
    private final DefaultToolPolicy policy = new DefaultToolPolicy();

    @Test
    void appliesConservativeDefaultDecisionMatrix() {
        assertThat(evaluate(
                        ToolRisk.LOW,
                        Set.of(ToolSideEffect.FILE_READ),
                        ToolResourceRequirements.none(),
                        List.of(),
                        ToolApprovalRequirement.POLICY))
                .isEqualTo(ToolPolicyDecision.ALLOW);
        assertThat(evaluate(
                        ToolRisk.LOW,
                        Set.of(ToolSideEffect.FILE_WRITE),
                        ToolResourceRequirements.none(),
                        List.of(),
                        ToolApprovalRequirement.POLICY))
                .isEqualTo(ToolPolicyDecision.REQUIRE_APPROVAL);
        assertThat(evaluate(
                        ToolRisk.LOW,
                        Set.of(ToolSideEffect.PROCESS_EXECUTION),
                        ToolResourceRequirements.none(),
                        List.of(),
                        ToolApprovalRequirement.POLICY))
                .isEqualTo(ToolPolicyDecision.REQUIRE_APPROVAL);
        assertThat(evaluate(
                        ToolRisk.LOW,
                        Set.of(ToolSideEffect.NETWORK_ACCESS),
                        new ToolResourceRequirements(Set.of(), Set.of("api.example.test"), Set.of()),
                        List.of(),
                        ToolApprovalRequirement.POLICY))
                .isEqualTo(ToolPolicyDecision.REQUIRE_APPROVAL);
        assertThat(evaluate(
                        ToolRisk.LOW,
                        Set.of(ToolSideEffect.NETWORK_ACCESS),
                        ToolResourceRequirements.none(),
                        List.of(),
                        ToolApprovalRequirement.POLICY))
                .isEqualTo(ToolPolicyDecision.DENY);
        assertThat(evaluate(
                        ToolRisk.LOW,
                        Set.of(ToolSideEffect.CREDENTIAL_USE),
                        ToolResourceRequirements.none(),
                        credentialRequirement(),
                        ToolApprovalRequirement.POLICY))
                .isEqualTo(ToolPolicyDecision.REQUIRE_REAUTHENTICATION);
        assertThat(evaluate(
                        ToolRisk.CRITICAL,
                        Set.of(ToolSideEffect.FILE_READ),
                        ToolResourceRequirements.none(),
                        List.of(),
                        ToolApprovalRequirement.NEVER))
                .isEqualTo(ToolPolicyDecision.DENY);
        assertThat(evaluate(
                        ToolRisk.LOW,
                        Set.of(ToolSideEffect.FILE_READ),
                        ToolResourceRequirements.none(),
                        List.of(),
                        ToolApprovalRequirement.ALWAYS))
                .isEqualTo(ToolPolicyDecision.REQUIRE_APPROVAL);
    }

    private ToolPolicyDecision evaluate(
            ToolRisk risk,
            Set<ToolSideEffect> sideEffects,
            ToolResourceRequirements resources,
            List<CredentialRequirement> credentials,
            ToolApprovalRequirement approval) {
        return policy.evaluate(null, binding(risk, sideEffects, resources, credentials, approval), null);
    }

    private static FrozenToolBinding binding(
            ToolRisk risk,
            Set<ToolSideEffect> sideEffects,
            ToolResourceRequirements resources,
            List<CredentialRequirement> credentials,
            ToolApprovalRequirement approval) {
        var name = new ToolName("policy.test");
        var version = new SemanticVersion("1.0.0");
        var provider = new ToolProviderId("runtime-test");
        Map<String, Object> schema =
                Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", false);
        var definition = new ToolDefinition(
                name,
                version,
                provider,
                "Policy test",
                "Exercises the default tool policy",
                new ToolSchema("policy.input", "1.0.0", schema),
                new ToolSchema("policy.output", "1.0.0", schema),
                ToolExecutionMode.IN_PROCESS,
                true,
                Duration.ofSeconds(5),
                "serial",
                ToolIdempotency.UNKNOWN,
                risk,
                sideEffects,
                resources,
                credentials,
                approval,
                "test",
                false,
                Set.of());
        var coordinate = new ToolCoordinate(name, version, provider, new ToolDefinitionHash("0".repeat(64)));
        return new FrozenToolBinding(new ToolAlias("policy_test"), coordinate, definition, "test", "catalog");
    }

    private static List<CredentialRequirement> credentialRequirement() {
        return List.of(new CredentialRequirement(
                new CredentialDefinitionId("api-key"),
                "invoke",
                Set.of("api:call"),
                CredentialExposureMode.HTTP_HEADER));
    }
}
