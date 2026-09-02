package io.haifa.agent.project.ledger;

import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.root.WorkspaceRootAlias;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, in-place delta tracking of session file mutations.
 * Maintains O(N) unique changed files with immediate net-state compaction.
 */
public final class InMemorySessionChangeLedger implements SessionChangeLedger {

    private final ConcurrentHashMap<WorkspaceRootAlias, ConcurrentHashMap<ProjectPath, SessionFileChangeRecord>> state =
            new ConcurrentHashMap<>();

    public InMemorySessionChangeLedger() {}

    @Override
    public void record(SessionFileChangeRecord change) {
        Objects.requireNonNull(change, "change must not be null");
        ConcurrentHashMap<ProjectPath, SessionFileChangeRecord> rootMap =
                state.computeIfAbsent(change.rootAlias(), k -> new ConcurrentHashMap<>());

        if (change.type() == FileChangeType.MOVE) {
            ProjectPath src = change.sourcePath();
            SessionFileChangeRecord existingSrc = rootMap.remove(src);
            rootMap.compute(change.path(), (targetPath, existingDst) -> {
                if (existingSrc != null && existingSrc.type() == FileChangeType.CREATE) {
                    return SessionFileChangeRecord.create(
                            change.rootAlias(),
                            targetPath,
                            change.afterHash(),
                            change.afterSize(),
                            change.toolCallId(),
                            change.timestamp());
                }
                if (existingSrc != null) {
                    ProjectPath originalOrigin = existingSrc.sourcePath() != null ? existingSrc.sourcePath() : src;
                    return SessionFileChangeRecord.move(
                            change.rootAlias(),
                            originalOrigin,
                            targetPath,
                            existingSrc.beforeHash(),
                            existingSrc.beforeSize(),
                            change.afterHash(),
                            change.afterSize(),
                            change.toolCallId(),
                            change.timestamp());
                }
                return change;
            });
            return;
        }

        rootMap.compute(change.path(), (targetPath, existing) -> {
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
                                change.rootAlias(),
                                targetPath,
                                change.afterHash(),
                                change.afterSize(),
                                change.toolCallId(),
                                change.timestamp());
                    }
                    return new SessionFileChangeRecord(
                            change.rootAlias(),
                            targetPath,
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
                        ProjectPath origSource = existing.sourcePath() != null ? existing.sourcePath() : targetPath;
                        return SessionFileChangeRecord.delete(
                                change.rootAlias(),
                                origSource,
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
    public List<SessionFileChangeRecord> rawChanges(WorkspaceRootAlias rootAlias) {
        return compactedChanges(rootAlias);
    }

    @Override
    public List<SessionFileChangeRecord> compactedChanges(WorkspaceRootAlias rootAlias) {
        Objects.requireNonNull(rootAlias, "rootAlias must not be null");
        ConcurrentHashMap<ProjectPath, SessionFileChangeRecord> rootMap = state.get(rootAlias);
        if (rootMap == null || rootMap.isEmpty()) {
            return List.of();
        }
        return rootMap.values().stream()
                .sorted(Comparator.comparing(SessionFileChangeRecord::path))
                .toList();
    }

    @Override
    public Map<WorkspaceRootAlias, List<SessionFileChangeRecord>> allCompactedChanges() {
        Map<WorkspaceRootAlias, List<SessionFileChangeRecord>> result = new LinkedHashMap<>();
        for (WorkspaceRootAlias alias : state.keySet()) {
            List<SessionFileChangeRecord> compacted = compactedChanges(alias);
            if (!compacted.isEmpty()) {
                result.put(alias, compacted);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public void clear() {
        state.clear();
    }
}
