package io.haifa.agent.project.hostworkspace.scope;

import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.workspace.WorkspaceId;
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
 * Stable, path-redacted identity of one authorized local directory. The host path never appears in
 * the derived identifiers; one canonical location derives a stable logical workspace identity,
 * while a separate filesystem identity signal records physical replacement at that location for
 * host-only validation and audit. Authorization remains the responsibility of the current
 * authorized-directory record.
 */
public final class HostDirectoryIdentity {
    private static final String NAMESPACE = "io.haifa.agent.project.local-authorized-directory/v1";

    private final String fingerprint;
    private final String physicalFingerprint;
    private final String digest;

    private HostDirectoryIdentity(String fingerprint, String physicalFingerprint, String digest) {
        this.fingerprint = fingerprint;
        this.physicalFingerprint = physicalFingerprint;
        this.digest = digest;
    }

    public static HostDirectoryIdentity resolve(Path realDirectory) {
        Objects.requireNonNull(realDirectory, "realDirectory must not be null");
        if (!Files.isDirectory(realDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw HostWorkspaceScopeException.invalidArgument(
                    realDirectory.toString(), "Authorized directory must be an existing directory");
        }
        try {
            String fingerprint = HostWorkspaceLocationStore.fingerprintFor(realDirectory);
            String platform =
                    System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "windows" : "posix";
            String physicalFingerprint = HostWorkspaceLocationStore.physicalFingerprintFor(realDirectory);
            return new HostDirectoryIdentity(
                    fingerprint, physicalFingerprint, sha256(NAMESPACE + "\0" + platform + "\0" + fingerprint));
        } catch (IllegalStateException exception) {
            throw HostWorkspaceScopeException.invalidArgument(
                    realDirectory.toString(), "Authorized directory must exist and be accessible");
        }
    }

    public String fingerprint() {
        return fingerprint;
    }

    /** Host-only physical identity signal refreshed after safe same-canonical-path validation. */
    public String physicalFingerprint() {
        return physicalFingerprint;
    }

    public WorkspaceId workspaceId() {
        return new WorkspaceId("local-directory-ws-v1-" + digest);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
