package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCategory;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.error.AgentErrorSeverity;
import io.haifa.agent.core.error.Retryability;
import java.time.Instant;
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
                error.code().value(),
                error.category().name(),
                error.severity().name(),
                error.retryability().name(),
                error.message(),
                error.technicalDetailRef(),
                error.attributes(),
                error.occurredAt().toEpochMilli());
    }

    public AgentError toDomain() {
        return new AgentError(
                new AgentErrorCode(code),
                AgentErrorCategory.valueOf(category),
                AgentErrorSeverity.valueOf(severity),
                Retryability.valueOf(retryability),
                message,
                technicalDetailRef,
                attributes,
                Instant.ofEpochMilli(occurredAt));
    }
}
