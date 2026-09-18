package io.haifa.agent.application.project.persistence;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.directory.AuthorizedDirectoryEntry;
import io.haifa.agent.project.hostworkspace.directory.AuthorizedDirectoryStatus;
import io.haifa.agent.project.hostworkspace.directory.AuthorizedDirectoryStore;
import io.haifa.agent.project.workspace.WorkspaceAccessMode;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationProtector;
import io.haifa.agent.store.sqlite.SqliteRuntimeUnitOfWork;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** SQLite single authorized-directory store with protected physical locations and CAS transitions. */
public final class SqliteAuthorizedDirectoryStore implements AuthorizedDirectoryStore {
    private static final String CORRUPT_LOCATION = "LOCATION_DECRYPTION_FAILED";

    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final CodingWorkspaceLocationCodec locations;
    private final Clock clock;

    public SqliteAuthorizedDirectoryStore(
            SqliteRuntimeUnitOfWork unitOfWork, ModelContinuationProtector protector, Clock clock) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.locations = new CodingWorkspaceLocationCodec(protector);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public AuthorizedDirectoryEntry create(AuthorizedDirectoryEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        return unitOfWork.execute(() -> {
            CodingAuthorizedDirectoryMapper mapper = mapper();
            CodingAuthorizedDirectoryRow existing =
                    mapper.find(entry.projectId().value(), entry.workspaceRef().value());
            if (existing != null) {
                Optional<AuthorizedDirectoryEntry> decoded = decodeOrDisable(mapper, existing);
                if (decoded.isPresent() && decoded.orElseThrow().equals(entry)) return entry;
                throw new IllegalStateException("authorized directory entry already exists");
            }
            requireOne(mapper.insert(row(entry)), "authorized directory insert");
            return entry;
        });
    }

    @Override
    public Optional<AuthorizedDirectoryEntry> find(ProjectId projectId, WorkspaceId workspaceRef) {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(workspaceRef, "workspaceRef must not be null");
        return unitOfWork.execute(() -> Optional.ofNullable(mapper().find(projectId.value(), workspaceRef.value()))
                .flatMap(row -> decodeOrDisable(mapper(), row)));
    }

    @Override
    public List<AuthorizedDirectoryEntry> list(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId must not be null");
        return unitOfWork.execute(() -> {
            CodingAuthorizedDirectoryMapper mapper = mapper();
            List<AuthorizedDirectoryEntry> values = new ArrayList<>();
            for (CodingAuthorizedDirectoryRow row : mapper.list(projectId.value())) {
                decodeOrDisable(mapper, row).ifPresent(values::add);
            }
            return List.copyOf(values);
        });
    }

    @Override
    public AuthorizedDirectoryEntry update(AuthorizedDirectoryEntry entry, long expectedVersion) {
        Objects.requireNonNull(entry, "entry must not be null");
        if (entry.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("updated authorized directory version must advance by one");
        }
        return unitOfWork.execute(() -> {
            requireOne(mapper().update(row(entry), expectedVersion), "authorized directory update");
            return entry;
        });
    }

    private Optional<AuthorizedDirectoryEntry> decodeOrDisable(
            CodingAuthorizedDirectoryMapper mapper, CodingAuthorizedDirectoryRow row) {
        try {
            String binding = binding(row);
            return Optional.of(new AuthorizedDirectoryEntry(
                    new ProjectId(row.projectId()),
                    new WorkspaceId(row.workspaceRef()),
                    new TenantRef(row.tenantId()),
                    new PrincipalRef(row.principalId(), row.principalType()),
                    WorkspaceAccessMode.valueOf(row.mode()),
                    row.safeDisplayName(),
                    AuthorizedDirectoryStatus.valueOf(row.status()),
                    locations.decode(row.locationNonce(), row.locationCiphertext(), row.locationDigest(), binding),
                    row.physicalFingerprint(),
                    row.createdAt(),
                    row.validatedAt(),
                    Optional.ofNullable(row.revokedAt()),
                    Optional.ofNullable(row.revocationReasonCode()),
                    row.version()));
        } catch (RuntimeException invalidProtectedLocation) {
            return disable(mapper, row, CORRUPT_LOCATION);
        }
    }

    private Optional<AuthorizedDirectoryEntry> disable(
            CodingAuthorizedDirectoryMapper mapper, CodingAuthorizedDirectoryRow row, String reasonCode) {
        if (AuthorizedDirectoryStatus.ACTIVE.name().equals(row.status())) {
            requireOne(
                    mapper.disable(
                            row.projectId(),
                            row.workspaceRef(),
                            row.version(),
                            Instant.ofEpochMilli(clock.millis()),
                            reasonCode),
                    "authorized directory disable");
        }
        return Optional.empty();
    }

    private CodingAuthorizedDirectoryRow row(AuthorizedDirectoryEntry entry) {
        String binding = CodingWorkspaceLocationCodec.binding(
                entry.projectId().value(), entry.workspaceRef().value(), entry.physicalFingerprint());
        CodingWorkspaceLocationCodec.ProtectedLocation protectedLocation = locations.encode(entry.realPath(), binding);
        return new CodingAuthorizedDirectoryRow(
                entry.projectId().value(),
                entry.workspaceRef().value(),
                entry.tenant().tenantId(),
                entry.owner().principalType(),
                entry.owner().principalId(),
                entry.mode().name(),
                entry.safeDisplayName(),
                entry.status().name(),
                protectedLocation.nonce(),
                protectedLocation.ciphertext(),
                protectedLocation.digest(),
                entry.physicalFingerprint(),
                entry.createdAt(),
                entry.validatedAt(),
                entry.revokedAt().orElse(null),
                entry.revocationReasonCode().orElse(null),
                entry.version());
    }

    private static String binding(CodingAuthorizedDirectoryRow row) {
        return CodingWorkspaceLocationCodec.binding(row.projectId(), row.workspaceRef(), row.physicalFingerprint());
    }

    private CodingAuthorizedDirectoryMapper mapper() {
        return unitOfWork.mapper(CodingAuthorizedDirectoryMapper.class);
    }

    private static void requireOne(int count, String operation) {
        if (count != 1) throw new IllegalStateException(operation + " did not affect exactly one row");
    }
}
