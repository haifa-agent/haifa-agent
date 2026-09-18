package io.haifa.agent.application.project.artifact;

import io.haifa.agent.artifact.ArtifactType;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import java.util.Arrays;
import java.util.Objects;

public record ArtifactExportRequest(
        ArtifactExportSourceKind sourceKind,
        ProjectRef project,
        WorkspaceId workspaceId,
        ProjectPath sourcePath,
        WorkspaceRevision expectedRevision,
        String expectedSourceHash,
        String fileChangeSetRef,
        String executionRef,
        byte[] suppliedContent,
        ArtifactType artifactType,
        String title,
        String mediaType,
        String exportPolicy,
        AgentRunId runId,
        AgentSessionId sessionId,
        PrincipalRef actor,
        long maxBytes) {
    public ArtifactExportRequest {
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind must not be null");
        project = Objects.requireNonNull(project, "project must not be null");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        sourcePath = Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision must not be null");
        expectedSourceHash = require(expectedSourceHash, "expectedSourceHash");
        suppliedContent = suppliedContent == null ? null : Arrays.copyOf(suppliedContent, suppliedContent.length);
        if (sourceKind == ArtifactExportSourceKind.FILE && suppliedContent != null) {
            throw new IllegalArgumentException("file export resolves content from the guarded workspace");
        }
        if (sourceKind != ArtifactExportSourceKind.FILE && suppliedContent == null) {
            throw new IllegalArgumentException("document export requires explicit supplied content");
        }
        artifactType = Objects.requireNonNull(artifactType, "artifactType must not be null");
        title = require(title, "title");
        mediaType = require(mediaType, "mediaType");
        exportPolicy = require(exportPolicy, "exportPolicy");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        actor = Objects.requireNonNull(actor, "actor must not be null");
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
    }

    @Override
    public byte[] suppliedContent() {
        return suppliedContent == null ? null : Arrays.copyOf(suppliedContent, suppliedContent.length);
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
