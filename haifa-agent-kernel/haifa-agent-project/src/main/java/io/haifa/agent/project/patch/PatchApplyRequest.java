package io.haifa.agent.project.patch;

import io.haifa.agent.project.mutation.MutationContext;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import java.util.Map;
import java.util.Objects;

public record PatchApplyRequest(
        WorkspaceId workspaceId,
        PatchDocument document,
        WorkspaceRevision expectedRevision,
        Map<ProjectPath, String> expectedHashes,
        MutationContext context) {
    public PatchApplyRequest {
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        document = Objects.requireNonNull(document, "document must not be null");
        expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision must not be null");
        expectedHashes = Map.copyOf(Objects.requireNonNull(expectedHashes, "expectedHashes must not be null"));
        context = Objects.requireNonNull(context, "context must not be null");
    }
}
