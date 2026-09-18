package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record AgentErrorPayload(
        String code,
        String category,
        String severity,
        String retryability,
        String message,
        String technicalDetailRef,
        Map<String, Object> attributes,
        long occurredAt) {

    public static AgentErrorPayload from(AgentError error) {
        return new AgentErrorPayload(
                error.code().wireCode(),
                error.category().name(),
                "ERROR",
                error.retryability().name(),
                error.message(),
                error.diagnosticId(),
                error.details(),
                error.occurredAt().toEpochMilli());
    }

    public AgentError toDomain() {
        AgentErrorCode knownCode = AgentErrorCode.fromWireCode(code);
        Map<String, Object> details = attributes == null ? Map.of() : attributes;
        if (knownCode == AgentErrorCode.UNKNOWN
                && !AgentErrorCode.UNKNOWN.wireCode().equals(code)) {
            details = new LinkedHashMap<>(details);
            details.put("unrecognizedErrorCode", code);
        }
        return new AgentError(knownCode, details, technicalDetailRef, Instant.ofEpochMilli(occurredAt));
    }
}
