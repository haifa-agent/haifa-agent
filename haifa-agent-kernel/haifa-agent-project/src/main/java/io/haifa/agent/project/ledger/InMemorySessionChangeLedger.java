package io.haifa.agent.project.ledger;

import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.root.WorkspaceRootAlias;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemorySessionChangeLedger implements SessionChangeLedger {

    private final ConcurrentHashMap<WorkspaceRootAlias, CopyOnWriteArrayList<SessionFileChangeRecord>> entries =
            new ConcurrentHashMap<>();

    @Override
    public void record(SessionFileChangeRecord change) {
        Objects.requireNonNull(change, "change must not be null");
        entries.computeIfAbsent(change.rootAlias(), k -> new CopyOnWriteArrayList<>())
                .add(change);
    }

    @Override
    public List<SessionFileChangeRecord> rawChanges(WorkspaceRootAlias rootAlias) {
        Objects.requireNonNull(rootAlias, "rootAlias must not be null");
        List<SessionFileChangeRecord> list = entries.get(rootAlias);
        return list == null ? List.of() : List.copyOf(list);
    }

    @Override
    public List<SessionFileChangeRecord> compactedChanges(WorkspaceRootAlias rootAlias) {
        Objects.requireNonNull(rootAlias, "rootAlias must not be null");
        List<SessionFileChangeRecord> raw = entries.get(rootAlias);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        Map<ProjectPath, SessionFileChangeRecord> stateMap = new LinkedHashMap<>();
        for (SessionFileChangeRecord change : raw) {
            ProjectPath targetPath = change.path();
            switch (change.type()) {
                case CREATE -> {
                    stateMap.put(targetPath, change);
                }
                case REPLACE -> {
                    SessionFileChangeRecord existing = stateMap.get(targetPath);
                    if (existing == null) {
                        stateMap.put(targetPath, change);
                    } else if (existing.type() == FileChangeType.CREATE) {
                        stateMap.put(
                                targetPath,
                                SessionFileChangeRecord.create(
                                        rootAlias,
                                        targetPath,
                                        change.afterHash(),
                                        change.afterSize(),
                                        change.timestamp()));
                    } else {
                        stateMap.put(
                                targetPath,
                                new SessionFileChangeRecord(
                                        rootAlias,
                                        targetPath,
                                        existing.sourcePath(),
                                        existing.type(),
                                        existing.beforeHash(),
                                        existing.beforeSize(),
                                        change.afterHash(),
                                        change.afterSize(),
                                        change.timestamp()));
                    }
                }
                case DELETE -> {
                    SessionFileChangeRecord existing = stateMap.get(targetPath);
                    if (existing != null && existing.type() == FileChangeType.CREATE) {
                        // Created and deleted within the same session -> cancels out
                        stateMap.remove(targetPath);
                    } else if (existing != null) {
                        ProjectPath origSource = existing.sourcePath() != null ? existing.sourcePath() : targetPath;
                        stateMap.remove(targetPath);
                        stateMap.put(
                                origSource,
                                SessionFileChangeRecord.delete(
                                        rootAlias,
                                        origSource,
                                        existing.beforeHash(),
                                        existing.beforeSize(),
                                        change.timestamp()));
                    } else {
                        stateMap.put(targetPath, change);
                    }
                }
                case MOVE -> {
                    ProjectPath src = change.sourcePath();
                    SessionFileChangeRecord existingSrc = stateMap.remove(src);
                    if (existingSrc != null && existingSrc.type() == FileChangeType.CREATE) {
                        stateMap.put(
                                targetPath,
                                SessionFileChangeRecord.create(
                                        rootAlias,
                                        targetPath,
                                        change.afterHash(),
                                        change.afterSize(),
                                        change.timestamp()));
                    } else if (existingSrc != null) {
                        ProjectPath originalOrigin = existingSrc.sourcePath() != null ? existingSrc.sourcePath() : src;
                        stateMap.put(
                                targetPath,
                                SessionFileChangeRecord.move(
                                        rootAlias,
                                        originalOrigin,
                                        targetPath,
                                        existingSrc.beforeHash(),
                                        existingSrc.beforeSize(),
                                        change.afterHash(),
                                        change.afterSize(),
                                        change.timestamp()));
                    } else {
                        stateMap.put(targetPath, change);
                    }
                }
            }
        }
        return List.copyOf(stateMap.values());
    }

    @Override
    public Map<WorkspaceRootAlias, List<SessionFileChangeRecord>> allCompactedChanges() {
        Map<WorkspaceRootAlias, List<SessionFileChangeRecord>> result = new LinkedHashMap<>();
        for (WorkspaceRootAlias alias : entries.keySet()) {
            List<SessionFileChangeRecord> compacted = compactedChanges(alias);
            if (!compacted.isEmpty()) {
                result.put(alias, compacted);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public void clear() {
        entries.clear();
    }
}
