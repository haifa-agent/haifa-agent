package io.haifa.agent.core.error;

import static io.haifa.agent.core.support.DomainValues.requireText;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Stable, provider-neutral execution error catalog. */
public enum AgentErrorCode {
    RUN_BUDGET_EXCEEDED(
            "RUN_BUDGET_EXCEEDED",
            "Run budget exceeded",
            AgentErrorCategory.RESOURCE_LIMIT,
            Retryability.NOT_RETRYABLE),
    COMPLETION_REPAIR_EXHAUSTED(
            "COMPLETION_REPAIR_EXHAUSTED",
            "Required completion evidence is still missing",
            AgentErrorCategory.VALIDATION,
            Retryability.NOT_RETRYABLE),
    MODEL_AUTHENTICATION_FAILED(
            "MODEL_AUTHENTICATION_FAILED",
            "Model authentication failed",
            AgentErrorCategory.CONFIGURATION,
            Retryability.NOT_RETRYABLE),
    MODEL_PERMISSION_DENIED(
            "MODEL_PERMISSION_DENIED", "Model access was denied", AgentErrorCategory.MODEL, Retryability.NOT_RETRYABLE),
    MODEL_RATE_LIMITED(
            "MODEL_RATE_LIMITED",
            "Model request was rate limited",
            AgentErrorCategory.MODEL,
            Retryability.RETRYABLE_WITH_BACKOFF),
    MODEL_TIMEOUT("MODEL_TIMEOUT", "Model request timed out", AgentErrorCategory.TIMEOUT, Retryability.RETRYABLE),
    MODEL_PROVIDER_UNAVAILABLE(
            "MODEL_PROVIDER_UNAVAILABLE",
            "Model provider is unavailable",
            AgentErrorCategory.MODEL,
            Retryability.RETRYABLE_WITH_BACKOFF),
    MODEL_REQUEST_INVALID(
            "MODEL_REQUEST_INVALID", "Model request was invalid", AgentErrorCategory.MODEL, Retryability.NOT_RETRYABLE),
    MODEL_NOT_FOUND(
            "MODEL_NOT_FOUND",
            "Configured model was not found",
            AgentErrorCategory.CONFIGURATION,
            Retryability.NOT_RETRYABLE),
    MODEL_CONTEXT_TOO_LONG(
            "MODEL_CONTEXT_TOO_LONG",
            "Model context limit was exceeded",
            AgentErrorCategory.RESOURCE_LIMIT,
            Retryability.NOT_RETRYABLE),
    MODEL_CONTENT_REJECTED(
            "MODEL_CONTENT_REJECTED",
            "Model request content was rejected",
            AgentErrorCategory.MODEL,
            Retryability.NOT_RETRYABLE),
    MODEL_RESPONSE_INVALID(
            "MODEL_RESPONSE_INVALID",
            "Model returned an invalid response",
            AgentErrorCategory.MODEL,
            Retryability.RETRYABLE),
    MODEL_CANCELLED(
            "MODEL_CANCELLED", "Model request was cancelled", AgentErrorCategory.CANCELLED, Retryability.NOT_RETRYABLE),
    MODEL_CALL_FAILED(
            "MODEL_CALL_FAILED", "Model call failed", AgentErrorCategory.MODEL, Retryability.RETRYABLE_WITH_BACKOFF),
    TOOL_REQUEST_REJECTED(
            "TOOL_REQUEST_REJECTED",
            "Tool request was rejected",
            AgentErrorCategory.VALIDATION,
            Retryability.NOT_RETRYABLE),
    TOOL_APPROVAL_REJECTED(
            "TOOL_APPROVAL_REJECTED",
            "Tool execution was rejected",
            AgentErrorCategory.TOOL,
            Retryability.NOT_RETRYABLE),
    TOOL_INVOCATION_FAILED(
            "TOOL_INVOCATION_FAILED", "Tool invocation failed", AgentErrorCategory.TOOL, Retryability.NOT_RETRYABLE),
    TOOL_BUSINESS_FAILURE(
            "TOOL_BUSINESS_FAILURE",
            "Tool reported a business failure",
            AgentErrorCategory.TOOL,
            Retryability.NOT_RETRYABLE),
    TOOL_OUTCOME_UNKNOWN(
            "TOOL_OUTCOME_UNKNOWN",
            "Tool outcome could not be determined",
            AgentErrorCategory.TOOL,
            Retryability.RETRYABLE_AFTER_INTERACTION),
    TOOL_RESULT_PERSISTENCE_FAILED(
            "TOOL_RESULT_PERSISTENCE_FAILED",
            "Tool result could not be persisted",
            AgentErrorCategory.INTERNAL,
            Retryability.RETRYABLE),
    WORKSPACE_MANIFEST_UNAVAILABLE(
            "WORKSPACE_MANIFEST_UNAVAILABLE",
            "Workspace manifest is unavailable",
            AgentErrorCategory.CONFIGURATION,
            Retryability.RETRYABLE_AFTER_INTERACTION),
    REPEATED_TOOL_FAILURE(
            "REPEATED_TOOL_FAILURE",
            "Repeated Tool failures made no meaningful progress",
            AgentErrorCategory.TOOL,
            Retryability.NOT_RETRYABLE),
    RUNTIME_EXECUTION_FAILED(
            "RUNTIME_EXECUTION_FAILED", "Agent execution failed", AgentErrorCategory.INTERNAL, Retryability.UNKNOWN),
    UNKNOWN("UNKNOWN", "Unknown agent error", AgentErrorCategory.INTERNAL, Retryability.UNKNOWN);

    private static final Map<String, AgentErrorCode> BY_WIRE_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(AgentErrorCode::wireCode, Function.identity()));

    private final String wireCode;
    private final String displayMessage;
    private final AgentErrorCategory category;
    private final Retryability retryability;

    AgentErrorCode(String wireCode, String displayMessage, AgentErrorCategory category, Retryability retryability) {
        this.wireCode = requireText(wireCode, "wireCode");
        this.displayMessage = requireText(displayMessage, "displayMessage");
        this.category = java.util.Objects.requireNonNull(category, "category must not be null");
        this.retryability = java.util.Objects.requireNonNull(retryability, "retryability must not be null");
    }

    public String wireCode() {
        return wireCode;
    }

    public String displayMessage() {
        return displayMessage;
    }

    public AgentErrorCategory category() {
        return category;
    }

    public Retryability retryability() {
        return retryability;
    }

    public static AgentErrorCode fromWireCode(String wireCode) {
        return BY_WIRE_CODE.getOrDefault(requireText(wireCode, "wireCode"), UNKNOWN);
    }

    public static boolean isKnownWireCode(String wireCode) {
        return wireCode != null && BY_WIRE_CODE.containsKey(wireCode.strip());
    }
}
