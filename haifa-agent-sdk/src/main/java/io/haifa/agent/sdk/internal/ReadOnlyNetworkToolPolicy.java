package io.haifa.agent.sdk.internal;

import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyDecisionService;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyRequirementDigest;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import io.haifa.agent.policy.api.PolicyRule;
import io.haifa.agent.policy.api.PolicyRuleMatcher;
import io.haifa.agent.policy.api.PolicyRuleRef;
import io.haifa.agent.policy.api.PolicyRuleSet;
import io.haifa.agent.policy.api.PolicyRuleSource;
import io.haifa.agent.policy.api.PolicySideEffect;
import io.haifa.agent.sdk.api.HaifaAgentException;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolCatalog;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.net.IDN;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * SDK-owned policy projection for read-only network Tools that a product explicitly auto-approves.
 *
 * <p>This is not a second policy mechanism: it wraps the product's own {@link PolicyDecisionService}.
 * The auto-approval list becomes narrow {@code ALLOW} marker rules inside the frozen
 * {@link PolicyRuleSet}, so the list participates in the requirement digest. At decision time the
 * original evaluator is always consulted first and its {@code DENY} is preserved. Only for the exact
 * listed Tools does the wrapper re-run the same evaluator against a request-local view with the
 * canonical standard-preset network {@code ASK} rule removed; if that view is not {@code ALLOW} the
 * original decision stands. The original rules and catalog are never mutated.
 */
public final class ReadOnlyNetworkToolPolicy implements PolicyDecisionService {
    private static final String MARKER_PREFIX = "sdk-readonly-network-";
    // Configuration-only marker: it must never match the Runtime's "invoke" action,
    // otherwise it could shadow a product's default ASK/DENY after the preset ASK is removed.
    private static final String MANIFEST_OPERATION = "declare-read-only-network";
    private static final int HASH_PREFIX_LENGTH = 12;

    private final PolicyDecisionService delegate;
    private final Set<String> capabilities;
    private final PolicyRuleSet reducedRules;

