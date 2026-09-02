package io.haifa.agent.cli;

import io.haifa.agent.application.project.tool.ProjectToolOperations;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.project.filesystem.FileContent;
import io.haifa.agent.project.filesystem.FileListRequest;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.filesystem.ReadOptions;
import io.haifa.agent.project.filesystem.SearchRequest;
import io.haifa.agent.project.filesystem.WorkspaceFileErrorCode;
import io.haifa.agent.project.filesystem.WorkspaceFileException;
import io.haifa.agent.project.ledger.SessionChangeLedger;
import io.haifa.agent.project.ledger.SessionFileChangeRecord;
import io.haifa.agent.project.mutation.CreateFileRequest;
import io.haifa.agent.project.mutation.DeleteFileRequest;
import io.haifa.agent.project.mutation.MoveFileRequest;
import io.haifa.agent.project.mutation.MutationContext;
import io.haifa.agent.project.mutation.MutationErrorCode;
import io.haifa.agent.project.mutation.MutationPrecondition;
import io.haifa.agent.project.mutation.WorkspaceMutationException;
import io.haifa.agent.project.mutation.WorkspaceMutationProvider;
import io.haifa.agent.project.mutation.WriteFileRequest;
import io.haifa.agent.project.patch.ApplyPatchParser;
import io.haifa.agent.project.patch.FilePatch;
import io.haifa.agent.project.patch.PatchHunk;
import io.haifa.agent.project.patch.PatchLine;
import io.haifa.agent.project.patch.PatchLineType;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.provider.local.LocalWorkspaceFileService;
import io.haifa.agent.project.provider.local.LocalWorkspacePathSafety;
import io.haifa.agent.project.provider.local.root.LocalMultiRootPathResolver;
import io.haifa.agent.project.provider.local.root.LocalWorkspaceRoot;
import io.haifa.agent.project.provider.local.root.LocalWorkspaceRootRegistry;
import io.haifa.agent.project.provider.local.root.LocalWorkspaceRootStrategyDetector;
import io.haifa.agent.project.root.MultiRootPath;
import io.haifa.agent.project.root.WorkspaceRootAlias;
import io.haifa.agent.project.root.WorkspaceRootErrorCode;
import io.haifa.agent.project.root.WorkspaceRootException;
import io.haifa.agent.project.root.WorkspaceRootPermission;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.tool.api.ToolReconciliation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Local, capability-scoped file operations with authoritative multi-root physical routing.
 */
final class LocalFileToolOperations implements ProjectToolOperations {
    private static final int DEFAULT_READ_BYTES = 64 * 1024;
    private static final int MAX_READ_BYTES = 256 * 1024;
    private static final int DEFAULT_READ_LINES = 400;
    private static final int MAX_READ_LINES = 2_000;

    private final WorkspaceStore workspaces;
    private final LocalWorkspaceFileService files;
    private final WorkspaceMutationProvider mutations;
    private final IdentifierGenerator identifiers;
    private final ApplyPatchParser patchParser;
    private final LocalWorkspaceRootRegistry rootRegistry;
    private final SessionChangeLedger ledger;

    LocalFileToolOperations(
            WorkspaceStore workspaces,
            LocalWorkspaceFileService files,
            WorkspaceMutationProvider mutations,
            IdentifierGenerator identifiers) {
        this(workspaces, files, mutations, identifiers, null, null);
    }

    LocalFileToolOperations(
            WorkspaceStore workspaces,
            LocalWorkspaceFileService files,
            WorkspaceMutationProvider mutations,
            IdentifierGenerator identifiers,
            LocalWorkspaceRootRegistry rootRegistry) {
        this(workspaces, files, mutations, identifiers, rootRegistry, null);
    }

    LocalFileToolOperations(
            WorkspaceStore workspaces,
            LocalWorkspaceFileService files,
            WorkspaceMutationProvider mutations,
            IdentifierGenerator identifiers,
            LocalWorkspaceRootRegistry rootRegistry,
            SessionChangeLedger ledger) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.files = Objects.requireNonNull(files, "files must not be null");
        this.mutations = Objects.requireNonNull(mutations, "mutations must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.patchParser = new ApplyPatchParser(100, 1_000, 20_000, 4 * 1024 * 1024);
        this.rootRegistry = rootRegistry;
        this.ledger = ledger;
    }

    @Override
    public ToolResult execute(
            String toolName,
            WorkspaceId workspaceId,
            PrincipalRef actor,
            String runRef,
            String policyDecisionRef,
            ToolArguments arguments) {
        return execute(
                toolName, workspaceId, actor, runRef, null, identifiers.nextValue(), policyDecisionRef, arguments);
    }

    @Override
    public ToolResult execute(
            String toolName,
            WorkspaceId workspaceId,
            PrincipalRef actor,
            String runRef,
            String toolCallRef,
            String idempotencyKey,
            String policyDecisionRef,
            ToolArguments arguments) {
        MutationContext mutationContext = context(idempotencyKey, runRef, toolCallRef, actor, policyDecisionRef);
        try {
            return switch (toolName) {
                case "file.list" -> list(workspaceId, arguments.values());
                case "file.stat" -> stat(workspaceId, arguments.values());
                case "file.read" -> read(workspaceId, arguments.values());
                case "file.search" -> search(workspaceId, arguments.values());
                case "file.create" -> create(workspaceId, mutationContext, arguments.values());
                case "file.write" -> write(workspaceId, mutationContext, arguments.values());
                case "file.patch" -> patch(workspaceId, mutationContext, arguments.values());
                case "file.delete" -> delete(workspaceId, mutationContext, arguments.values());
                case "file.move" -> move(workspaceId, mutationContext, arguments.values());
                case "workspace.attach" -> attach(arguments.values());
                default -> throw new IllegalStateException("CLI does not support tool: " + toolName);
            };
        } catch (WorkspaceRootException exception) {
            Map<String, Object> data = workspaceRootFailure(toolName, exception);
            String summary = "Multi-root workspace error: " + exception.code().name()
                    + (exception.path() == null ? "" : " (path=" + exception.path() + ")");
            return failure(summary, data);
        } catch (WorkspaceFileException exception) {
            Map<String, Object> data = workspaceFileFailure(toolName, exception.code());
            String logicalPath = exception
                    .logicalPath()
                    .map(WorkspacePath::projectPath)
                    .map(ProjectPath::toString)
                    .orElse(null);
            if (logicalPath != null) data.put("path", logicalPath);
            String summary = "Workspace file operation failed: "
                    + exception.code().name() + (logicalPath == null ? "" : " (path=" + logicalPath + ")");
            return failure(summary, data);
        } catch (WorkspaceMutationException exception) {
            String logicalPath = exception.path().projectPath().toString();
            Map<String, Object> data = workspaceMutationFailure(toolName, exception.code());
            data.put("path", logicalPath);
            return failure(
                    "Workspace mutation failed: " + exception.code().name() + " (path=" + logicalPath + ")",
                    Map.copyOf(data));
        } catch (IllegalArgumentException exception) {
            return failure("Workspace file arguments are invalid", Map.of("errorCode", "INVALID_ARGUMENT"));
        }
    }

