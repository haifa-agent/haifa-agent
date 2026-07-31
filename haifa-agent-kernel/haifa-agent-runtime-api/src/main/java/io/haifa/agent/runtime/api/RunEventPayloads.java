package io.haifa.agent.runtime.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Typed, bounded client-event payloads. Internal maps and store rows never cross this boundary. */
public final class RunEventPayloads {
    private static final int MAXIMUM_TEXT_DELTA_LENGTH = 65_536;

    private RunEventPayloads() {}

    public record RunLifecycle(
            String status,
            long version,
            String reasonCode,
            Optional<String> errorMessage,
            Optional<String> diagnosticId)
            implements AgentRunEvent.Payload {
        public RunLifecycle(String status, long version, String reasonCode) {
            this(status, version, reasonCode, Optional.empty(), Optional.empty());
        }

        public RunLifecycle {
            status = InteractionOption.requireText(status, "status", 64);
            if (version < 0) throw new IllegalArgumentException("version must not be negative");
            reasonCode = InteractionOption.requireText(reasonCode, "reasonCode", 128);
            errorMessage = optional(errorMessage, "errorMessage", 2_048);
            diagnosticId = optional(diagnosticId, "diagnosticId", 256);
        }
    }

    public record AssistantOutput(String generationId, String status, String referenceOrSafeCode)
            implements AgentRunEvent.Payload {
        public AssistantOutput {
            generationId = InteractionOption.requireText(generationId, "generationId", 256);
            status = InteractionOption.requireText(status, "status", 64);
            referenceOrSafeCode = InteractionOption.requireText(referenceOrSafeCode, "referenceOrSafeCode", 512);
        }
    }

    /**
     * Legacy durable-feed payload retained only for decoding old adapters and stored events.
     *
     * @deprecated new Assistant deltas use {@link AgentRuntime#subscribeOutput}; Runtime does not
     *     emit this payload into the durable Run Event Feed
     */
    @Deprecated(forRemoval = true)
    public record AssistantTextDelta(String generationId, String textDelta) implements AgentRunEvent.Payload {
        public AssistantTextDelta {
            generationId = InteractionOption.requireText(generationId, "generationId", 256);
            textDelta = requireTextDelta(textDelta);
        }
    }

    public record InteractionLifecycle(String requestId, String kind, String state, String actionOrReason)
            implements AgentRunEvent.Payload {
        public InteractionLifecycle {
            requestId = InteractionOption.requireText(requestId, "requestId", 256);
            kind = InteractionOption.requireText(kind, "kind", 64);
            state = InteractionOption.requireText(state, "state", 64);
            actionOrReason = InteractionOption.requireText(actionOrReason, "actionOrReason", 128);
        }
    }

    public record RunInputLifecycle(String inputId, String state, String applicationCoordinate)
            implements AgentRunEvent.Payload {
        public RunInputLifecycle {
            inputId = InteractionOption.requireText(inputId, "inputId", 256);
            state = InteractionOption.requireText(state, "state", 64);
            applicationCoordinate = InteractionOption.requireText(applicationCoordinate, "applicationCoordinate", 256);
        }
    }

    public record CommandResult(String commandId, String commandType, String status) implements AgentRunEvent.Payload {
        public CommandResult {
            commandId = InteractionOption.requireText(commandId, "commandId", 256);
            commandType = InteractionOption.requireText(commandType, "commandType", 64);
            status = InteractionOption.requireText(status, "status", 64);
        }
    }

    /**
     * Provider-neutral model-call lifecycle. Prompt, response content, endpoint and raw provider
     * failures are intentionally excluded.
     */
    public record ModelLifecycle(
            String modelCallId,
            String providerId,
            String modelId,
            String status,
            int iteration,
            int attempt,
            long inputTokens,
            long outputTokens,
            String finishReason,
            String reasonCode)
            implements AgentRunEvent.Payload {
        public ModelLifecycle {
            modelCallId = text(modelCallId, "modelCallId", 256);
            providerId = text(providerId, "providerId", 128);
            modelId = text(modelId, "modelId", 256);
            status = text(status, "status", 64);
            if (iteration < 1) throw new IllegalArgumentException("iteration must be positive");
            if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
            if (inputTokens < 0) throw new IllegalArgumentException("inputTokens must not be negative");
            if (outputTokens < 0) throw new IllegalArgumentException("outputTokens must not be negative");
            finishReason = optionalText(finishReason, "finishReason", 128);
            reasonCode = text(reasonCode, "reasonCode", 128);
        }
    }

