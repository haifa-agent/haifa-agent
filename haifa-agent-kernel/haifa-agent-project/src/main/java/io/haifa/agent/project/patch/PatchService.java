package io.haifa.agent.project.patch;

import io.haifa.agent.project.filesystem.FileContent;
import io.haifa.agent.project.filesystem.FileMetadata;
import io.haifa.agent.project.filesystem.ReadOptions;
import io.haifa.agent.project.filesystem.WorkspaceFileErrorCode;
import io.haifa.agent.project.filesystem.WorkspaceFileException;
import io.haifa.agent.project.filesystem.WorkspaceFileService;
import io.haifa.agent.project.mutation.CreateFileRequest;
import io.haifa.agent.project.mutation.DeleteFileRequest;
import io.haifa.agent.project.mutation.MutationContext;
import io.haifa.agent.project.mutation.MutationPrecondition;
import io.haifa.agent.project.mutation.MutationResult;
import io.haifa.agent.project.mutation.WorkspaceMutationException;
import io.haifa.agent.project.mutation.WorkspaceMutationService;
import io.haifa.agent.project.mutation.WriteFileRequest;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PatchService {
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;

    private final WorkspaceStore workspaces;
    private final WorkspaceFileService files;
    private final WorkspaceMutationService mutations;
    private final PatchValidationService validation;

    public PatchService(
            WorkspaceStore workspaces,
            WorkspaceFileService files,
            WorkspaceMutationService mutations,
            PatchValidationService validation) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.files = Objects.requireNonNull(files, "files must not be null");
        this.mutations = Objects.requireNonNull(mutations, "mutations must not be null");
        this.validation = Objects.requireNonNull(validation, "validation must not be null");
    }

    public PatchApplyResult apply(PatchApplyRequest request) {
        validation.validate(request.document());
        Workspace workspace = workspaces
                .find(request.workspaceId())
                .orElseThrow(() -> new IllegalArgumentException("workspace not found"));
        if (!workspace.revision().equals(request.expectedRevision())) {
            ProjectPath first = request.document().files().get(0).targetPath();
            return conflict(
                    request, first, PatchConflictCode.REVISION_CONFLICT, -1, "workspace revision precondition failed");
        }

        List<PreparedPatch> prepared = new ArrayList<>();
        List<PatchConflict> conflicts = new ArrayList<>();
        for (FilePatch patch : request.document().files()) {
            prepare(request, patch, conflicts).ifPresent(prepared::add);
        }
        if (!conflicts.isEmpty()) {
            return new PatchApplyResult(request.document().sha256(), List.of(), conflicts, false);
        }

        List<MutationResult> results = new ArrayList<>();
        WorkspaceRevision revision = request.expectedRevision();
        for (int index = 0; index < prepared.size(); index++) {
            PreparedPatch item = prepared.get(index);
            MutationContext context = childContext(request.context(), index);
            WorkspacePath path =
                    new WorkspacePath(request.workspaceId(), item.patch().targetPath());
            try {
                MutationResult result;
                if (item.patch().creation()) {
                    result = mutations.create(
                            new CreateFileRequest(path, item.after(), MutationPrecondition.absent(revision), context));
                } else if (item.patch().deletion()) {
                    result = mutations.delete(new DeleteFileRequest(
                            path, MutationPrecondition.existing(revision, item.expectedHash()), context));
                } else {
                    result = mutations.write(new WriteFileRequest(
                            path, item.after(), MutationPrecondition.existing(revision, item.expectedHash()), context));
                }
                results.add(result);
                if (result.optionalResultRevision().isEmpty()) {
                    conflicts.add(new PatchConflict(
                            item.patch().targetPath(),
                            PatchConflictCode.MUTATION_REJECTED,
                            -1,
                            "mutation outcome requires reconciliation"));
                    break;
                }
                revision = result.optionalResultRevision().orElse(revision);
            } catch (WorkspaceMutationException exception) {
                conflicts.add(new PatchConflict(
                        item.patch().targetPath(),
                        PatchConflictCode.MUTATION_REJECTED,
                        -1,
                        exception.code().name()));
                break;
            }
        }
        return new PatchApplyResult(
                request.document().sha256(),
                results,
                conflicts,
                conflicts.isEmpty() && results.size() == prepared.size());
    }

    private java.util.Optional<PreparedPatch> prepare(
            PatchApplyRequest request, FilePatch patch, List<PatchConflict> conflicts) {
        WorkspacePath path = new WorkspacePath(request.workspaceId(), patch.targetPath());
        if (patch.creation()) {
            try {
                files.stat(path, false);
                conflicts.add(new PatchConflict(
                        patch.targetPath(), PatchConflictCode.TARGET_EXISTS, -1, "create target already exists"));
                return java.util.Optional.empty();
            } catch (WorkspaceFileException exception) {
                if (exception.code() != WorkspaceFileErrorCode.PATH_NOT_FOUND) {
                    conflicts.add(new PatchConflict(
                            patch.targetPath(),
                            PatchConflictCode.MUTATION_REJECTED,
                            -1,
                            exception.code().name()));
                    return java.util.Optional.empty();
                }
            }
            try {
                return java.util.Optional.of(new PreparedPatch(patch, null, applyHunks(patch, "")));
            } catch (HunkConflict exception) {
                conflicts.add(exception.toConflict(patch.targetPath()));
                return java.util.Optional.empty();
            }
        }

        String expected = request.expectedHashes().get(patch.targetPath());
        if (expected == null || expected.isBlank()) {
            conflicts.add(new PatchConflict(
                    patch.targetPath(),
                    PatchConflictCode.EXPECTED_HASH_REQUIRED,
                    -1,
                    "expected content hash is required"));
            return java.util.Optional.empty();
        }
        try {
            FileMetadata metadata = files.stat(path, true);
            String actual = metadata.contentHash().orElse("");
            if (!expected.equals(actual)) {
                conflicts.add(new PatchConflict(
                        patch.targetPath(),
                        PatchConflictCode.CONTENT_HASH_CONFLICT,
                        -1,
                        "content hash precondition failed"));
                return java.util.Optional.empty();
            }
            FileContent content =
                    files.read(path, new ReadOptions(MAX_FILE_BYTES, MAX_FILE_BYTES, StandardCharsets.UTF_8, false));
            byte[] after = applyHunks(patch, content.text());
            if (patch.deletion() && after.length != 0) {
                conflicts.add(new PatchConflict(
                        patch.targetPath(),
                        PatchConflictCode.HUNK_MISMATCH,
                        -1,
                        "delete patch did not remove all content"));
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new PreparedPatch(patch, expected, after));
        } catch (HunkConflict exception) {
            conflicts.add(exception.toConflict(patch.targetPath()));
            return java.util.Optional.empty();
        } catch (WorkspaceFileException exception) {
            PatchConflictCode code = exception.code() == WorkspaceFileErrorCode.PATH_NOT_FOUND
                    ? PatchConflictCode.TARGET_NOT_FOUND
                    : PatchConflictCode.MUTATION_REJECTED;
            conflicts.add(new PatchConflict(
                    patch.targetPath(), code, -1, exception.code().name()));
            return java.util.Optional.empty();
        }
    }

    private static byte[] applyHunks(FilePatch patch, String source) {
        String newline = source.contains("\r\n") ? "\r\n" : source.contains("\r") ? "\r" : "\n";
        List<String> original = splitLines(source);
        List<String> output = new ArrayList<>();
        int cursor = 0;
        for (int hunkIndex = 0; hunkIndex < patch.hunks().size(); hunkIndex++) {
            PatchHunk hunk = patch.hunks().get(hunkIndex);
            int target = hunk.oldStart() == 0 ? 0 : hunk.oldStart() - 1;
            if (target < cursor || target > original.size()) {
                throw new HunkConflict(hunkIndex, "hunk location is outside the source");
            }
            output.addAll(original.subList(cursor, target));
            cursor = target;
            for (PatchLine line : hunk.lines()) {
                if (line.type() == PatchLineType.ADD) {
                    output.add(line.text());
                    continue;
                }
                if (cursor >= original.size() || !original.get(cursor).equals(line.text())) {
                    throw new HunkConflict(hunkIndex, "hunk context does not match exactly");
                }
                if (line.type() == PatchLineType.CONTEXT) output.add(line.text());
                cursor++;
            }
        }
        output.addAll(original.subList(cursor, original.size()));
        String joined = String.join(newline, output);
        if (patch.newEndsWithNewline() && !output.isEmpty()) joined += newline;
        return joined.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> splitLines(String source) {
        if (source.isEmpty()) return List.of();
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        String[] values = normalized.split("\n", -1);
        int length = source.endsWith("\n") || source.endsWith("\r") ? values.length - 1 : values.length;
        return java.util.Arrays.asList(values).subList(0, length);
    }

    private static MutationContext childContext(MutationContext parent, int index) {
        return new MutationContext(
                parent.operationId() + ":file:" + index,
                parent.runRef(),
                parent.toolCallRef(),
                parent.actor(),
                parent.securityDecisionRef());
    }

    private static PatchApplyResult conflict(
            PatchApplyRequest request, ProjectPath path, PatchConflictCode code, int hunk, String detail) {
        return new PatchApplyResult(
                request.document().sha256(), List.of(), List.of(new PatchConflict(path, code, hunk, detail)), false);
    }

    private record PreparedPatch(FilePatch patch, String expectedHash, byte[] after) {
        private PreparedPatch {
            after = java.util.Arrays.copyOf(after, after.length);
        }

        @Override
        public byte[] after() {
            return java.util.Arrays.copyOf(after, after.length);
        }
    }

    private static final class HunkConflict extends RuntimeException {
        private final int hunkIndex;

        private HunkConflict(int hunkIndex, String message) {
            super(message);
            this.hunkIndex = hunkIndex;
        }

        private PatchConflict toConflict(ProjectPath path) {
            return new PatchConflict(path, PatchConflictCode.HUNK_MISMATCH, hunkIndex, getMessage());
        }
    }
}
