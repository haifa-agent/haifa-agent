package io.haifa.agent.project.ledger;

import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, in-place delta tracking of session file mutations. Maintains O(N) unique changed files
 * with immediate net-state compaction, grouped per logical workspace.
 */
public final class InMemorySessionChangeLedger implements SessionChangeLedger {

    private final ConcurrentHashMap<WorkspaceId, ConcurrentHashMap<ProjectPath, SessionFileChangeRecord>> state =
            new ConcurrentHashMap<>();

    public InMemorySessionChangeLedger() {}

    @Override
    public void record(SessionFileChangeRecord change) {
        Objects.requireNonNull(change, "change must not be null");
        WorkspaceId workspaceId = change.path().workspaceId();
        ConcurrentHashMap<ProjectPath, SessionFileChangeRecord> workspaceMap =
                state.computeIfAbsent(workspaceId, k -> new ConcurrentHashMap<>());

        if (change.type() == FileChangeType.MOVE) {
            WorkspacePath source = change.sourcePath();
            SessionFileChangeRecord existingSource = workspaceMap.remove(source.projectPath());
            workspaceMap.compute(change.path().projectPath(), (targetPath, existingTarget) -> {
                if (existingSource != null && existingSource.type() == FileChangeType.CREATE) {
                    return SessionFileChangeRecord.create(
                            new WorkspacePath(workspaceId, targetPath),
                            change.afterHash(),
                            change.afterSize(),
                            change.toolCallId(),
                            change.timestamp());
                }
                if (existingSource != null) {
                    WorkspacePath originalOrigin =
                            existingSource.sourcePath() != null ? existingSource.sourcePath() : source;
                    return SessionFileChangeRecord.move(
                            originalOrigin,
                            new WorkspacePath(workspaceId, targetPath),
                            existingSource.beforeHash(),
                            existingSource.beforeSize(),
                            change.afterHash(),
                            change.afterSize(),
                            change.toolCallId(),
                            change.timestamp());
                }
                return change;
            });
            return;
        }

        workspaceMap.compute(change.path().projectPath(), (targetPath, existing) -> {
            switch (change.type()) {
                case CREATE -> {
                    return change;
                }
                case REPLACE -> {
                    if (existing == null) {
                        return change;
                    }
                    if (existing.type() == FileChangeType.CREATE) {
                        return SessionFileChangeRecord.create(
                                new WorkspacePath(workspaceId, targetPath),
                                change.afterHash(),
                                change.afterSize(),
                                change.toolCallId(),
                                change.timestamp());
                    }
                    return new SessionFileChangeRecord(
                            new WorkspacePath(workspaceId, targetPath),
                            existing.sourcePath(),
                            existing.type(),
                            existing.beforeHash(),
                            existing.beforeSize(),
                            change.afterHash(),
                            change.afterSize(),
                            change.toolCallId(),
                            change.timestamp());
                }
                case DELETE -> {
                    if (existing != null && existing.type() == FileChangeType.CREATE) {
                        // Created and deleted within the same session -> cancels out completely
                        return null;
                    }
                    if (existing != null) {
                        ProjectPath origSource = existing.sourcePath() != null
                                ? existing.sourcePath().projectPath()
                                : targetPath;
                        return SessionFileChangeRecord.delete(
                                new WorkspacePath(workspaceId, origSource),
                                existing.beforeHash(),
                                existing.beforeSize(),
                                change.toolCallId(),
                                change.timestamp());
                    }
                    return change;
                }
                default -> {
                    return change;
                }
            }
        });
    }

    @Override
    public List<SessionFileChangeRecord> rawChanges(WorkspaceId workspaceId) {
        return compactedChanges(workspaceId);
    }

    @Override
    public List<SessionFileChangeRecord> compactedChanges(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        ConcurrentHashMap<ProjectPath, SessionFileChangeRecord> workspaceMap = state.get(workspaceId);
        if (workspaceMap == null || workspaceMap.isEmpty()) {
            return List.of();
        }
        return workspaceMap.values().stream()
                .sorted(Comparator.comparing(change -> change.path().projectPath()))
                .toList();
    }

    @Override
    public Map<WorkspaceId, List<SessionFileChangeRecord>> allCompactedChanges() {
        Map<WorkspaceId, List<SessionFileChangeRecord>> result = new LinkedHashMap<>();
        for (WorkspaceId workspaceId : state.keySet()) {
            List<SessionFileChangeRecord> compacted = compactedChanges(workspaceId);
            if (!compacted.isEmpty()) {
                result.put(workspaceId, compacted);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public void clear() {
        state.clear();
    }
}
