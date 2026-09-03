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
    private static final long MAX_PATCHED_FILE_BYTES = 4L * 1024 * 1024 * 1024;

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
            WorkspacePath first = request.document().files().get(0).targetPath();
            return conflict(
                    request, first, PatchConflictCode.REVISION_CONFLICT, -1, "workspace revision precondition failed");
        }

        List<PreparedPatch> prepared = new ArrayList<>();
        List<PatchConflict> conflicts = new ArrayList<>();
        for (FilePatch patch : request.document().files()) {
            if (!patch.sourcePath().workspaceId().equals(request.workspaceId())) {
                throw new IllegalArgumentException("patch file belongs to a different logical workspace");
            }
            prepare(request, patch, conflicts).ifPresent(prepared::add);
        }
        if (!conflicts.isEmpty()) {
            return new PatchApplyResult(request.document().sha256(), List.of(), conflicts, false);
        }

        List<MutationResult> results = new ArrayList<>();
        WorkspaceRevision revision = request.expectedRevision();
        int completedFiles = 0;
        for (int index = 0; index < prepared.size(); index++) {
            PreparedPatch item = prepared.get(index);
            MutationContext context = childContext(request.context(), index);
            WorkspacePath path = item.patch().sourcePath();
            try {
                MutationResult result;
                if (item.patch().creation()) {
                    result = mutations.create(
                            new CreateFileRequest(path, item.after(), MutationPrecondition.absent(revision), context));
                } else if (item.patch().deletion()) {
                    result = mutations.delete(new DeleteFileRequest(
                            path, MutationPrecondition.existing(revision, item.expectedHash()), context));
                } else if (mutations instanceof StreamingPatchMutationService streaming) {
                    result = streaming.patch(new PatchFileMutationRequest(
                            path,
                            item.patch(),
                            MutationPrecondition.existing(revision, item.expectedHash()),
                            context,
                            MAX_PATCHED_FILE_BYTES));
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
                if (item.patch().move()) {
                    String patchedHash = files.stat(path, true)
                            .contentHash()
                            .orElseThrow(() -> new IllegalStateException("patched file hash is unavailable"));
                    MutationResult moved = mutations.move(new io.haifa.agent.project.mutation.MoveFileRequest(
                            path,
                            item.patch().targetPath(),
                            MutationPrecondition.existing(revision, patchedHash),
                            childContext(request.context(), index, "move")));
                    results.add(moved);
                    if (moved.optionalResultRevision().isEmpty()) {
                        conflicts.add(new PatchConflict(
                                item.patch().targetPath(),
                                PatchConflictCode.MUTATION_REJECTED,
                                -1,
                                "move outcome requires reconciliation"));
                        break;
                    }
                    revision = moved.optionalResultRevision().orElse(revision);
                }
                completedFiles++;
            } catch (PatchTransformException exception) {
                conflicts.add(new PatchConflict(
                        item.patch().sourcePath(),
                        PatchConflictCode.HUNK_MISMATCH,
                        exception.hunkIndex(),
                        exception.getMessage()));
                break;
            } catch (WorkspaceMutationException exception) {
                conflicts.add(new PatchConflict(
                        item.patch().targetPath(),
                        PatchConflictCode.MUTATION_REJECTED,
                        -1,
                        exception.code().name()));
                break;
            }
        }
        return new PatchApplyResult(request.document().sha256(), results, conflicts, completedFiles == prepared.size());
    }

    private java.util.Optional<PreparedPatch> prepare(
            PatchApplyRequest request, FilePatch patch, List<PatchConflict> conflicts) {
        WorkspacePath path = patch.sourcePath();
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

        String expected = request.expectedHashes().get(patch.sourcePath());
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
            if (patch.move()) {
                WorkspacePath destination = patch.targetPath();
                try {
                    files.stat(destination, false);
                    conflicts.add(new PatchConflict(
                            patch.targetPath(), PatchConflictCode.TARGET_EXISTS, -1, "move target already exists"));
                    return java.util.Optional.empty();
                } catch (WorkspaceFileException exception) {
                    if (exception.code() != WorkspaceFileErrorCode.PATH_NOT_FOUND) throw exception;
                }
            }
            if (patch.deletion()) {
                if (patch.hunks().stream().allMatch(hunk -> hunk.lines().isEmpty())) {
                    return java.util.Optional.of(new PreparedPatch(patch, expected, new byte[0]));
                }
                FileContent content = files.read(
                        path, new ReadOptions(MAX_FILE_BYTES, MAX_FILE_BYTES, StandardCharsets.UTF_8, false));
                byte[] after = applyHunks(patch, content.text());
                if (after.length != 0) {
                    conflicts.add(new PatchConflict(
                            patch.targetPath(),
                            PatchConflictCode.HUNK_MISMATCH,
                            -1,
                            "delete patch did not remove all content"));
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(new PreparedPatch(patch, expected, after));
            }
            if (mutations instanceof StreamingPatchMutationService) {
                return java.util.Optional.of(new PreparedPatch(patch, expected, null));
            }
            FileContent content =
                    files.read(path, new ReadOptions(MAX_FILE_BYTES, MAX_FILE_BYTES, StandardCharsets.UTF_8, false));
            return java.util.Optional.of(new PreparedPatch(patch, expected, applyHunks(patch, content.text())));
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
            int target;
            if (hunk.locateByContent()) {
                if (hunk.changeContext() != null) {
                    int anchor = original.subList(cursor, original.size()).indexOf(hunk.changeContext());
                    if (anchor < 0) throw new HunkConflict(hunkIndex, "failed to find change context");
                    anchor += cursor;
                    output.addAll(original.subList(cursor, anchor + 1));
                    cursor = anchor + 1;
                }
                List<String> expected = hunk.lines().stream()
                        .filter(line -> line.type() != PatchLineType.ADD)
                        .map(PatchLine::text)
                        .toList();
                target = expected.isEmpty() ? original.size() : findSequence(original, expected, cursor);
                if (target < 0) throw new HunkConflict(hunkIndex, "failed to find expected lines");
                if (hunk.endOfFile() && target + expected.size() != original.size()) {
                    throw new HunkConflict(hunkIndex, "expected lines are not at end of file");
                }
            } else {
                target = hunk.oldStart() == 0 ? 0 : hunk.oldStart() - 1;
            }
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

    private static MutationContext childContext(MutationContext parent, int index) {
        return childContext(parent, index, "file");
    }

    private static MutationContext childContext(MutationContext parent, int index, String suffix) {
        return new MutationContext(
                parent.operationId() + ":" + suffix + ":" + index,
                parent.runRef(),
                parent.toolCallRef(),
                parent.actor(),
                parent.securityDecisionRef());
    }

    private static PatchApplyResult conflict(
            PatchApplyRequest request, WorkspacePath path, PatchConflictCode code, int hunk, String detail) {
        return new PatchApplyResult(
                request.document().sha256(), List.of(), List.of(new PatchConflict(path, code, hunk, detail)), false);
    }

    private record PreparedPatch(FilePatch patch, String expectedHash, byte[] after) {
        private PreparedPatch {
            after = after == null ? null : java.util.Arrays.copyOf(after, after.length);
        }

        @Override
        public byte[] after() {
            return after == null ? null : java.util.Arrays.copyOf(after, after.length);
        }
    }

    private static final class HunkConflict extends RuntimeException {
        private final int hunkIndex;

        private HunkConflict(int hunkIndex, String message) {
            super(message);
            this.hunkIndex = hunkIndex;
        }

        private PatchConflict toConflict(WorkspacePath path) {
            return new PatchConflict(path, PatchConflictCode.HUNK_MISMATCH, hunkIndex, getMessage());
        }
    }
}