    @Override
    public ToolReconciliation reconcile(
            String toolName,
            WorkspaceId workspaceId,
            PrincipalRef actor,
            String runRef,
            String toolCallRef,
            String idempotencyKey,
            ToolArguments arguments) {
        return ToolReconciliation.unsupported();
    }

    private ToolResult list(WorkspaceId workspaceId, Map<String, Object> values) {
        String pathStr = optionalString(values, "path");
        ResolvedTarget target = resolveTarget(workspaceId, pathStr, WorkspaceRootPermission.READ_ONLY);
        TargetListing listing = listEntries(target);
        return success(
                "Listed " + listing.entries().size() + " workspace entries",
                Map.of("entries", listing.entries(), "truncated", listing.truncated()));
    }

    private ToolResult stat(WorkspaceId workspaceId, Map<String, Object> values) {
        String pathStr = string(values, "path");
        ResolvedTarget target = resolveTarget(workspaceId, pathStr, WorkspaceRootPermission.READ_ONLY);
        TargetMetadata metadata = inspectExisting(target);
        return success(
                "Inspected " + target.displayPath(),
                Map.of(
                        "path", target.displayPath(),
                        "type", metadata.type().name(),
                        "size", metadata.size(),
                        "contentHash", metadata.type() == FileType.DIRECTORY ? "" : metadata.contentHash()));
    }

    private ToolResult read(WorkspaceId workspaceId, Map<String, Object> values) {
        String pathStr = string(values, "path");
        ResolvedTarget target = resolveTarget(workspaceId, pathStr, WorkspaceRootPermission.READ_ONLY);
        String pathText = target.displayPath();
        ReadCursor cursor = decodeCursor(optionalString(values, "cursor"), pathText);
        int maxBytes = boundedInteger(values, "maxBytes", DEFAULT_READ_BYTES, MAX_READ_BYTES);
        int maxLines = boundedInteger(values, "maxLines", DEFAULT_READ_LINES, MAX_READ_LINES);
        TargetRead content = readContent(target, cursor.offset(), maxBytes);
        if (cursor.sourceVersion() != null && !cursor.sourceVersion().equals(content.sourceVersion())) {
            throw new WorkspaceFileException(
                    WorkspaceFileErrorCode.FILE_CURSOR_STALE,
                    target.workspacePath(),
                    "file changed after the read cursor was issued");
        }
        String visible = firstLines(content.text(), maxLines);
        long visibleBytes = visible.getBytes(StandardCharsets.UTF_8).length;
        long nextOffset = content.offset() + visibleBytes;
        boolean hasMore = nextOffset < content.totalByteCount();
        int nextLine = cursor.startLine() + lineBreaks(visible);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", pathText);
        data.put("content", visible);
        data.put("startLine", cursor.startLine());
        data.put("endLine", visible.isEmpty() ? cursor.startLine() : nextLine);
        data.put("bytesRead", visibleBytes);
        data.put("totalBytes", content.totalByteCount());
        data.put("contentVersion", content.sourceVersion());
        data.put("hasMore", hasMore);
        data.put("truncated", hasMore);
        if (hasMore) {
            data.put("nextCursor", encodeCursor(nextOffset, nextLine, content.sourceVersion(), pathText));
        }
        return success("Read " + pathText, Map.copyOf(data));
    }

    private ToolResult search(WorkspaceId workspaceId, Map<String, Object> values) {
        String query = string(values, "query");
        String pathStr = optionalString(values, "path");
        ResolvedTarget target = resolveTarget(workspaceId, pathStr, WorkspaceRootPermission.READ_ONLY);
        List<Map<String, Object>> results = searchEntries(target, query, integer(values, "maxResults", 100));
        return success("Found " + results.size() + " matches", Map.of("results", results));
    }

    private ToolResult create(WorkspaceId workspaceId, MutationContext mutationContext, Map<String, Object> values) {
        String pathStr = string(values, "path");
        ResolvedTarget target = resolveTarget(workspaceId, pathStr, WorkspaceRootPermission.READ_WRITE);
        byte[] bytes = string(values, "content").getBytes(StandardCharsets.UTF_8);
        ensureAbsent(target);
        createTarget(workspaceId, mutationContext, target, bytes);
        recordCreate(target, bytes, mutationContext);
        return success("Created " + target.displayPath(), Map.of("path", target.displayPath()));
    }

    private ToolResult write(WorkspaceId workspaceId, MutationContext mutationContext, Map<String, Object> values) {
        String pathStr = string(values, "path");
        ResolvedTarget target = resolveTarget(workspaceId, pathStr, WorkspaceRootPermission.READ_WRITE);
        byte[] bytes = string(values, "content").getBytes(StandardCharsets.UTF_8);

        TargetMetadata before = requireRegularFile(target);
        writeTarget(workspaceId, mutationContext, target, bytes, before.contentHash());
        recordReplace(target, before, bytes, mutationContext);
        return success("Wrote " + target.displayPath(), Map.of("path", target.displayPath()));
    }

