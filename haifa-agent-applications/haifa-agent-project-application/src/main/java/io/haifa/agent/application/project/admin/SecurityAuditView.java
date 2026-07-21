package io.haifa.agent.application.project.admin;

import java.time.Instant;

public record SecurityAuditView(
        String eventId,
        String projectId,
        String workspaceId,
        String runRef,
        String decisionCode,
        String policyVersion,
        Instant occurredAt) {}
