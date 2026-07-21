package io.haifa.agent.project.filesystem;

import io.haifa.agent.project.path.WorkspacePath;
import java.util.List;

public interface WorkspaceFileService {
    FileListPage list(FileListRequest request);

    default List<FileEntry> list(WorkspacePath directory, int limit) {
        return list(new FileListRequest(directory, 0, limit)).entries();
    }

    FileMetadata stat(WorkspacePath path, boolean includeHash);

    FileContent read(WorkspacePath path, ReadOptions options);

    List<SearchResult> search(SearchRequest request);
}
