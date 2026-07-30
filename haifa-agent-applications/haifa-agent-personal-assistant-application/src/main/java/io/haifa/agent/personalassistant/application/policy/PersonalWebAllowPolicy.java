package io.haifa.agent.personalassistant.application.policy;

import io.haifa.agent.common.id.UuidV7IdentifierGenerator;
import io.haifa.agent.personalassistant.application.web.PersonalWebPlatform;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyDecisionId;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyRequestDigest;
import io.haifa.agent.policy.api.PolicyRule;
import io.haifa.agent.policy.api.PolicyRuleMatcher;
import io.haifa.agent.policy.api.PolicyRuleRef;
import io.haifa.agent.policy.api.PolicyRuleSource;
import io.haifa.agent.policy.api.PolicySnapshot;
import io.haifa.agent.policy.api.PolicySnapshotRef;
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
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Personal-only direct allow for the exact frozen web.search/web.fetch coordinates selected at
 * startup. Every other Tool delegates to the Runtime-selected policy unchanged.
 */
public final class PersonalWebAllowPolicy implements PublicToolPolicy {
    private static final String PRODUCT_ID = "haifa-personal-assistant";
    private static final String POLICY_VERSION = "personal-web-direct-allow-v1";
    private static final Set<String> WEB_TOOL_NAMES = Set.of("web.search", "web.fetch");
    private static final Set<ToolSideEffect> ALLOWED_SIDE_EFFECTS =
            Set.of(ToolSideEffect.NETWORK_ACCESS, ToolSideEffect.CREDENTIAL_USE);

    private final PublicToolPolicy delegate;
    private final Set<ToolCoordinate> allowedCoordinates;
    private final DefaultToolPolicyRequestAdapter requests =
            new DefaultToolPolicyRequestAdapter(PRODUCT_ID, ApprovalMode.ASK);
    private final PolicyPlatformContribution persistence;
    private final PolicySnapshot snapshot;
    private final UuidV7IdentifierGenerator identifiers;
    private final Clock clock;

    private PersonalWebAllowPolicy(
            PublicToolPolicy delegate,
            Set<ToolCoordinate> allowedCoordinates,
            PolicyPlatformContribution persistence,
            PolicySnapshot snapshot,
            Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.allowedCoordinates = Set.copyOf(allowedCoordinates);
        this.persistence = Objects.requireNonNull(persistence, "persistence must not be null");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        this.identifiers = new UuidV7IdentifierGenerator();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public static UnaryOperator<PublicToolPolicy> decorator(
            ToolCatalog catalog, PersonalWebPlatform web, PolicyPlatformContribution persistence, Clock clock) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        Objects.requireNonNull(web, "web must not be null");
        Objects.requireNonNull(persistence, "persistence must not be null");
        Objects.requireNonNull(clock, "clock must not be null");

        Set<ToolCoordinate> allowedCoordinates = resolveAllowedCoordinates(catalog, web);
        PolicySnapshot snapshot = ensureSnapshot(catalog, allowedCoordinates, persistence, clock.instant());
        return delegate -> new PersonalWebAllowPolicy(delegate, allowedCoordinates, persistence, snapshot, clock);
    }

    @Override
    public PolicyDecision evaluate(
            io.haifa.agent.core.run.AgentRun run,
            FrozenToolBinding binding,
            io.haifa.agent.runtime.core.decision.ToolRequest request) {
        if (!allowedCoordinates.contains(binding.coordinate())) {
            return delegate.evaluate(run, binding, request);
        }
        PolicyRequest policyRequest = requests.adapt(run, binding, request);
        PolicyDecision decision = new PolicyDecision(
                new PolicyDecisionId(identifiers.nextValue()),
                Optional.of(policyRequest),
                PolicyRequestDigest.compute(policyRequest),
                PolicyEffect.ALLOW,
                Optional.empty(),
                "PERSONAL_WEB_READ_ALLOWED",
                "Personal frozen public Web Tool is allowed",
                snapshot.ref(),
                Optional.of(ruleRef(binding)),
                Instant.ofEpochMilli(clock.millis()));
        persistence.decisions().save(decision);
        return decision;
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

    private static PolicySnapshot ensureSnapshot(
            ToolCatalog catalog,
            Set<ToolCoordinate> allowedCoordinates,
            PolicyPlatformContribution persistence,
            Instant createdAt) {
        List<ToolCoordinate> coordinates = allowedCoordinates.stream().sorted().toList();
        List<String> coordinateValues =
                coordinates.stream().map(ToolCoordinate::externalForm).toList();
        String contentDigest = PolicyDigest.sha256Fields(
                List.of(POLICY_VERSION, catalog.snapshot().digest(), String.join("\n", coordinateValues)));
        PolicySnapshotRef ref = new PolicySnapshotRef(POLICY_VERSION + "-" + contentDigest.substring(0, 16));
        List<PolicyRule> rules = coordinates.stream()
                .map(PersonalWebAllowPolicy::rule)
                .sorted(Comparator.comparing(item -> item.ref().ruleId()))
                .toList();
        PolicySnapshot candidate = new PolicySnapshot(
                ref, rules, Optional.empty(), ApprovalMode.ASK, PRODUCT_ID, Optional.empty(), contentDigest, createdAt);
        return persistence
                .snapshots()
                .find(ref)
                .map(existing -> validateExisting(existing, candidate))
                .orElseGet(() -> {
                    persistence.snapshots().save(candidate);
                    return candidate;
                });
    }

    private static PolicySnapshot validateExisting(PolicySnapshot existing, PolicySnapshot candidate) {
        if (!existing.rules().equals(candidate.rules())
                || !existing.defaultRule().equals(candidate.defaultRule())
                || existing.approvalMode() != candidate.approvalMode()
                || !existing.productProfileRef().equals(candidate.productProfileRef())
                || !existing.projectTrustRef().equals(candidate.projectTrustRef())
                || !existing.contentDigest().equals(candidate.contentDigest())) {
            throw new IllegalStateException("persisted Personal Web policy snapshot has incompatible content");
        }
        return existing;
    }

    private static PolicyRule rule(ToolCoordinate coordinate) {
        String toolName = coordinate.name().value();
        return new PolicyRule(
                ruleRef(coordinate),
                PolicyRuleSource.MANAGED,
                100,
                new PolicyRuleMatcher(
                        Optional.empty(),
                        Optional.of(PRODUCT_ID),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(toolName),
                        Optional.of("invoke"),
                        Optional.of("tool"),
                        Optional.empty(),
                        Set.of()),
                PolicyEffect.ALLOW,
                Optional.empty(),
                "PERSONAL_WEB_READ_ALLOWED",
                "Personal frozen public Web Tool is allowed");
    }

    private static PolicyRuleRef ruleRef(FrozenToolBinding binding) {
        return ruleRef(binding.coordinate());
    }

    private static PolicyRuleRef ruleRef(ToolCoordinate coordinate) {
        String normalizedName = coordinate.name().value().replace('.', '-');
        String hash = coordinate.definitionHash().value();
        return new PolicyRuleRef("personal-" + normalizedName + "-" + hash.substring(0, 12), "1");
    }
}
