package io.haifa.agent.personalassistant.application.policy;

import io.haifa.agent.personalassistant.application.web.PersonalWebPlatform;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRule;
import io.haifa.agent.policy.api.PolicyRuleMatcher;
import io.haifa.agent.policy.api.PolicyRuleRef;
import io.haifa.agent.policy.api.PolicyRuleSet;
import io.haifa.agent.policy.api.PolicyRuleSource;
import io.haifa.agent.runtime.core.tool.DefaultToolPolicyRequestAdapter;
import io.haifa.agent.runtime.core.tool.PublicToolPolicy;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolCatalog;
import io.haifa.agent.tool.api.ToolCoordinate;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/** PA-only direct allow for exact frozen public Web Tool coordinates. */
public final class PersonalWebAllowPolicy implements PublicToolPolicy {
    private static final String PRODUCT_ID = "haifa-personal-assistant";
    private static final Set<String> WEB_TOOL_NAMES = Set.of("web_search", "web_fetch");
    private static final Set<ToolSideEffect> ALLOWED_SIDE_EFFECTS =
            Set.of(ToolSideEffect.NETWORK_ACCESS, ToolSideEffect.CREDENTIAL_USE);

    private final PublicToolPolicy delegate;
    private final Set<ToolCoordinate> allowedCoordinates;
    private final DefaultToolPolicyRequestAdapter requests =
            new DefaultToolPolicyRequestAdapter(PRODUCT_ID, ApprovalMode.ASK);
    private final PolicyPlatformContribution policy;
    private final PolicyRuleSet webRules;

    private PersonalWebAllowPolicy(
            PublicToolPolicy delegate,
            Set<ToolCoordinate> allowedCoordinates,
            PolicyPlatformContribution policy,
            PolicyRuleSet webRules) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.allowedCoordinates = Set.copyOf(allowedCoordinates);
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.webRules = Objects.requireNonNull(webRules, "webRules must not be null");
    }

    public static UnaryOperator<PublicToolPolicy> decorator(
            ToolCatalog catalog, PersonalWebPlatform web, PolicyPlatformContribution policy) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        Objects.requireNonNull(web, "web must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Set<ToolCoordinate> coordinates = resolveAllowedCoordinates(catalog, web);
        PolicyRuleSet rules = PolicyRuleSet.of(
                coordinates.stream()
                        .map(PersonalWebAllowPolicy::rule)
                        .sorted(Comparator.comparing(item -> item.ref().ruleId()))
                        .toList(),
                Optional.empty(),
                ApprovalMode.ASK);
        return delegate -> new PersonalWebAllowPolicy(delegate, coordinates, policy, rules);
    }

    @Override
    public PolicyDecision evaluate(
            io.haifa.agent.core.run.AgentRun run,
            FrozenToolBinding binding,
            io.haifa.agent.runtime.core.decision.ToolRequest request) {
        if (!allowedCoordinates.contains(binding.coordinate())) return delegate.evaluate(run, binding, request);
        return policy.evaluator().evaluate(requests.adapt(run, binding, request), webRules);
    }

    private static Set<ToolCoordinate> resolveAllowedCoordinates(ToolCatalog catalog, PersonalWebPlatform web) {
        return web.contributions().stream()
                .map(contribution -> {
                    FrozenToolBinding binding = catalog.findByAlias(contribution.alias())
                            .orElseThrow(() ->
                                    new IllegalStateException("Personal Web Tool is missing from the frozen catalog: "
                                            + contribution.alias().value()));
                    if (!binding.definition().equals(contribution.definition())
                            || !binding.providerBindingReference().equals(contribution.providerBindingReference())) {
                        throw new IllegalStateException("Personal Web Tool drifted during catalog assembly: "
                                + contribution.alias().value());
                    }
                    validateDirectAllow(binding);
                    return binding.coordinate();
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void validateDirectAllow(FrozenToolBinding binding) {
        var definition = binding.definition();
        if (!WEB_TOOL_NAMES.contains(definition.name().value())
                || definition.approvalRequirement() != ToolApprovalRequirement.POLICY
                || definition.executionMode() != ToolExecutionMode.REMOTE_PROVIDER
                || definition.idempotency() != ToolIdempotency.IDEMPOTENT
                || definition.risk() != ToolRisk.MEDIUM
                || !definition.sideEffects().contains(ToolSideEffect.NETWORK_ACCESS)
                || !ALLOWED_SIDE_EFFECTS.containsAll(definition.sideEffects())
                || definition.resources().networkHosts().isEmpty()) {
            throw new IllegalStateException("Personal Web Tool no longer satisfies the direct-allow safety contract: "
                    + binding.coordinate().externalForm());
        }
    }

    private static PolicyRule rule(ToolCoordinate coordinate) {
        String name = coordinate.name().value();
        return new PolicyRule(
                new PolicyRuleRef(
                        "personal-" + name.replace('.', '-') + "-"
                                + coordinate.definitionHash().value().substring(0, 12),
                        "1"),
                PolicyRuleSource.MANAGED,
                100,
                new PolicyRuleMatcher(
                        Optional.empty(),
                        Optional.of(PRODUCT_ID),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(name),
                        Optional.of("invoke"),
                        Optional.of("tool"),
                        Optional.empty(),
                        Set.of()),
                PolicyEffect.ALLOW,
                Optional.empty(),
                "PERSONAL_WEB_READ_ALLOWED",
                "Personal frozen public Web Tool is allowed");
    }
}
