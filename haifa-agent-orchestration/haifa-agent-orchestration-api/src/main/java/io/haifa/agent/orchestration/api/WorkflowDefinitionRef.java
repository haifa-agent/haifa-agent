package io.haifa.agent.orchestration.api;

import java.util.Objects;

public record WorkflowDefinitionRef(
        WorkflowDefinitionId id, WorkflowDefinitionVersion version, WorkflowDefinitionDigest digest) {
    public WorkflowDefinitionRef {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(digest, "digest must not be null");
    }
}
