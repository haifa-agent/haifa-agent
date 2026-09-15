package io.haifa.agent.project.hostworkspace.directory;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.workspace.WorkspaceAccessMode;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Host-only persisted authorized-directory fact. It is the single durable CA authorization record:
 * it answers who (tenant/principal) may use which canonical host root with which READ/DEVELOP mode
 * and whether the authorization is still valid. The physical path must be protected by durable store
 * implementations and is deliberately excluded from {@link #toString()}.
 */
public record AuthorizedDirectoryEntry(
        ProjectId projectId,
        WorkspaceId workspaceRef,
        TenantRef tenant,
        PrincipalRef owner,
        WorkspaceAccessMode mode,
        String safeDisplayName,
        AuthorizedDirectoryStatus status,
        Path realPath,
        String physicalFingerprint,
        Instant createdAt,
        Instant validatedAt,
        Optional<Instant> revokedAt,
        Optional<String> revocationReasonCode,
        long version) {

    public AuthorizedDirectoryEntry {
        projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        workspaceRef = Objects.requireNonNull(workspaceRef, "workspaceRef must not be null");
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        owner = Objects.requireNonNull(owner, "owner must not be null");
        mode = Objects.requireNonNull(mode, "mode must not be null");
        safeDisplayName = safeDisplayName(safeDisplayName);
        status = Objects.requireNonNull(status, "status must not be null");
        realPath = Objects.requireNonNull(realPath, "realPath must not be null")
                .toAbsolutePath()
                .normalize();
        physicalFingerprint = required(physicalFingerprint, "physicalFingerprint");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        validatedAt = Objects.requireNonNull(validatedAt, "validatedAt must not be null");
        revokedAt = Objects.requireNonNull(revokedAt, "revokedAt must not be null");
        revocationReasonCode = Objects.requireNonNull(revocationReasonCode, "revocationReasonCode must not be null")
                .map(value -> required(value, "revocationReasonCode"));
        if (validatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("validatedAt must not precede createdAt");
        }
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        if (status == AuthorizedDirectoryStatus.ACTIVE && (revokedAt.isPresent() || revocationReasonCode.isPresent())) {
            throw new IllegalArgumentException("active authorized directory cannot carry revocation facts");
        }
        if (status != AuthorizedDirectoryStatus.ACTIVE && (revokedAt.isEmpty() || revocationReasonCode.isEmpty())) {
            throw new IllegalArgumentException("inactive authorized directory requires reason and time");
        }
    }

    public static AuthorizedDirectoryEntry active(
            ProjectId projectId,
            WorkspaceId workspaceRef,
            TenantRef tenant,
            PrincipalRef owner,
            WorkspaceAccessMode mode,
            String safeDisplayName,
            Path realPath,
            String physicalFingerprint,
            Instant at) {
        return new AuthorizedDirectoryEntry(
                projectId,
                workspaceRef,
                tenant,
                owner,
                mode,
                safeDisplayName,
                AuthorizedDirectoryStatus.ACTIVE,
                realPath,
                physicalFingerprint,
                at,
                at,
                Optional.empty(),
                Optional.empty(),
                0);
    }

    public AuthorizedDirectoryEntry revalidated(Path verifiedRealPath, String currentPhysicalFingerprint, Instant at) {
        if (status != AuthorizedDirectoryStatus.ACTIVE) {
            throw new IllegalStateException("only an active authorized directory can be revalidated");
        }
        requireNotBefore(at, validatedAt, "revalidation");
        return new AuthorizedDirectoryEntry(
                projectId,
                workspaceRef,
                tenant,
                owner,
                mode,
                safeDisplayName,
                AuthorizedDirectoryStatus.ACTIVE,
                verifiedRealPath,
                currentPhysicalFingerprint,
                createdAt,
                at,
                Optional.empty(),
                Optional.empty(),
                version + 1);
    }

    public AuthorizedDirectoryEntry reactivate(
            Path verifiedRealPath, String currentPhysicalFingerprint, WorkspaceAccessMode nextMode, Instant at) {
        if (status == AuthorizedDirectoryStatus.ACTIVE) {
            throw new IllegalStateException("only an inactive authorized directory can be reactivated");
        }
        requireNotBefore(at, validatedAt, "reactivation");
        return new AuthorizedDirectoryEntry(
                projectId,
                workspaceRef,
                tenant,
                owner,
                nextMode,
                safeDisplayName,
                AuthorizedDirectoryStatus.ACTIVE,
                verifiedRealPath,
                currentPhysicalFingerprint,
                createdAt,
                at,
                Optional.empty(),
                Optional.empty(),
                version + 1);
    }

    public AuthorizedDirectoryEntry disable(String reasonCode, Instant at) {
        return inactive(AuthorizedDirectoryStatus.DISABLED, reasonCode, at);
    }

    public AuthorizedDirectoryEntry revoke(String reasonCode, Instant at) {
        return inactive(AuthorizedDirectoryStatus.REVOKED, reasonCode, at);
    }

    /** True when this entry is the current authorization for the given owner and required mode. */
    public boolean authorizes(TenantRef requestTenant, PrincipalRef requester, WorkspaceAccessMode required) {
        return status == AuthorizedDirectoryStatus.ACTIVE
                && tenant.equals(requestTenant)
                && owner.equals(requester)
                && mode.allows(required);
    }

    public AuthorizedDirectoryView view() {
        return new AuthorizedDirectoryView(workspaceRef.value(), safeDisplayName, mode, status);
    }

    @Override
    public String toString() {
        return "AuthorizedDirectoryEntry[projectId=" + projectId.value() + ", workspaceRef="
                + workspaceRef.value() + ", safeDisplayName=" + safeDisplayName + ", mode=" + mode + ", status="
                + status + ", physicalFingerprint=" + physicalFingerprint + ", version=" + version + "]";
    }

    private AuthorizedDirectoryEntry inactive(AuthorizedDirectoryStatus target, String reasonCode, Instant at) {
        if (status != AuthorizedDirectoryStatus.ACTIVE) {
            throw new IllegalStateException("authorized directory is not active");
        }
        requireNotBefore(at, validatedAt, "inactivation");
        return new AuthorizedDirectoryEntry(
                projectId,
                workspaceRef,
                tenant,
                owner,
                mode,
                safeDisplayName,
                target,
                realPath,
                physicalFingerprint,
                createdAt,
                at,
                Optional.of(at),
                Optional.of(required(reasonCode, "reasonCode")),
                version + 1);
    }

    private static void requireNotBefore(Instant at, Instant reference, String transition) {
        Objects.requireNonNull(at, "at must not be null");
        if (at.isBefore(reference)) {
            throw new IllegalArgumentException(transition + " time must not move backwards");
        }
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