    public record ToolLifecycle(
            String toolCallId,
            String displayName,
            String status,
            String reasonCode,
            String targetSummary,
            String resultRef)
            implements AgentRunEvent.Payload {
        public ToolLifecycle {
            toolCallId = text(toolCallId, "toolCallId", 256);
            displayName = text(displayName, "displayName", 128);
            status = text(status, "status", 64);
            reasonCode = text(reasonCode, "reasonCode", 128);
            targetSummary = optionalText(targetSummary, "targetSummary", 512);
            resultRef = optionalText(resultRef, "resultRef", 512);
        }
    }

    public record ExecutionLifecycle(
            String executionId,
            String toolCallId,
            String status,
            String commandSummary,
            String logicalWorkdir,
            String streamKind,
            String chunkOrRef,
            Integer exitCode,
            boolean truncated,
            String fileChangeSetRef)
            implements AgentRunEvent.Payload {
        public ExecutionLifecycle {
            executionId = text(executionId, "executionId", 256);
            toolCallId = text(toolCallId, "toolCallId", 256);
            status = text(status, "status", 64);
            commandSummary = text(commandSummary, "commandSummary", 256);
            logicalWorkdir = optionalText(logicalWorkdir, "logicalWorkdir", 512);
            streamKind = text(streamKind, "streamKind", 32);
            chunkOrRef = optionalText(chunkOrRef, "chunkOrRef", 4096);
            fileChangeSetRef = optionalText(fileChangeSetRef, "fileChangeSetRef", 512);
        }
    }

    public record ResourceAvailable(String reference, String kind, String title, String status, String action)
            implements AgentRunEvent.Payload {
        public ResourceAvailable {
            reference = text(reference, "reference", 512);
            kind = text(kind, "kind", 64);
            title = text(title, "title", 256);
            status = text(status, "status", 64);
            action = text(action, "action", 128);
        }
    }

    /** Safe delivery-control projection; it intentionally excludes prompts, paths and raw tool output. */
    public record DeliveryLifecycle(
            String phase,
            String status,
            String reasonCode,
            List<String> missingEvidence,
            int remainingPercent,
            int attempt)
            implements AgentRunEvent.Payload {
        public DeliveryLifecycle {
            phase = text(phase, "phase", 64);
            status = text(status, "status", 64);
            reasonCode = text(reasonCode, "reasonCode", 128);
            missingEvidence = List.copyOf(Objects.requireNonNull(missingEvidence, "missingEvidence must not be null"));
            if (missingEvidence.size() > 32
                    || missingEvidence.stream()
                            .anyMatch(value -> value == null
                                    || value.isBlank()
                                    || value.length() > 128
                                    || !value.matches("[A-Z][A-Z0-9_]*"))) {
                throw new IllegalArgumentException("missingEvidence contains an invalid safe code");
            }
            if (remainingPercent < 0 || remainingPercent > 100) {
                throw new IllegalArgumentException("remainingPercent must be between zero and one hundred");
            }
            if (attempt < 0) throw new IllegalArgumentException("attempt must not be negative");
        }
    }

    private static String text(String value, String field, int maximumLength) {
        return InteractionOption.requireText(value, field, maximumLength);
    }

    private static String requireTextDelta(String value) {
        String delta = java.util.Objects.requireNonNull(value, "textDelta must not be null");
        if (delta.isEmpty() || delta.length() > MAXIMUM_TEXT_DELTA_LENGTH) {
            throw new IllegalArgumentException(
                    "textDelta must contain 1.." + MAXIMUM_TEXT_DELTA_LENGTH + " characters");
        }
        return delta;
    }

    private static String optionalText(String value, String field, int maximumLength) {
        String checked = java.util.Objects.requireNonNull(value, field + " must not be null");
        if (checked.length() > maximumLength) throw new IllegalArgumentException(field + " is too long");
        return checked;
    }

    private static Optional<String> optional(Optional<String> value, String field, int maximumLength) {
        return Objects.requireNonNull(value, field + " must not be null").map(item -> text(item, field, maximumLength));
    }
}
