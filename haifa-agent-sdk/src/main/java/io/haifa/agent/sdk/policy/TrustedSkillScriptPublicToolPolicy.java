package io.haifa.agent.sdk.policy;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.runtime.core.tool.PublicToolPolicy;
import io.haifa.agent.runtime.core.tool.ToolPolicyRequestAdapter;
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

/** Product assembly policy for exact reviewed Skill scripts before ordinary public Tool policy. */
public final class TrustedSkillScriptPublicToolPolicy implements PublicToolPolicy {
    public static final String REASON_CODE = "TRUSTED_SKILL_SCRIPT_AUTO_APPROVED";
    private static final Set<String> FORBIDDEN_ARGUMENT_NAMES = Set.of(
            "executable", "content", "env", "args", "argv", "command", "script", "scriptpath", "language", "endpoint", "proxy");

    private final PublicToolPolicy delegate;
    private final RuntimeStateRepository state;
    private final ToolPolicyRequestAdapter requests;
    private final TimeProvider time;

    public TrustedSkillScriptPublicToolPolicy(
            PublicToolPolicy delegate,
            RuntimeStateRepository state,
            ToolPolicyRequestAdapter requests,
            TimeProvider time) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.requests = Objects.requireNonNull(requests, "requests must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    @Override
    public PolicyDecision evaluate(AgentRun run, FrozenToolBinding tool, ToolRequest request) {
        PolicyRequest policyRequest = requests.adapt(run, tool, request);
        if (evidence(run, tool, policyRequest).isEmpty()) return delegate.evaluate(run, tool, request);

        PolicyDecision evaluated = delegate.evaluate(run, tool, request);
        return new PolicyDecision(
                PolicyEffect.ALLOW,
                Optional.empty(),
                REASON_CODE,
                "Exact reviewed Skill package and script execution grants matched",
                evaluated.requirementDigest());
    }

    private Optional<GrantPair> evidence(AgentRun run, FrozenToolBinding tool, PolicyRequest policyRequest) {
        if (!eligibleFixedTool(tool)) return Optional.empty();
        var configuration = state.configuration(run.configurationSnapshot()).orElse(null);
        if (configuration == null || configuration.skillTrust().scriptExecutionGrants().isEmpty()) return Optional.empty();
        var subject = new SkillTrustSubject(
                policyRequest.subject().tenant(),
                policyRequest.subject().principal(),
                policyRequest.subject().productId(),
                policyRequest.context().projectRef());
        var matches = configuration.skillTrust().scriptExecutionGrants().stream()
                .filter(grant -> grant.toolCoordinate().equals(tool.coordinate()))
                .map(grant -> match(configuration, tool, subject, time.now(), grant))
                .flatMap(Optional::stream)
                .toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
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
                .filter(candidate -> candidate.packageReviewGrantId()
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
        if (!exactScript || !scriptGrant.argumentPolicyDigest().equals(SkillTrustDigests.argumentPolicy(tool.coordinate()))) {
            return Optional.empty();
        }
        if (!Set.copyOf(scriptGrant.capabilities()).equals(tool.definition().resources().filesystemCapabilities())) {
            return Optional.empty();
        }
        if (!Set.copyOf(scriptGrant.networkHosts()).equals(tool.definition().resources().networkHosts())) {
            return Optional.empty();
        }
        if (!tool.definition().resources().executionProfiles().contains("sandbox@" + scriptGrant.sandboxDigest())) {
            return Optional.empty();
        }
        String expectedProfile = SkillTrustDigests.executionProfile(
                scriptGrant.scriptRuntimeRef(),
                tool.definition().resources().executionProfiles().stream().sorted().toList());
        return scriptGrant.executionProfileDigest().equals(expectedProfile)
                ? Optional.of(new GrantPair(packageGrant, scriptGrant))
                : Optional.empty();
    }

    private static boolean eligibleFixedTool(FrozenToolBinding tool) {
        var definition = tool.definition();
        if (definition.approvalRequirement() != ToolApprovalRequirement.ALWAYS) return false;
        if ("execution.run".equals(definition.name().value())) return false;
        if (definition.sideEffects().contains(ToolSideEffect.NETWORK_ACCESS)
                && definition.resources().networkHosts().isEmpty()) return false;
        Object properties = definition.inputSchema().document().get("properties");
        if (!(properties instanceof java.util.Map<?, ?> map)) return false;
        return map.keySet().stream()
                .map(String::valueOf)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .noneMatch(FORBIDDEN_ARGUMENT_NAMES::contains);
    }

    private record GrantPair(SkillPackageReviewGrant packageGrant, SkillScriptExecutionGrant scriptGrant) {}
}
