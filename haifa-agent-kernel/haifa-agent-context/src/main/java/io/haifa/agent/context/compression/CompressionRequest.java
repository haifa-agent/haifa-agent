package io.haifa.agent.context.compression;

import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record CompressionRequest(
        SummaryId summaryId,
        SummaryVersion version,
        AgentSessionId sessionId,
        List<AgentMessage> sourceMessages,
        int maxFacts,
        Instant createdAt,
        String policyVersion) {
    public CompressionRequest {
        summaryId = Objects.requireNonNull(summaryId, "summaryId must not be null");
        version = Objects.requireNonNull(version, "version must not be null");
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        sourceMessages = List.copyOf(Objects.requireNonNull(sourceMessages, "sourceMessages must not be null"));
        if (sourceMessages.isEmpty()) throw new IllegalArgumentException("compression sources must not be empty");
        for (var message : sourceMessages) {
            if (!message.sessionId().equals(sessionId)) {
                throw new IllegalArgumentException("compression sources must belong to the same session");
            }
        }
        if (maxFacts < 1) throw new IllegalArgumentException("maxFacts must be positive");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        policyVersion = Objects.requireNonNull(policyVersion, "policyVersion must not be null")
                .trim();
        if (policyVersion.isEmpty()) throw new IllegalArgumentException("policyVersion must not be blank");
    }
}
