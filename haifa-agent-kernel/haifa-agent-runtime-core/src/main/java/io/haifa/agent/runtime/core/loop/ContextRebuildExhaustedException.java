package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.core.error.AgentErrorCode;
import java.util.Map;

/** Typed terminal failure after the single allowed CONTEXT_TOO_LONG rebuild. */
public final class ContextRebuildExhaustedException extends RuntimeException {
    public ContextRebuildExhaustedException(String message) {
        super(message);
    }
    /** One safe classification shared by the loop and outer attempt boundary. */
    public AgentErrorCode errorCode() {
        return AgentErrorCode.MODEL_CONTEXT_TOO_LONG;
    }

    public Map<String, Object> details() {
        return Map.of("contextFailure", "REBUILD_EXHAUSTED");
    }
}
