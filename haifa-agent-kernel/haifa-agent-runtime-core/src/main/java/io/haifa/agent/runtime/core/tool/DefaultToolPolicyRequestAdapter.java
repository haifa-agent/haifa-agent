package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyAction;
import io.haifa.agent.policy.api.PolicyContext;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyResource;
import io.haifa.agent.policy.api.PolicyRisk;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import io.haifa.agent.policy.api.PolicySideEffect;
import io.haifa.agent.policy.api.PolicySubject;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class DefaultToolPolicyRequestAdapter implements ToolPolicyRequestAdapter {
    private final String productId;
    private final ApprovalMode approvalMode;

    public DefaultToolPolicyRequestAdapter(String productId, ApprovalMode approvalMode) {
        this.productId = productId;
        this.approvalMode = approvalMode;
    }

    @Override
    public PolicyRequest adapt(io.haifa.agent.core.run.AgentRun run, FrozenToolBinding binding, ToolRequest request) {
        var definition = binding.definition();
        String invocationDigest = resourceDigest(definition.name().value(), request);
        String resourceDigest;
        if (definition.name().value().equals("execution.run")) {
            String executionProfile = definition.resources().executionProfiles().stream()
                    .reduce((first, ignored) -> {
                        throw new IllegalArgumentException("execution.run must bind exactly one execution profile");
                    })
                    .orElseThrow(() -> new IllegalArgumentException("execution.run requires an execution profile"));
            resourceDigest = PolicyDigest.sha256Fields(List.of(invocationDigest, executionProfile));
        } else {
            resourceDigest = invocationDigest;
        }
        return new PolicyRequest(
                new PolicySubject(run.tenant(), run.principal(), productId),
                new PolicyContext(
                        run.project().map(value -> value.projectId()),
                        Optional.of(run.sessionId().value()),
                        Optional.of(run.id().value()),
                        Optional.empty(),
                        approvalMode,
                        Optional.empty(),
                        Optional.empty()),
                new PolicyAction(definition.name().value(), "invoke"),
                new PolicyResource(
                        "tool", binding.coordinate().externalForm(), Optional.of(resourceDigest), definition.title()),
                new PolicyRisk(
                        map(definition.risk()),
                        map(definition.sideEffects()),
                        !definition.credentialRequirements().isEmpty(),
                        definition.resources().networkHosts().isEmpty()
                                ? Optional.empty()
                                : Optional.of(
                                        String.join(",", definition.resources().networkHosts()))));
    }

    public static String resourceDigest(String capability, ToolRequest request) {
        if ("execution.run".equals(capability)) {
            Object command = request.arguments().values().get("command");
            Object workdir = request.arguments().values().getOrDefault("workdir", ".");
            if (command instanceof String commandText && workdir instanceof String workdirText) {
                return PolicyDigest.sha256Fields(List.of(commandText, workdirText));
            }
        }
        return ToolPipeline.argumentsDigest(request);
    }

    private static PolicyRiskLevel map(ToolRisk risk) {
        return PolicyRiskLevel.valueOf(risk.name());
    }

    private static Set<PolicySideEffect> map(Set<ToolSideEffect> effects) {
        return effects.stream()
                .filter(effect -> effect != ToolSideEffect.FILE_READ)
                .map(effect -> PolicySideEffect.valueOf(effect.name()))
                .collect(Collectors.toUnmodifiableSet());
    }
}
