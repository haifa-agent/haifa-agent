package io.haifa.agent.artifact;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.util.Objects;

public record ArtifactProvenance(
        ProjectRef project,
        String workspaceRef,
        AgentRunId runId,
        AgentSessionId sessionId,
        String fileChangeSetRef,
        String executionRef,
        String sourceLogicalPath,
        String sourceHash,
        String exportPolicy,
        PrincipalRef createdBy) {
    public ArtifactProvenance {
        project = Objects.requireNonNull(project, "project must not be null");
        workspaceRef = require(workspaceRef, "workspaceRef");
        sourceLogicalPath = require(sourceLogicalPath, "sourceLogicalPath");
        sourceHash = require(sourceHash, "sourceHash");
        exportPolicy = require(exportPolicy, "exportPolicy");
        createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
        fileChangeSetRef = optional(fileChangeSetRef);
        executionRef = optional(executionRef);
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
