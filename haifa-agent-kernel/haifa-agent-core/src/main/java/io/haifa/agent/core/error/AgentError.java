package io.haifa.agent.core.error;

import static io.haifa.agent.core.support.DomainValues.immutableMap;
import static io.haifa.agent.core.support.DomainValues.optionalText;
import static io.haifa.agent.core.support.DomainValues.requireText;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Provider-neutral, persistence-safe failure information. */
public record AgentError(
        AgentErrorCode code,
        AgentErrorCategory category,
        AgentErrorSeverity severity,
        Retryability retryability,
        String message,
        String technicalDetailRef,
        Map<String, Object> attributes,
        Instant occurredAt) {

    public AgentError {
        code = Objects.requireNonNull(code, "code must not be null");
        category = Objects.requireNonNull(category, "category must not be null");
        severity = Objects.requireNonNull(severity, "severity must not be null");
        retryability = Objects.requireNonNull(retryability, "retryability must not be null");
        message = requireText(message, "message");
        technicalDetailRef = optionalText(technicalDetailRef);
        attributes = immutableMap(attributes, "attributes");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    public Optional<String> optionalTechnicalDetailRef() {
        return Optional.ofNullable(technicalDetailRef).filter(value -> !value.isEmpty());
    }
}
