package io.haifa.agent.application.project.context;

import io.haifa.agent.context.api.ContextBuildRequest;
import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.context.item.ContextItemId;
import io.haifa.agent.context.item.ContextItemType;
import io.haifa.agent.context.item.ContextPriority;
import io.haifa.agent.context.item.ContextProvenance;
import io.haifa.agent.context.item.ContextRetention;
import io.haifa.agent.context.item.ContextRole;
import io.haifa.agent.context.item.ContextSecurity;
import io.haifa.agent.context.item.TextContextContent;
import io.haifa.agent.context.source.ContextSource;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.filesystem.ReadOptions;
import io.haifa.agent.project.filesystem.WorkspaceFileService;
import io.haifa.agent.project.index.ProjectIndexService;
import io.haifa.agent.project.index.file.FileIndexQuery;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Capability-gated adapter; index hits are re-authorized by both index and file service. */
public final class WorkspaceFileContextSource implements ContextSource {
    public static final String SOURCE_ID = "project.workspace.files";
    private final TrustedProjectContextResolver bindings;
    private final ProjectIndexService index;
    private final WorkspaceFileService files;
    private final int maxFiles;
    private final int maxBytesPerFile;

    public WorkspaceFileContextSource(
            TrustedProjectContextResolver bindings,
            ProjectIndexService index,
            WorkspaceFileService files,
            int maxFiles,
            int maxBytesPerFile) {
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.index = Objects.requireNonNull(index, "index must not be null");
        this.files = Objects.requireNonNull(files, "files must not be null");
        if (maxFiles < 1 || maxFiles > 20 || maxBytesPerFile < 1 || maxBytesPerFile > 64 * 1024) {
            throw new IllegalArgumentException("context source budget is out of range");
        }
        this.maxFiles = maxFiles;
        this.maxBytesPerFile = maxBytesPerFile;
    }

    @Override
    public String id() {
        return SOURCE_ID;
    }

    @Override
    public String version() {
        return "1.0";
    }

    @Override
    public boolean supports(ContextBuildRequest request) {
        var binding = bindings.resolve(request.runId()).orElse(null);
        return binding != null
                && binding.capabilities().contains("file.read")
                && binding.sourceIds().contains(id());
    }

    @Override
    public List<ContextItem> load(ContextBuildRequest request) {
        var binding = bindings.resolve(request.runId()).orElse(null);
        if (binding == null
                || !binding.capabilities().contains("file.read")
                || !binding.sourceIds().contains(id())) {
            return List.of();
        }
        var result = index.queryFiles(
                new FileIndexQuery(binding.workspaceId(), ProjectPath.root(), "", 0, Math.min(200, maxFiles * 4)));
        List<ContextItem> items = new ArrayList<>();
        for (var entry : result.entries()) {
            if (entry.type() != FileType.FILE || items.size() >= maxFiles) continue;
            try {
                var content = files.read(
                        new WorkspacePath(binding.workspaceId(), entry.path()),
                        new ReadOptions(maxBytesPerFile, maxBytesPerFile, StandardCharsets.UTF_8, true));
                String text = "Untrusted project file " + entry.path().value() + ":\n" + content.text();
                items.add(new ContextItem(
                        new ContextItemId("project-file:" + request.runId().value() + ":"
                                + entry.path().value() + ":"
                                + entry.contentHash().replace(':', '-')),
                        ContextItemType.RUNTIME_STATE,
                        new TextContextContent(ContextRole.USER, text),
                        Math.max(1, text.length() / 4),
                        ContextPriority.NORMAL,
                        ContextRetention.COMPRESSIBLE,
                        new ContextSecurity(Set.of("project-content", "untrusted-evidence"), true),
                        new ContextProvenance("workspace-file", entry.path().value(), version(), content.contentHash()),
                        Map.of(
                                "logicalPath", entry.path().value(),
                                "generation", Long.toString(result.generation().value()),
                                "configurationDigest", binding.configurationDigest(),
                                "truncated", Boolean.toString(content.truncated()))));
            } catch (RuntimeException deniedOrUnreadable) {
                // A stale index entry is not authority and produces no ContextItem.
            }
        }
        return List.copyOf(items);
    }
}
