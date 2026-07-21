package io.haifa.agent.execution.core.manifest;

import io.haifa.agent.project.changeset.FileChange;
import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.changeset.FileVersion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ManifestDiffService {
    public List<FileChange> diff(WorkspaceManifest before, WorkspaceManifest after) {
        if (!before.workspaceId().equals(after.workspaceId()))
            throw new IllegalArgumentException("manifest workspace mismatch");
        Map<io.haifa.agent.project.path.ProjectPath, WorkspaceManifestEntry> oldEntries = index(before.entries());
        Map<io.haifa.agent.project.path.ProjectPath, WorkspaceManifestEntry> newEntries = index(after.entries());
        List<FileChange> changes = new ArrayList<>();
        Set<io.haifa.agent.project.path.ProjectPath> removed = new HashSet<>(oldEntries.keySet());
        removed.removeAll(newEntries.keySet());
        Set<io.haifa.agent.project.path.ProjectPath> added = new HashSet<>(newEntries.keySet());
        added.removeAll(oldEntries.keySet());
        for (var path : oldEntries.keySet()) {
            if (!newEntries.containsKey(path)) continue;
            var oldValue = oldEntries.get(path);
            var newValue = newEntries.get(path);
            if (!oldValue.equals(newValue)) {
                changes.add(new FileChange(FileChangeType.REPLACE, path, null, version(oldValue), version(newValue)));
            }
        }
        for (var oldPath : new ArrayList<>(removed)) {
            WorkspaceManifestEntry oldValue = oldEntries.get(oldPath);
            var candidates = added.stream()
                    .filter(path -> sameContent(oldValue, newEntries.get(path)))
                    .toList();
            if (candidates.size() == 1) {
                var destination = candidates.get(0);
                changes.add(new FileChange(
                        FileChangeType.MOVE,
                        oldPath,
                        destination,
                        version(oldValue),
                        version(newEntries.get(destination))));
                removed.remove(oldPath);
                added.remove(destination);
            }
        }
        removed.forEach(path ->
                changes.add(new FileChange(FileChangeType.DELETE, path, null, version(oldEntries.get(path)), null)));
        added.forEach(path ->
                changes.add(new FileChange(FileChangeType.CREATE, path, null, null, version(newEntries.get(path)))));
        return changes.stream()
                .sorted(java.util.Comparator.comparing(value -> value.path().value()))
                .toList();
    }

    private static Map<io.haifa.agent.project.path.ProjectPath, WorkspaceManifestEntry> index(
            List<WorkspaceManifestEntry> entries) {
        Map<io.haifa.agent.project.path.ProjectPath, WorkspaceManifestEntry> values = new HashMap<>();
        entries.forEach(value -> values.put(value.path(), value));
        return values;
    }

    private static boolean sameContent(WorkspaceManifestEntry first, WorkspaceManifestEntry second) {
        return first.type() == second.type()
                && first.size() == second.size()
                && first.contentHash().equals(second.contentHash());
    }

    private static FileVersion version(WorkspaceManifestEntry value) {
        return new FileVersion(value.type(), value.size(), value.contentHash());
    }
}
