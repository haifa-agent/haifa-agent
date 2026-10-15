package io.haifa.agent.sdk.internal;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyContext;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyRequirementDigest;
import io.haifa.agent.policy.api.PolicyRuleSet;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.core.bootstrap.ConfigurationSnapshotFactory;
import io.haifa.agent.runtime.core.bootstrap.EffectiveCapability;
import io.haifa.agent.runtime.core.bootstrap.ResolvedDefinition;
import io.haifa.agent.runtime.core.bootstrap.ResolvedProfile;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.runtime.core.tool.DefaultToolPolicyRequestAdapter;
import io.haifa.agent.runtime.core.tool.PublicToolPolicy;
import io.haifa.agent.tool.api.FrozenToolBinding;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Snapshot-owned narrowing over the selected policy; the existing denial path owns execution. */
public final class NonInteractiveChildToolPolicy implements PublicToolPolicy {
    private static final String CAPABILITY_ID = "sdk.child-tools.non-interactive";
    private static final EffectiveCapability ENABLED =
            new EffectiveCapability(CAPABILITY_ID, "1", null, CanonicalSdkDigest.sha256(CAPABILITY_ID, "1", "enabled"));
    private final PublicToolPolicy delegate;
    private final RuntimeStateRepository state;
    private final PolicyRuleSet rules;
    private final DefaultToolPolicyRequestAdapter requests;

    public NonInteractiveChildToolPolicy(
            PublicToolPolicy delegate, RuntimeStateRepository state, PolicyRuleSet rules, String productId) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.rules = rules;
        this.requests = new DefaultToolPolicyRequestAdapter(productId, ApprovalMode.ASK);
    }

    @Override
    public PolicyDecision evaluate(AgentRun run, FrozenToolBinding binding, ToolRequest request) {
        PolicyDecision original = delegate.evaluate(run, binding, request);
        if (original.effect() != PolicyEffect.ASK || run.parentRunId().isEmpty()) return original;
        if (!enabled(configuration(state, run))) return original;
        if (rules == null) throw invalidConfiguration();
        PolicyRequest adapted = requests.adapt(run, binding, request);
        PolicyContext context = adapted.context();
        PolicyRequest frozen = new PolicyRequest(
                adapted.subject(),
                new PolicyContext(
                        context.projectRef(),
                        context.sessionRef(),
                        context.runRef(),
                        context.attemptRef(),
                        context.approvalMode(),
                        Optional.of(run.configurationSnapshot().contentHash())),
                adapted.action(),
                adapted.resource(),
                adapted.risk());
        return new PolicyDecision(
                PolicyEffect.DENY,
                Optional.empty(),
                "CHILD_TOOL_APPROVAL_UNAVAILABLE",
                "Delegated Child Tools cannot request interactive approval",
                PolicyRequirementDigest.compute(frozen, rules));
    }

    /** Augments existing capability facts; all Tool, Skill and trust freezing remains in the delegate. */
    public static ConfigurationSnapshotFactory snapshots(
            ConfigurationSnapshotFactory delegate, RuntimeStateRepository state, boolean enabled) {
        Objects.requireNonNull(delegate, "delegate must not be null");
        Objects.requireNonNull(state, "state must not be null");
        return new ConfigurationSnapshotFactory() {
            @Override
            public RuntimeConfigurationSnapshot create(
                    AgentRunRequest request,
                    ResolvedDefinition definition,
                    ResolvedProfile profile,
                    RuntimeCallerContext caller,
                    List<EffectiveCapability> capabilities) {
                return checkOption(
                        delegate.create(request, definition, profile, caller, withOption(capabilities, enabled)),
                        enabled);
            }

            @Override
            public RuntimeConfigurationSnapshot createChild(
                    AgentRun parent,
                    AgentRunRequest request,
                    ResolvedDefinition definition,
                    ResolvedProfile profile,
                    RuntimeCallerContext caller,
                    List<EffectiveCapability> capabilities) {
                boolean inherited = enabled(configuration(state, parent));
                return checkOption(
                        delegate.createChild(
                                parent, request, definition, profile, caller, withOption(capabilities, inherited)),
                        inherited);
            }
        };
    }

    private static RuntimeConfigurationSnapshot checkOption(RuntimeConfigurationSnapshot snapshot, boolean expected) {
        if (enabled(snapshot) != expected) throw invalidConfiguration();
        return snapshot;
    }

    private static List<EffectiveCapability> withOption(List<EffectiveCapability> capabilities, boolean enabled) {
        if (capabilities.stream().anyMatch(value -> value.capabilityId().equals(CAPABILITY_ID))) {
            throw invalidConfiguration();
        }
        if (!enabled) return capabilities;
        var augmented = new ArrayList<>(capabilities);
        augmented.add(ENABLED);
        return augmented.stream().sorted().toList();
    }

    private static boolean enabled(RuntimeConfigurationSnapshot configuration) {
        var declarations = configuration.capabilities().stream()
                .filter(value -> value.capabilityId().equals(CAPABILITY_ID))
                .toList();
        if (declarations.isEmpty()) return false;
        if (declarations.size() != 1 || !declarations.getFirst().equals(ENABLED)) throw invalidConfiguration();
        return true;
    }

    private static RuntimeConfigurationSnapshot configuration(RuntimeStateRepository state, AgentRun run) {
        var snapshot = state.configuration(run.configurationSnapshot())
                .orElseThrow(NonInteractiveChildToolPolicy::invalidConfiguration);
        if (!snapshot.reference().equals(run.configurationSnapshot())
                || !snapshot.definitionId().equals(run.agentDefinitionId())
                || !snapshot.definitionVersion().equals(run.agentDefinitionVersion())) throw invalidConfiguration();
        return snapshot;
    }

    private static IllegalStateException invalidConfiguration() {
        return new IllegalStateException("CHILD_TOOL_POLICY_CONFIGURATION_INVALID");
    }
}
