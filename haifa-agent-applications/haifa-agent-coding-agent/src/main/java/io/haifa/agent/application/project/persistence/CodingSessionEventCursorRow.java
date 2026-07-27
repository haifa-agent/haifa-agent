package io.haifa.agent.application.project.persistence;

import java.time.Instant;

public record CodingSessionEventCursorRow(
        String sessionId, String runId, String feedVersion, long exclusiveSequence, Instant updatedAt) {}
