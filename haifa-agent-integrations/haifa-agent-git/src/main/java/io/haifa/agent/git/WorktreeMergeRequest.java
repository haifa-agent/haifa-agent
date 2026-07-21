package io.haifa.agent.git;

import io.haifa.agent.project.patch.PatchApplyRequest;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import java.util.Objects;

public record WorktreeMergeRequest(
        GitCommandContext context,
        GitRepositoryRef parentRepository,
        WorkspaceRevision expectedParentRevision,
        String expectedBaseCommit,
        PatchApplyRequest patchRequest) {
    public WorktreeMergeRequest {
        context = Objects.requireNonNull(context, "context must not be null");
        parentRepository = Objects.requireNonNull(parentRepository, "parentRepository must not be null");
        expectedParentRevision =
                Objects.requireNonNull(expectedParentRevision, "expectedParentRevision must not be null");
        expectedBaseCommit = Objects.requireNonNull(expectedBaseCommit, "expectedBaseCommit must not be null");
        patchRequest = Objects.requireNonNull(patchRequest, "patchRequest must not be null");
    }
}
