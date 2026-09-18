package io.haifa.agent.contract.error;

import io.haifa.agent.contract.common.CorrelationId;

public record FieldViolation(String field, String reasonCode, String safeMessage) {
    public FieldViolation {
        field = CorrelationId.requireText(field, "field", 256);
        reasonCode = CorrelationId.requireText(reasonCode, "reasonCode", 128);
        safeMessage = CorrelationId.requireText(safeMessage, "safeMessage", 512);
    }
}
