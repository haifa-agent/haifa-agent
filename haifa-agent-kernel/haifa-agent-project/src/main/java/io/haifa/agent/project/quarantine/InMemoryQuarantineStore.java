package io.haifa.agent.project.quarantine;

import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryQuarantineStore implements QuarantineStore {
    private final ConcurrentHashMap<String, QuarantineEntry> values = new ConcurrentHashMap<>();

    @Override
    public void create(QuarantineEntry entry) {
        if (values.putIfAbsent(entry.token(), entry) != null) {
            throw new IllegalStateException("quarantine token already exists");
        }
    }

    @Override
    public Optional<QuarantineEntry> find(String token) {
        return Optional.ofNullable(values.get(token));
    }

    @Override
    public Optional<QuarantineEntry> findByOperation(WorkspaceId workspaceId, String operationId) {
        return values.values().stream()
                .filter(value -> value.workspaceId().equals(workspaceId)
                        && value.operationId().equals(operationId))
                .findFirst();
    }

    @Override
    public void remove(String token) {
        values.remove(token);
    }

    @Override
    public List<QuarantineEntry> findByWorkspace(WorkspaceId workspaceId) {
        return values.values().stream()
                .filter(value -> value.workspaceId().equals(workspaceId))
                .sorted(Comparator.comparing(QuarantineEntry::quarantinedAt))
                .toList();
    }
}
