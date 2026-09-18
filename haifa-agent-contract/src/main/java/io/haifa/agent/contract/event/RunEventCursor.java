package io.haifa.agent.contract.event;

import io.haifa.agent.contract.common.CorrelationId;

/** Opaque remote cursor. Clients may store and return it but must not edit it. */
public record RunEventCursor(String value) {
    public RunEventCursor {
        value = CorrelationId.requireText(value, "value", 2_048);
    }
}
