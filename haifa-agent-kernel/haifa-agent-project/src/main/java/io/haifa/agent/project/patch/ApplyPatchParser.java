package io.haifa.agent.project.patch;

import io.haifa.agent.project.path.WorkspacePath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Parses the bounded, context-oriented patch format used by coding models. */
public final class ApplyPatchParser {
    private static final String BEGIN = "*** Begin Patch";
    private static final String END = "*** End Patch";
    private static final String ADD = "*** Add File: ";
    private static final String DELETE = "*** Delete File: ";
    private static final String UPDATE = "*** Update File: ";
    private static final String MOVE = "*** Move to: ";
    private static final String END_OF_FILE = "*** End of File";

    private final int maxFiles;
    private final int maxHunks;
    private final int maxLines;
    private final int maxBytes;

    public ApplyPatchParser(int maxFiles, int maxHunks, int maxLines, int maxBytes) {
        if (maxFiles < 1 || maxHunks < 1 || maxLines < 1 || maxBytes < 1) {
            throw new IllegalArgumentException("patch budgets must be positive");
        }
        this.maxFiles = maxFiles;
        this.maxHunks = maxHunks;
        this.maxLines = maxLines;
        this.maxBytes = maxBytes;
    }

    /**
     * Parses a patch document whose headers specify logical relative project paths within the given
     * workspace. Absolute host paths must be resolved and mapped to workspace paths before calling this parser.
     */
    public PatchDocument parse(io.haifa.agent.project.workspace.WorkspaceId workspaceId, String patch) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        if (patch == null || patch.isBlank()) throw new IllegalArgumentException("patch must not be blank");
        byte[] bytes = patch.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) throw new IllegalArgumentException("patch byte budget exceeded");
        List<String> lines = patch.strip().lines().toList();
        if (lines.size() < 3 || !BEGIN.equals(lines.get(0)) || !END.equals(lines.get(lines.size() - 1))) {
            throw new IllegalArgumentException("patch must start with '*** Begin Patch' and end with '*** End Patch'");
        }

        List<FilePatch> files = new ArrayList<>();
        Set<String> sources = new HashSet<>();
        int index = 1;
        int totalHunks = 0;
        int totalLines = 0;
        while (index < lines.size() - 1) {
            String header = lines.get(index++);
            FileParse parsed;
            if (header.startsWith(ADD)) {
                parsed = parseAdd(parsePatchPath(header, ADD, workspaceId, index), lines, index);
            } else if (header.startsWith(DELETE)) {
                parsed = parseDelete(parsePatchPath(header, DELETE, workspaceId, index), lines, index);
            } else if (header.startsWith(UPDATE)) {
                parsed = parseUpdate(parsePatchPath(header, UPDATE, workspaceId, index), lines, index, workspaceId);
            } else {
                throw invalid(index, "invalid file patch header");
            }
            index = parsed.nextIndex();
            FilePatch file = parsed.patch();
            if (!sources.add(file.sourcePath().toString())) {
                throw new IllegalArgumentException("duplicate logical patch path");
            }
            files.add(file);
            totalHunks += file.hunks().size();
            totalLines += file.hunks().stream()
                    .mapToInt(value -> value.lines().size())
                    .sum();
            if (files.size() > maxFiles) throw new IllegalArgumentException("patch file budget exceeded");
            if (totalHunks > maxHunks) throw new IllegalArgumentException("patch hunk budget exceeded");
            if (totalLines > maxLines) throw new IllegalArgumentException("patch line budget exceeded");
        }
        if (files.isEmpty()) throw new IllegalArgumentException("patch must contain at least one file operation");
        return new PatchDocument(files, "sha256:" + hash(bytes));
    }

    private static WorkspacePath parsePatchPath(
            String header, String prefix, io.haifa.agent.project.workspace.WorkspaceId workspaceId, int index) {
        String value = header.substring(prefix.length()).trim();
        if (value.isEmpty()) throw invalid(index, "patch file path must not be blank");
        return new WorkspacePath(workspaceId, io.haifa.agent.project.path.ProjectPath.of(value));
    }

    private static FileParse parseAdd(WorkspacePath target, List<String> lines, int index) {
        List<PatchLine> additions = new ArrayList<>();
        while (index < lines.size() - 1 && !isFileHeader(lines.get(index))) {
            String line = lines.get(index++);
            if (!line.startsWith("+")) throw invalid(index, "added file lines must start with '+'");
            additions.add(new PatchLine(PatchLineType.ADD, line.substring(1)));
        }
        if (additions.isEmpty()) throw invalid(index, "added file requires content");
        return new FileParse(
                new FilePatch(null, target, List.of(PatchHunk.contextual(null, additions, true)), false, true), index);
    }

    private static FileParse parseDelete(WorkspacePath target, List<String> lines, int index) {
        if (index < lines.size() - 1 && !isFileHeader(lines.get(index))) {
            throw invalid(index + 1, "delete file does not accept patch lines");
        }
        return new FileParse(
                new FilePatch(target, null, List.of(PatchHunk.contextual(null, List.of(), true)), true, false), index);
    }

    private static FileParse parseUpdate(
            WorkspacePath source,
            List<String> lines,
            int index,
            io.haifa.agent.project.workspace.WorkspaceId workspaceId) {
        WorkspacePath destination = source;
        if (index < lines.size() - 1 && lines.get(index).startsWith(MOVE)) {
            destination = parsePatchPath(lines.get(index++), MOVE, workspaceId, index);
        }
        List<PatchHunk> hunks = new ArrayList<>();
        while (index < lines.size() - 1 && !isFileHeader(lines.get(index))) {
            String marker = lines.get(index++);
            if (!marker.equals("@@") && !marker.startsWith("@@ ")) {
                throw invalid(index, "update hunk must start with '@@' or '@@ context'");
            }
            String context = marker.equals("@@") ? null : marker.substring(3);
            List<PatchLine> patchLines = new ArrayList<>();
            boolean endOfFile = false;
            while (index < lines.size() - 1
                    && !isFileHeader(lines.get(index))
                    && !lines.get(index).startsWith("@@")) {
                String line = lines.get(index++);
                if (END_OF_FILE.equals(line)) {
                    endOfFile = true;
                    break;
                }
                if (line.isEmpty()) throw invalid(index, "patch line must start with space, '+' or '-'");
                PatchLineType type =
                        switch (line.charAt(0)) {
                            case ' ' -> PatchLineType.CONTEXT;
                            case '+' -> PatchLineType.ADD;
                            case '-' -> PatchLineType.REMOVE;
                            default -> throw invalid(index, "patch line must start with space, '+' or '-'");
                        };
                patchLines.add(new PatchLine(type, line.substring(1)));
            }
            if (patchLines.isEmpty()) throw invalid(index, "update hunk must contain changes");
            hunks.add(PatchHunk.contextual(context, patchLines, endOfFile));
        }
        if (hunks.isEmpty()) throw invalid(index, "updated file requires at least one hunk");
        return new FileParse(new FilePatch(source, destination, hunks, true, true), index);
    }

    private static boolean isFileHeader(String line) {
        return line.startsWith(ADD) || line.startsWith(DELETE) || line.startsWith(UPDATE) || line.equals(END);
    }

    private static IllegalArgumentException invalid(int line, String message) {
        return new IllegalArgumentException("invalid patch at line " + line + ": " + message);
    }

    private static String hash(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 required", exception);
        }
    }

    private record FileParse(FilePatch patch, int nextIndex) {}
}
