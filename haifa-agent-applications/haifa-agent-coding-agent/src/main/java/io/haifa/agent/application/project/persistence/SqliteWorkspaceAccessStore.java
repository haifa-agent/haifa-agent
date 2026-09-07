package io.haifa.agent.application.project.persistence;

import io.haifa.agent.application.project.workspace.WorkspaceAccess;
import io.haifa.agent.application.project.workspace.WorkspaceAccessMode;
import io.haifa.agent.application.project.workspace.WorkspaceAccessStore;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.store.sqlite.SqliteRuntimeUnitOfWork;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** SQLite adapter for the minimal CA workspace access relation. */
public final class SqliteWorkspaceAccessStore implements WorkspaceAccessStore {
    private final SqliteRuntimeUnitOfWork unitOfWork;

    public SqliteWorkspaceAccessStore(SqliteRuntimeUnitOfWork unitOfWork) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
    }

    @Override
    public WorkspaceAccess createIfAbsent(WorkspaceAccess access) {
        Objects.requireNonNull(access, "access must not be null");
        return unitOfWork.execute(() -> {
            CodingWorkspaceAccessMapper mapper = mapper();
            int inserted = mapper.insertIfAbsent(row(access));
            if (inserted < 0 || inserted > 1) {
                throw new IllegalStateException("workspace access insert affected an unexpected row count");
            }
            CodingWorkspaceAccessRow current = mapper.find(
                    access.tenant().tenantId(),
                    access.principal().principalType(),
                    access.principal().principalId(),
                    access.workspaceId().value());
            if (current == null) throw new IllegalStateException("workspace access insert did not converge");
            return access(current);
        });
    }

    @Override
    public WorkspaceAccess replace(WorkspaceAccess access) {
        Objects.requireNonNull(access, "access must not be null");
        return unitOfWork.execute(() -> {
            requireOne(mapper().replace(row(access)), "workspace access replacement");
            return access;
        });
    }

    @Override
    public Optional<WorkspaceAccess> find(TenantRef tenant, PrincipalRef principal, WorkspaceId workspaceId) {
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        return unitOfWork.execute(() -> Optional.ofNullable(mapper().find(
                                tenant.tenantId(),
                                principal.principalType(),
                                principal.principalId(),
                                workspaceId.value()))
                .map(SqliteWorkspaceAccessStore::access));
    }

    @Override
    public List<WorkspaceAccess> list(TenantRef tenant, PrincipalRef principal) {
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        return unitOfWork.execute(
                () -> mapper().list(tenant.tenantId(), principal.principalType(), principal.principalId()).stream()
                        .map(SqliteWorkspaceAccessStore::access)
                        .toList());
    }

    @Override
    public boolean delete(TenantRef tenant, PrincipalRef principal, WorkspaceId workspaceId) {
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        return unitOfWork.execute(() -> mapper().delete(
                                tenant.tenantId(),
                                principal.principalType(),
                                principal.principalId(),
                                workspaceId.value())
                == 1);
    }

    private CodingWorkspaceAccessMapper mapper() {
        return unitOfWork.mapper(CodingWorkspaceAccessMapper.class);
    }

    private static CodingWorkspaceAccessRow row(WorkspaceAccess access) {
        return new CodingWorkspaceAccessRow(
                access.tenant().tenantId(),
                access.principal().principalType(),
                access.principal().principalId(),
                access.workspaceId().value(),
                access.mode().name());
    }

    private static WorkspaceAccess access(CodingWorkspaceAccessRow row) {
        return new WorkspaceAccess(
                new TenantRef(row.tenantId()),
                new PrincipalRef(row.principalId(), row.principalType()),
                new WorkspaceId(row.workspaceId()),
                WorkspaceAccessMode.valueOf(row.mode()));
    }

    private static void requireOne(int count, String operation) {
        if (count != 1) throw new IllegalStateException(operation + " did not affect exactly one row");
    }
}
