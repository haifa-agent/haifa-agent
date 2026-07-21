package io.haifa.agent.core.reference;

import static io.haifa.agent.core.support.DomainValues.requireText;

/** Logical workspace identity; never a host absolute path. */
public record WorkspaceRef(String workspaceId) {
    public WorkspaceRef {
        workspaceId = requireText(workspaceId, "workspaceId");
    }
}
