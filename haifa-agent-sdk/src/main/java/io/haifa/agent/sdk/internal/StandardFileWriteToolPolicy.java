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
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Request-local standard FILE_WRITE ASK projection using the product's original evaluator. */
public final class StandardFileWriteToolPolicy implements PolicyDecisionService {
    private static final String MARKER_PREFIX = "sdk-standard-file-write-";
    // A manifest operation cannot match an actual invocation or shadow a custom default rule.
    private static final String MANIFEST_OPERATION = "declare-standard-file-write";
    private static final int HASH_PREFIX_LENGTH = 12;

    private final PolicyDecisionService delegate;
    private final Set<String> capabilities;
    private final Set<String> resourceRefs;
    private final String productId;
    private final PolicyRuleSet reducedRules;

    private StandardFileWriteToolPolicy(
            PolicyDecisionService delegate,
            Set<String> capabilities,
            Set<String> resourceRefs,
            String productId,
            PolicyRuleSet reducedRules) {
        this.delegate = delegate;
        this.capabilities = Set.copyOf(capabilities);
        this.resourceRefs = Set.copyOf(resourceRefs);
        this.productId = productId;
        this.reducedRules = reducedRules;
    }

    /** Applies only to bindings identified by the one-pass Java registration, never provenance text. */
    public static PolicyPlatformContribution apply(
            PolicyPlatformContribution base,
            Set<ToolName> names,
            ToolCatalog catalog,
            Set<FrozenToolBinding> javaBindings,
            String productId) {
        Objects.requireNonNull(base, "policy contribution must not be null");
        Objects.requireNonNull(names, "names must not be null");
        Objects.requireNonNull(catalog, "catalog must not be null");
        Objects.requireNonNull(javaBindings, "javaBindings must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        if (names.isEmpty()) return base;

        PolicyRule canonical = canonicalFileWriteAskRule();
        rejectConflictingRef(base.rules(), canonical);
        Set<String> capabilities = new LinkedHashSet<>();
        Set<String> refs = new LinkedHashSet<>();
        var markers = new ArrayList<PolicyRule>();
        for (ToolName name : names) {
            var bindings = catalog.snapshot().bindings().stream()
                    .filter(binding -> binding.definition().name().equals(name))
                    .toList();
            if (bindings.isEmpty()) {
                throw assembly(
                        "FILE_WRITE_AUTO_APPROVE_TOOL_UNAVAILABLE",
                        "auto-approved Java file-writing Tool is absent from the frozen catalog");
            }
            for (FrozenToolBinding binding : bindings) {
                if (!javaBindings.contains(binding)) {
                    throw assembly(
                            "FILE_WRITE_AUTO_APPROVE_TOOL_NOT_JAVA",
                            "auto-approved file-writing Tool is not a registered Java binding");
                }
                requireSafeTool(binding);
                PolicyRule marker = markerRule(binding, productId);
                rejectConflictingRef(base.rules(), marker);
                if (!markers.contains(marker)) markers.add(marker);
                refs.add(binding.coordinate().externalForm());
            }
            capabilities.add(name.value());
        }
        var augmented = new ArrayList<>(base.rules().rules());
        augmented.addAll(markers);
        PolicyRuleSet frozen = PolicyRuleSet.of(
                augmented, base.rules().defaultRule(), base.rules().approvalMode());
        PolicyRuleSet reduced = base.rules().approvalMode() == ApprovalMode.ASK
                ? PolicyRuleSet.of(
                        augmented.stream()
                                .filter(rule -> !rule.equals(canonical))
                                .toList(),
                        base.rules().defaultRule(),
                        base.rules().approvalMode())
                : frozen;
        return new PolicyPlatformContribution(
                frozen, new StandardFileWriteToolPolicy(base.evaluator(), capabilities, refs, productId, reduced));
    }

    @Override
    public PolicyDecision evaluate(PolicyRequest request, PolicyRuleSet rules) {
        PolicyDecision original = delegate.evaluate(request, rules);
        if (original.effect() != PolicyEffect.ASK || !eligible(request)) return original;
        PolicyDecision reduced = delegate.evaluate(request, reducedRules);
        if (reduced.effect() != PolicyEffect.ALLOW) return original;
        return new PolicyDecision(
                PolicyEffect.ALLOW,
                Optional.empty(),
                reduced.reasonCode(),
                reduced.safeExplanation(),
                PolicyRequirementDigest.compute(request, rules));
    }

    private boolean eligible(PolicyRequest request) {
        return capabilities.contains(request.action().capability())
                && productId.equals(request.subject().productId())
                && resourceRefs.contains(request.resource().resourceRef())
                && request.context().approvalMode() == ApprovalMode.ASK
                && "invoke".equals(request.action().operation())
                && "tool".equals(request.resource().resourceType())
                && request.risk().sideEffects().equals(Set.of(PolicySideEffect.FILE_WRITE))
                && request.risk().level() != PolicyRiskLevel.HIGH
                && request.risk().level() != PolicyRiskLevel.CRITICAL;
    }

    private static void requireSafeTool(FrozenToolBinding binding) {
        var definition = binding.definition();
        if (!definition.sideEffects().equals(Set.of(ToolSideEffect.FILE_WRITE))
                || definition.risk() == ToolRisk.HIGH
                || definition.risk() == ToolRisk.CRITICAL
                || definition.approvalRequirement() != ToolApprovalRequirement.POLICY) {
            throw assembly(
                    "FILE_WRITE_AUTO_APPROVE_TOOL_UNSAFE",
                    "auto-approved Java file-writing Tool does not satisfy the safety contract");
        }
    }

    private static void rejectConflictingRef(PolicyRuleSet rules, PolicyRule expected) {
        Set<PolicyRule> existing = new HashSet<>(rules.rules());
        rules.defaultRule().ifPresent(existing::add);
        for (PolicyRule rule : existing) {
            if (rule.ref().equals(expected.ref()) && !rule.equals(expected)) {
                throw assembly(
                        "POLICY_RULE_REF_CONFLICT", "file-writing policy rule ref collides with different content");
            }
        }
    }

    private static PolicyRule markerRule(FrozenToolBinding binding, String productId) {
        String name = binding.definition().name().value();
        return new PolicyRule(
                new PolicyRuleRef(
                        MARKER_PREFIX + name + "-"
                                + binding.coordinate().definitionHash().value().substring(0, HASH_PREFIX_LENGTH),
                        "1"),
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
                        Set.of(PolicySideEffect.FILE_WRITE)),
                PolicyEffect.ALLOW,
                Optional.empty(),
                "SDK_STANDARD_FILE_WRITE_ALLOWED",
                "Java file-writing Tool is on the product auto-approval list");
    }

    private static PolicyRule canonicalFileWriteAskRule() {
        return PolicyPresets.standardApproval().rules().stream()
                .filter(rule -> rule.effect() == PolicyEffect.ASK
                        && rule.matcher().requiredSideEffects().equals(Set.of(PolicySideEffect.FILE_WRITE)))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalStateException("standard policy preset is missing its file-write ASK rule"));
    }

    private static HaifaAgentException assembly(String code, String message) {
        return new HaifaAgentException(code, "product.assemble", "assembly", message);
    }
}
