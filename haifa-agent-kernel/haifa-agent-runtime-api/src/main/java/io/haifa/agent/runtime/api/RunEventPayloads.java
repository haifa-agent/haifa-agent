package io.haifa.agent.runtime.api;

/** Typed, bounded client-event payloads. Internal maps and store rows never cross this boundary. */
public final class RunEventPayloads {
    private RunEventPayloads() {}

    public record RunLifecycle(String status, long version, String reasonCode) implements AgentRunEvent.Payload {
        public RunLifecycle {
            status = InteractionOption.requireText(status, "status", 64);
            if (version < 0) throw new IllegalArgumentException("version must not be negative");
            reasonCode = InteractionOption.requireText(reasonCode, "reasonCode", 128);
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

    public record AssistantTextDelta(String generationId, String textDelta) implements AgentRunEvent.Payload {
        public AssistantTextDelta {
            generationId = InteractionOption.requireText(generationId, "generationId", 256);
            textDelta = InteractionOption.requireText(textDelta, "textDelta", 65_536);
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

    private static String text(String value, String field, int maximumLength) {
        return InteractionOption.requireText(value, field, maximumLength);
    }

    private static String optionalText(String value, String field, int maximumLength) {
        String checked = java.util.Objects.requireNonNull(value, field + " must not be null");
        if (checked.length() > maximumLength) throw new IllegalArgumentException(field + " is too long");
        return checked;
    }
}
