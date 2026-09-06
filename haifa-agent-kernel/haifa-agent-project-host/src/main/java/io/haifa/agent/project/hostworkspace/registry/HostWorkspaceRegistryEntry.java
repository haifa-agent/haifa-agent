package io.haifa.agent.project.hostworkspace.registry;

import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.scope.HostDirectoryPermission;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Host-only persisted workspace registration. The physical path must be protected by durable store
 * implementations and is deliberately excluded from {@link #toString()}.
 */
public record HostWorkspaceRegistryEntry(
        ProjectId projectId,
        WorkspaceId workspaceRef,
        WorkspaceLocationRef locationRef,
        String safeDisplayName,
        HostDirectoryPermission permission,
        HostWorkspaceRegistrySource source,
        HostWorkspaceRegistryStatus status,
        Path realPath,
        String fingerprint,
        String authorizationRef,
        Instant createdAt,
        Instant validatedAt,
        Optional<Instant> revokedAt,
        Optional<String> revocationReasonCode,
        long version) {

    public HostWorkspaceRegistryEntry {
        projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        workspaceRef = Objects.requireNonNull(workspaceRef, "workspaceRef must not be null");
        locationRef = Objects.requireNonNull(locationRef, "locationRef must not be null");
        safeDisplayName = safeDisplayName(safeDisplayName);
        permission = Objects.requireNonNull(permission, "permission must not be null");
        source = Objects.requireNonNull(source, "source must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        realPath = Objects.requireNonNull(realPath, "realPath must not be null")
                .toAbsolutePath()
                .normalize();
        fingerprint = required(fingerprint, "fingerprint");
        authorizationRef = required(authorizationRef, "authorizationRef");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        validatedAt = Objects.requireNonNull(validatedAt, "validatedAt must not be null");
        revokedAt = Objects.requireNonNull(revokedAt, "revokedAt must not be null");
        revocationReasonCode = Objects.requireNonNull(revocationReasonCode, "revocationReasonCode must not be null")
                .map(value -> required(value, "revocationReasonCode"));
        if (validatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("validatedAt must not precede createdAt");
        }
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        if (status == HostWorkspaceRegistryStatus.ACTIVE
                && (revokedAt.isPresent() || revocationReasonCode.isPresent())) {
            throw new IllegalArgumentException("active registry entry cannot carry revocation facts");
        }
        if (status != HostWorkspaceRegistryStatus.ACTIVE && (revokedAt.isEmpty() || revocationReasonCode.isEmpty())) {
            throw new IllegalArgumentException("inactive registry entry requires reason and time");
        }
    }

    public static HostWorkspaceRegistryEntry active(
            ProjectId projectId,
            WorkspaceId workspaceRef,
            WorkspaceLocationRef locationRef,
            String safeDisplayName,
            HostDirectoryPermission permission,
            HostWorkspaceRegistrySource source,
            Path realPath,
            String fingerprint,
            String authorizationRef,
            Instant at) {
        return new HostWorkspaceRegistryEntry(
                projectId,
                workspaceRef,
                locationRef,
                safeDisplayName,
                permission,
                source,
                HostWorkspaceRegistryStatus.ACTIVE,
                realPath,
                fingerprint,
                authorizationRef,
                at,
                at,
                Optional.empty(),
                Optional.empty(),
                0);
    }

    public HostWorkspaceRegistryEntry revalidated(Path verifiedRealPath, Instant at) {
        return new HostWorkspaceRegistryEntry(
                projectId,
                workspaceRef,
                locationRef,
                safeDisplayName,
                permission,
                source,
                HostWorkspaceRegistryStatus.ACTIVE,
                verifiedRealPath,
                fingerprint,
                authorizationRef,
                createdAt,
                at,
                Optional.empty(),
                Optional.empty(),
                version + 1);
    }

    public HostWorkspaceRegistryEntry reactivate(
            Path verifiedRealPath, String currentFingerprint, String currentAuthorizationRef, Instant at) {
        return new HostWorkspaceRegistryEntry(
                projectId,
                workspaceRef,
                locationRef,
                safeDisplayName,
                permission,
                source,
                HostWorkspaceRegistryStatus.ACTIVE,
                verifiedRealPath,
                currentFingerprint,
                currentAuthorizationRef,
                createdAt,
                at,
                Optional.empty(),
                Optional.empty(),
                version + 1);
    }

    public HostWorkspaceRegistryEntry disable(String reasonCode, Instant at) {
        return inactive(HostWorkspaceRegistryStatus.DISABLED, reasonCode, at);
    }

    public HostWorkspaceRegistryEntry revoke(String reasonCode, Instant at) {
        return inactive(HostWorkspaceRegistryStatus.REVOKED, reasonCode, at);
    }

    public HostWorkspaceRegistryView view() {
        return new HostWorkspaceRegistryView(workspaceRef.value(), safeDisplayName, permission, source, status);
    }

    @Override
    public String toString() {
        return "HostWorkspaceRegistryEntry[projectId=" + projectId.value() + ", workspaceRef="
                + workspaceRef.value() + ", safeDisplayName=" + safeDisplayName + ", permission=" + permission
                + ", source=" + source + ", status=" + status + ", fingerprint=" + fingerprint
                + ", authorizationRef=" + authorizationRef + ", version=" + version + "]";
    }

    private HostWorkspaceRegistryEntry inactive(HostWorkspaceRegistryStatus target, String reasonCode, Instant at) {
        if (status != HostWorkspaceRegistryStatus.ACTIVE) {
            throw new IllegalStateException("registry entry is not active");
        }
        return new HostWorkspaceRegistryEntry(
                projectId,
                workspaceRef,
                locationRef,
                safeDisplayName,
                permission,
                source,
                target,
                realPath,
                fingerprint,
                authorizationRef,
                createdAt,
                at,
                Optional.of(at),
                Optional.of(required(reasonCode, "reasonCode")),
                version + 1);
    }

    private static String safeDisplayName(String value) {
        String normalized = required(value, "safeDisplayName")
                .replaceAll("[\\p{Cntrl}\\\\/]", "-")
                .trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("safeDisplayName must contain visible text");
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private static String required(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
