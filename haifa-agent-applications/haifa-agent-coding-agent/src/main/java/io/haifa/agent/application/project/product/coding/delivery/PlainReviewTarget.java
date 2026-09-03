package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Objects;

public record PlainReviewTarget(WorkspaceId workspaceId) implements ReviewTarget {
    public PlainReviewTarget {
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
    }
}
