package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.delivery.CodingChangeReviewArtifactFactory;
import io.haifa.agent.application.project.tool.ProjectToolOperations;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.project.changeset.FileChangeSetStatus;
import io.haifa.agent.project.changeset.FileChangeSetStore;
import io.haifa.agent.project.filesystem.FileListRequest;
import io.haifa.agent.project.filesystem.ReadOptions;
import io.haifa.agent.project.filesystem.SearchRequest;
import io.haifa.agent.project.filesystem.WorkspaceFileErrorCode;
import io.haifa.agent.project.filesystem.WorkspaceFileException;
import io.haifa.agent.project.mutation.CreateFileRequest;
import io.haifa.agent.project.mutation.DeleteFileRequest;
import io.haifa.agent.project.mutation.MoveFileRequest;
import io.haifa.agent.project.mutation.MutationContext;
import io.haifa.agent.project.mutation.MutationPrecondition;
import io.haifa.agent.project.mutation.WorkspaceMutationException;
import io.haifa.agent.project.mutation.WorkspaceMutationProvider;
import io.haifa.agent.project.mutation.WriteFileRequest;
import io.haifa.agent.project.patch.ApplyPatchParser;
import io.haifa.agent.project.patch.PatchApplyRequest;
import io.haifa.agent.project.patch.PatchService;
import io.haifa.agent.project.patch.PatchValidationService;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.provider.local.LocalWorkspaceFileService;
import io.haifa.agent.project.provider.local.root.LocalMultiRootPathResolver;
import io.haifa.agent.project.provider.local.root.LocalWorkspaceRootRegistry;
import io.haifa.agent.project.root.MultiRootPath;
import io.haifa.agent.project.root.WorkspaceRootErrorCode;
import io.haifa.agent.project.root.WorkspaceRootException;
import io.haifa.agent.project.root.WorkspaceRootPermission;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.tool.api.ToolReconciliation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Local, capability-scoped file operations used by the CLI's Project Tool provider. */
final class LocalFileToolOperations implements ProjectToolOperations {
    private static final int DEFAULT_READ_BYTES = 64 * 1024;
    private static final int MAX_READ_BYTES = 256 * 1024;
    private static final int DEFAULT_READ_LINES = 400;
    private static final int MAX_READ_LINES = 2_000;
    private static final String POST_MUTATION_REVIEW =
            " Before validation, inspect every changed classification and counter branch against the authoritative "
                    + "contract, then exercise one mixed scenario with exact counts. Differently named outcomes stay "
                    + "disjoint unless the contract defines overlap; an item ignored only as a duplicate is not invalid.";

    private final WorkspaceStore workspaces;
    private final LocalWorkspaceFileService files;
    private final WorkspaceMutationProvider mutations;
    private final IdentifierGenerator identifiers;
    private final ApplyPatchParser patchParser;
    private final PatchService patchService;
    private final FileChangeSetStore changeSets;
    private final CodingChangeReviewArtifactFactory changeReviews;
    private final LocalWorkspaceRootRegistry rootRegistry;

    LocalFileToolOperations(
            WorkspaceStore workspaces,
            LocalWorkspaceFileService files,
            WorkspaceMutationProvider mutations,
            IdentifierGenerator identifiers) {
        this(workspaces, files, mutations, identifiers, null, null, null);
    }

    LocalFileToolOperations(
            WorkspaceStore workspaces,
            LocalWorkspaceFileService files,
            WorkspaceMutationProvider mutations,
            IdentifierGenerator identifiers,
            FileChangeSetStore changeSets) {
        this(workspaces, files, mutations, identifiers, changeSets, (LocalWorkspaceRootRegistry) null);
    }

    LocalFileToolOperations(
            WorkspaceStore workspaces,
            LocalWorkspaceFileService files,
            WorkspaceMutationProvider mutations,
            IdentifierGenerator identifiers,
            FileChangeSetStore changeSets,
            LocalWorkspaceRootRegistry rootRegistry) {
        this(
                workspaces,
                files,
                mutations,
                identifiers,
                changeSets,
                changeSets == null
                        ? null
                        : new io.haifa.agent.application.project.product.coding.delivery.CodingChangeReviewArtifactFactory(
                                changeSets, new LocalCodingChangeContentClassifier(files), 512 * 1024),
                rootRegistry);
    }

