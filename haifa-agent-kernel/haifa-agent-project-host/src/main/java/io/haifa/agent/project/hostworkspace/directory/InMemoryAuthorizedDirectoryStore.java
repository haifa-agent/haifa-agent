package io.haifa.agent.project.hostworkspace.directory;

import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory authorized-directory store used by non-durable Coding Agent configurations and tests. */
public final class InMemoryAuthorizedDirectoryStore implements AuthorizedDirectoryStore {
    private final ConcurrentHashMap<Key, AuthorizedDirectoryEntry> entries = new ConcurrentHashMap<>();

    @Override
    public AuthorizedDirectoryEntry create(AuthorizedDirectoryEntry entry) {
        AuthorizedDirectoryEntry existing = entries.putIfAbsent(key(entry), entry);
        if (existing != null && !existing.equals(entry)) {
            throw new IllegalStateException("authorized directory entry already exists");
        }
        return existing == null ? entry : existing;
    }

    @Override
    public Optional<AuthorizedDirectoryEntry> find(ProjectId projectId, WorkspaceId workspaceRef) {
        return Optional.ofNullable(entries.get(new Key(projectId.value(), workspaceRef.value())));
    }

    @Override
    public List<AuthorizedDirectoryEntry> list(ProjectId projectId) {
        return entries.entrySet().stream()
                .filter(entry -> entry.getKey().projectId.equals(projectId.value()))
                .map(java.util.Map.Entry::getValue)
                .sorted(Comparator.comparing(value -> value.workspaceRef().value()))
                .toList();
    }

    @Override
    public AuthorizedDirectoryEntry update(AuthorizedDirectoryEntry entry, long expectedVersion) {
        Key key = key(entry);
        entries.compute(key, (ignored, current) -> {
            if (current == null || current.version() != expectedVersion || entry.version() != expectedVersion + 1) {
                throw new IllegalStateException("authorized directory version is stale");
            }
            return entry;
        });
        return entry;
    }

    private static Key key(AuthorizedDirectoryEntry entry) {
        return new Key(entry.projectId().value(), entry.workspaceRef().value());
    }

    private record Key(String projectId, String workspaceRef) {}
}
