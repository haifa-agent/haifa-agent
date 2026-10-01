package io.haifa.agent.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyAction;
import io.haifa.agent.policy.api.PolicyChallenge;
import io.haifa.agent.policy.api.PolicyContext;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyRequirementDigest;
import io.haifa.agent.policy.api.PolicyResource;
import io.haifa.agent.policy.api.PolicyRisk;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import io.haifa.agent.policy.api.PolicyRule;
import io.haifa.agent.policy.api.PolicyRuleMatcher;
import io.haifa.agent.policy.api.PolicyRuleRef;
import io.haifa.agent.policy.api.PolicyRuleSet;
import io.haifa.agent.policy.api.PolicyRuleSource;
import io.haifa.agent.policy.api.PolicySideEffect;
import io.haifa.agent.policy.api.PolicySubject;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.sdk.api.HaifaAgentException;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.contribution.ToolRegistration;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class StandardFileWriteToolPolicyTest {
    private static final String PRODUCT_ID = "test-product";

    @Test
    void emptyListLeavesOriginalContributionAndCatalogUnchanged() {
        var prepared = tools(new TestTool("writer", false));
        var base = standard();
        var digest = prepared.platform().catalog().snapshot().digest();
        assertThat(apply(base, Set.of(), prepared)).isSameAs(base);
        assertThat(prepared.platform().catalog().snapshot().digest()).isEqualTo(digest);
    }

    @Test
    void onlyExactFrozenJavaBindingGetsStandardAskProjection() {
        var prepared = tools(new TestTool("writer", false), new TestTool("other", false));
        var applied = apply(standard(), Set.of(new ToolName("writer")), prepared);
        var request = request(
                binding(prepared, "writer"),
                PolicyRiskLevel.MEDIUM,
                Set.of(PolicySideEffect.FILE_WRITE),
                ApprovalMode.ASK);
        assertThat(standard().evaluator().evaluate(request, standard().rules()).effect())
                .isEqualTo(PolicyEffect.ASK);
        var allowed = applied.evaluator().evaluate(request, applied.rules());
        assertThat(allowed.effect()).isEqualTo(PolicyEffect.ALLOW);
        assertThat(allowed.challenge()).isEmpty();
        assertThat(allowed.requirementDigest()).isEqualTo(PolicyRequirementDigest.compute(request, applied.rules()));
        assertThat(applied.evaluator()
                        .evaluate(
                                request(
                                        binding(prepared, "other"),
                                        PolicyRiskLevel.MEDIUM,
                                        Set.of(PolicySideEffect.FILE_WRITE),
                                        ApprovalMode.ASK),
                                applied.rules())
                        .effect())
                .isEqualTo(PolicyEffect.ASK);
        var spoofed = new PolicyRequest(
                request.subject(),
                request.context(),
                request.action(),
                new PolicyResource("tool", "writer@1.0.0#different#binding", Optional.empty(), "writer"),
                request.risk());
        assertThat(applied.evaluator().evaluate(spoofed, applied.rules()).effect())
                .isEqualTo(PolicyEffect.ASK);
        var otherProduct = new PolicyRequest(
                new PolicySubject(new TenantRef("tenant"), new PrincipalRef("user", "user"), "other"),
                request.context(),
                request.action(),
                request.resource(),
                request.risk());
        assertThat(applied.evaluator().evaluate(otherProduct, applied.rules()).effect())
                .isEqualTo(PolicyEffect.ASK);
    }

    @Test
    void customRulesAndDefaultsRemainAuthoritative() {
        var prepared = tools(new TestTool("writer", false));
        var request = request(
                binding(prepared, "writer"),
                PolicyRiskLevel.MEDIUM,
                Set.of(PolicySideEffect.FILE_WRITE),
                ApprovalMode.ASK);
        for (PolicyEffect effect : List.of(PolicyEffect.ASK, PolicyEffect.DENY)) {
            PolicyRule custom = rule(new PolicyRuleRef("custom-" + effect, "1"), effect);
            var rules = new ArrayList<>(standard().rules().rules());
            rules.add(custom);
            var applied = apply(
                    policy(PolicyRuleSet.of(rules, standard().rules().defaultRule(), ApprovalMode.ASK)),
                    Set.of(new ToolName("writer")),
                    prepared);
            assertThat(applied.evaluator().evaluate(request, applied.rules()).effect())
                    .isEqualTo(effect);
            var fallback = apply(
                    policy(PolicyRuleSet.of(List.of(), Optional.of(custom), ApprovalMode.ASK)),
                    Set.of(new ToolName("writer")),
                    prepared);
            assertThat(fallback.evaluator().evaluate(request, fallback.rules()).effect())
                    .isEqualTo(effect);
        }
    }

    @Test
    void nonAskModesRiskAndMixedEffectsNeverGainAnExemption() {
        var prepared = tools(new TestTool("writer", false));
        for (ApprovalMode mode : ApprovalMode.values()) {
            if (mode == ApprovalMode.ASK) continue;
            var rules = PolicyRuleSet.of(
                    standard().rules().rules(), standard().rules().defaultRule(), mode);
            var applied = apply(policy(rules), Set.of(new ToolName("writer")), prepared);
            var request = request(
                    binding(prepared, "writer"), PolicyRiskLevel.MEDIUM, Set.of(PolicySideEffect.FILE_WRITE), mode);
            assertThat(applied.evaluator().evaluate(request, applied.rules()).effect())
                    .isEqualTo(
                            policy(rules).evaluator().evaluate(request, rules).effect());
        }
        var applied = apply(standard(), Set.of(new ToolName("writer")), prepared);
        for (PolicyRiskLevel risk : List.of(PolicyRiskLevel.HIGH, PolicyRiskLevel.CRITICAL)) {
            assertThat(applied.evaluator()
                            .evaluate(
                                    request(
                                            binding(prepared, "writer"),
                                            risk,
                                            Set.of(PolicySideEffect.FILE_WRITE),
                                            ApprovalMode.ASK),
                                    applied.rules())
                            .effect())
                    .isNotEqualTo(PolicyEffect.ALLOW);
        }
        assertThat(applied.evaluator()
                        .evaluate(
                                request(
                                        binding(prepared, "writer"),
                                        PolicyRiskLevel.MEDIUM,
                                        Set.of(PolicySideEffect.FILE_WRITE, PolicySideEffect.NETWORK_ACCESS),
                                        ApprovalMode.ASK),
                                applied.rules())
                        .effect())
                .isEqualTo(PolicyEffect.ASK);
    }

    @Test
    void rejectsMissingAndIntegrationBindingsEvenWithForgedJavaProvenance() {
        var prepared = tools(new TestTool("writer", false));
        assertAssembly(
                "FILE_WRITE_AUTO_APPROVE_TOOL_UNAVAILABLE",
                () -> apply(standard(), Set.of(new ToolName("missing")), prepared));
        var external = ToolAssembly.prepare(
                null,
                List.of(),
                List.of(registration(
                        ToolRisk.MEDIUM, ToolApprovalRequirement.POLICY, Set.of(ToolSideEffect.FILE_WRITE))));
        assertThat(external.javaBindings()).isEmpty();
        assertAssembly(
                "FILE_WRITE_AUTO_APPROVE_TOOL_NOT_JAVA",
                () -> apply(standard(), Set.of(new ToolName("writer")), external));
    }

    @Test
    void rejectsUnsafeDefinitionsBeforeAnyInvocation() {
        var mixed = tools(new TestTool("writer", true));
        assertAssembly(
                "FILE_WRITE_AUTO_APPROVE_TOOL_UNSAFE", () -> apply(standard(), Set.of(new ToolName("writer")), mixed));
        for (ToolRisk risk : List.of(ToolRisk.HIGH, ToolRisk.CRITICAL)) {
            assertUnsafe(risk, ToolApprovalRequirement.POLICY);
        }
        for (ToolApprovalRequirement approval : List.of(
                ToolApprovalRequirement.ALWAYS,
                ToolApprovalRequirement.REAUTHENTICATE,
                ToolApprovalRequirement.NEVER)) {
            assertUnsafe(ToolRisk.MEDIUM, approval);
        }
    }

    @Test
    void rejectsCanonicalAndMarkerRefConflictsIncludingDefaultRules() {
        var prepared = tools(new TestTool("writer", false));
        var canonical = standard().rules().rules().stream()
                .filter(rule -> rule.matcher().requiredSideEffects().equals(Set.of(PolicySideEffect.FILE_WRITE)))
                .findFirst()
                .orElseThrow();
        assertAssembly(
                "POLICY_RULE_REF_CONFLICT",
                () -> apply(
                        policy(PolicyRuleSet.of(
                                List.of(rule(canonical.ref(), PolicyEffect.ASK)),
                                standard().rules().defaultRule(),
                                ApprovalMode.ASK)),
                        Set.of(new ToolName("writer")),
                        prepared));
        var marker = apply(standard(), Set.of(new ToolName("writer")), prepared).rules().rules().stream()
                .filter(rule -> rule.ref().ruleId().startsWith("sdk-standard-file-write-"))
                .findFirst()
                .orElseThrow();
        assertAssembly(
                "POLICY_RULE_REF_CONFLICT",
                () -> apply(
                        policy(PolicyRuleSet.of(
                                standard().rules().rules(),
                                Optional.of(rule(marker.ref(), PolicyEffect.DENY)),
                                ApprovalMode.ASK)),
                        Set.of(new ToolName("writer")),
                        prepared));
    }

    @Test
    void manifestsFreezeBothListsWithoutBroadeningEitherSideEffect() {
        var prepared = tools(new TestTool("writer", false), new TestTool("other", false), new NetworkTool());
        var first = apply(standard(), Set.of(new ToolName("writer")), prepared);
        var second = apply(standard(), Set.of(new ToolName("other")), prepared);
        assertThat(first.rules().contentDigest())
                .isNotEqualTo(second.rules().contentDigest())
                .isNotEqualTo(standard().rules().contentDigest());
        var network = ReadOnlyNetworkToolPolicy.apply(
                standard(), Set.of(new ToolName("reader")), prepared.platform().catalog(), PRODUCT_ID);
        var both = apply(network, Set.of(new ToolName("writer")), prepared);
        assertThat(both.rules().contentDigest())
                .isNotEqualTo(first.rules().contentDigest())
                .isNotEqualTo(network.rules().contentDigest());
        for (String name : List.of("writer", "reader")) {
            var effects = name.equals("writer")
                    ? Set.of(PolicySideEffect.FILE_WRITE)
                    : Set.of(PolicySideEffect.NETWORK_ACCESS);
            var request = request(binding(prepared, name), PolicyRiskLevel.MEDIUM, effects, ApprovalMode.ASK);
            var decision = both.evaluator().evaluate(request, both.rules());
            assertThat(decision.effect()).isEqualTo(PolicyEffect.ALLOW);
            assertThat(decision.requirementDigest()).isEqualTo(PolicyRequirementDigest.compute(request, both.rules()));
        }
        assertThat(both.evaluator()
                        .evaluate(
                                request(
                                        binding(prepared, "other"),
                                        PolicyRiskLevel.MEDIUM,
                                        Set.of(PolicySideEffect.FILE_WRITE),
                                        ApprovalMode.ASK),
                                both.rules())
                        .effect())
                .isEqualTo(PolicyEffect.ASK);
    }

    private static void assertUnsafe(ToolRisk risk, ToolApprovalRequirement approval) {
        var prepared = ToolAssembly.prepare(
                null, List.of(), List.of(registration(risk, approval, Set.of(ToolSideEffect.FILE_WRITE))));
        // Exercise declaration validation independently of the production Java-registration origin gate.
        assertAssembly(
                "FILE_WRITE_AUTO_APPROVE_TOOL_UNSAFE",
                () -> StandardFileWriteToolPolicy.apply(
                        standard(),
                        Set.of(new ToolName("writer")),
                        prepared.platform().catalog(),
                        Set.copyOf(prepared.platform().catalog().snapshot().bindings()),
                        PRODUCT_ID));
    }

    private static void assertAssembly(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo(code);
    }

    private static ToolAssembly.Prepared tools(JavaTool<?, ?>... values) {
        return ToolAssembly.prepare(null, List.of(values), List.of());
    }

    private static FrozenToolBinding binding(ToolAssembly.Prepared prepared, String name) {
        return prepared.platform().catalog().snapshot().bindings().stream()
                .filter(binding -> binding.definition().name().value().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static PolicyPlatformContribution apply(
            PolicyPlatformContribution base, Set<ToolName> names, ToolAssembly.Prepared prepared) {
        return StandardFileWriteToolPolicy.apply(
                base, names, prepared.platform().catalog(), prepared.javaBindings(), PRODUCT_ID);
    }

    private static PolicyPlatformContribution standard() {
        return policy(PolicyPresets.standardApproval());
    }

    private static PolicyPlatformContribution policy(PolicyRuleSet rules) {
        return new PolicyPlatformContribution(rules, new DefaultPolicyDecisionService());
    }

    private static PolicyRule rule(PolicyRuleRef ref, PolicyEffect effect) {
        return new PolicyRule(
                ref,
                PolicyRuleSource.MANAGED,
                500,
                new PolicyRuleMatcher(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of("writer"),
                        Optional.of("invoke"),
                        Optional.of("tool"),
                        Optional.empty(),
                        Set.of()),
                effect,
                effect == PolicyEffect.ASK ? Optional.of(PolicyChallenge.APPROVAL) : Optional.empty(),
                "TEST_CUSTOM_" + effect,
                "Custom policy");
    }

    private static PolicyRequest request(
            FrozenToolBinding binding, PolicyRiskLevel risk, Set<PolicySideEffect> effects, ApprovalMode mode) {
        return new PolicyRequest(
                new PolicySubject(new TenantRef("tenant"), new PrincipalRef("user", "user"), PRODUCT_ID),
                new PolicyContext(
                        Optional.empty(),
                        Optional.of("session"),
                        Optional.of("run"),
                        Optional.empty(),
                        mode,
                        Optional.empty()),
                new PolicyAction(binding.definition().name().value(), "invoke"),
                new PolicyResource(
                        "tool", binding.coordinate().externalForm(), Optional.of("arguments-digest"), "Tool"),
                new PolicyRisk(risk, effects, false, Optional.of("api.example.com")));
    }

    private static ToolRegistration registration(
            ToolRisk risk, ToolApprovalRequirement approval, Set<ToolSideEffect> effects) {
        ToolProviderId id = new ToolProviderId("java.writer");
        ToolSchema schema = new ToolSchema(
                "test.input",
                "1.0.0",
                Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", true));
        ToolDefinition definition = new ToolDefinition(
                new ToolName("writer"),
                new SemanticVersion("1.0.0"),
                id,
                "Writer",
                "Integration writer",
                schema,
                schema,
                ToolExecutionMode.IN_PROCESS,
                true,
                Duration.ofSeconds(30),
                "per-run",
                ToolIdempotency.UNKNOWN,
                risk,
                effects,
                ToolResourceRequirements.none(),
                List.of(),
                approval,
                "java-sdk",
                false,
                Set.of());
        ToolProvider provider = new ToolProvider() {
            @Override
            public ToolProviderId id() {
                return id;
            }

            @Override
            public io.haifa.agent.core.tool.ToolResult invoke(io.haifa.agent.tool.api.ToolInvocationRequest request) {
                throw new AssertionError("assembly test must never invoke a Tool");
            }
        };
        return new ToolRegistration(new ToolAlias("writer"), definition, "java-tool:writer@1.0.0", provider);
    }

    public record Request(String value) {}

    public record Response(String value) {}

    private record TestTool(String name, boolean mixed) implements JavaTool<Request, Response> {
        @Override
        public JavaToolSpec<Request, Response> spec() {
            var builder =
                    JavaToolSpec.builder(name, Request.class, Response.class).description("Writes a file");
            if (mixed)
                builder.networkAccess("api.example.com")
                        .sideEffects(ToolSideEffect.FILE_WRITE, ToolSideEffect.NETWORK_ACCESS);
            else builder.sideEffects(ToolSideEffect.FILE_WRITE);
            return builder.build();
        }

        @Override
        public Response invoke(Request request, JavaToolContext context) {
            return new Response(request.value());
        }
    }

    private static final class NetworkTool implements JavaTool<Request, Response> {
        @Override
        public JavaToolSpec<Request, Response> spec() {
            return JavaToolSpec.builder("reader", Request.class, Response.class)
                    .description("Reads an exact host")
                    .networkAccess("api.example.com")
                    .build();
        }

        @Override
        public Response invoke(Request request, JavaToolContext context) {
            return new Response(request.value());
        }
    }
}
