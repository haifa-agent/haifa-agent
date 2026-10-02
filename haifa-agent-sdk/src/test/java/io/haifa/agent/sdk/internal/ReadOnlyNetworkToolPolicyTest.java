package io.haifa.agent.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyRequirementDigest;
import io.haifa.agent.policy.api.PolicyRisk;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import io.haifa.agent.policy.api.PolicyRule;
import io.haifa.agent.policy.api.PolicyRuleMatcher;
import io.haifa.agent.policy.api.PolicyRuleRef;
import io.haifa.agent.policy.api.PolicyRuleSet;
import io.haifa.agent.policy.api.PolicyRuleSource;
import io.haifa.agent.policy.api.PolicySideEffect;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.sdk.SdkTestFixtures;
import io.haifa.agent.sdk.api.HaifaAgentException;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.contribution.ToolRegistration;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolCatalog;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class ReadOnlyNetworkToolPolicyTest {
    private static final String PRODUCT_ID = "test-product";

    @Test
    void emptyListReturnsBaseContributionUnchanged() {
        PolicyPlatformContribution base = standardPolicy();

        assertThat(ReadOnlyNetworkToolPolicy.apply(base, Set.of(), catalog(new WebFetchTool()), PRODUCT_ID))
                .isSameAs(base);
    }

    @Test
    void allowsListedReadOnlyNetworkToolAndBindsTheRealFrozenDigest() {
        ToolCatalog catalog = catalog(new WebFetchTool());
        PolicyPlatformContribution applied = ReadOnlyNetworkToolPolicy.apply(
                standardPolicy(), Set.of(new ToolName("web_fetch")), catalog, PRODUCT_ID);
        PolicyRequest request = request("web_fetch", PolicyRiskLevel.MEDIUM, Set.of(PolicySideEffect.NETWORK_ACCESS));

        assertThat(new DefaultPolicyDecisionService()
                        .evaluate(request, standardPolicy().rules())
                        .effect())
                .as("without the manifest the standard preset still asks")
                .isEqualTo(PolicyEffect.ASK);

        PolicyDecision decision = applied.evaluator().evaluate(request, applied.rules());

        assertThat(decision.effect()).isEqualTo(PolicyEffect.ALLOW);
        assertThat(decision.challenge()).isEmpty();
        assertThat(decision.requirementDigest()).isEqualTo(PolicyRequirementDigest.compute(request, applied.rules()));
    }

    @Test
    void keepsAskForAnUnlistedNetworkTool() {
        ToolCatalog catalog = catalog(new WebFetchTool());
        PolicyPlatformContribution applied = ReadOnlyNetworkToolPolicy.apply(
                standardPolicy(), Set.of(new ToolName("web_fetch")), catalog, PRODUCT_ID);
        PolicyRequest request = request("web_search", PolicyRiskLevel.MEDIUM, Set.of(PolicySideEffect.NETWORK_ACCESS));

        assertThat(applied.evaluator().evaluate(request, applied.rules()).effect())
                .isEqualTo(PolicyEffect.ASK);
    }

    @Test
    void doesNotExemptAMutatedRequestWithMixedEffectsOrMissingTargets() {
        PolicyPlatformContribution applied = ReadOnlyNetworkToolPolicy.apply(
                standardPolicy(), Set.of(new ToolName("web_fetch")), catalog(new WebFetchTool()), PRODUCT_ID);
        PolicyRequest mixed = request(
                "web_fetch",
                PolicyRiskLevel.MEDIUM,
                Set.of(PolicySideEffect.NETWORK_ACCESS, PolicySideEffect.FILE_WRITE));
        assertThat(applied.evaluator().evaluate(mixed, applied.rules()).effect())
                .isEqualTo(PolicyEffect.ASK);
        PolicyRequest original = request("web_fetch", PolicyRiskLevel.MEDIUM, Set.of(PolicySideEffect.NETWORK_ACCESS));
        PolicyRequest missingTarget = new PolicyRequest(
                original.subject(),
                original.context(),
                original.action(),
                original.resource(),
                new PolicyRisk(
                        PolicyRiskLevel.MEDIUM, Set.of(PolicySideEffect.NETWORK_ACCESS), false, Optional.empty()));
        assertThat(applied.evaluator().evaluate(missingTarget, applied.rules()).effect())
                .isEqualTo(PolicyEffect.ASK);
        for (PolicyRiskLevel level : List.of(PolicyRiskLevel.HIGH, PolicyRiskLevel.CRITICAL)) {
            PolicyRequest high = request("web_fetch", level, Set.of(PolicySideEffect.NETWORK_ACCESS));
            assertThat(applied.evaluator().evaluate(high, applied.rules()).effect())
                    .isNotEqualTo(PolicyEffect.ALLOW);
        }
    }

    @Test
    void preservesAnOriginalDeny() {
        PolicyPlatformContribution applied = ReadOnlyNetworkToolPolicy.apply(
                standardPolicyWith(denyRule("web_fetch", 500)),
                Set.of(new ToolName("web_fetch")),
                catalog(new WebFetchTool()),
                PRODUCT_ID);
        PolicyRequest request = request("web_fetch", PolicyRiskLevel.MEDIUM, Set.of(PolicySideEffect.NETWORK_ACCESS));

        assertThat(applied.evaluator().evaluate(request, applied.rules()).effect())
                .isEqualTo(PolicyEffect.DENY);
    }

    @Test
    void preservesACustomAskThatIsNotTheStandardPresetRule() {
        PolicyPlatformContribution applied = ReadOnlyNetworkToolPolicy.apply(
                standardPolicyWith(askRule("web_fetch", 500)),
                Set.of(new ToolName("web_fetch")),
                catalog(new WebFetchTool()),
                PRODUCT_ID);
        PolicyRequest request = request("web_fetch", PolicyRiskLevel.MEDIUM, Set.of(PolicySideEffect.NETWORK_ACCESS));

        PolicyDecision decision = applied.evaluator().evaluate(request, applied.rules());

        assertThat(decision.effect()).isEqualTo(PolicyEffect.ASK);
        assertThat(decision.reasonCode()).isEqualTo("TEST_CUSTOM_ASK");
    }

    @Test
    void manifestDoesNotOverrideACustomDefaultAskOrDeny() {
        PolicyRequest request = request("web_fetch", PolicyRiskLevel.MEDIUM, Set.of(PolicySideEffect.NETWORK_ACCESS));
        for (PolicyRule fallback : List.of(askRule("web_fetch", 500), denyRule("web_fetch", 500))) {
            PolicyRuleSet rules = PolicyRuleSet.of(List.of(), Optional.of(fallback), ApprovalMode.ASK);
            var applied = ReadOnlyNetworkToolPolicy.apply(
                    new PolicyPlatformContribution(rules, new DefaultPolicyDecisionService()),
                    Set.of(new ToolName("web_fetch")),
                    catalog(new WebFetchTool()),
                    PRODUCT_ID);
            assertThat(applied.evaluator().evaluate(request, applied.rules()).effect())
                    .as("SDK manifest must not shadow a product default rule")
                    .isEqualTo(fallback.effect());
        }
    }

    @Test
    void nonAskApprovalModeIsNotAutomaticallyChanged() {
        PolicyRuleSet auto = PolicyRuleSet.of(
                PolicyPresets.standardApproval().rules(),
                PolicyPresets.standardApproval().defaultRule(),
                ApprovalMode.AUTO);
        PolicyPlatformContribution applied = ReadOnlyNetworkToolPolicy.apply(
                new PolicyPlatformContribution(auto, new DefaultPolicyDecisionService()),
                Set.of(new ToolName("web_fetch")),
                catalog(new WebFetchTool()),
                PRODUCT_ID);
        PolicyRequest request = request("web_fetch", PolicyRiskLevel.MEDIUM, Set.of(PolicySideEffect.NETWORK_ACCESS));

        assertThat(applied.evaluator().evaluate(request, applied.rules()).effect())
                .isEqualTo(PolicyEffect.ASK);
    }

    @Test
    void rejectsACustomRuleThatReusesTheStandardRefWithDifferentContent() {
        PolicyRule canonical = standardNetworkAskRule();
        PolicyRule impostor = new PolicyRule(
                canonical.ref(),
                PolicyRuleSource.MANAGED,
                100,
                new PolicyRuleMatcher(
                        Optional.empty(),
                        Optional.of(PRODUCT_ID),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of("web_fetch"),
                        Optional.of("invoke"),
                        Optional.of("tool"),
                        Optional.empty(),
                        Set.of(PolicySideEffect.NETWORK_ACCESS)),
                PolicyEffect.ASK,
                Optional.of(io.haifa.agent.policy.api.PolicyChallenge.APPROVAL),
                "IMPOSTOR_ASK",
                "A custom rule that only shares the standard ref");

        assertThatThrownBy(() -> ReadOnlyNetworkToolPolicy.apply(
                        standardPolicyReplacingNetworkAsk(impostor),
                        Set.of(new ToolName("web_fetch")),
                        catalog(new WebFetchTool()),
                        PRODUCT_ID))
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("POLICY_RULE_REF_CONFLICT");
    }

    @Test
    void rejectsAnUnsafeToolContract() {
        assertThatThrownBy(() -> ReadOnlyNetworkToolPolicy.apply(
                        standardPolicy(), Set.of(new ToolName("writer")), catalog(new FileWriteTool()), PRODUCT_ID))
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("NETWORK_AUTO_APPROVE_TOOL_UNSAFE");
        assertThatThrownBy(() -> ReadOnlyNetworkToolPolicy.apply(
                        standardPolicy(),
                        Set.of(new ToolName("mixed")),
                        catalog(new MixedSideEffectTool()),
                        PRODUCT_ID))
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("NETWORK_AUTO_APPROVE_TOOL_UNSAFE");
        assertThatThrownBy(() -> ReadOnlyNetworkToolPolicy.apply(
                        standardPolicy(),
                        Set.of(new ToolName("high_risk")),
                        ToolAssembly.prepare(null, List.of(), List.of(highRiskRegistration()))
                                .platform()
                                .catalog(),
                        PRODUCT_ID))
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("NETWORK_AUTO_APPROVE_TOOL_UNSAFE");
    }

    @Test
    void rejectsAListedToolMissingFromTheCatalog() {
        assertThatThrownBy(() -> ReadOnlyNetworkToolPolicy.apply(
                        standardPolicy(), Set.of(new ToolName("missing")), catalog(new WebFetchTool()), PRODUCT_ID))
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("NETWORK_AUTO_APPROVE_TOOL_UNAVAILABLE");
    }

    @Test
    void rejectsCriticalOrUnconstrainedPublicRegistrations() {
        for (ToolRegistration registration : List.of(
                highRiskRegistration(ToolRisk.CRITICAL, Set.of("api.example.com")),
                highRiskRegistration(ToolRisk.MEDIUM, Set.of()),
                highRiskRegistration(ToolRisk.MEDIUM, Set.of("*")))) {
            ToolCatalog catalog = ToolAssembly.prepare(null, List.of(), List.of(registration))
                    .platform()
                    .catalog();
            assertThatThrownBy(() -> ReadOnlyNetworkToolPolicy.apply(
                            standardPolicy(), Set.of(new ToolName("high_risk")), catalog, PRODUCT_ID))
                    .isInstanceOf(HaifaAgentException.class)
                    .extracting("code")
                    .isEqualTo("NETWORK_AUTO_APPROVE_TOOL_UNSAFE");
        }
    }

    @Test
    void listChangesTheFrozenPolicyDigest() {
        ToolCatalog catalog = catalog(new WebFetchTool(), new WebSearchTool());
        PolicyPlatformContribution first = ReadOnlyNetworkToolPolicy.apply(
                standardPolicy(), Set.of(new ToolName("web_fetch")), catalog, PRODUCT_ID);
        PolicyPlatformContribution second = ReadOnlyNetworkToolPolicy.apply(
                standardPolicy(), Set.of(new ToolName("web_search")), catalog, PRODUCT_ID);

        assertThat(first.rules().contentDigest()).isNotEqualTo(second.rules().contentDigest());
        assertThat(first.rules().contentDigest())
                .isNotEqualTo(standardPolicy().rules().contentDigest());
    }

    private static PolicyPlatformContribution standardPolicy() {
        return new PolicyPlatformContribution(PolicyPresets.standardApproval(), new DefaultPolicyDecisionService());
    }

    private static PolicyPlatformContribution standardPolicyReplacingNetworkAsk(PolicyRule replacement) {
        PolicyRule canonical = standardNetworkAskRule();
        PolicyRuleSet standard = PolicyPresets.standardApproval();
        List<PolicyRule> rules = standard.rules().stream()
                .filter(rule -> !rule.equals(canonical))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        rules.add(replacement);
        return new PolicyPlatformContribution(
                PolicyRuleSet.of(rules, standard.defaultRule(), standard.approvalMode()),
                new DefaultPolicyDecisionService());
    }

    private static PolicyPlatformContribution standardPolicyWith(PolicyRule extra) {
        PolicyRuleSet standard = PolicyPresets.standardApproval();
        List<PolicyRule> rules = new java.util.ArrayList<>(standard.rules());
        rules.add(extra);
        return new PolicyPlatformContribution(
                PolicyRuleSet.of(rules, standard.defaultRule(), standard.approvalMode()),
                new DefaultPolicyDecisionService());
    }

    private static PolicyRule denyRule(String capability, int priority) {
        return new PolicyRule(
                new PolicyRuleRef("test-deny-" + capability, "1"),
                PolicyRuleSource.MANAGED,
                priority,
                new PolicyRuleMatcher(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(capability),
                        Optional.of("invoke"),
                        Optional.of("tool"),
                        Optional.empty(),
                        Set.of()),
                PolicyEffect.DENY,
                Optional.empty(),
                "TEST_CUSTOM_DENY",
                "Custom deny");
    }

    private static PolicyRule askRule(String capability, int priority) {
        return new PolicyRule(
                new PolicyRuleRef("test-ask-" + capability, "1"),
                PolicyRuleSource.MANAGED,
                priority,
                new PolicyRuleMatcher(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(capability),
                        Optional.of("invoke"),
                        Optional.of("tool"),
                        Optional.empty(),
                        Set.of()),
                PolicyEffect.ASK,
                Optional.of(io.haifa.agent.policy.api.PolicyChallenge.APPROVAL),
                "TEST_CUSTOM_ASK",
                "Custom ask");
    }

    private static PolicyRule standardNetworkAskRule() {
        return PolicyPresets.standardApproval().rules().stream()
                .filter(rule -> rule.effect() == PolicyEffect.ASK
                        && rule.matcher().requiredSideEffects().equals(Set.of(PolicySideEffect.NETWORK_ACCESS)))
                .findFirst()
                .orElseThrow();
    }

    private static PolicyRequest request(String capability, PolicyRiskLevel level, Set<PolicySideEffect> effects) {
        return SdkTestFixtures.policyRequest(
                PRODUCT_ID, capability, capability + "@1.0.0", level, effects, ApprovalMode.ASK);
    }

    private static ToolCatalog catalog(JavaTool<?, ?>... tools) {
        return ToolAssembly.prepare(null, List.of(tools), List.of()).platform().catalog();
    }

    private static ToolRegistration highRiskRegistration() {
        return highRiskRegistration(ToolRisk.HIGH, Set.of("api.example.com"));
    }

    private static ToolRegistration highRiskRegistration(ToolRisk risk, Set<String> hosts) {
        return SdkTestFixtures.toolRegistration(
                "high_risk", risk, ToolApprovalRequirement.POLICY, Set.of(ToolSideEffect.NETWORK_ACCESS), hosts);
    }

    public record Request(String value) {}

    public record Response(String value) {}

    private static final class WebFetchTool implements JavaTool<Request, Response> {
        @Override
        public JavaToolSpec<Request, Response> spec() {
            return JavaToolSpec.builder("web_fetch", Request.class, Response.class)
                    .description("Fetches an exact host")
                    .networkAccess("api.example.com")
                    .build();
        }

        @Override
        public Response invoke(Request input, JavaToolContext context) {
            return new Response(input.value());
        }
    }

    private static final class WebSearchTool implements JavaTool<Request, Response> {
        @Override
        public JavaToolSpec<Request, Response> spec() {
            return JavaToolSpec.builder("web_search", Request.class, Response.class)
                    .description("Searches an exact host")
                    .networkAccess("api.example.com")
                    .build();
        }

        @Override
        public Response invoke(Request input, JavaToolContext context) {
            return new Response(input.value());
        }
    }

    private static final class FileWriteTool implements JavaTool<Request, Response> {
        @Override
        public JavaToolSpec<Request, Response> spec() {
            return JavaToolSpec.builder("writer", Request.class, Response.class)
                    .description("Writes a file")
                    .sideEffects(ToolSideEffect.FILE_WRITE)
                    .build();
        }

        @Override
        public Response invoke(Request input, JavaToolContext context) {
            return new Response(input.value());
        }
    }

    private static final class MixedSideEffectTool implements JavaTool<Request, Response> {
        @Override
        public JavaToolSpec<Request, Response> spec() {
            return JavaToolSpec.builder("mixed", Request.class, Response.class)
                    .description("Reads a host and writes a file")
                    .networkAccess("api.example.com")
                    .sideEffects(ToolSideEffect.FILE_WRITE, ToolSideEffect.NETWORK_ACCESS)
                    .build();
        }

        @Override
        public Response invoke(Request input, JavaToolContext context) {
            return new Response(input.value());
        }
    }
}
