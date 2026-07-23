package io.haifa.agent.cli;

import io.haifa.agent.application.project.tool.ProjectToolOperations;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.project.filesystem.FileListRequest;
import io.haifa.agent.project.filesystem.ReadOptions;
import io.haifa.agent.project.filesystem.SearchRequest;
import io.haifa.agent.project.filesystem.WorkspaceFileException;
import io.haifa.agent.project.mutation.CreateFileRequest;
import io.haifa.agent.project.mutation.DeleteFileRequest;
import io.haifa.agent.project.mutation.MoveFileRequest;
import io.haifa.agent.project.mutation.MutationContext;
import io.haifa.agent.project.mutation.MutationPrecondition;
import io.haifa.agent.project.mutation.WorkspaceMutationProvider;
import io.haifa.agent.project.mutation.WriteFileRequest;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.provider.local.LocalWorkspaceFileService;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Local, capability-scoped file operations used by the CLI's Project Tool provider. */
final class LocalFileToolOperations implements ProjectToolOperations {
    private final WorkspaceStore workspaces;
    private final LocalWorkspaceFileService files;
    private final WorkspaceMutationProvider mutations;
    private final IdentifierGenerator identifiers;

    LocalFileToolOperations(
            WorkspaceStore workspaces,
            LocalWorkspaceFileService files,
            WorkspaceMutationProvider mutations,
            IdentifierGenerator identifiers) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.files = Objects.requireNonNull(files, "files must not be null");
        this.mutations = Objects.requireNonNull(mutations, "mutations must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
    }

    @Override
    public ToolResult execute(
            String toolName,
            WorkspaceId workspaceId,
            PrincipalRef actor,
            String runRef,
            String policyDecisionRef,
            ToolArguments arguments) {
        try {
            return switch (toolName) {
                case "file.list" -> list(workspaceId, arguments.values());
                case "file.stat" -> stat(workspaceId, arguments.values());
                case "file.read" -> read(workspaceId, arguments.values());
                case "file.search" -> search(workspaceId, arguments.values());
                case "file.create" -> create(workspaceId, actor, runRef, policyDecisionRef, arguments.values());
                case "file.write" -> write(workspaceId, actor, runRef, policyDecisionRef, arguments.values());
                case "file.delete" -> delete(workspaceId, actor, runRef, policyDecisionRef, arguments.values());
                case "file.move" -> move(workspaceId, actor, runRef, policyDecisionRef, arguments.values());
                default -> throw new IllegalStateException("CLI does not support tool: " + toolName);
            };
        } catch (WorkspaceFileException exception) {
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("errorCode", exception.code().name());
            String logicalPath = exception
                    .logicalPath()
                    .map(WorkspacePath::projectPath)
                    .map(ProjectPath::toString)
                    .orElse(null);
            if (logicalPath != null) data.put("path", logicalPath);
            String summary = "Workspace file operation failed: "
                    + exception.code().name() + (logicalPath == null ? "" : " (path=" + logicalPath + ")");
            return failure(summary, data);
        } catch (IllegalArgumentException exception) {
            return failure("Workspace file arguments are invalid", Map.of("errorCode", "INVALID_ARGUMENT"));
        }
    }

    private ToolResult list(WorkspaceId workspaceId, Map<String, Object> values) {
        var page = files.list(new FileListRequest(path(workspaceId, values, "path"), 0, 500));
        List<Map<String, Object>> entries = page.entries().stream()
                .map(entry -> Map.<String, Object>of(
                        "path", entry.metadata().path().projectPath().toString(),
                        "type", entry.metadata().type().name(),
                        "size", entry.metadata().size()))
                .toList();
        return success(
                "Listed " + entries.size() + " workspace entries",
                Map.of("entries", entries, "truncated", page.truncated()));
    }

    private ToolResult stat(WorkspaceId workspaceId, Map<String, Object> values) {
        var metadata = files.stat(path(workspaceId, values, "path"), true);
        return success(
                "Inspected " + metadata.path().projectPath(),
                Map.of(
                        "path",
                        metadata.path().projectPath().toString(),
                        "type",
                        metadata.type().name(),
                        "size",
                        metadata.size(),
                        "contentHash",
                        metadata.contentHash().orElse("")));
    }

    private ToolResult read(WorkspaceId workspaceId, Map<String, Object> values) {
        var content = files.read(path(workspaceId, values, "path"), ReadOptions.defaults());
        return success(
                "Read " + content.path().projectPath(),
                Map.of(
                        "path",
                        content.path().projectPath().toString(),
                        "content",
                        content.text(),
                        "truncated",
                        content.truncated()));
    }

