package io.haifa.agent.application.project.policy;

import io.haifa.agent.application.project.product.coding.delivery.CodingDeliveryIntent;
import io.haifa.agent.application.project.product.coding.delivery.CodingDeliveryIntentResolver;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.execution.core.command.SystemGitCliCommandClassifier;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyContext;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyRisk;
import io.haifa.agent.policy.api.PolicySideEffect;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.tool.DefaultToolPolicyRequestAdapter;
import io.haifa.agent.runtime.core.tool.ToolAuthorizationProtocolException;
import io.haifa.agent.runtime.core.tool.ToolPolicyRequestAdapter;
import io.haifa.agent.tool.api.FrozenToolBinding;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Coding-owned execution risk resolver. The execution broker remains the authority for hard boundaries. */
public final class CodingExecutionPolicyRequestAdapter implements ToolPolicyRequestAdapter {
    static final String EXECUTION_RUN = "execution_run";
    private static final String PRODUCT_ID = "haifa-coding-agent";
    private static final Pattern GIT_DIRECTORY_OVERRIDE = Pattern.compile("(?:^|\\s)[\\\"']?-C[\\\"']?(?:\\s|=)");

    private final DefaultToolPolicyRequestAdapter delegate;
    private final CodingDeliveryIntentResolver deliveryIntents;

    public CodingExecutionPolicyRequestAdapter(
            ApprovalMode approvalMode, CodingDeliveryIntentResolver deliveryIntents) {
        delegate = new DefaultToolPolicyRequestAdapter(PRODUCT_ID, approvalMode);
        this.deliveryIntents = Objects.requireNonNull(deliveryIntents, "deliveryIntents must not be null");
    }

    @Override
    public PolicyRequest adapt(AgentRun run, FrozenToolBinding binding, ToolRequest request) {
        PolicyRequest baseline = delegate.adapt(run, binding, request);
        String definitionName = binding.definition().name().value();
        PolicyRequest effective = withEffectiveExecutionRisk(baseline, definitionName, request);
        enforceDeliveryIntent(definitionName, request, deliveryIntents.resolve(run));
        return effective;
    }

    static void enforceDeliveryIntent(
            String definitionName, ToolRequest request, CodingDeliveryIntent permittedIntent) {
        if (!EXECUTION_RUN.equals(definitionName)) return;
        Object value = request.arguments().values().get("command");
        if (!(value instanceof String command)) return;
        var classification = SystemGitCliCommandClassifier.classify(command);
        Optional<CodingDeliveryIntent> required = requiredDeliveryIntent(classification);
        if (required.isPresent()
                && !Objects.requireNonNull(permittedIntent, "permittedIntent must not be null")
                        .allows(required.orElseThrow())) {
            String explanation = classification.risk() == SystemGitCliCommandClassifier.Risk.UNKNOWN
                    ? "The command cannot be proven to stay within the frozen repository delivery boundary; split it "
                            + "into direct git or gh commands."
                    : "The command exceeds the repository delivery side-effect boundary frozen for this Coding Run.";
            throw new ToolAuthorizationProtocolException("DELIVERY_INTENT_EXCEEDED", explanation);
        }
    }

    private static Optional<CodingDeliveryIntent> requiredDeliveryIntent(
            SystemGitCliCommandClassifier.Classification classification) {
        CodingDeliveryIntent explicit =
                switch (classification.reasonCode()) {
                    case "GIT_STAGE", "GIT_COMMIT" -> CodingDeliveryIntent.LOCAL_COMMIT;
                    case "GIT_PUSH" -> CodingDeliveryIntent.REMOTE_PUSH;
                    case "GH_PR_CREATE", "GH_PR_UPDATE" -> CodingDeliveryIntent.PULL_REQUEST;
                    default -> null;
                };
        if (explicit != null) return Optional.of(explicit);
        if (classification.target() != SystemGitCliCommandClassifier.Target.OTHER
                && classification.risk() == SystemGitCliCommandClassifier.Risk.UNKNOWN) {
            return Optional.of(CodingDeliveryIntent.PULL_REQUEST);
        }
        return Optional.empty();
    }

