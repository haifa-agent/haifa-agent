package io.haifa.agent.application.project.product.coding;

/** Path-redacted Coding Agent projection of one active workspace and its current access mode. */
public record CodingWorkspaceView(
        String workspaceRef, String safeDisplayName, String mode, String status, boolean revocable) {
    public CodingWorkspaceView {
        workspaceRef = CodingProductValues.requireText(workspaceRef, "workspaceRef", 256);
        safeDisplayName = CodingProductValues.requireText(safeDisplayName, "safeDisplayName", 128);
        mode = CodingProductValues.requireText(mode, "mode", 32);
        status = CodingProductValues.requireText(status, "status", 32);
    }
}
