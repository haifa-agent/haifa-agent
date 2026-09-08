package io.haifa.agent.runtime.core.recovery;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.api.InteractionRequestId;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Deterministic, non-authoritative correlation keys for one-hop execution recovery. */
public final class ExecutionRecoveryKeys {
    private static final String DOMAIN = "execution-recovery/v1";

    private ExecutionRecoveryKeys() {}

    public static InteractionRequestId requestId(AgentRunId runId, ToolCallId originalToolCallId) {
        return new InteractionRequestId("execution-recovery:v1:" + digest(runId.value(), originalToolCallId.value()));
    }

    public static Successor successor(AgentRunId runId, ToolCallId originalToolCallId, String canonicalIntentDigest) {
        String digest = digest(runId.value(), originalToolCallId.value(), require(canonicalIntentDigest));
        return new Successor(
                new ToolCallId("execution-recovery-tool:v1:" + digest),
                new AgentStepId("execution-recovery-step:v1:" + digest),
                new ProviderToolCallCorrelationId("execution-recovery-correlation:v1:" + digest),
                new RuntimeIdempotencyKey("execution-recovery-idempotency:v1:" + digest));
    }

    public static String requirementDigest(
            AgentRunId runId,
            ToolCallId originalToolCallId,
            String coordinate,
            String definitionHash,
            String argumentsDigest,
            String configurationDigest,
            String failureCode,
            String principalScope) {
        return "sha256:"
                + digest(
                        DOMAIN,
                        runId.value(),
                        originalToolCallId.value(),
                        require(coordinate),
                        require(definitionHash),
                        require(argumentsDigest),
                        require(configurationDigest),
                        require(failureCode),
                        "NOT_DISPATCHED",
                        require(principalScope));
    }

    private static String digest(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] value = require(field).getBytes(StandardCharsets.UTF_8);
                digest.update(
                        ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
                digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static String require(String value) {
        String normalized = Objects.requireNonNull(value, "digest field must not be null");
        if (normalized.isBlank()) throw new IllegalArgumentException("digest field must not be blank");
        return normalized;
    }

    public record Successor(
            ToolCallId toolCallId,
            AgentStepId stepId,
            ProviderToolCallCorrelationId providerCorrelationId,
            RuntimeIdempotencyKey idempotencyKey) {
        public Successor {
            Objects.requireNonNull(toolCallId, "toolCallId must not be null");
            Objects.requireNonNull(stepId, "stepId must not be null");
            Objects.requireNonNull(providerCorrelationId, "providerCorrelationId must not be null");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        }
    }
}