    private ReadOnlyNetworkToolPolicy(
            PolicyDecisionService delegate, Set<String> capabilities, PolicyRuleSet reducedRules) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
        this.reducedRules = Objects.requireNonNull(reducedRules, "reducedRules must not be null");
    }

    /**
     * Projects the auto-approval list into the product policy and returns the effective contribution.
     * An empty list returns the supplied contribution unchanged.
     */
    public static PolicyPlatformContribution apply(
            PolicyPlatformContribution base, Set<ToolName> toolNames, ToolCatalog catalog, String productId) {
        Objects.requireNonNull(base, "policy contribution must not be null");
        Objects.requireNonNull(toolNames, "toolNames must not be null");
        Objects.requireNonNull(catalog, "catalog must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        if (toolNames.isEmpty()) return base;

        PolicyRule canonicalNetworkAsk = canonicalNetworkAskRule();
        rejectConflictingRef(base.rules(), canonicalNetworkAsk, "standard network ASK rule");

        List<PolicyRule> markers = new ArrayList<>();
        Set<String> capabilities = new LinkedHashSet<>();
        for (ToolName name : toolNames) {
            List<FrozenToolBinding> bindings = catalog.snapshot().bindings().stream()
                    .filter(value -> value.definition().name().equals(name))
                    .toList();
            if (bindings.isEmpty()) {
                throw new HaifaAgentException(
                        "NETWORK_AUTO_APPROVE_TOOL_UNAVAILABLE",
                        "product.assemble",
                        "assembly",
                        "auto-approved read-only network Tool is absent from the frozen catalog");
            }
            for (FrozenToolBinding binding : bindings) {
                requireSafeReadOnlyNetworkTool(binding);
                PolicyRule marker = markerRule(binding, productId);
                rejectConflictingRef(base.rules(), marker, "read-only network ALLOW marker");
                if (!markers.contains(marker)) markers.add(marker);
            }
            capabilities.add(name.value());
        }

        List<PolicyRule> augmented = new ArrayList<>(base.rules().rules());
        augmented.addAll(markers);
        PolicyRuleSet frozen = PolicyRuleSet.of(
                augmented, base.rules().defaultRule(), base.rules().approvalMode());
        PolicyRuleSet reduced = base.rules().approvalMode() == ApprovalMode.ASK
                ? PolicyRuleSet.of(
                        augmented.stream()
                                .filter(rule -> !rule.equals(canonicalNetworkAsk))
                                .toList(),
                        base.rules().defaultRule(),
                        base.rules().approvalMode())
                : frozen;
        return new PolicyPlatformContribution(
                frozen, new ReadOnlyNetworkToolPolicy(base.evaluator(), capabilities, reduced));
    }

    @Override
    public PolicyDecision evaluate(PolicyRequest request, PolicyRuleSet rules) {
        PolicyDecision original = delegate.evaluate(request, rules);
        if (original.effect() != PolicyEffect.ASK) return original;
        if (!isAutoApprovedReadOnlyNetworkRequest(request)) return original;
        PolicyDecision reduced = delegate.evaluate(request, reducedRules);
        if (reduced.effect() != PolicyEffect.ALLOW) return original;
        return new PolicyDecision(
                PolicyEffect.ALLOW,
                Optional.empty(),
                reduced.reasonCode(),
                reduced.safeExplanation(),
                PolicyRequirementDigest.compute(request, rules));
    }

    private boolean isAutoApprovedReadOnlyNetworkRequest(PolicyRequest request) {
        return capabilities.contains(request.action().capability())
                && request.context().approvalMode() == ApprovalMode.ASK
                && "invoke".equals(request.action().operation())
                && "tool".equals(request.resource().resourceType())
                && request.risk().sideEffects().equals(Set.of(PolicySideEffect.NETWORK_ACCESS))
                && request.risk()
                        .networkTargetSummary()
                        .filter(value -> !value.isBlank())
                        .isPresent()
                && request.risk().level() != PolicyRiskLevel.HIGH
                && request.risk().level() != PolicyRiskLevel.CRITICAL;
    }

    private static void requireSafeReadOnlyNetworkTool(FrozenToolBinding binding) {
        ToolDefinition definition = binding.definition();
        if (definition.sideEffects().size() != 1
                || !definition.sideEffects().contains(ToolSideEffect.NETWORK_ACCESS)
                || definition.risk() == ToolRisk.HIGH
                || definition.risk() == ToolRisk.CRITICAL
                || definition.approvalRequirement() != ToolApprovalRequirement.POLICY
                || definition.resources().networkHosts().isEmpty()
                || !definition.resources().networkHosts().stream().allMatch(ReadOnlyNetworkToolPolicy::isExactHost)) {
            throw new HaifaAgentException(
                    "NETWORK_AUTO_APPROVE_TOOL_UNSAFE",
                    "product.assemble",
                    "assembly",
                    "auto-approved read-only network Tool does not satisfy the safety contract: "
                            + binding.alias().value());
        }
    }

    private static boolean isExactHost(String host) {
        try {
            return !host.isBlank()
                    && !host.endsWith(".")
                    && host.length() <= 253
                    && host.equals(IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void rejectConflictingRef(PolicyRuleSet rules, PolicyRule expected, String description) {
        Set<PolicyRule> existing = new HashSet<>(rules.rules());
        rules.defaultRule().ifPresent(existing::add);
        for (PolicyRule rule : existing) {
            if (rule.ref().equals(expected.ref()) && !rule.equals(expected)) {
                throw new HaifaAgentException(
                        "POLICY_RULE_REF_CONFLICT",
                        "product.assemble",
                        "assembly",
                        "policy rule ref " + expected.ref().ruleId() + " collides with a different " + description);
            }
        }
    }

    private static PolicyRule markerRule(FrozenToolBinding binding, String productId) {
        String name = binding.definition().name().value();
        String ruleId = MARKER_PREFIX + name + "-"
                + binding.coordinate().definitionHash().value().substring(0, HASH_PREFIX_LENGTH);
        return new PolicyRule(
                new PolicyRuleRef(ruleId, "1"),
                PolicyRuleSource.MANAGED,
                1_000,
                new PolicyRuleMatcher(
                        Optional.empty(),
                        Optional.of(productId),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(name),
                        Optional.of(MANIFEST_OPERATION),
                        Optional.of("tool"),
                        Optional.empty(),
                        Set.of(PolicySideEffect.NETWORK_ACCESS)),
                PolicyEffect.ALLOW,
                Optional.empty(),
                "SDK_READONLY_NETWORK_ALLOWED",
                "Read-only network Tool is on the product auto-approval list");
    }

    private static PolicyRule canonicalNetworkAskRule() {
        return PolicyPresets.standardApproval().rules().stream()
                .filter(rule -> rule.effect() == PolicyEffect.ASK
                        && rule.matcher().requiredSideEffects().equals(Set.of(PolicySideEffect.NETWORK_ACCESS)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("standard policy preset is missing its network ASK rule"));
    }
}
