package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyDecisionId;
import io.haifa.agent.policy.api.PolicyDecisionStore;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyRequestDigest;
import io.haifa.agent.policy.api.PolicyRuleRef;
import io.haifa.agent.policy.api.PolicySnapshotRef;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillPackageReviewGrant;
import io.haifa.agent.skill.api.SkillResourceKind;
import io.haifa.agent.skill.api.SkillScriptExecutionGrant;
import io.haifa.agent.skill.api.SkillTrustDigests;
import io.haifa.agent.skill.api.SkillTrustSubject;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Exact trusted-script rule placed before the ordinary Tool approval branch.
 *
 * <p>Missing, ambiguous or drifted evidence delegates to the normal policy; it never creates an
 * approval response or changes the generic execution Tool.
 */
public final class TrustedSkillScriptPublicToolPolicy implements PublicToolPolicy {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(TrustedSkillScriptPublicToolPolicy.class);
    public static final String REASON_CODE = "TRUSTED_SKILL_SCRIPT_AUTO_APPROVED";
    private static final Set<String> FORBIDDEN_ARGUMENT_NAMES = Set.of(
            "executable",
            "content",
            "env",
            "args",
            "argv",
            "command",
            "script",
            "scriptpath",
            "language",
            "endpoint",
            "proxy");

    private final PublicToolPolicy delegate;
    private final RuntimeStateRepository state;
    private final ToolPolicyRequestAdapter requests;
    private final IdentifierGenerator ids;
    private final TimeProvider time;
    private final PolicyDecisionStore decisions;

    public TrustedSkillScriptPublicToolPolicy(
            PublicToolPolicy delegate,
            RuntimeStateRepository state,
            ToolPolicyRequestAdapter requests,
            IdentifierGenerator ids,
            TimeProvider time,
            PolicyDecisionStore decisions) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.requests = Objects.requireNonNull(requests, "requests must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
        this.decisions = Objects.requireNonNull(decisions, "decisions must not be null");
    }

    @Override
    public PolicyDecision evaluate(AgentRun run, FrozenToolBinding tool, ToolRequest request) {
        PolicyRequest policyRequest = requests.adapt(run, tool, request);
        Optional<TrustedEvidence> evidence = evidence(run, tool, policyRequest);
        if (evidence.isEmpty()) return delegate.evaluate(run, tool, request);

        TrustedEvidence trusted = evidence.orElseThrow();
        PolicyDecision decision = new PolicyDecision(
                new PolicyDecisionId(ids.nextValue()),
                Optional.of(policyRequest),
                PolicyRequestDigest.compute(policyRequest),
                PolicyEffect.ALLOW,
                Optional.empty(),
                REASON_CODE,
                "Exact reviewed Skill package and script execution grants matched",
                new PolicySnapshotRef(
                        "trusted-skill-" + trusted.manifestDigest().substring("sha256:".length(), 24)),
                Optional.of(new PolicyRuleRef(
                        trusted.scriptGrant().id(), trusted.packageGrant().id())),
                time.now());
        decisions.save(decision);
        LOGGER.info(
                "Trusted Skill script auto-approved runId={} toolCallId={} tool={} packageGrant={} scriptGrant={}",
                run.id().value(),
                request.toolCallId().value(),
                tool.alias().value(),
                trusted.packageGrant().id(),
                trusted.scriptGrant().id());
        return decision;
    }

    private Optional<TrustedEvidence> evidence(AgentRun run, FrozenToolBinding tool, PolicyRequest policyRequest) {
        if (!eligibleFixedTool(tool)) return Optional.empty();
        var configuration = state.configuration(run.configurationSnapshot()).orElse(null);
        if (configuration == null
                || configuration.skillTrust().scriptExecutionGrants().isEmpty()) {
            return Optional.empty();
        }
        var subject = new SkillTrustSubject(
                policyRequest.subject().tenant(),
                policyRequest.subject().principal(),
                policyRequest.subject().productId(),
                policyRequest.context().projectRef());
        var now = time.now();
        var matches = configuration.skillTrust().scriptExecutionGrants().stream()
                .filter(grant -> grant.toolCoordinate().equals(tool.coordinate()))
                .map(grant -> match(configuration, tool, subject, now, grant))
                .flatMap(Optional::stream)
                .toList();
        return matches.size() == 1
                ? Optional.of(new TrustedEvidence(
                        configuration.skillTrust().manifestDigest(),
                        matches.getFirst().packageGrant(),
                        matches.getFirst().scriptGrant()))
                : Optional.empty();
    }

