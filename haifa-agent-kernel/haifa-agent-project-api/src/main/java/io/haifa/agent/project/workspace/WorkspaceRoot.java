package io.haifa.agent.project.workspace;

import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.path.ProjectPath;
import java.util.Objects;

public record WorkspaceRoot(ProjectPath logicalRoot, WorkspaceBindingId bindingId, String semanticsId) {
    public WorkspaceRoot {
        logicalRoot = Objects.requireNonNull(logicalRoot, "logicalRoot must not be null");
        bindingId = Objects.requireNonNull(bindingId, "bindingId must not be null");
        semanticsId = Objects.requireNonNull(semanticsId, "semanticsId must not be null")
                .trim();
        if (semanticsId.isEmpty()) throw new IllegalArgumentException("semanticsId must not be blank");
    }
}
