package io.haifa.agent.project.snapshot;

import io.haifa.agent.project.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class WorkspaceSnapshotDigests {
    private WorkspaceSnapshotDigests() {}

    public static String digest(
            Workspace workspace, WorkspaceSnapshotStrategy strategy, WorkspaceSnapshotEvidence evidence) {
        String value = workspace.id().value() + "|" + workspace.revision() + "|" + strategy + "|" + evidence;
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
