package io.haifa.agent.application.project.product.coding;

/** Path-redacted Coding Agent projection of one durable workspace authorization. */
public record CodingWorkspaceGrant(
        String workspaceRef,
        String safeDisplayName,
        String permission,
        String source,
        String status,
        boolean revocable) {
    public CodingWorkspaceGrant {
        workspaceRef = CodingProductValues.requireText(workspaceRef, "workspaceRef", 256);
        safeDisplayName = CodingProductValues.requireText(safeDisplayName, "safeDisplayName", 128);
        permission = CodingProductValues.requireText(permission, "permission", 32);
        source = CodingProductValues.requireText(source, "source", 64);
        status = CodingProductValues.requireText(status, "status", 32);
    }
}
