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
}
