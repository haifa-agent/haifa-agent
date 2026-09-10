package io.haifa.agent.runtime.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.credential.api.CredentialDefinitionId;
import io.haifa.agent.credential.api.CredentialExposureMode;
import io.haifa.agent.credential.api.CredentialRequirement;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyAction;
import io.haifa.agent.policy.api.PolicyChallenge;
import io.haifa.agent.policy.api.PolicyContext;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyDecisionService;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyResource;
import io.haifa.agent.policy.api.PolicyRisk;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import io.haifa.agent.policy.api.PolicyRuleSet;
import io.haifa.agent.policy.api.PolicySideEffect;
import io.haifa.agent.policy.api.PolicySubject;
import io.haifa.agent.runtime.core.decision.ToolRequest;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DefaultPublicToolPolicyTest {
    private static final TenantRef TENANT = new TenantRef("tenant");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("principal", "human");

    @Test
    void criticalToolsCannotDisableApproval() {
        for (ApprovalMode approvalMode : ApprovalMode.values()) {
            PolicyDecision decision = evaluate(
                    approvalMode,
                    ToolRisk.CRITICAL,
                    Set.of(ToolSideEffect.FILE_READ),
                    ToolResourceRequirements.none(),
                    List.of(),
                    ToolApprovalRequirement.NEVER);

            assertThat(decision.effect()).as("approval mode %s", approvalMode).isEqualTo(PolicyEffect.DENY);
            assertThat(decision.challenge()).isEmpty();
            assertThat(decision.reasonCode()).isEqualTo("TOOL_CRITICAL_NEVER_CONTRADICTION");
        }
    }

    @Test
    void unconstrainedNetworkTargetDeniesBeforeApprovalRequirements() {
        for (ApprovalMode approvalMode : ApprovalMode.values()) {
            PolicyDecision decision = evaluate(
                    approvalMode,
                    ToolRisk.LOW,
                    Set.of(ToolSideEffect.NETWORK_ACCESS),
                    ToolResourceRequirements.none(),
                    List.of(),
                    ToolApprovalRequirement.REAUTHENTICATE);

            assertThat(decision.effect()).as("approval mode %s", approvalMode).isEqualTo(PolicyEffect.DENY);
            assertThat(decision.challenge()).isEmpty();
            assertThat(decision.reasonCode()).isEqualTo("TOOL_NETWORK_TARGET_UNCONSTRAINED");
        }
    }

    @Test
    void reauthenticationRequirementFollowsApprovalMode() {
        for (ApprovalMode approvalMode : ApprovalMode.values()) {
            PolicyDecision decision = evaluate(
                    approvalMode,
                    ToolRisk.LOW,
                    Set.of(ToolSideEffect.FILE_READ),
                    ToolResourceRequirements.none(),
                    List.of(),
                    ToolApprovalRequirement.REAUTHENTICATE);

            assertThat(decision.reasonCode()).isEqualTo("TOOL_REAUTHENTICATION_REQUIRED");
            if (approvalMode == ApprovalMode.DENY) {
                assertThat(decision.effect()).isEqualTo(PolicyEffect.DENY);
                assertThat(decision.challenge()).isEmpty();
            } else {
                assertThat(decision.effect()).isEqualTo(PolicyEffect.ASK);
                assertThat(decision.challenge()).contains(PolicyChallenge.REAUTHENTICATE);
            }
        }
    }

    @Test
    void alwaysRequirementFollowsApprovalMode() {
        for (ApprovalMode approvalMode : ApprovalMode.values()) {
            PolicyDecision decision = evaluate(
                    approvalMode,
                    ToolRisk.LOW,
                    Set.of(ToolSideEffect.FILE_READ),
                    ToolResourceRequirements.none(),
                    List.of(),
                    ToolApprovalRequirement.ALWAYS);

            PolicyEffect expected =
                    switch (approvalMode) {
                        case ASK -> PolicyEffect.ASK;
                        case AUTO -> PolicyEffect.ALLOW;
                        case DENY -> PolicyEffect.DENY;
                    };
            assertThat(decision.effect()).isEqualTo(expected);
            assertThat(decision.reasonCode()).isEqualTo("TOOL_APPROVAL_REQUIRED");
            if (expected == PolicyEffect.ASK) {
                assertThat(decision.challenge()).contains(PolicyChallenge.APPROVAL);
            } else {
                assertThat(decision.challenge()).isEmpty();
            }
        }
    }

    @Test
    void credentialRequirementsReachTheCurrentPolicyEvaluator() {
        for (ApprovalMode approvalMode : ApprovalMode.values()) {
            AtomicReference<PolicyRequest> received = new AtomicReference<>();
            DefaultPublicToolPolicy policy = policy(approvalMode, (request, rules) -> {
                received.set(request);
                return new PolicyDecision(
                        PolicyEffect.ASK,
                        Optional.of(PolicyChallenge.REAUTHENTICATE),
                        "CREDENTIAL_REAUTHENTICATION_REQUIRED",
                        "Credential use requires reauthentication",
                        "sha256:credential-reauthentication");
            });

            PolicyDecision decision = policy.evaluate(
                    null,
                    binding(
                            ToolRisk.LOW,
                            Set.of(ToolSideEffect.CREDENTIAL_USE),
                            ToolResourceRequirements.none(),
                            credentialRequirement(),
                            ToolApprovalRequirement.POLICY),
                    request());

            assertThat(received.get().risk().credentialRequired()).isTrue();
            assertThat(decision.effect()).isEqualTo(PolicyEffect.ASK);
            assertThat(decision.challenge()).contains(PolicyChallenge.REAUTHENTICATE);
            assertThat(decision.reasonCode()).isEqualTo("CREDENTIAL_REAUTHENTICATION_REQUIRED");
        }
    }

    private static PolicyDecision evaluate(
            ApprovalMode approvalMode,
            ToolRisk risk,
            Set<ToolSideEffect> sideEffects,
            ToolResourceRequirements resources,
            List<CredentialRequirement> credentials,
            ToolApprovalRequirement approvalRequirement) {
        return policy(approvalMode, (request, rules) -> allow())
                .evaluate(null, binding(risk, sideEffects, resources, credentials, approvalRequirement), request());
    }

    private static DefaultPublicToolPolicy policy(ApprovalMode approvalMode, PolicyDecisionService evaluator) {
        return new DefaultPublicToolPolicy(
                (ignoredRun, binding, ignoredRequest) -> policyRequest(approvalMode, binding),
                evaluator,
                PolicyRuleSet.of(List.of(), Optional.empty(), approvalMode));
    }

    private static PolicyRequest policyRequest(ApprovalMode approvalMode, FrozenToolBinding binding) {
        ToolDefinition definition = binding.definition();
        return new PolicyRequest(
                new PolicySubject(TENANT, PRINCIPAL, "runtime-core-test"),
                PolicyContext.run("run-1", approvalMode),
                new PolicyAction(definition.name().value(), "invoke"),
                new PolicyResource(
                        "tool",
                        binding.coordinate().externalForm(),
                        Optional.of("sha256:policy-test-resource"),
                        definition.title()),
                new PolicyRisk(
                        PolicyRiskLevel.valueOf(definition.risk().name()),
                        definition.sideEffects().stream()
                                .filter(effect -> effect != ToolSideEffect.FILE_READ)
                                .map(effect -> PolicySideEffect.valueOf(effect.name()))
                                .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                        !definition.credentialRequirements().isEmpty(),
                        definition.resources().networkHosts().isEmpty()
                                ? Optional.empty()
                                : Optional.of(
                                        String.join(",", definition.resources().networkHosts()))));
    }

    private static PolicyDecision allow() {
        return new PolicyDecision(
                PolicyEffect.ALLOW,
                Optional.empty(),
                "POLICY_ALLOWED",
                "Policy evaluator allowed the tool",
                "sha256:policy-allowed");
    }

    private static FrozenToolBinding binding(
            ToolRisk risk,
            Set<ToolSideEffect> sideEffects,
            ToolResourceRequirements resources,
            List<CredentialRequirement> credentials,
            ToolApprovalRequirement approvalRequirement) {
        ToolName name = new ToolName("policy.test");
        SemanticVersion version = new SemanticVersion("1.0.0");
        ToolProviderId provider = new ToolProviderId("runtime-core-test");
        Map<String, Object> schema =
                Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", false);
        ToolDefinition definition = new ToolDefinition(
                name,
                version,
                provider,
                "Policy test",
                "Exercises DefaultPublicToolPolicy",
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
                approvalRequirement,
                "test",
                false,
                Set.of());
        ToolCoordinate coordinate = new ToolCoordinate(name, version, provider, new ToolDefinitionHash("0".repeat(64)));
        return new FrozenToolBinding(new ToolAlias("policy_test"), coordinate, definition, "test", "catalog");
    }

    private static ToolRequest request() {
        return new ToolRequest(
                new ToolCallId("call"),
                new ProviderToolCallCorrelationId("provider-call"),
                new RuntimeIdempotencyKey("idempotency-key"),
                "policy_test",
                "1.0.0",
                new ToolArguments("policy.input", "1.0.0", Map.of()));
    }

    private static List<CredentialRequirement> credentialRequirement() {
        return List.of(new CredentialRequirement(
                new CredentialDefinitionId("api-key"),
                "invoke",
                Set.of("api:call"),
                CredentialExposureMode.HTTP_HEADER));
    }
}