    private ToolResult delete(WorkspaceId workspaceId, MutationContext mutationContext, Map<String, Object> values) {
        String pathStr = string(values, "path");
        ResolvedTarget target = resolveTarget(workspaceId, pathStr, WorkspaceRootPermission.READ_WRITE);
        if (target.projectPath().isRoot()) {
            throw new WorkspaceMutationException(
                    MutationErrorCode.PATH_DENIED,
                    target.workspacePath(),
                    "cannot delete workspace root: " + target.displayPath());
        }

        TargetMetadata before = inspectExisting(target);
        deleteTarget(workspaceId, mutationContext, target, before);
        recordDelete(target, before, mutationContext);
        if (before.type() == FileType.DIRECTORY) {
            return success("Deleted directory " + target.displayPath(), Map.of("path", target.displayPath()));
        }
        return success("Deleted " + target.displayPath(), Map.of("path", target.displayPath()));
    }

    private ToolResult move(WorkspaceId workspaceId, MutationContext mutationContext, Map<String, Object> values) {
        String srcStr = string(values, "source");
        String dstStr = string(values, "destination");
        ResolvedTarget srcTarget = resolveTarget(workspaceId, srcStr, WorkspaceRootPermission.READ_WRITE);
        ResolvedTarget dstTarget = resolveTarget(workspaceId, dstStr, WorkspaceRootPermission.READ_WRITE);
        if (!srcTarget.rootAlias().equals(dstTarget.rootAlias())) {
            throw new IllegalArgumentException(
                    "cross-root file.move is not supported; use file.create and file.delete explicitly");
        }

        TargetMetadata before = requireRegularFile(srcTarget);
        ensureAbsent(dstTarget);
        moveTarget(workspaceId, mutationContext, srcTarget, dstTarget, before.contentHash());
        recordMove(srcTarget, dstTarget, before, mutationContext);
        return success(
                "Moved " + srcTarget.displayPath() + " to " + dstTarget.displayPath(),
                Map.of("source", srcTarget.displayPath(), "destination", dstTarget.displayPath()));
    }

