package io.haifa.agent.execution.core.manifest;

import io.haifa.agent.project.filesystem.FileListRequest;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.filesystem.WorkspaceFileService;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class WorkspaceManifestService {
    private final WorkspaceStore workspaces;
    private final WorkspaceFileService files;
    private final ManifestBudget budget;
    private final String ignorePolicyVersion;

    public WorkspaceManifestService(
            WorkspaceStore workspaces, WorkspaceFileService files, ManifestBudget budget, String ignorePolicyVersion) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.files = Objects.requireNonNull(files, "files must not be null");
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
        this.ignorePolicyVersion = Objects.requireNonNull(ignorePolicyVersion, "ignorePolicyVersion must not be null");
    }

    public WorkspaceManifest capture(WorkspaceId workspaceId) {
        var workspace =
                workspaces.find(workspaceId).orElseThrow(() -> new IllegalArgumentException("workspace not found"));
        var directories = new ArrayDeque<WorkspacePath>();
        directories.add(WorkspacePath.root(workspaceId));
        List<WorkspaceManifestEntry> entries = new ArrayList<>();
        long totalBytes = 0;
        long hashedBytes = 0;
        while (!directories.isEmpty()) {
            WorkspacePath directory = directories.removeFirst();
            int offset = 0;
            boolean more;
            do {
                var page = files.list(new FileListRequest(directory, offset, 256));
                for (var file : page.entries()) {
                    var metadata = file.metadata();
                    if (entries.size() + 1 > budget.maxFiles()) {
                        throw new ManifestBudgetException("manifest file budget exceeded");
                    }
                    totalBytes += metadata.size();
                    if (totalBytes > budget.maxTotalBytes())
                        throw new ManifestBudgetException("manifest byte budget exceeded");
                    if (metadata.type() == FileType.DIRECTORY) directories.addLast(metadata.path());
                    var detailed = metadata.type() == FileType.FILE ? files.stat(metadata.path(), true) : metadata;
                    if (metadata.type() == FileType.FILE) {
                        hashedBytes += metadata.size();
                        if (hashedBytes > budget.maxHashBytes())
                            throw new ManifestBudgetException("manifest hash budget exceeded");
                    }
                    String hash = detailed.contentHash().orElse("metadata:" + metadata.type() + ":" + metadata.size());
                    entries.add(new WorkspaceManifestEntry(
                            metadata.path().projectPath(), metadata.type(), metadata.size(), hash));
                }
                more = page.truncated();
                offset = page.nextOffset();
            } while (more);
        }
        entries.sort(Comparator.comparing(WorkspaceManifestEntry::path));
        return new WorkspaceManifest(workspaceId, workspace.revision(), ignorePolicyVersion, entries);
    }
}
