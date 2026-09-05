package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.delivery.RepositoryBaselineUnavailableException;
import io.haifa.agent.application.project.product.coding.delivery.RepositoryRunContext;
import io.haifa.agent.application.project.product.coding.delivery.RunRepositoryBaselineRegistry;
import io.haifa.agent.application.project.tool.ProjectToolCallContext;
import io.haifa.agent.application.project.tool.ProjectToolOperations;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
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
import io.haifa.agent.project.hostworkspace.HostWorkspaceFileService;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedWorkspaceProvisioning;
import io.haifa.agent.project.hostworkspace.scope.HostDirectoryPermission;
import io.haifa.agent.project.hostworkspace.scope.HostWorkspaceScope;
import io.haifa.agent.project.hostworkspace.scope.HostWorkspaceScopeException;
import io.haifa.agent.project.hostworkspace.scope.ResolvedAuthorizedPath;
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
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.tool.api.ToolReconciliation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Local, capability-scoped file operations over one authorized workspace service path.
 */
final class LocalFileToolOperations implements ProjectToolOperations {
    private static final int DEFAULT_READ_BYTES = 64 * 1024;
    private static final int MAX_READ_BYTES = 256 * 1024;
    private static final int DEFAULT_READ_LINES = 400;
    private static final int MAX_READ_LINES = 2_000;

    private final WorkspaceStore workspaces;
    private final HostWorkspaceFileService files;
    private final WorkspaceMutationProvider mutations;
    private final IdentifierGenerator identifiers;
    private final TimeProvider time;
    private final ApplyPatchParser patchParser;
    private final SessionChangeLedger ledger;
    private final AuthorizedWorkspaceProvisioning provisioning;
    private final RunRepositoryBaselineRegistry repositoryBaselines;
    private final boolean workspaceAttachmentDisclosed;

    LocalFileToolOperations(
            WorkspaceStore workspaces,
            HostWorkspaceFileService files,
            WorkspaceMutationProvider mutations,
            IdentifierGenerator identifiers,
            TimeProvider time,
            AuthorizedWorkspaceProvisioning provisioning,
            SessionChangeLedger ledger) {
        this(workspaces, files, mutations, identifiers, time, provisioning, ledger, null, false);
    }

    LocalFileToolOperations(
            WorkspaceStore workspaces,
            HostWorkspaceFileService files,
            WorkspaceMutationProvider mutations,
            IdentifierGenerator identifiers,
            TimeProvider time,
            AuthorizedWorkspaceProvisioning provisioning,
            SessionChangeLedger ledger,
            RunRepositoryBaselineRegistry repositoryBaselines) {
        this(workspaces, files, mutations, identifiers, time, provisioning, ledger, repositoryBaselines, false);
    }

    LocalFileToolOperations(
            WorkspaceStore workspaces,
            HostWorkspaceFileService files,
            WorkspaceMutationProvider mutations,
            IdentifierGenerator identifiers,
            TimeProvider time,
            AuthorizedWorkspaceProvisioning provisioning,
            SessionChangeLedger ledger,
            RunRepositoryBaselineRegistry repositoryBaselines,
            boolean workspaceAttachmentDisclosed) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.files = Objects.requireNonNull(files, "files must not be null");
        this.mutations = Objects.requireNonNull(mutations, "mutations must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
        this.patchParser = new ApplyPatchParser(100, 1_000, 20_000, 4 * 1024 * 1024);
        this.provisioning = Objects.requireNonNull(provisioning, "provisioning must not be null");
        this.ledger = ledger;
        this.repositoryBaselines = repositoryBaselines;
        this.workspaceAttachmentDisclosed = workspaceAttachmentDisclosed;
    }

    HostWorkspaceScope currentScope() {
        return provisioning.scope();
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
        return execute(
                toolName, workspaceId, actor, runRef, toolCallRef, idempotencyKey, policyDecisionRef, arguments, null);
    }

    @Override
    public ToolResult execute(ProjectToolCallContext call, String toolName, ToolArguments arguments) {
        return execute(
                toolName,
                call.workspaceId(),
                call.actor(),
                call.runRef(),
                call.toolCallRef(),
                call.idempotencyKey(),
                call.policyDecisionRef(),
                arguments,
                new RepositoryRunContext(call.tenant(), call.runRef(), call.actor()));
    }

