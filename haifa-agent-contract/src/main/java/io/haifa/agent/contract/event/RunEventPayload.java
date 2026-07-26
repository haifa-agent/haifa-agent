package io.haifa.agent.contract.event;

/** Typed P0 payload family. Unknown event types are represented by unknown envelopes, not arbitrary maps. */
public sealed interface RunEventPayload
        permits RunEventPayload.RunLifecycle,
                RunEventPayload.AssistantOutput,
                RunEventPayload.AssistantTextDelta,
                RunEventPayload.InteractionLifecycle,
                RunEventPayload.RunInputLifecycle,
                RunEventPayload.CommandResult {

    record RunLifecycle(String status, long version, String reasonCode) implements RunEventPayload {
        public RunLifecycle {
            status = require(status, "status", 64);
            if (version < 0) throw new IllegalArgumentException("version must not be negative");
            reasonCode = require(reasonCode, "reasonCode", 128);
        }
    }

    record AssistantOutput(String generationId, String status, String referenceOrSafeCode) implements RunEventPayload {
        public AssistantOutput {
            generationId = require(generationId, "generationId", 256);
            status = require(status, "status", 64);
            referenceOrSafeCode = require(referenceOrSafeCode, "referenceOrSafeCode", 512);
        }
    }

    record AssistantTextDelta(String generationId, String textDelta) implements RunEventPayload {
        public AssistantTextDelta {
            generationId = require(generationId, "generationId", 256);
            textDelta = require(textDelta, "textDelta", 65_536);
        }
    }

    record InteractionLifecycle(String requestId, String kind, String state, String actionOrReason)
            implements RunEventPayload {
        public InteractionLifecycle {
            requestId = require(requestId, "requestId", 256);
            kind = require(kind, "kind", 64);
            state = require(state, "state", 64);
            actionOrReason = require(actionOrReason, "actionOrReason", 128);
        }
    }

    record RunInputLifecycle(String inputId, String state, String applicationCoordinate) implements RunEventPayload {
        public RunInputLifecycle {
            inputId = require(inputId, "inputId", 256);
            state = require(state, "state", 64);
            applicationCoordinate = require(applicationCoordinate, "applicationCoordinate", 256);
        }
    }

    record CommandResult(String commandId, String commandType, String status) implements RunEventPayload {
        public CommandResult {
            commandId = require(commandId, "commandId", 256);
            commandType = require(commandType, "commandType", 64);
            status = require(status, "status", 64);
        }
    }

    private static String require(String value, String field, int maximumLength) {
        return io.haifa.agent.contract.common.CorrelationId.requireText(value, field, maximumLength);
    }
}