    private TargetMetadata inspectExisting(ResolvedTarget target) {
        if (target.isMain()) {
            var metadata = files.stat(target.workspacePath(), true);
            return new TargetMetadata(
                    metadata.type(), metadata.size(), metadata.contentHash().orElse("directory:empty"));
        }
        Path file = target.hostPath();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceFileException(
                    WorkspaceFileErrorCode.PATH_NOT_FOUND,
                    target.workspacePath(),
                    "file not found: " + target.displayPath());
        }
        if (LocalWorkspacePathSafety.isUnsafeNode(file)) {
            throw new WorkspaceMutationException(
                    MutationErrorCode.PATH_DENIED,
                    target.workspacePath(),
                    "links, reparse points, and special nodes are denied: " + target.displayPath());
        }
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
            return new TargetMetadata(FileType.DIRECTORY, 0, "directory:empty");
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceMutationException(
                    MutationErrorCode.WRONG_FILE_TYPE,
                    target.workspacePath(),
                    "unsupported file type: " + target.displayPath());
        }
        try {
            byte[] content = Files.readAllBytes(file);
            return new TargetMetadata(FileType.FILE, content.length, "sha256:" + digest(content));
        } catch (IOException exception) {
            throw new WorkspaceMutationException(
                    MutationErrorCode.IO_FAILURE, target.workspacePath(), "unable to inspect " + target.displayPath());
        }
    }

    private TargetMetadata requireRegularFile(ResolvedTarget target) {
        TargetMetadata metadata = inspectExisting(target);
        if (metadata.type() != FileType.FILE) {
            throw new WorkspaceMutationException(
                    MutationErrorCode.WRONG_FILE_TYPE,
                    target.workspacePath(),
                    "path is not a regular file: " + target.displayPath());
        }
        return metadata;
    }

    private TargetContent readAll(ResolvedTarget target) {
        if (target.isMain()) {
            FileContent content = files.read(
                    target.workspacePath(),
                    new ReadOptions(0, 16 * 1024 * 1024, 16 * 1024 * 1024, StandardCharsets.UTF_8, false));
            return new TargetContent(content.text(), content.totalByteCount(), content.sourceVersion());
        }
        TargetMetadata metadata = requireRegularFile(target);
        try {
            return new TargetContent(
                    new String(Files.readAllBytes(target.hostPath()), StandardCharsets.UTF_8),
                    metadata.size(),
                    metadata.contentHash());
        } catch (IOException exception) {
            throw new WorkspaceMutationException(
                    MutationErrorCode.IO_FAILURE, target.workspacePath(), "unable to read " + target.displayPath());
        }
    }

    private TargetRead readContent(ResolvedTarget target, long requestedOffset, int maxBytes) {
        if (target.isMain()) {
            FileContent content = files.read(
                    target.workspacePath(),
                    new ReadOptions(requestedOffset, maxBytes, maxBytes, StandardCharsets.UTF_8, true));
            return new TargetRead(content.text(), content.offset(), content.totalByteCount(), content.sourceVersion());
        }
        TargetMetadata metadata = inspectExisting(target);
        if (metadata.type() != FileType.FILE) {
            throw new WorkspaceFileException(
                    WorkspaceFileErrorCode.WRONG_FILE_TYPE,
                    target.workspacePath(),
                    "path is a directory: " + target.displayPath());
        }
        try {
            byte[] bytes = Files.readAllBytes(target.hostPath());
            int offset = (int) Math.min(requestedOffset, bytes.length);
            int length = Math.min(maxBytes, bytes.length - offset);
            return new TargetRead(
                    new String(bytes, offset, length, StandardCharsets.UTF_8),
                    offset,
                    bytes.length,
                    "sha256:" + digest(bytes));
        } catch (IOException exception) {
            throw new WorkspaceFileException(
                    WorkspaceFileErrorCode.IO_FAILURE,
                    target.workspacePath(),
                    "unable to read " + target.displayPath());
        }
    }

    private TargetListing listEntries(ResolvedTarget target) {
        if (target.isMain()) {
            var page = files.list(new FileListRequest(target.workspacePath(), 0, 500));
            List<Map<String, Object>> entries = page.entries().stream()
                    .map(entry -> Map.<String, Object>of(
                            "path", entry.metadata().path().projectPath().toString(),
                            "type", entry.metadata().type().name(),
                            "size", entry.metadata().size()))
                    .toList();
            return new TargetListing(entries, page.truncated());
        }
        TargetMetadata metadata = inspectExisting(target);
        if (metadata.type() != FileType.DIRECTORY) {
            throw new WorkspaceFileException(
                    WorkspaceFileErrorCode.WRONG_FILE_TYPE,
                    target.workspacePath(),
                    "path is not a directory: " + target.displayPath());
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(target.hostPath())) {
            for (Path child : stream) {
                if (entries.size() >= 500) {
                    truncated = true;
                    break;
                }
                if (LocalWorkspacePathSafety.isUnsafeNode(child)) continue;
                String childName = child.getFileName().toString();
                String entryPath = target.rootAlias().value() + ":"
                        + (target.relativePath().isEmpty() ? childName : target.relativePath() + "/" + childName);
                boolean directory = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
                long size = directory ? 0L : Files.size(child);
                entries.add(Map.of("path", entryPath, "type", directory ? "DIRECTORY" : "FILE", "size", size));
            }
        } catch (IOException exception) {
            throw new WorkspaceFileException(
                    WorkspaceFileErrorCode.IO_FAILURE,
                    target.workspacePath(),
                    "unable to list " + target.displayPath());
        }
        entries.sort(Comparator.comparing(entry -> String.valueOf(entry.get("path"))));
        return new TargetListing(entries, truncated);
    }

    private List<Map<String, Object>> searchEntries(ResolvedTarget target, String query, int maxResults) {
        if (target.isMain()) {
            return files
                    .search(new SearchRequest(target.workspacePath(), query, 2_000, maxResults, 1_048_576, false))
                    .stream()
                    .map(match -> Map.<String, Object>of(
                            "path", match.path().projectPath().toString(),
                            "line", match.line(),
                            "column", match.column(),
                            "excerpt", match.excerpt()))
                    .toList();
        }
        TargetMetadata metadata = inspectExisting(target);
        if (metadata.type() != FileType.DIRECTORY) {
            throw new WorkspaceFileException(
                    WorkspaceFileErrorCode.WRONG_FILE_TYPE,
                    target.workspacePath(),
                    "path is not a directory: " + target.displayPath());
        }
        List<Map<String, Object>> results = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(target.hostPath())) {
            stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !LocalWorkspacePathSafety.isUnsafeNode(path))
                    .forEach(path -> appendMatches(target, query, maxResults, results, path));
        } catch (IOException exception) {
            throw new WorkspaceFileException(
                    WorkspaceFileErrorCode.IO_FAILURE,
                    target.workspacePath(),
                    "unable to search " + target.displayPath());
        }
        return List.copyOf(results);
    }

    private static void appendMatches(
            ResolvedTarget target, String query, int maxResults, List<Map<String, Object>> results, Path path) {
        if (results.size() >= maxResults) return;
        try {
            String relative = target.hostPath().relativize(path).toString().replace('\\', '/');
            String display = target.rootAlias().value() + ":"
                    + (target.relativePath().isEmpty() ? relative : target.relativePath() + "/" + relative);
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size() && results.size() < maxResults; index++) {
                String line = lines.get(index);
                int column = line.indexOf(query);
                if (column >= 0) {
                    results.add(
                            Map.of("path", display, "line", index + 1, "column", column + 1, "excerpt", line.trim()));
                }
            }
        } catch (IOException ignored) {
            // An unreadable individual file is skipped; the bounded search remains usable.
        }
    }

    private void ensureAbsent(ResolvedTarget target) {
        if (target.isMain()) {
            try {
                files.stat(target.workspacePath(), true);
            } catch (WorkspaceFileException exception) {
                if (exception.code() == WorkspaceFileErrorCode.PATH_NOT_FOUND) return;
                throw exception;
            }
        } else if (!Files.exists(target.hostPath(), LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        throw new WorkspaceMutationException(
                MutationErrorCode.TARGET_EXISTS,
                target.workspacePath(),
                "file already exists: " + target.displayPath());
    }

    private void createTarget(
            WorkspaceId workspaceId, MutationContext mutationContext, ResolvedTarget target, byte[] content) {
        if (target.isMain()) {
            Workspace workspace = workspace(workspaceId);
            mutations.create(new CreateFileRequest(
                    target.workspacePath(),
                    content,
                    MutationPrecondition.absent(workspace.revision()),
                    mutationContext));
            return;
        }
        try {
            Path file = target.hostPath();
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspaceMutationException(
                        MutationErrorCode.CONCURRENT_MODIFICATION,
                        target.workspacePath(),
                        "file appeared before create: " + target.displayPath());
            }
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.write(file, content);
        } catch (WorkspaceMutationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new WorkspaceMutationException(
                    MutationErrorCode.IO_FAILURE, target.workspacePath(), "unable to create " + target.displayPath());
        }
    }

    private void writeTarget(
            WorkspaceId workspaceId,
            MutationContext mutationContext,
            ResolvedTarget target,
            byte[] content,
            String expectedHash) {
        if (target.isMain()) {
            Workspace workspace = workspace(workspaceId);
            mutations.write(new WriteFileRequest(
                    target.workspacePath(),
                    content,
                    MutationPrecondition.existing(workspace.revision(), expectedHash),
                    mutationContext));
            return;
        }
        TargetMetadata current = requireRegularFile(target);
        if (!expectedHash.equals(current.contentHash())) {
            throw new WorkspaceMutationException(
                    MutationErrorCode.CONCURRENT_MODIFICATION,
                    target.workspacePath(),
                    "file changed before write: " + target.displayPath());
        }
        try {
            Files.write(target.hostPath(), content);
        } catch (IOException exception) {
            throw new WorkspaceMutationException(
                    MutationErrorCode.IO_FAILURE, target.workspacePath(), "unable to write " + target.displayPath());
        }
    }

    private void deleteTarget(
            WorkspaceId workspaceId, MutationContext mutationContext, ResolvedTarget target, TargetMetadata before) {
        if (target.isMain()) {
            Workspace workspace = workspace(workspaceId);
            mutations.delete(new DeleteFileRequest(
                    target.workspacePath(),
                    MutationPrecondition.existing(workspace.revision(), before.contentHash()),
                    mutationContext));
            return;
        }
        if (before.type() == FileType.DIRECTORY) {
            deleteDirectory(target.hostPath(), target);
            return;
        }
        TargetMetadata current = requireRegularFile(target);
        if (!before.contentHash().equals(current.contentHash())) {
            throw new WorkspaceMutationException(
                    MutationErrorCode.CONCURRENT_MODIFICATION,
                    target.workspacePath(),
                    "file changed before delete: " + target.displayPath());
        }
        try {
            Files.delete(target.hostPath());
        } catch (IOException exception) {
            throw new WorkspaceMutationException(
                    MutationErrorCode.IO_FAILURE, target.workspacePath(), "unable to delete " + target.displayPath());
        }
    }

    private void moveTarget(
            WorkspaceId workspaceId,
            MutationContext mutationContext,
            ResolvedTarget source,
            ResolvedTarget destination,
            String expectedHash) {
        if (source.isMain()) {
            Workspace workspace = workspace(workspaceId);
            mutations.move(new MoveFileRequest(
                    source.workspacePath(),
                    destination.workspacePath(),
                    MutationPrecondition.existing(workspace.revision(), expectedHash),
                    mutationContext));
            return;
        }
        TargetMetadata current = requireRegularFile(source);
        if (!expectedHash.equals(current.contentHash())) {
            throw new WorkspaceMutationException(
                    MutationErrorCode.CONCURRENT_MODIFICATION,
                    source.workspacePath(),
                    "source changed before move: " + source.displayPath());
        }
        try {
            if (Files.exists(destination.hostPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspaceMutationException(
                        MutationErrorCode.CONCURRENT_MODIFICATION,
                        destination.workspacePath(),
                        "destination appeared before move: " + destination.displayPath());
            }
            Path parent = destination.hostPath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.move(source.hostPath(), destination.hostPath());
        } catch (WorkspaceMutationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new WorkspaceMutationException(
                    MutationErrorCode.IO_FAILURE, source.workspacePath(), "unable to move " + source.displayPath());
        }
    }

    private void recordCreate(ResolvedTarget target, byte[] content, MutationContext mutationContext) {
        if (ledger == null) return;
        ledger.record(SessionFileChangeRecord.create(
                target.rootAlias(),
                target.projectPath(),
                "sha256:" + digest(content),
                content.length,
                mutationContext.toolCallRef(),
                Instant.now()));
    }

    private void recordReplace(
            ResolvedTarget target, TargetMetadata before, byte[] content, MutationContext mutationContext) {
        if (ledger == null) return;
        ledger.record(SessionFileChangeRecord.replace(
                target.rootAlias(),
                target.projectPath(),
                before.contentHash(),
                before.size(),
                "sha256:" + digest(content),
                content.length,
                mutationContext.toolCallRef(),
                Instant.now()));
    }

    private void recordDelete(ResolvedTarget target, TargetMetadata before, MutationContext mutationContext) {
        if (ledger == null) return;
        ledger.record(SessionFileChangeRecord.delete(
                target.rootAlias(),
                target.projectPath(),
                before.contentHash(),
                before.size(),
                mutationContext.toolCallRef(),
                Instant.now()));
    }

    private void recordMove(
            ResolvedTarget source, ResolvedTarget destination, TargetMetadata before, MutationContext mutationContext) {
        if (ledger == null) return;
        ledger.record(SessionFileChangeRecord.move(
                source.rootAlias(),
                source.projectPath(),
                destination.projectPath(),
                before.contentHash(),
                before.size(),
                before.contentHash(),
                before.size(),
                mutationContext.toolCallRef(),
                Instant.now()));
    }

    private ToolResult patch(WorkspaceId workspaceId, MutationContext mutationContext, Map<String, Object> values) {
        String patchText = string(values, "patch");
        var document = patchParser.parse(patchText);
        if (document.files().stream().anyMatch(file -> file.deletion() || file.move())) {
            throw new IllegalArgumentException(
                    "file.patch supports Add and Update only; use file.delete or file.move for Delete and Move");
        }
        WorkspaceRootAlias patchRoot = null;
        List<PatchPlanItem> plan = new ArrayList<>();
        for (FilePatch file : document.files()) {
            String pathStr = file.rootAlias().isMain()
                    ? file.targetPath().toString()
                    : file.rootAlias().value() + ":" + file.targetPath().toString();
            ResolvedTarget target = resolveTarget(workspaceId, pathStr, WorkspaceRootPermission.READ_WRITE);
            if (patchRoot != null && !patchRoot.equals(target.rootAlias())) {
                return patchFailure(
                        patchText,
                        List.of(),
                        target.displayPath(),
                        "CROSS_ROOT_PATCH_FORBIDDEN",
                        "USE_SEPARATE_PATCH_PER_ROOT",
                        false);
            }
            patchRoot = target.rootAlias();
            try {
                plan.add(preflightPatchFile(workspaceId, file, target));
            } catch (WorkspaceFileException | WorkspaceMutationException exception) {
                return patchFailure(
                        patchText,
                        List.of(),
                        target.displayPath(),
                        publicErrorCode(exception),
                        "RE_READ_AND_REGENERATE_PATCH",
                        false);
            } catch (IllegalArgumentException exception) {
                return patchFailure(
                        patchText,
                        List.of(),
                        target.displayPath(),
                        "PATCH_CONFLICT",
                        "RE_READ_AND_REGENERATE_PATCH",
                        false);
            }
        }

        List<String> appliedPaths = new ArrayList<>();
        for (PatchPlanItem item : plan) {
            try {
                commitPatchFile(workspaceId, mutationContext, item);
                appliedPaths.add(item.target().displayPath());
            } catch (WorkspaceFileException | WorkspaceMutationException exception) {
                return patchFailure(
                        patchText,
                        appliedPaths,
                        item.target().displayPath(),
                        publicErrorCode(exception),
                        "RE_READ_AND_REGENERATE_PATCH",
                        !appliedPaths.isEmpty());
            } catch (IllegalArgumentException exception) {
                return patchFailure(
                        patchText,
                        appliedPaths,
                        item.target().displayPath(),
                        "PATCH_CONFLICT",
                        "RE_READ_AND_REGENERATE_PATCH",
                        !appliedPaths.isEmpty());
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("patchSha256", "sha256:" + digest(patchText.getBytes(StandardCharsets.UTF_8)));
        data.put("complete", true);
        data.put("atomic", false);
        data.put("appliedPaths", List.copyOf(appliedPaths));
        data.put("conflicts", List.of());
        return success("Applied patch to " + document.files().size() + " file(s).", Map.copyOf(data));
    }

    private PatchPlanItem preflightPatchFile(WorkspaceId workspaceId, FilePatch file, ResolvedTarget target) {
        if (file.creation()) {
            ensureAbsent(target);
            return new PatchPlanItem(file, target, applyHunksToContent(file, ""), null, 0);
        }
        TargetContent current = readAll(target);
        return new PatchPlanItem(
                file, target, applyHunksToContent(file, current.text()), current.contentHash(), current.size());
    }

    private void commitPatchFile(WorkspaceId workspaceId, MutationContext mutationContext, PatchPlanItem item) {
        ResolvedTarget target = item.target();
        if (item.file().creation()) {
            createTarget(workspaceId, mutationContext, target, item.content());
            recordCreate(target, item.content(), mutationContext);
            return;
        }
        TargetMetadata before = new TargetMetadata(FileType.FILE, item.beforeSize(), item.beforeHash());
        writeTarget(workspaceId, mutationContext, target, item.content(), before.contentHash());
        recordReplace(target, before, item.content(), mutationContext);
    }

    private static ToolResult patchFailure(
            String patchText,
            List<String> appliedPaths,
            String failedPath,
            String errorCode,
            String failureActionCode,
            boolean reconciliationRequired) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("patchSha256", "sha256:" + digest(patchText.getBytes(StandardCharsets.UTF_8)));
        data.put("complete", false);
        data.put("atomic", false);
        data.put("appliedPaths", List.copyOf(appliedPaths));
        data.put("failedPath", failedPath);
        data.put("errorCode", errorCode);
        data.put("failureActionCode", failureActionCode);
        data.put("reconciliationRequired", reconciliationRequired);
        data.put("conflicts", List.of(Map.of("path", failedPath, "code", errorCode)));
        return failure("Patch was not fully applied", Map.copyOf(data));
    }

    private static byte[] applyHunksToContent(FilePatch patch, String source) {
        List<String> original = splitLines(source);
        List<String> output = new ArrayList<>();
        int cursor = 0;
        for (int hunkIndex = 0; hunkIndex < patch.hunks().size(); hunkIndex++) {
            PatchHunk hunk = patch.hunks().get(hunkIndex);
            int target;
            if (hunk.locateByContent()) {
                List<String> expected = hunk.lines().stream()
                        .filter(line -> line.type() != PatchLineType.ADD)
                        .map(PatchLine::text)
                        .toList();
                if (hunk.changeContext() != null) {
                    int anchor = findSequence(original, List.of(hunk.changeContext()), cursor);
                    if (anchor < 0)
                        throw new IllegalArgumentException("failed to find context anchor: " + hunk.changeContext());
                    target = expected.isEmpty() ? anchor : findSequence(original, expected, anchor);
                } else {
                    target = findSequence(original, expected, cursor);
                }
                if (target < 0)
                    throw new IllegalArgumentException("failed to find expected lines in hunk " + hunkIndex);
            } else {
                target = hunk.oldStart() == 0 ? 0 : hunk.oldStart() - 1;
            }
            if (target < cursor || target > original.size()) {
                throw new IllegalArgumentException("hunk location is outside the source");
            }
            output.addAll(original.subList(cursor, target));
            cursor = target;
            for (PatchLine line : hunk.lines()) {
                if (line.type() == PatchLineType.ADD) {
                    output.add(line.text());
                    continue;
                }
                if (cursor < original.size() && original.get(cursor).equals(line.text())) {
                    if (line.type() == PatchLineType.CONTEXT) output.add(line.text());
                    cursor++;
                }
            }
        }
        output.addAll(original.subList(cursor, original.size()));
        String joined = String.join("\n", output);
        if (!output.isEmpty() && (source.endsWith("\n") || patch.creation())) {
            joined += "\n";
        }
        return joined.getBytes(StandardCharsets.UTF_8);
    }

    private static int findSequence(List<String> source, List<String> expected, int start) {
        for (int index = start; index <= source.size() - expected.size(); index++) {
            if (source.subList(index, index + expected.size()).equals(expected)) return index;
        }
        return -1;
    }

    private static List<String> splitLines(String source) {
        if (source.isEmpty()) return List.of();
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        String[] values = normalized.split("\n", -1);
        int length = source.endsWith("\n") || source.endsWith("\r") ? values.length - 1 : values.length;
        return java.util.Arrays.asList(values).subList(0, length);
    }

    private Workspace workspace(WorkspaceId workspaceId) {
        return workspaces
                .find(workspaceId)
                .orElseThrow(() -> new WorkspaceFileException(
                        WorkspaceFileErrorCode.WORKSPACE_NOT_FOUND,
                        new WorkspacePath(workspaceId, ProjectPath.root()),
                        "workspace not found"));
    }

    private ToolResult attach(Map<String, Object> values) {
        if (rootRegistry == null) {
            throw new IllegalStateException("workspace.attach requires a local root registry");
        }
        WorkspaceRootAlias alias = WorkspaceRootAlias.of(string(values, "alias"));
        if (alias.isMain()) {
            throw new IllegalArgumentException("workspace.attach cannot replace the main root");
        }
        String requestedPath = string(values, "path");
        Path requested = Path.of(requestedPath);
        if (!requested.isAbsolute()) {
            throw new IllegalArgumentException("workspace.attach path must be an absolute host directory");
        }
        WorkspaceRootPermission permission =
                switch (string(values, "permission")) {
                    case "read-only" -> WorkspaceRootPermission.READ_ONLY;
                    case "read-write" -> WorkspaceRootPermission.READ_WRITE;
                    default -> throw new IllegalArgumentException("permission must be read-only or read-write");
                };
        try {
            Path normalizedPath = requested.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(normalizedPath)) {
                throw new IllegalArgumentException("workspace.attach path must not be a symbolic link");
            }
            Path realPath = normalizedPath.toRealPath();
            if (!Files.isDirectory(realPath)) {
                throw new IllegalArgumentException("workspace.attach path must be an existing directory");
            }
            if (Files.isSymbolicLink(realPath)) {
                throw new IllegalArgumentException("workspace.attach path must not be a symbolic link");
            }
            for (LocalWorkspaceRoot existing : rootRegistry.allRoots()) {
                Path existingRealPath = existing.hostPath().toRealPath();
                if (realPath.startsWith(existingRealPath) || existingRealPath.startsWith(realPath)) {
                    throw new IllegalArgumentException("workspace.attach path overlaps an existing root");
                }
            }
            var detection = new LocalWorkspaceRootStrategyDetector().detect(realPath);
            rootRegistry.attach(LocalWorkspaceRoot.of(alias, realPath, permission, detection.strategy()));
            return success(
                    "Attached " + alias.value() + " as " + permission.name(),
                    Map.of(
                            "alias", alias.value(),
                            "path", realPath.toString(),
                            "permission", permission.name(),
                            "strategy", detection.strategy().name()));
        } catch (IOException e) {
            throw new IllegalArgumentException("workspace.attach path cannot be accessed");
        }
    }

    private record ResolvedTarget(
            WorkspaceRootAlias rootAlias,
            String relativePath,
            ProjectPath projectPath,
            Path hostPath,
            WorkspacePath workspacePath,
            String displayPath,
            boolean isMain) {}

    private record PatchPlanItem(
            FilePatch file, ResolvedTarget target, byte[] content, String beforeHash, long beforeSize) {}

    private record TargetMetadata(FileType type, long size, String contentHash) {}

    private record TargetContent(String text, long size, String contentHash) {}

    private record TargetRead(String text, long offset, long totalByteCount, String sourceVersion) {}

    private record TargetListing(List<Map<String, Object>> entries, boolean truncated) {}

    private ResolvedTarget resolveTarget(
            WorkspaceId workspaceId, String pathInput, WorkspaceRootPermission requiredPermission) {
        String safeInput = (pathInput == null || pathInput.isBlank()) ? "" : pathInput.trim();
        MultiRootPath parsed = LocalMultiRootPathResolver.parse(safeInput);

        if (rootRegistry != null) {
            rootRegistry.checkPermission(parsed.rootAlias(), requiredPermission);
            LocalMultiRootPathResolver.ResolvedRootPath resolved =
                    LocalMultiRootPathResolver.resolve(rootRegistry, parsed);
            ProjectPath projectPath =
                    parsed.relativePath().isEmpty() ? ProjectPath.root() : ProjectPath.of(parsed.relativePath());
            WorkspacePath wsPath = new WorkspacePath(workspaceId, projectPath);
            String display = parsed.rootAlias().isMain()
                    ? parsed.relativePath()
                    : parsed.rootAlias().value() + ":" + parsed.relativePath();
            return new ResolvedTarget(
                    parsed.rootAlias(),
                    parsed.relativePath(),
                    projectPath,
                    resolved.hostPath(),
                    wsPath,
                    display,
                    parsed.rootAlias().isMain());
        } else {
            if (!parsed.rootAlias().isMain()) {
                throw new WorkspaceRootException(
                        WorkspaceRootErrorCode.ROOT_ALIAS_NOT_FOUND,
                        parsed.rootAlias().value(),
                        safeInput,
                        "Unregistered root alias: " + parsed.rootAlias().value());
            }
            if (parsed.relativePath().contains("..")) {
                throw new WorkspaceRootException(
                        WorkspaceRootErrorCode.PATH_ESCAPE_FORBIDDEN,
                        parsed.rootAlias().value(),
                        parsed.relativePath(),
                        "Path contains '..' traversal escaping workspace: " + safeInput);
            }
            ProjectPath projectPath =
                    parsed.relativePath().isEmpty() ? ProjectPath.root() : ProjectPath.of(parsed.relativePath());
            WorkspacePath wsPath = new WorkspacePath(workspaceId, projectPath);
            Path hostPath = Path.of(parsed.relativePath()).toAbsolutePath().normalize();
            return new ResolvedTarget(
                    WorkspaceRootAlias.MAIN,
                    parsed.relativePath(),
                    projectPath,
                    hostPath,
                    wsPath,
                    parsed.relativePath(),
                    true);
        }
    }

    private static String string(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank())
            throw new IllegalArgumentException(key + " must be non-empty text");
        return text;
    }

    private static String optionalString(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static int integer(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        if (value instanceof Number number) return number.intValue();
        return fallback;
    }

    private static boolean bool(Map<String, Object> values, String key, boolean fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean flag) return flag;
        throw new IllegalArgumentException(key + " must be boolean");
    }

    private static void deleteDirectory(Path directory, ResolvedTarget target) {
        List<Path> paths;
        try (var walk = Files.walk(directory)) {
            paths = walk.toList();
        } catch (IOException exception) {
            throw new WorkspaceMutationException(
                    MutationErrorCode.IO_FAILURE, target.workspacePath(), "unable to inspect directory for deletion");
        }
        if (paths.size() > 1) {
            throw new WorkspaceMutationException(
                    MutationErrorCode.PATH_DENIED,
                    target.workspacePath(),
                    "directory is not empty; recursive deletion is not supported: " + target.displayPath());
        }
        try {
            Files.delete(directory);
        } catch (IOException exception) {
            throw new WorkspaceMutationException(
                    MutationErrorCode.IO_FAILURE,
                    target.workspacePath(),
                    "failed to delete empty directory: " + exception.getMessage());
        }
    }

    private static int boundedInteger(Map<String, Object> values, String key, int fallback, int max) {
        int value = integer(values, key, fallback);
        return Math.min(Math.max(1, value), max);
    }

    private static String firstLines(String content, int maxLines) {
        if (content == null || content.isEmpty()) return "";
        int lineCount = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lineCount++;
                if (lineCount == maxLines) {
                    return content.substring(0, i + 1);
                }
            }
        }
        return content;
    }

    private static int lineBreaks(String text) {
        if (text == null || text.isEmpty()) return 0;
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }

    private static String digest(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ReadCursor decodeCursor(String cursor, String path) {
        if (cursor == null || cursor.isBlank()) {
            return new ReadCursor(0, 1, null, path);
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split(java.util.regex.Pattern.quote("|"), 4);
            if (parts.length == 4) {
                long offset = Long.parseLong(parts[0]);
                int line = Integer.parseInt(parts[1]);
                String version = parts[2].isEmpty() ? null : parts[2];
                String cursorPath = parts[3];
                return new ReadCursor(offset, line, version, cursorPath);
            }
        } catch (Exception ignored) {
        }
        return new ReadCursor(0, 1, null, path);
    }

    private static String encodeCursor(long offset, int line, String version, String path) {
        String raw = offset + "|" + line + "|" + (version == null ? "" : version) + "|" + path;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private record ReadCursor(long offset, int startLine, String sourceVersion, String path) {}

    private static MutationContext context(
            String idempotencyKey, String runRef, String toolCallRef, PrincipalRef actor, String decisionRef) {
        return new MutationContext(idempotencyKey, runRef, toolCallRef, actor, decisionRef);
    }

    private static ToolResult success(String summary, Map<String, Object> data) {
        return new ToolResult(true, summary, data, List.of(), List.of(), false);
    }

    private static ToolResult failure(String summary, Map<String, Object> data) {
        return new ToolResult(false, summary, data, List.of(), List.of(), false);
    }

    private static Map<String, Object> workspaceRootFailure(String toolName, WorkspaceRootException exception) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", exception.code().name());
        if (exception.rootAlias() != null) data.put("rootAlias", exception.rootAlias());
        if (exception.path() != null) data.put("path", exception.path());
        data.put("stableFailureCode", exception.code().name());
        data.put(
                "failureCategory",
                switch (exception.code()) {
                    case ROOT_READ_ONLY -> "POLICY_DENIED";
                    case PATH_ESCAPE_FORBIDDEN -> "POLICY_DENIED";
                    case ABSOLUTE_PATH_FORBIDDEN -> "INVALID_INPUT";
                    case ROOT_ALIAS_NOT_FOUND -> "INVALID_INPUT";
                    default -> "INVALID_ARGUMENT";
                });
        data.put(
                "failureActionCode",
                switch (exception.code()) {
                    case ROOT_READ_ONLY -> "REQUEST_WRITE_PERMISSION";
                    case PATH_ESCAPE_FORBIDDEN -> "USE_BOUNDED_PATH";
                    case ABSOLUTE_PATH_FORBIDDEN -> "USE_ALIAS_RELATIVE_SYNTAX";
                    case ROOT_ALIAS_NOT_FOUND -> "USE_REGISTERED_ROOT_ALIAS";
                    default -> "UNSPECIFIED";
                });
        return data;
    }

    private static Map<String, Object> workspaceFileFailure(String toolName, WorkspaceFileErrorCode code) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", code.name());
        data.put(
                "stableFailureCode",
                switch (code) {
                    case PATH_NOT_FOUND -> "PATH_NOT_FOUND";
                    case SENSITIVE_PATH -> "SENSITIVE_PATH";
                    case FILE_CURSOR_STALE -> "FILE_CURSOR_STALE";
                    default -> code.name();
                });
        data.put(
                "failureActionCode",
                switch (code) {
                    case PATH_NOT_FOUND -> "USE_FILE_CREATE";
                    case SENSITIVE_PATH -> "USER_ACTION_REQUIRED";
                    case FILE_CURSOR_STALE -> "RESTART_READ_FROM_CURRENT_VERSION";
                    default -> "UNSPECIFIED";
                });
        data.put("retryable", code == WorkspaceFileErrorCode.FILE_CURSOR_STALE);
        if (code == WorkspaceFileErrorCode.FILE_CURSOR_STALE) {
            data.put("maximumAutomaticRetries", 1);
        }
        if (code == WorkspaceFileErrorCode.SENSITIVE_PATH) {
            data.put("maximumAutomaticRetries", 0);
            data.put("failureAction", "Path is sensitive; do not rename, relocate, or copy.");
        }
        return data;
    }

    private static Map<String, Object> workspaceMutationFailure(String toolName, MutationErrorCode code) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", publicMutationErrorCode(code));
        data.put(
                "stableFailureCode",
                switch (code) {
                    case TARGET_NOT_FOUND -> "PATH_NOT_FOUND";
                    case TARGET_EXISTS -> "TARGET_EXISTS";
                    default -> code.name();
                });
        data.put(
                "failureActionCode",
                switch (code) {
                    case TARGET_EXISTS -> "USE_FILE_WRITE_OR_PATCH";
                    case TARGET_NOT_FOUND -> "USE_FILE_CREATE";
                    default -> "UNSPECIFIED";
                });
        data.put("retryable", false);
        return data;
    }

    private static String publicErrorCode(RuntimeException exception) {
        if (exception instanceof WorkspaceFileException workspaceFileException) {
            return workspaceFileException.code().name();
        }
        if (exception instanceof WorkspaceMutationException workspaceMutationException) {
            return publicMutationErrorCode(workspaceMutationException.code());
        }
        return "PATCH_CONFLICT";
    }

    private static String publicMutationErrorCode(MutationErrorCode code) {
        return code == MutationErrorCode.TARGET_NOT_FOUND ? "PATH_NOT_FOUND" : code.name();
    }
}
