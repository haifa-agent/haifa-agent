package io.haifa.agent.runtime.core.interaction;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.api.InteractionConsequenceView;
import io.haifa.agent.runtime.api.InteractionKind;
import io.haifa.agent.runtime.api.InteractionRequesterView;
import io.haifa.agent.runtime.api.InteractionTargetView;
import io.haifa.agent.runtime.api.InteractionView;
import java.util.Optional;

/** Projects internal interaction state through a safe, provider-neutral whitelist. */
public final class InteractionViewProjector {
    private static final int MAX_SAFE_PROMPT_LENGTH = 2_048;

    public InteractionView project(AgentRun run, InteractionRecord record) {
        InteractionRequest request = record.request();
        InteractionKind kind = InteractionSemantics.kind(request);
        return new InteractionView(
                request.id(),
                request.runId(),
                run.sessionId(),
                record.revision(),
                kind,
                record.state(),
                title(kind),
                boundedSafePrompt(request.prompt()),
                InteractionSemantics.allowedActions(kind),
                InteractionSemantics.inputContract(kind),
                target(request.target()),
                new InteractionRequesterView(request.requester().principalType(), "requester"),
                request.createdAt(),
                request.expiresAt(),
                consequences(request, kind));
    }

    static String boundedSafePrompt(String prompt) {
        if (prompt.length() <= MAX_SAFE_PROMPT_LENGTH) return prompt;
        String marker = "\n\n[Prompt truncated for safe display; original length=" + prompt.length() + "]";
        int end = MAX_SAFE_PROMPT_LENGTH - marker.length();
        if (end > 0 && Character.isHighSurrogate(prompt.charAt(end - 1))) end--;
        return prompt.substring(0, end).stripTrailing() + marker;
    }

    private static String title(InteractionKind kind) {
        if (kind.equals(InteractionKind.APPROVAL)) return "Approval required";
        if (kind.equals(InteractionKind.ARTIFACT_REVIEW)) return "Artifact review required";
        return "Input required";
    }

    private static InteractionTargetView target(InteractionTarget target) {
        if (target instanceof ToolApprovalTarget tool) {
            return new InteractionTargetView(
                    "tool",
                    tool.toolCallId().value(),
                    Optional.of(tool.definitionHash()),
                    Optional.of(tool.argumentsDigest()),
                    "Approval required for " + tool.coordinate());
        }
        GenericInteractionTarget generic = (GenericInteractionTarget) target;
        return new InteractionTargetView(
                "interaction", generic.type(), Optional.empty(), Optional.empty(), "Runtime interaction");
    }

    private static InteractionConsequenceView consequences(InteractionRequest request, InteractionKind kind) {
        if (kind.equals(InteractionKind.APPROVAL)) {
            return new InteractionConsequenceView(
                    "Runtime will revalidate the target before continuing",
                    "The requested action will not execute",
                    "The run will be cancelled without approving the action");
        }
        String expiration = request.expiresAt().isEmpty()
                ? "The interaction will remain pending until it is answered or explicitly cancelled"
                : switch (request.expirationOutcome()) {
                    case FAIL_RUN -> "The run will fail with a bounded interaction-expired error";
                    case CANCEL_RUN -> "The run will be cancelled without applying input";
                    case RETURN_TO_AGENT -> "The run will resume in a new attempt without response input";
                };
        return new InteractionConsequenceView(
                "The response will be applied in a new attempt",
                "The interaction will stop without applying the requested input",
                expiration);
    }
}
