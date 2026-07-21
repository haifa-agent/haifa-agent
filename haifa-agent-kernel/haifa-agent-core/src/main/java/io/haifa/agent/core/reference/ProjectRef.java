package io.haifa.agent.core.reference;

import static io.haifa.agent.core.support.DomainValues.requireText;

/** Stable project identity without embedding a Project aggregate. */
public record ProjectRef(String projectId) {
    public ProjectRef {
        projectId = requireText(projectId, "projectId");
    }
}
