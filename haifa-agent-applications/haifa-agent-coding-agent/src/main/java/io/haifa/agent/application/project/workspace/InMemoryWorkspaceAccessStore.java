package io.haifa.agent.application.project.workspace;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory implementation with one current relation per owner and workspace. */
public final class InMemoryWorkspaceAccessStore implements WorkspaceAccessStore {
    private final Map<Key, WorkspaceAccess> values = new ConcurrentHashMap<>();

    @Override
    public WorkspaceAccess createIfAbsent(WorkspaceAccess access) {
        Objects.requireNonNull(access, "access must not be null");
        return values.computeIfAbsent(Key.of(access), ignored -> access);
    }

    @Override
    public WorkspaceAccess replace(WorkspaceAccess access) {
        Objects.requireNonNull(access, "access must not be null");
        values.put(Key.of(access), access);
        return access;
    }

    @Override
    public Optional<WorkspaceAccess> find(TenantRef tenant, PrincipalRef principal, WorkspaceId workspaceId) {
        return Optional.ofNullable(values.get(new Key(tenant, principal, workspaceId)));
    }

    @Override
    public List<WorkspaceAccess> list(TenantRef tenant, PrincipalRef principal) {
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        return values.entrySet().stream()
                .filter(entry -> entry.getKey().tenant().equals(tenant)
                        && entry.getKey().principal().equals(principal))
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparing(access -> access.workspaceId().value()))
                .toList();
    }

    @Override
    public boolean delete(TenantRef tenant, PrincipalRef principal, WorkspaceId workspaceId) {
        return values.remove(new Key(tenant, principal, workspaceId)) != null;
    }

    private record Key(TenantRef tenant, PrincipalRef principal, WorkspaceId workspaceId) {
        private Key {
            tenant = Objects.requireNonNull(tenant, "tenant must not be null");
            principal = Objects.requireNonNull(principal, "principal must not be null");
            workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        }

        private static Key of(WorkspaceAccess access) {
            return new Key(access.tenant(), access.principal(), access.workspaceId());
        }
    }
}