    private static Optional<GrantPair> match(
            io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot configuration,
            FrozenToolBinding tool,
            SkillTrustSubject subject,
            java.time.Instant now,
            SkillScriptExecutionGrant scriptGrant) {
        SkillPackageReviewGrant packageGrant = configuration.skillTrust().packageReviewGrants().stream()
                .filter(candidate -> candidate.id().equals(scriptGrant.packageReviewGrantId()))
                .findFirst()
                .orElse(null);
        FrozenSkillBinding skill = configuration.skillBindings().stream()
                .filter(candidate -> candidate.coordinate().equals(scriptGrant.coordinate()))
                .filter(candidate -> candidate
                        .packageReviewGrantId()
                        .filter(scriptGrant.packageReviewGrantId()::equals)
                        .isPresent())
                .findFirst()
                .orElse(null);
        if (packageGrant == null || skill == null || !scriptGrant.matches(packageGrant, skill, tool, subject, now)) {
            return Optional.empty();
        }
        boolean exactScript = skill.packageIndex().resources().stream()
                .anyMatch(resource -> resource.kind() == SkillResourceKind.SCRIPT
                        && resource.relativePath().equals(scriptGrant.scriptRelativePath())
                        && resource.digest().equals(scriptGrant.scriptDigest()));
        if (!exactScript) return Optional.empty();
        if (!scriptGrant.argumentPolicyDigest().equals(SkillTrustDigests.argumentPolicy(tool.coordinate()))) {
            return Optional.empty();
        }
        if (!Set.copyOf(scriptGrant.capabilities())
                .equals(tool.definition().resources().filesystemCapabilities())) {
            return Optional.empty();
        }
        if (!Set.copyOf(scriptGrant.networkHosts())
                .equals(tool.definition().resources().networkHosts())) {
            return Optional.empty();
        }
        if (!tool.definition().resources().executionProfiles().contains("sandbox@" + scriptGrant.sandboxDigest())) {
            return Optional.empty();
        }
        String expectedProfile = SkillTrustDigests.executionProfile(
                scriptGrant.scriptRuntimeRef(),
                tool.definition().resources().executionProfiles().stream()
                        .sorted()
                        .toList());
        if (!scriptGrant.executionProfileDigest().equals(expectedProfile)) return Optional.empty();
        return Optional.of(new GrantPair(packageGrant, scriptGrant));
    }

    private static boolean eligibleFixedTool(FrozenToolBinding tool) {
        var definition = tool.definition();
        if (definition.approvalRequirement() != ToolApprovalRequirement.ALWAYS) return false;
        if ("execution.run".equals(definition.name().value())) return false;
        if (definition.sideEffects().contains(ToolSideEffect.NETWORK_ACCESS)
                && definition.resources().networkHosts().isEmpty()) {
            return false;
        }
        Object properties = definition.inputSchema().document().get("properties");
        if (!(properties instanceof java.util.Map<?, ?> map)) return false;
        return map.keySet().stream()
                .map(String::valueOf)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .noneMatch(FORBIDDEN_ARGUMENT_NAMES::contains);
    }

    private record GrantPair(SkillPackageReviewGrant packageGrant, SkillScriptExecutionGrant scriptGrant) {}

    private record TrustedEvidence(
            String manifestDigest, SkillPackageReviewGrant packageGrant, SkillScriptExecutionGrant scriptGrant) {}
}
