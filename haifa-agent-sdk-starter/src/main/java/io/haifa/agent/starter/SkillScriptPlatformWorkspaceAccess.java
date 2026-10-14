package io.haifa.agent.starter;

import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.sdk.api.HaifaAgentException;
import java.nio.file.Path;
import java.util.Objects;

/** Fail-closed access to the one declared Workspace root of a trusted Skill script platform. */
final class SkillScriptPlatformWorkspaceAccess {
    private final HostWorkspaceLocationStore locations;
    private final WorkspaceId workspaceId;
    private final String errorCode;

    SkillScriptPlatformWorkspaceAccess(
            HostWorkspaceLocationStore locations, WorkspaceId workspaceId, String errorCode) {
        this.locations = Objects.requireNonNull(locations, "locations must not be null");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    /**
     * Resolves the verified physical Workspace root; drift between the frozen location and the host
     * host directory fails closed exactly like the Sandbox provider path.
     */
    Path workspaceRoot() {
        try {
            return locations.resolveForTrustedProvider(workspaceId);
        } catch (RuntimeException exception) {
            throw new HaifaAgentException(
                    errorCode,
                    "execution.assemble",
                    "execution",
                    "the declared workspace is not the frozen execution workspace",
                    exception);
        }
    }
}
