package io.haifa.agent.application.project.persistence;

import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.registry.HostWorkspaceRegistryEntry;
import io.haifa.agent.project.hostworkspace.registry.HostWorkspaceRegistrySource;
import io.haifa.agent.project.hostworkspace.registry.HostWorkspaceRegistryStatus;
import io.haifa.agent.project.hostworkspace.registry.HostWorkspaceRegistryStore;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationProtector;
import io.haifa.agent.store.sqlite.SqliteRuntimeUnitOfWork;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** SQLite CA registry store with protected physical locations and CAS state transitions. */
public final class SqliteHostWorkspaceRegistryStore implements HostWorkspaceRegistryStore {
    private static final String CORRUPT_LOCATION = "LOCATION_DECRYPTION_FAILED";

    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final CodingWorkspaceLocationCodec locations;
    private final Clock clock;

    public SqliteHostWorkspaceRegistryStore(
            SqliteRuntimeUnitOfWork unitOfWork, ModelContinuationProtector protector, Clock clock) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.locations = new CodingWorkspaceLocationCodec(protector);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public HostWorkspaceRegistryEntry create(HostWorkspaceRegistryEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        return unitOfWork.execute(() -> {
            CodingWorkspaceRegistryMapper mapper = mapper();
            CodingWorkspaceRegistryRow existing =
                    mapper.find(entry.projectId().value(), entry.workspaceRef().value());
            if (existing != null) {
                Optional<HostWorkspaceRegistryEntry> decoded = decodeOrDisable(mapper, existing);
                if (decoded.isPresent() && decoded.orElseThrow().equals(entry)) return entry;
                throw new IllegalStateException("workspace registry entry already exists");
            }
            requireOne(mapper.insert(row(entry)), "workspace registry insert");
            return entry;
        });
    }

    @Override
    public Optional<HostWorkspaceRegistryEntry> find(ProjectId projectId, WorkspaceId workspaceRef) {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(workspaceRef, "workspaceRef must not be null");
        return unitOfWork.execute(() -> Optional.ofNullable(mapper().find(projectId.value(), workspaceRef.value()))
                .flatMap(row -> decodeOrDisable(mapper(), row)));
    }

    @Override
    public List<HostWorkspaceRegistryEntry> list(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId must not be null");
        return unitOfWork.execute(() -> {
            CodingWorkspaceRegistryMapper mapper = mapper();
            List<HostWorkspaceRegistryEntry> values = new ArrayList<>();
            for (CodingWorkspaceRegistryRow row : mapper.list(projectId.value())) {
                decodeOrDisable(mapper, row).ifPresent(values::add);
            }
            return List.copyOf(values);
        });
    }

    @Override
    public HostWorkspaceRegistryEntry update(HostWorkspaceRegistryEntry entry, long expectedVersion) {
        Objects.requireNonNull(entry, "entry must not be null");
        if (entry.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("updated workspace registry version must advance by one");
        }
        return unitOfWork.execute(() -> {
            requireOne(mapper().update(row(entry), expectedVersion), "workspace registry update");
            return entry;
        });
    }

    private Optional<HostWorkspaceRegistryEntry> decodeOrDisable(
            CodingWorkspaceRegistryMapper mapper, CodingWorkspaceRegistryRow row) {
        try {
            String binding = binding(row);
            return Optional.of(new HostWorkspaceRegistryEntry(
                    new ProjectId(row.projectId()),
                    new WorkspaceId(row.workspaceRef()),
                    new WorkspaceLocationRef(row.locationRef()),
                    row.safeDisplayName(),
                    HostWorkspaceRegistrySource.valueOf(row.source()),
                    HostWorkspaceRegistryStatus.valueOf(row.status()),
                    locations.decode(row.locationNonce(), row.locationCiphertext(), row.locationDigest(), binding),
                    row.fingerprint(),
                    row.createdAt(),
                    row.validatedAt(),
                    Optional.ofNullable(row.revokedAt()),
                    Optional.ofNullable(row.revocationReasonCode()),
                    row.version()));
        } catch (RuntimeException invalidProtectedLocation) {
            if (HostWorkspaceRegistryStatus.ACTIVE.name().equals(row.status())) {
                requireOne(
                        mapper.disableCorruptLocation(
                                row.projectId(),
                                row.workspaceRef(),
                                row.version(),
                                Instant.ofEpochMilli(clock.millis()),
                                CORRUPT_LOCATION),
                        "workspace registry corrupt location disable");
            }
            return Optional.empty();
        }
    }

    private CodingWorkspaceRegistryRow row(HostWorkspaceRegistryEntry entry) {
        String binding = CodingWorkspaceLocationCodec.binding(
                entry.projectId().value(),
                entry.workspaceRef().value(),
                entry.locationRef().value(),
                entry.fingerprint());
        CodingWorkspaceLocationCodec.ProtectedLocation protectedLocation = locations.encode(entry.realPath(), binding);
        return new CodingWorkspaceRegistryRow(
                entry.projectId().value(),
                entry.workspaceRef().value(),
                entry.locationRef().value(),
                entry.safeDisplayName(),
                entry.source().name(),
                entry.status().name(),
                protectedLocation.nonce(),
                protectedLocation.ciphertext(),
                protectedLocation.digest(),
                entry.fingerprint(),
                entry.createdAt(),
                entry.validatedAt(),
                entry.revokedAt().orElse(null),
                entry.revocationReasonCode().orElse(null),
                entry.version());
    }

    private static String binding(CodingWorkspaceRegistryRow row) {
        return CodingWorkspaceLocationCodec.binding(
                row.projectId(), row.workspaceRef(), row.locationRef(), row.fingerprint());
    }

    private CodingWorkspaceRegistryMapper mapper() {
        return unitOfWork.mapper(CodingWorkspaceRegistryMapper.class);
    }

    private static void requireOne(int count, String operation) {
        if (count != 1) throw new IllegalStateException(operation + " did not affect exactly one row");
    }
}