    LocalFileToolOperations(
            WorkspaceStore workspaces,
            LocalWorkspaceFileService files,
            WorkspaceMutationProvider mutations,
            IdentifierGenerator identifiers,
            FileChangeSetStore changeSets,
            CodingChangeReviewArtifactFactory changeReviews) {
        this(workspaces, files, mutations, identifiers, changeSets, changeReviews, null);
    }

    LocalFileToolOperations(
            WorkspaceStore workspaces,
            LocalWorkspaceFileService files,
            WorkspaceMutationProvider mutations,
            IdentifierGenerator identifiers,
            FileChangeSetStore changeSets,
            CodingChangeReviewArtifactFactory changeReviews,
            LocalWorkspaceRootRegistry rootRegistry) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.files = Objects.requireNonNull(files, "files must not be null");
        this.mutations = Objects.requireNonNull(mutations, "mutations must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.patchParser = new ApplyPatchParser(100, 1_000, 20_000, 4 * 1024 * 1024);
        this.patchService = new PatchService(
                this.workspaces, this.files, this.mutations, new PatchValidationService(100, 1_000, 20_000));
        this.changeSets = changeSets;
        this.changeReviews = changeReviews;
        this.rootRegistry = rootRegistry;
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
                default -> throw new IllegalStateException("CLI does not support tool: " + toolName);
            };
        } catch (WorkspaceRootException exception) {
            Map<String, Object> data = workspaceRootFailure(toolName, exception);
            String summary = "Multi-root workspace error: "
                    + exception.code().name() + (exception.path() == null ? "" : " (path=" + exception.path() + ")");
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
        if ((!toolName.equals("file.create") && !toolName.equals("file.write")) || changeSets == null) {
            return ToolReconciliation.unsupported();
        }
        var changeSet = changeSets.findByOperation(workspaceId, idempotencyKey).orElse(null);
        if (changeSet == null) return ToolReconciliation.stillUnknown("FILE_CHANGE_SET_MISSING");
        if (!Objects.equals(changeSet.runRef(), runRef) || !changeSet.actor().equals(actor)) {
            return ToolReconciliation.stillUnknown("FILE_CHANGE_SET_CONTEXT_MISMATCH");
        }
        if (!Objects.equals(changeSet.toolCallRef(), toolCallRef)) {
            return ToolReconciliation.stillUnknown("FILE_CHANGE_SET_CALL_MISMATCH");
        }
        if (changeSet.status() != FileChangeSetStatus.APPLIED && changeSet.status() != FileChangeSetStatus.RECONCILED) {
            return ToolReconciliation.stillUnknown("FILE_CHANGE_SET_NOT_TERMINAL");
        }
        try {
            WorkspacePath path = path(workspaceId, arguments.values(), "path", WorkspaceRootPermission.READ_ONLY);
            String expectedHash = "sha256:" + digest(string(arguments.values(), "content"));
            String actualHash = files.stat(path, true).contentHash().orElse("");
            if (!MessageDigest.isEqual(
                    expectedHash.getBytes(StandardCharsets.US_ASCII), actualHash.getBytes(StandardCharsets.US_ASCII))) {
                return ToolReconciliation.stillUnknown("FILE_CONTENT_DIGEST_MISMATCH");
            }
            return ToolReconciliation.resolved(
                    withReview(
                            success(
                                    "Reconciled " + path.projectPath() + " without replay",
                                    Map.of(
                                            "changeSetId",
                                            changeSet.id().value(),
                                            "contentHash",
                                            actualHash,
                                            "reconcileStatus",
                                            "RESOLVED",
                                            "reconcileReason",
                                            "FILE_CONTENT_AND_CHANGE_SET_CONFIRMED",
                                            "replayAllowed",
                                            false)),
                            runRef,
                            List.of(changeSet.id().value())),
                    "FILE_CONTENT_AND_CHANGE_SET_CONFIRMED");
        } catch (WorkspaceFileException | WorkspaceRootException | IllegalArgumentException failure) {
            return ToolReconciliation.stillUnknown("FILE_RECONCILIATION_EVIDENCE_UNAVAILABLE");
        }
    }

    private ToolResult list(WorkspaceId workspaceId, Map<String, Object> values) {
        var page = files.list(new FileListRequest(path(workspaceId, values, "path", WorkspaceRootPermission.READ_ONLY), 0, 500));
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
        var metadata = files.stat(path(workspaceId, values, "path", WorkspaceRootPermission.READ_ONLY), true);
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
        WorkspacePath path = path(workspaceId, values, "path", WorkspaceRootPermission.READ_ONLY);
        String pathText = path.projectPath().toString();
        ReadCursor cursor = decodeCursor(optionalString(values, "cursor"), pathText);
        int maxBytes = boundedInteger(values, "maxBytes", DEFAULT_READ_BYTES, MAX_READ_BYTES);
        int maxLines = boundedInteger(values, "maxLines", DEFAULT_READ_LINES, MAX_READ_LINES);
        var content =
                files.read(path, new ReadOptions(cursor.offset(), maxBytes, maxBytes, StandardCharsets.UTF_8, true));
        if (cursor.sourceVersion() != null && !cursor.sourceVersion().equals(content.sourceVersion())) {
            throw new WorkspaceFileException(
                    WorkspaceFileErrorCode.FILE_CURSOR_STALE, path, "file changed after the read cursor was issued");
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
        var matches = files.search(new SearchRequest(
                path(workspaceId, values, "path", WorkspaceRootPermission.READ_ONLY), query, 2_000, integer(values, "maxResults", 100), 1_048_576, false));
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

    private ToolResult create(WorkspaceId workspaceId, MutationContext mutationContext, Map<String, Object> values) {
        WorkspacePath path = path(workspaceId, values, "path", WorkspaceRootPermission.READ_WRITE);
        Workspace workspace = workspace(workspaceId);
        var result = mutations.create(new CreateFileRequest(
                path,
                string(values, "content").getBytes(StandardCharsets.UTF_8),
                MutationPrecondition.absent(workspace.revision()),
                mutationContext));
        return withReview(
                success(
                        "Created " + path.projectPath() + "." + POST_MUTATION_REVIEW,
                        Map.of("changeSetId", result.changeSetId().value())),
                mutationContext.runRef(),
                List.of(result.changeSetId().value()));
    }

    private ToolResult write(WorkspaceId workspaceId, MutationContext mutationContext, Map<String, Object> values) {
        WorkspacePath path = path(workspaceId, values, "path", WorkspaceRootPermission.READ_WRITE);
        Workspace workspace = workspace(workspaceId);
        String currentHash = files.stat(path, true)
                .contentHash()
                .orElseThrow(() -> new IllegalArgumentException("file hash is unavailable"));
        var result = mutations.write(new WriteFileRequest(
                path,
                string(values, "content").getBytes(StandardCharsets.UTF_8),
                MutationPrecondition.existing(workspace.revision(), currentHash),
                mutationContext));
        return withReview(
                success(
                        "Wrote " + path.projectPath() + "." + POST_MUTATION_REVIEW,
                        Map.of("changeSetId", result.changeSetId().value())),
                mutationContext.runRef(),
                List.of(result.changeSetId().value()));
    }

    private ToolResult delete(WorkspaceId workspaceId, MutationContext mutationContext, Map<String, Object> values) {
        WorkspacePath path = path(workspaceId, values, "path", WorkspaceRootPermission.READ_WRITE);
        Workspace workspace = workspace(workspaceId);
        String currentHash = files.stat(path, true)
                .contentHash()
                .orElseThrow(() -> new IllegalArgumentException("file hash is unavailable"));
        var result = mutations.delete(new DeleteFileRequest(
                path, MutationPrecondition.existing(workspace.revision(), currentHash), mutationContext));
        return withReview(
                success(
                        "Deleted " + path.projectPath(),
                        Map.of("changeSetId", result.changeSetId().value())),
                mutationContext.runRef(),
                List.of(result.changeSetId().value()));
    }

    private ToolResult patch(WorkspaceId workspaceId, MutationContext mutationContext, Map<String, Object> values) {
        var document = patchParser.parse(string(values, "patch"));
        Workspace workspace = workspace(workspaceId);
        Map<ProjectPath, String> expectedHashes = new LinkedHashMap<>();
        document.files().stream()
                .filter(file -> !file.creation())
                .forEach(file -> expectedHashes.put(
                        file.sourcePath(),
                        files.stat(new WorkspacePath(workspaceId, file.sourcePath()), true)
                                .contentHash()
                                .orElseThrow(() -> new IllegalArgumentException("file hash is unavailable"))));
        var result = patchService.apply(
                new PatchApplyRequest(workspaceId, document, workspace.revision(), expectedHashes, mutationContext));
        List<Map<String, Object>> conflicts = result.conflicts().stream()
                .map(conflict -> Map.<String, Object>of(
                        "path", conflict.path().toString(),
                        "code", conflict.code().name(),
                        "hunkIndex", conflict.hunkIndex(),
                        "detail", conflict.safeDetail()))
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("patchSha256", result.patchSha256());
        data.put("complete", result.complete());
        data.put(
                "changeSetIds",
                result.appliedMutations().stream()
                        .map(mutation -> mutation.changeSetId().value())
                        .toList());
        data.put("conflicts", conflicts);
        if (!result.complete()) {
            return withReview(
                    failure("Patch stopped after the exact committed prefix", Map.copyOf(data)),
                    mutationContext.runRef(),
                    result.appliedMutations().stream()
                            .map(mutation -> mutation.changeSetId().value())
                            .toList());
        }
        return withReview(
                success(
                        "Applied patch to " + document.files().size() + " file(s)." + POST_MUTATION_REVIEW,
                        Map.copyOf(data)),
                mutationContext.runRef(),
                result.appliedMutations().stream()
                        .map(mutation -> mutation.changeSetId().value())
                        .toList());
    }

    private ToolResult move(WorkspaceId workspaceId, MutationContext mutationContext, Map<String, Object> values) {
        WorkspacePath source = path(workspaceId, values, "source", WorkspaceRootPermission.READ_WRITE);
        WorkspacePath destination = path(workspaceId, values, "destination", WorkspaceRootPermission.READ_WRITE);
        Workspace workspace = workspace(workspaceId);
        String currentHash = files.stat(source, true)
                .contentHash()
                .orElseThrow(() -> new IllegalArgumentException("file hash is unavailable"));
        var result = mutations.move(new MoveFileRequest(
                source,
                destination,
                MutationPrecondition.existing(workspace.revision(), currentHash),
                mutationContext));
        return withReview(
                success(
                        "Moved " + source.projectPath() + " to " + destination.projectPath(),
                        Map.of("changeSetId", result.changeSetId().value())),
                mutationContext.runRef(),
                List.of(result.changeSetId().value()));
    }

    private static MutationContext context(
            String operationId, String runRef, String toolCallRef, PrincipalRef actor, String policyDecisionRef) {
        return new MutationContext(operationId, runRef, toolCallRef, actor, policyDecisionRef);
    }

    private Workspace workspace(WorkspaceId id) {
        return workspaces.find(id).orElseThrow(() -> new IllegalStateException("workspace is unavailable"));
    }

    private WorkspacePath path(
            WorkspaceId workspaceId,
            Map<String, Object> values,
            String key,
            WorkspaceRootPermission requiredPermission) {
        String value = string(values, key);
        MultiRootPath parsed = LocalMultiRootPathResolver.parse(value);
        if (rootRegistry != null) {
            rootRegistry.checkPermission(parsed.rootAlias(), requiredPermission);
            LocalMultiRootPathResolver.resolve(rootRegistry, parsed);
        } else {
            if (!parsed.rootAlias().isMain()) {
                throw new WorkspaceRootException(
                        WorkspaceRootErrorCode.ROOT_ALIAS_NOT_FOUND,
                        parsed.rootAlias().value(),
                        value,
                        "Unregistered root alias: " + parsed.rootAlias().value());
            }
            if (parsed.relativePath().contains("..")) {
                throw new WorkspaceRootException(
                        WorkspaceRootErrorCode.PATH_ESCAPE_FORBIDDEN,
                        parsed.rootAlias().value(),
                        parsed.relativePath(),
                        "Path contains '..' traversal escaping workspace: " + value);
            }
        }
        return new WorkspacePath(workspaceId, parsed.relativePath().isEmpty() ? ProjectPath.root() : ProjectPath.of(parsed.relativePath()));
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

    private static int boundedInteger(Map<String, Object> values, String key, int fallback, int maximum) {
        int value = integer(values, key, fallback);
        if (value > maximum) throw new IllegalArgumentException(key + " exceeds maximum " + maximum);
        return value;
    }

    private static String optionalString(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return null;
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " must be non-empty text");
        }
        if (text.length() > 2048) throw new IllegalArgumentException(key + " is too long");
        return text;
    }

    private static String firstLines(String value, int maximumLines) {
        int lines = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\n' && current != '\r') continue;
            if (current == '\r' && index + 1 < value.length() && value.charAt(index + 1) == '\n') index++;
            if (++lines == maximumLines && index + 1 < value.length()) return value.substring(0, index + 1);
        }
        return value;
    }

    private static int lineBreaks(String value) {
        int breaks = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\n' && current != '\r') continue;
            if (current == '\r' && index + 1 < value.length() && value.charAt(index + 1) == '\n') index++;
            breaks++;
        }
        return breaks;
    }

    private static String encodeCursor(long offset, int startLine, String sourceVersion, String path) {
        String body = "1\n" + offset + "\n" + startLine + "\n" + sourceVersion + "\n" + digest(path);
        String payload = body + "\n" + digest(body);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static ReadCursor decodeCursor(String encoded, String path) {
        if (encoded == null) return new ReadCursor(0, 1, null);
        try {
            String payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] fields = payload.split("\\n", -1);
            if (fields.length != 6 || !fields[0].equals("1")) throw new IllegalArgumentException();
            String body = String.join("\n", java.util.Arrays.copyOf(fields, 5));
            if (!MessageDigest.isEqual(
                            digest(body).getBytes(StandardCharsets.US_ASCII),
                            fields[5].getBytes(StandardCharsets.US_ASCII))
                    || !MessageDigest.isEqual(
                            digest(path).getBytes(StandardCharsets.US_ASCII),
                            fields[4].getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException();
            }
            long offset = Long.parseLong(fields[1]);
            int startLine = Integer.parseInt(fields[2]);
            if (offset < 0 || startLine < 1 || fields[3].isBlank()) throw new IllegalArgumentException();
            return new ReadCursor(offset, startLine, fields[3]);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("cursor is invalid or belongs to another path");
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private record ReadCursor(long offset, int startLine, String sourceVersion) {}

    private static ToolResult success(String summary, Map<String, Object> data) {
        return new ToolResult(true, summary, data, List.of(), List.of(), false);
    }

    private static ToolResult failure(String summary, Map<String, Object> data) {
        return new ToolResult(false, summary, data, List.of(), List.of(), false);
    }

    private ToolResult withReview(ToolResult result, String runRef, List<String> changeSetIds) {
        if (changeReviews == null || runRef == null || changeSetIds.isEmpty()) return result;
        return changeReviews
                .create(runRef, changeSetIds)
                .map(review -> {
                    Map<String, Object> data = new LinkedHashMap<>(result.structuredData());
                    data.put("changeReviewArtifact", review.toStructuredData());
                    data.put("changeReviewArtifactRef", review.artifactRef());
                    data.put("artifactRef", review.artifactRef());
                    return new ToolResult(
                            result.successful(),
                            result.summary(),
                            Map.copyOf(data),
                            result.assets(),
                            result.artifacts(),
                            result.truncated());
                })
                .orElse(result);
    }

    private static Map<String, Object> workspaceRootFailure(
            String toolName, WorkspaceRootException exception) {
        var data = baseFailure(exception.code().name());
        if (exception.rootAlias() != null) data.put("rootAlias", exception.rootAlias());
        if (exception.path() != null) data.put("path", exception.path());

        switch (exception.code()) {
            case ROOT_READ_ONLY -> recovery(
                    data,
                    "POLICY_DENIED",
                    "WORKSPACE_ROOT",
                    "REQUEST_WRITE_PERMISSION",
                    "The workspace root '" + exception.rootAlias() + "' is READ_ONLY. Ask the user for WRITE permission.",
                    false,
                    0);
            case ROOT_ALIAS_NOT_FOUND -> recovery(
                    data,
                    "INVALID_INPUT",
                    "WORKSPACE_ROOT",
                    "USE_REGISTERED_ROOT_ALIAS",
                    "The root alias '" + exception.rootAlias() + "' is not registered. Request the user to attach this directory.",
                    false,
                    0);
            case ABSOLUTE_PATH_FORBIDDEN -> recovery(
                    data,
                    "INVALID_INPUT",
                    "WORKSPACE_PATH",
                    "USE_ALIAS_RELATIVE_SYNTAX",
                    "Absolute host paths are forbidden. Use 'alias:relative/path' or 'main:relative/path'.",
                    false,
                    0);
            case PATH_ESCAPE_FORBIDDEN -> recovery(
                    data,
                    "POLICY_DENIED",
                    "WORKSPACE_PATH",
                    "USE_BOUNDED_PATH",
                    "Path escapes root directory boundary. Use a normalized relative path within the root.",
                    false,
                    0);
            default -> recovery(
                    data,
                    "INVALID_INPUT",
                    "WORKSPACE_ROOT",
                    "READ_AUTHORITATIVE_WORKSPACE_STATE",
                    "Resolve root boundary before retrying.",
                    false,
                    0);
        }
        return data;
    }

    private static Map<String, Object> workspaceFileFailure(String toolName, WorkspaceFileErrorCode code) {
        var data = baseFailure(code.name());
        switch (code) {
            case FILE_CURSOR_STALE ->
                recovery(
                        data,
                        "INVALID_INPUT",
                        "FILE_CURSOR",
                        "RESTART_READ_FROM_CURRENT_VERSION",
                        "Restart file.read once without the stale cursor and use the returned current contentVersion.",
                        true,
                        1);
            case SENSITIVE_PATH ->
                recovery(
                        data,
                        "POLICY_DENIED",
                        "SENSITIVE_PATH",
                        "USER_ACTION_REQUIRED",
                        "Ask the user to handle the sensitive path; do not rename, relocate, or copy its contents.",
                        false,
                        0);
            case PATH_NOT_FOUND ->
                recovery(
                        data,
                        "INVALID_INPUT",
                        "WORKSPACE_PATH",
                        toolName.equals("file.write") ? "USE_FILE_CREATE" : "READ_AUTHORITATIVE_PATH",
                        toolName.equals("file.write")
                                ? "The target does not exist; use file.create with the same intended content."
                                : "Read the authoritative workspace listing before choosing another path.",
                        false,
                        0);
            default ->
                recovery(
                        data,
                        code == WorkspaceFileErrorCode.PERMISSION_DENIED ? "POLICY_DENIED" : "FILESYSTEM_DENIED",
                        "WORKSPACE_PATH",
                        "USER_ACTION_REQUIRED",
                        "Use the reported logical workspace path and resolve the stated boundary before retrying.",
                        false,
                        0);
        }
        return data;
    }

    private static Map<String, Object> workspaceMutationFailure(
            String toolName, io.haifa.agent.project.mutation.MutationErrorCode code) {
        var data = baseFailure(code.name());
        if (code == io.haifa.agent.project.mutation.MutationErrorCode.TARGET_EXISTS && toolName.equals("file.create")) {
            recovery(
                    data,
                    "INVALID_INPUT",
                    "WORKSPACE_PATH",
                    "USE_FILE_WRITE_OR_PATCH",
                    "The target exists; use file.write for an intentional full replacement or file.patch for a bounded edit.",
                    false,
                    0);
        } else if (code == io.haifa.agent.project.mutation.MutationErrorCode.TARGET_NOT_FOUND
                && toolName.equals("file.write")) {
            recovery(
                    data,
                    "INVALID_INPUT",
                    "WORKSPACE_PATH",
                    "USE_FILE_CREATE",
                    "The target does not exist; use file.create with the same intended content.",
                    false,
                    0);
        } else {
            recovery(
                    data,
                    code == io.haifa.agent.project.mutation.MutationErrorCode.OUTCOME_UNKNOWN
                            ? "OUTCOME_UNKNOWN"
                            : "FILESYSTEM_DENIED",
                    "WORKSPACE_PATH",
                    "READ_AUTHORITATIVE_WORKSPACE_STATE",
                    "Read the authoritative workspace state before choosing one bounded mutation strategy.",
                    false,
                    0);
        }
        return data;
    }

    private static LinkedHashMap<String, Object> baseFailure(String stableCode) {
        var data = new LinkedHashMap<String, Object>();
        data.put("errorCode", stableCode);
        data.put("stableFailureCode", stableCode);
        return data;
    }

    private static void recovery(
            Map<String, Object> data,
            String category,
            String resourceClass,
            String actionCode,
            String action,
            boolean retryable,
            int maximumAutomaticRetries) {
        data.put("failureCategory", category);
        data.put("resourceClass", resourceClass);
        data.put("failureActionCode", actionCode);
        data.put("failureAction", action);
        data.put("retryable", retryable);
        data.put("maximumAutomaticRetries", maximumAutomaticRetries);
    }
}