    private ToolResult search(WorkspaceId workspaceId, Map<String, Object> values) {
        String query = string(values, "query");
        var matches = files.search(new SearchRequest(
                path(workspaceId, values, "path"), query, 2_000, integer(values, "maxResults", 100), 1_048_576, false));
        List<Map<String, Object>> results = matches.stream()
                .map(match -> Map.<String, Object>of(
                        "path",
                        match.path().projectPath().toString(),
                        "line",
                        match.line(),
                        "column",
                        match.column(),
                        "excerpt",
                        match.excerpt()))
                .toList();
        return success("Found " + results.size() + " matches", Map.of("results", results));
    }

    private ToolResult create(
            WorkspaceId workspaceId,
            PrincipalRef actor,
            String runRef,
            String policyDecisionRef,
            Map<String, Object> values) {
        WorkspacePath path = path(workspaceId, values, "path");
        Workspace workspace = workspace(workspaceId);
        var result = mutations.create(new CreateFileRequest(
                path,
                string(values, "content").getBytes(StandardCharsets.UTF_8),
                MutationPrecondition.absent(workspace.revision()),
                context(actor, runRef, policyDecisionRef)));
        return success(
                "Created " + path.projectPath(),
                Map.of("changeSetId", result.changeSetId().value()));
    }

    private ToolResult write(
            WorkspaceId workspaceId,
            PrincipalRef actor,
            String runRef,
            String policyDecisionRef,
            Map<String, Object> values) {
        WorkspacePath path = path(workspaceId, values, "path");
        Workspace workspace = workspace(workspaceId);
        String currentHash = files.stat(path, true)
                .contentHash()
                .orElseThrow(() -> new IllegalArgumentException("file hash is unavailable"));
        var result = mutations.write(new WriteFileRequest(
                path,
                string(values, "content").getBytes(StandardCharsets.UTF_8),
                MutationPrecondition.existing(workspace.revision(), currentHash),
                context(actor, runRef, policyDecisionRef)));
        return success(
                "Wrote " + path.projectPath(),
                Map.of("changeSetId", result.changeSetId().value()));
    }

    private ToolResult delete(
            WorkspaceId workspaceId,
            PrincipalRef actor,
            String runRef,
            String policyDecisionRef,
            Map<String, Object> values) {
        WorkspacePath path = path(workspaceId, values, "path");
        Workspace workspace = workspace(workspaceId);
        String currentHash = files.stat(path, true)
                .contentHash()
                .orElseThrow(() -> new IllegalArgumentException("file hash is unavailable"));
        var result = mutations.delete(new DeleteFileRequest(
                path,
                MutationPrecondition.existing(workspace.revision(), currentHash),
                context(actor, runRef, policyDecisionRef)));
        return success(
                "Deleted " + path.projectPath(),
                Map.of("changeSetId", result.changeSetId().value()));
    }

    private ToolResult move(
            WorkspaceId workspaceId,
            PrincipalRef actor,
            String runRef,
            String policyDecisionRef,
            Map<String, Object> values) {
        WorkspacePath source = path(workspaceId, values, "source");
        WorkspacePath destination = path(workspaceId, values, "destination");
        Workspace workspace = workspace(workspaceId);
        String currentHash = files.stat(source, true)
                .contentHash()
                .orElseThrow(() -> new IllegalArgumentException("file hash is unavailable"));
        var result = mutations.move(new MoveFileRequest(
                source,
                destination,
                MutationPrecondition.existing(workspace.revision(), currentHash),
                context(actor, runRef, policyDecisionRef)));
        return success(
                "Moved " + source.projectPath() + " to " + destination.projectPath(),
                Map.of("changeSetId", result.changeSetId().value()));
    }

    private MutationContext context(PrincipalRef actor, String runRef, String policyDecisionRef) {
        return new MutationContext(identifiers.nextValue(), runRef, null, actor, policyDecisionRef);
    }

    private Workspace workspace(WorkspaceId id) {
        return workspaces.find(id).orElseThrow(() -> new IllegalStateException("workspace is unavailable"));
    }

    private static WorkspacePath path(WorkspaceId workspaceId, Map<String, Object> values, String key) {
        String value = string(values, key);
        return new WorkspacePath(workspaceId, value.equals(".") ? ProjectPath.root() : ProjectPath.of(value));
    }

    private static String string(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank())
            throw new IllegalArgumentException(key + " must be non-empty text");
        return text;
    }

    private static int integer(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || number.intValue() < 1)
            throw new IllegalArgumentException(key + " must be positive");
        return number.intValue();
    }

    private static ToolResult success(String summary, Map<String, Object> data) {
        return new ToolResult(true, summary, data, List.of(), List.of(), false);
    }

    private static ToolResult failure(String summary, Map<String, Object> data) {
        return new ToolResult(false, summary, data, List.of(), List.of(), false);
    }
}