    private ToolResult execute(
            String toolName,
            WorkspaceId workspaceId,
            PrincipalRef actor,
            String runRef,
            String toolCallRef,
            String idempotencyKey,
            String policyDecisionRef,
            ToolArguments arguments,
            RepositoryRunContext reviewContext) {
        MutationContext mutationContext = context(idempotencyKey, runRef, toolCallRef, actor, policyDecisionRef);
        try {
            return switch (toolName) {
                case "file.list" -> list(arguments.values());
                case "file.stat" -> stat(arguments.values());
                case "file.read" -> read(arguments.values());
                case "file.search" -> search(arguments.values());
                case "file.create" -> create(reviewContext, mutationContext, arguments.values());
                case "file.write" -> write(reviewContext, mutationContext, arguments.values());
                case "file.patch" -> patch(workspaceId, reviewContext, mutationContext, arguments.values());
                case "file.delete" -> delete(reviewContext, mutationContext, arguments.values());
                case "file.move" -> move(reviewContext, mutationContext, arguments.values());
                case "workspace.attach" -> attach(arguments.values());
                default -> throw new IllegalStateException("CLI does not support tool: " + toolName);
            };
        } catch (HostWorkspaceScopeException exception) {
            Map<String, Object> data = workspaceScopeFailure(toolName, exception);
            String summary = "Workspace scope error: " + exception.code().name()
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
        } catch (RepositoryBaselineUnavailableException exception) {
            return failure(
                    "Repository baseline could not be established before the managed write",
                    Map.of(
                            "errorCode", "REPOSITORY_BASELINE_UNAVAILABLE",
                            "stableFailureCode", "REPOSITORY_BASELINE_UNAVAILABLE",
                            "failureCategory", "LOCAL_ENVIRONMENT_UNAVAILABLE",
                            "failureActionCode", "CHECK_GIT_AVAILABILITY",
                            "retryable", false));
        } catch (IllegalArgumentException exception) {
            return failure(
                    "Workspace file arguments are invalid",
                    Map.of(
                            "errorCode", "INVALID_ARGUMENT",
                            "stableFailureCode", "INVALID_ARGUMENT",
                            "failureCategory", "INVALID_INPUT",
                            "failureActionCode", "READ_CURRENT_STATE",
                            "retryable", false));
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

    private ToolResult list(Map<String, Object> values) {
        String pathStr = string(values, "path");
        ResolvedTarget target = resolveTarget(pathStr, HostDirectoryPermission.READ_ONLY);
        TargetListing listing = listEntries(target);
        return success(
                "Listed " + listing.entries().size() + " workspace entries",
                Map.of("entries", listing.entries(), "truncated", listing.truncated()));
    }

    private ToolResult stat(Map<String, Object> values) {
        String pathStr = string(values, "path");
        ResolvedTarget target = resolveTarget(pathStr, HostDirectoryPermission.READ_ONLY);
        TargetMetadata metadata = inspectExisting(target);
        return success(
                "Inspected " + target.displayPath(),
                Map.of(
                        "path", target.displayPath(),
                        "type", metadata.type().name(),
                        "size", metadata.size(),
                        "contentHash", metadata.type() == FileType.DIRECTORY ? "" : metadata.contentHash()));
    }

    private ToolResult read(Map<String, Object> values) {
        String pathStr = string(values, "path");
        ResolvedTarget target = resolveTarget(pathStr, HostDirectoryPermission.READ_ONLY);
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

    private ToolResult search(Map<String, Object> values) {
        String query = string(values, "query");
        String pathStr = string(values, "path");
        ResolvedTarget target = resolveTarget(pathStr, HostDirectoryPermission.READ_ONLY);
        List<Map<String, Object>> results = searchEntries(target, query, integer(values, "maxResults", 100));
        return success("Found " + results.size() + " matches", Map.of("results", results));
    }

    private ToolResult create(
            RepositoryRunContext reviewContext, MutationContext mutationContext, Map<String, Object> values) {
        String pathStr = string(values, "path");
        ResolvedTarget target = resolveTarget(pathStr, HostDirectoryPermission.READ_WRITE);
        prepareBaseline(reviewContext, target);
        byte[] bytes = string(values, "content").getBytes(StandardCharsets.UTF_8);
        ensureAbsent(target);
        createTarget(mutationContext, target, bytes);
        recordCreate(target, bytes, mutationContext);
        return success("Created " + target.displayPath(), Map.of("path", target.displayPath()));
    }

    private ToolResult write(
            RepositoryRunContext reviewContext, MutationContext mutationContext, Map<String, Object> values) {
        String pathStr = string(values, "path");
        ResolvedTarget target = resolveTarget(pathStr, HostDirectoryPermission.READ_WRITE);
        prepareBaseline(reviewContext, target);
        byte[] bytes = string(values, "content").getBytes(StandardCharsets.UTF_8);

        TargetMetadata before;
        try {
            before = requireRegularFile(target);
        } catch (WorkspaceFileException exception) {
            if (exception.code() != WorkspaceFileErrorCode.PATH_NOT_FOUND) throw exception;
            createTarget(mutationContext, target, bytes);
            recordCreate(target, bytes, mutationContext);
            return success("Created " + target.displayPath(), Map.of("path", target.displayPath()));
        }
        writeTarget(mutationContext, target, bytes, before.contentHash());
        recordReplace(target, before, bytes, mutationContext);
        return success("Wrote " + target.displayPath(), Map.of("path", target.displayPath()));
    }

    private ToolResult delete(
            RepositoryRunContext reviewContext, MutationContext mutationContext, Map<String, Object> values) {
        String pathStr = string(values, "path");
        ResolvedTarget target = resolveTarget(pathStr, HostDirectoryPermission.READ_WRITE);
        prepareBaseline(reviewContext, target);
        if (target.workspacePath().projectPath().isRoot()) {
            throw new WorkspaceMutationException(
                    MutationErrorCode.PATH_DENIED,
                    target.workspacePath(),
                    "cannot delete workspace root: " + target.displayPath());
        }

        TargetMetadata before = inspectExisting(target);
        deleteTarget(mutationContext, target, before);
        recordDelete(target, before, mutationContext);
        if (before.type() == FileType.DIRECTORY) {
            return success("Deleted directory " + target.displayPath(), Map.of("path", target.displayPath()));
        }
        return success("Deleted " + target.displayPath(), Map.of("path", target.displayPath()));
    }

    private ToolResult move(
            RepositoryRunContext reviewContext, MutationContext mutationContext, Map<String, Object> values) {
        String srcStr = string(values, "source");
        String dstStr = string(values, "destination");
        ResolvedTarget srcTarget = resolveTarget(srcStr, HostDirectoryPermission.READ_WRITE);
        ResolvedTarget dstTarget = resolveTarget(dstStr, HostDirectoryPermission.READ_WRITE);
        prepareBaseline(reviewContext, srcTarget);
        prepareBaseline(reviewContext, dstTarget);
        if (!srcTarget
                .workspacePath()
                .workspaceId()
                .equals(dstTarget.workspacePath().workspaceId())) {
            throw HostWorkspaceScopeException.crossDirectoryMove(
                    srcStr, "cross-directory file.move is not supported; use file.create and file.delete explicitly");
        }

        TargetMetadata before = requireRegularFile(srcTarget);
        ensureAbsent(dstTarget);
        moveTarget(mutationContext, srcTarget, dstTarget, before.contentHash());
        recordMove(srcTarget, dstTarget, before, mutationContext);
        return success(
                "Moved " + srcTarget.displayPath() + " to " + dstTarget.displayPath(),
                Map.of("source", srcTarget.displayPath(), "destination", dstTarget.displayPath()));
    }

    private TargetMetadata inspectExisting(ResolvedTarget target) {
        validateScopeUnchanged(target);
        var metadata = files.stat(target.workspacePath(), true);
        return new TargetMetadata(
                metadata.type(), metadata.size(), metadata.contentHash().orElse("directory:empty"));
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
        validateScopeUnchanged(target);
        FileContent content = files.read(
                target.workspacePath(),
                new ReadOptions(0, 16 * 1024 * 1024, 16 * 1024 * 1024, StandardCharsets.UTF_8, false));
        return new TargetContent(content.text(), content.totalByteCount(), content.contentHash());
    }

    private TargetRead readContent(ResolvedTarget target, long requestedOffset, int maxBytes) {
        validateScopeUnchanged(target);
        FileContent content = files.read(
                target.workspacePath(),
                new ReadOptions(requestedOffset, maxBytes, maxBytes, StandardCharsets.UTF_8, true));
        return new TargetRead(content.text(), content.offset(), content.totalByteCount(), content.sourceVersion());
    }

    private TargetListing listEntries(ResolvedTarget target) {
        validateScopeUnchanged(target);
        var page = files.list(new FileListRequest(target.workspacePath(), 0, 500));
        List<Map<String, Object>> entries = page.entries().stream()
                .map(entry -> Map.<String, Object>of(
                        "path",
                        target.workspaceRoot()
                                .resolve(entry.metadata().path().projectPath().value())
                                .normalize()
                                .toString(),
                        "type",
                        entry.metadata().type().name(),
                        "size",
                        entry.metadata().size()))
                .toList();
        return new TargetListing(entries, page.truncated());
    }

    private List<Map<String, Object>> searchEntries(ResolvedTarget target, String query, int maxResults) {
        validateScopeUnchanged(target);
        return files
                .search(new SearchRequest(target.workspacePath(), query, 2_000, maxResults, 1_048_576, false))
                .stream()
                .map(match -> Map.<String, Object>of(
                        "path",
                        target.workspaceRoot()
                                .resolve(match.path().projectPath().value())
                                .normalize()
                                .toString(),
                        "line",
                        match.line(),
                        "column",
                        match.column(),
                        "excerpt",
                        match.excerpt()))
                .toList();
    }

    private void ensureAbsent(ResolvedTarget target) {
        validateScopeUnchanged(target);
        try {
            files.stat(target.workspacePath(), true);
        } catch (WorkspaceFileException exception) {
            if (exception.code() == WorkspaceFileErrorCode.PATH_NOT_FOUND) return;
            throw exception;
        }
        throw new WorkspaceMutationException(
                MutationErrorCode.TARGET_EXISTS,
                target.workspacePath(),
                "file already exists: " + target.displayPath());
    }

    private void validateScopeUnchanged(ResolvedTarget target) {
        if (target == null || target.scope() == null) return;
        provisioning.requireUnchanged(target.scope());
    }

    private void createTarget(MutationContext mutationContext, ResolvedTarget target, byte[] content) {
        validateScopeUnchanged(target);
        Workspace workspace = workspace(target.workspacePath().workspaceId());
        mutations.create(new CreateFileRequest(
                target.workspacePath(), content, MutationPrecondition.absent(workspace.revision()), mutationContext));
    }

    private void writeTarget(
            MutationContext mutationContext, ResolvedTarget target, byte[] content, String expectedHash) {
        validateScopeUnchanged(target);
        Workspace workspace = workspace(target.workspacePath().workspaceId());
        mutations.write(new WriteFileRequest(
                target.workspacePath(),
                content,
                MutationPrecondition.existing(workspace.revision(), expectedHash),
                mutationContext));
    }

    private void deleteTarget(MutationContext mutationContext, ResolvedTarget target, TargetMetadata before) {
        validateScopeUnchanged(target);
        Workspace workspace = workspace(target.workspacePath().workspaceId());
        mutations.delete(new DeleteFileRequest(
                target.workspacePath(),
                MutationPrecondition.existing(workspace.revision(), before.contentHash()),
                mutationContext));
    }

    private void moveTarget(
            MutationContext mutationContext, ResolvedTarget source, ResolvedTarget destination, String expectedHash) {
        validateScopeUnchanged(source);
        validateScopeUnchanged(destination);
        Workspace workspace = workspace(source.workspacePath().workspaceId());
        mutations.move(new MoveFileRequest(
                source.workspacePath(),
                destination.workspacePath(),
                MutationPrecondition.existing(workspace.revision(), expectedHash),
                mutationContext));
    }

    private void recordCreate(ResolvedTarget target, byte[] content, MutationContext mutationContext) {
        if (ledger == null) return;
        ledger.record(SessionFileChangeRecord.create(
                target.workspacePath(),
                "sha256:" + digest(content),
                content.length,
                mutationContext.toolCallRef(),
                time.now()));
    }

    private void recordReplace(
            ResolvedTarget target, TargetMetadata before, byte[] content, MutationContext mutationContext) {
        if (ledger == null) return;
        ledger.record(SessionFileChangeRecord.replace(
                target.workspacePath(),
                before.contentHash(),
                before.size(),
                "sha256:" + digest(content),
                content.length,
                mutationContext.toolCallRef(),
                time.now()));
    }

    private void recordDelete(ResolvedTarget target, TargetMetadata before, MutationContext mutationContext) {
        if (ledger == null) return;
        ledger.record(SessionFileChangeRecord.delete(
                target.workspacePath(),
                before.contentHash(),
                before.size(),
                mutationContext.toolCallRef(),
                time.now()));
    }

    private void recordMove(
            ResolvedTarget source, ResolvedTarget destination, TargetMetadata before, MutationContext mutationContext) {
        if (ledger == null) return;
        ledger.record(SessionFileChangeRecord.move(
                source.workspacePath(),
                destination.workspacePath(),
                before.contentHash(),
                before.size(),
                before.contentHash(),
                before.size(),
                mutationContext.toolCallRef(),
                time.now()));
    }

    private ToolResult patch(
            WorkspaceId workspaceId,
            RepositoryRunContext reviewContext,
            MutationContext mutationContext,
            Map<String, Object> values) {
        String patchText = string(values, "patch");
        List<String> lines = patchText.strip().lines().toList();
        List<String> rewrittenLines = new ArrayList<>(lines.size());
        WorkspaceId patchWorkspace = null;
        Map<ProjectPath, ResolvedTarget> targetByProjectPath = new LinkedHashMap<>();

        for (String line : lines) {
            String prefix = null;
            if (line.startsWith("*** Add File: ")) prefix = "*** Add File: ";
            else if (line.startsWith("*** Update File: ")) prefix = "*** Update File: ";
            else if (line.startsWith("*** Delete File: ")) prefix = "*** Delete File: ";
            else if (line.startsWith("*** Move to: ")) prefix = "*** Move to: ";

            if (prefix != null) {
                String rawPath = line.substring(prefix.length()).trim();
                ResolvedTarget target = resolveTarget(rawPath, HostDirectoryPermission.READ_WRITE);
                prepareBaseline(reviewContext, target);
                WorkspaceId targetWorkspace = target.workspacePath().workspaceId();
                if (patchWorkspace != null && !patchWorkspace.equals(targetWorkspace)) {
                    return patchFailure(
                            patchText,
                            List.of(),
                            target.displayPath(),
                            "CROSS_ROOT_PATCH_FORBIDDEN",
                            "USE_SEPARATE_PATCH_PER_ROOT",
                            false);
                }
                patchWorkspace = targetWorkspace;
                targetByProjectPath.put(target.workspacePath().projectPath(), target);
                rewrittenLines.add(prefix + target.workspacePath().projectPath().value());
            } else {
                rewrittenLines.add(line);
            }
        }

        if (patchWorkspace == null) {
            patchWorkspace = workspaceId;
        }

        String logicalPatch = String.join("\n", rewrittenLines);
        var document = patchParser.parse(patchWorkspace, logicalPatch);
        if (document.files().stream().anyMatch(file -> file.deletion() || file.move())) {
            throw new IllegalArgumentException(
                    "file.patch supports Add and Update only; use file.delete or file.move for Delete and Move");
        }

        List<PatchPlanItem> plan = new ArrayList<>();
        for (FilePatch file : document.files()) {
            ResolvedTarget target = targetByProjectPath.get(file.targetPath().projectPath());
            if (target == null) {
                throw new IllegalStateException("Resolved target missing for patch file: " + file.targetPath());
            }
            try {
                plan.add(preflightPatchFile(file, target));
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
                commitPatchFile(mutationContext, item);
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

    private PatchPlanItem preflightPatchFile(FilePatch file, ResolvedTarget target) {
        if (file.creation()) {
            ensureAbsent(target);
            return new PatchPlanItem(file, target, applyHunksToContent(file, ""), null, 0);
        }
        TargetContent current = readAll(target);
        return new PatchPlanItem(
                file, target, applyHunksToContent(file, current.text()), current.contentHash(), current.size());
    }

    private void commitPatchFile(MutationContext mutationContext, PatchPlanItem item) {
        validateScopeUnchanged(item.target());
        ResolvedTarget target = item.target();
        if (item.file().creation()) {
            createTarget(mutationContext, target, item.content());
            recordCreate(target, item.content(), mutationContext);
            return;
        }
        TargetMetadata before = new TargetMetadata(FileType.FILE, item.beforeSize(), item.beforeHash());
        writeTarget(mutationContext, target, item.content(), before.contentHash());
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
        String requestedPath = string(values, "path");
        Path requested = Path.of(requestedPath);
        if (!requested.isAbsolute()) {
            throw new IllegalArgumentException("workspace.attach path must be an absolute host directory");
        }
        HostDirectoryPermission permission =
                switch (string(values, "permission")) {
                    case "read-only" -> HostDirectoryPermission.READ_ONLY;
                    case "read-write" -> HostDirectoryPermission.READ_WRITE;
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
            var result = provisioning.authorize(realPath, permission);
            return success(
                    "Authorized " + realPath + " as " + permission.name(),
                    Map.of(
                            "workspaceId", result.directory().workspaceId().value(),
                            "path", realPath.toString(),
                            "permission", permission.name()));
        } catch (IOException e) {
            throw new IllegalArgumentException("workspace.attach path cannot be accessed");
        }
    }

    private record ResolvedTarget(
            Path workspaceRoot,
            WorkspacePath workspacePath,
            String displayPath,
            HostWorkspaceScope scope,
            ResolvedAuthorizedPath resolved) {}

    private record PatchPlanItem(
            FilePatch file, ResolvedTarget target, byte[] content, String beforeHash, long beforeSize) {}

    private record TargetMetadata(FileType type, long size, String contentHash) {}

    private record TargetContent(String text, long size, String contentHash) {}

    private record TargetRead(String text, long offset, long totalByteCount, String sourceVersion) {}

    private record TargetListing(List<Map<String, Object>> entries, boolean truncated) {}

    private ResolvedTarget resolveTarget(String pathInput, HostDirectoryPermission requiredPermission) {
        String safeInput = (pathInput == null || pathInput.isBlank()) ? "" : pathInput.trim();
        HostWorkspaceScope scope = currentScope();
        ResolvedAuthorizedPath resolved = scope.resolve(safeInput);
        if (requiredPermission == HostDirectoryPermission.READ_WRITE) {
            scope.requireWritable(resolved.directory());
        }
        return new ResolvedTarget(
                resolved.directory().realPath(),
                resolved.workspacePath(),
                resolved.hostPath().toString(),
                scope,
                resolved);
    }

    private void prepareBaseline(RepositoryRunContext context, ResolvedTarget target) {
        if (repositoryBaselines != null && context != null) {
            repositoryBaselines.beforeManagedWrite(context, target.resolved());
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

    private Map<String, Object> workspaceScopeFailure(String toolName, HostWorkspaceScopeException exception) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", exception.code().name());
        if (exception.path() != null) data.put("path", exception.path());
        data.put("stableFailureCode", exception.code().name());
        data.put(
                "failureCategory",
                switch (exception.code()) {
                    case PERMISSION_DENIED -> "POLICY_DENIED";
                    case PATH_ESCAPE_DENIED -> "POLICY_DENIED";
                    case ACCESS_DENIED -> "WORKSPACE_SCOPE_DENIED";
                    case INVALID_ARGUMENT -> "INVALID_INPUT";
                    case CROSS_DIRECTORY_MOVE -> "INVALID_INPUT";
                });
        data.put(
                "failureActionCode",
                switch (exception.code()) {
                    case PERMISSION_DENIED -> "REQUEST_WRITE_PERMISSION";
                    case PATH_ESCAPE_DENIED -> "USE_BOUNDED_PATH";
                    case ACCESS_DENIED ->
                        workspaceAttachmentDisclosed
                                ? "REQUEST_DIRECTORY_AUTHORIZATION"
                                : "USE_AUTHORIZED_WORKSPACE_PATH";
                    case INVALID_ARGUMENT -> "USE_ABSOLUTE_HOST_PATH";
                    case CROSS_DIRECTORY_MOVE -> "USE_CREATE_AND_DELETE";
                });
        data.put("retryable", false);
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