    static PolicyRequest withEffectiveExecutionRisk(
            PolicyRequest baseline, String definitionName, ToolRequest request) {
        if (!EXECUTION_RUN.equals(definitionName)) return baseline;
        Object value = request.arguments().values().get("command");
        if (!(value instanceof String command)) return baseline;

        var assessment =
                CodingExecutionRiskResolver.assess(command, baseline.risk().level());
        var classification = assessment.classification();
        if (classification.target() == SystemGitCliCommandClassifier.Target.GIT
                && classification.risk() == SystemGitCliCommandClassifier.Risk.DENIED
                && GIT_DIRECTORY_OVERRIDE.matcher(command).find()) {
            throw new ToolAuthorizationProtocolException(
                    "WORKSPACE_PROTOCOL_REQUIRED",
                    "Use workspaceRef and relativeWorkdir; remove git -C from the command.");
        }
        PolicyRisk risk = resolveRisk(baseline.risk(), assessment);
        String resolverDigest = PolicyDigest.sha256Fields(List.of(
                "coding-execution-risk",
                CodingExecutionRiskResolver.VERSION,
                classification.target().name(),
                classification.risk().name(),
                classification.operation().name(),
                classification.reasonCode(),
                baseline.context().securityConfigurationDigest().orElse("")));
        PolicyContext original = baseline.context();
        PolicyContext context = new PolicyContext(
                original.projectRef(),
                original.sessionRef(),
                original.runRef(),
                original.attemptRef(),
                original.approvalMode(),
                Optional.of(resolverDigest));
        return new PolicyRequest(baseline.subject(), context, baseline.action(), baseline.resource(), risk);
    }

    private static PolicyRisk resolveRisk(PolicyRisk baseline, CodingExecutionRiskResolver.Assessment assessment) {
        SystemGitCliCommandClassifier.Classification classification = assessment.classification();
        SystemGitCliCommandClassifier.Risk commandRisk = classification.risk();
        if (classification.target() == SystemGitCliCommandClassifier.Target.OTHER
                && commandRisk != SystemGitCliCommandClassifier.Risk.DENIED) {
            return baseline;
        }
        EnumSet<PolicySideEffect> sideEffects = baseline.sideEffects().isEmpty()
                ? EnumSet.noneOf(PolicySideEffect.class)
                : EnumSet.copyOf(baseline.sideEffects());
        switch (commandRisk) {
            case LOCAL_WRITE -> sideEffects.add(PolicySideEffect.FILE_WRITE);
            case NETWORK_READ -> sideEffects.add(PolicySideEffect.NETWORK_ACCESS);
            case EXTERNAL_WRITE -> {
                sideEffects.add(PolicySideEffect.NETWORK_ACCESS);
                sideEffects.add(PolicySideEffect.EXTERNAL_SYSTEM_MUTATION);
            }
            case DESTRUCTIVE, UNKNOWN, DENIED -> {
                sideEffects.add(PolicySideEffect.FILE_WRITE);
                sideEffects.add(PolicySideEffect.NETWORK_ACCESS);
                sideEffects.add(PolicySideEffect.EXTERNAL_SYSTEM_MUTATION);
            }
            case NOT_APPLICABLE, LOCAL_READ -> {
                // Preserve the static execution side effect and its configured baseline.
            }
        }
        if (classification.reasonCode().equals("GIT_FETCH")
                || classification.reasonCode().equals("GIT_PULL")) {
            sideEffects.add(PolicySideEffect.NETWORK_ACCESS);
        }
        return new PolicyRisk(
                assessment.effectiveRisk(),
                sideEffects,
                baseline.credentialRequired(),
                baseline.networkTargetSummary());
    }
}
