package io.haifa.agent.application.project.admin;

public record PatchConflictView(
        String patchRef,
        String workspaceId,
        String logicalPath,
        String conflictCode,
        Integer hunkIndex,
        String expectedHash,
        String actualHash) {}
