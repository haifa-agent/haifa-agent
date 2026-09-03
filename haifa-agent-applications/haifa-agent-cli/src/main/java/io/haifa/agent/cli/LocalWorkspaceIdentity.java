package io.haifa.agent.cli;

import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.configuration.ProjectConfigurationId;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Stable, path-redacted identity for one verified local workspace root.
 *
 * <p>The host path remains available only to the local provider assembly. Product identifiers use
 * a versioned namespace and a full SHA-256 digest so the same workspace is provisioned with the
 * same logical identity after a process restart.
 */
final class LocalWorkspaceIdentity {
    private static final String NAMESPACE = "io.haifa.coding-agent.local-workspace/v1";

    private final Path root;
    private final String rootFingerprint;
    private final String digest;

    private LocalWorkspaceIdentity(Path root, String rootFingerprint, String digest) {
        this.root = root;
        this.rootFingerprint = rootFingerprint;
        this.digest = digest;
    }

    static LocalWorkspaceIdentity resolve(Path requestedRoot) {
        Path normalized = Objects.requireNonNull(requestedRoot, "workspace must not be null")
                .toAbsolutePath()
                .normalize();
        if (Files.isSymbolicLink(normalized)) {
            throw new IllegalArgumentException("workspace root must not be a symbolic link");
        }
        try {
            Path real = normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(real)) {
                throw new IllegalArgumentException("workspace must be an existing non-symbolic-link directory");
            }
            String fingerprint = HostWorkspaceLocationStore.fingerprintFor(real);
            String platform =
                    System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "windows" : "posix";
            return new LocalWorkspaceIdentity(
                    real, fingerprint, sha256(NAMESPACE + "\0" + platform + "\0" + fingerprint));
        } catch (IOException exception) {
            throw new IllegalArgumentException("workspace must exist and be accessible");
        }
    }

    Path providerRoot() {
        return root;
    }

    ProjectId projectId() {
        return new ProjectId("local-project-v1-" + digest);
    }

    WorkspaceId workspaceId() {
        return new WorkspaceId("local-workspace-v1-" + digest);
    }

    WorkspaceBindingId bindingId() {
        return new WorkspaceBindingId("local-binding-v1-" + digest);
    }

    WorkspaceLocationRef locationRef() {
        return new WorkspaceLocationRef("local-location-v1:" + digest);
    }

    ProjectConfigurationId configurationId() {
        return new ProjectConfigurationId("local-configuration-v1-" + digest);
    }

    String rootFingerprint() {
        return rootFingerprint;
    }

    String safeDisplayName() {
        return "workspace-" + digest.substring(0, 12);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Override
    public String toString() {
        return "LocalWorkspaceIdentity[digest=" + digest.substring(0, 12) + "]";
    }
}
