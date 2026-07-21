package io.haifa.agent.project.patch;

import io.haifa.agent.project.path.ProjectPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UnifiedPatchParser {
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(?: .*)?$");
    private final int maxFiles;
    private final int maxLines;
    private final int maxBytes;

    public UnifiedPatchParser(int maxFiles, int maxLines, int maxBytes) {
        if (maxFiles < 1 || maxLines < 1 || maxBytes < 1)
            throw new IllegalArgumentException("budgets must be positive");
        this.maxFiles = maxFiles;
        this.maxLines = maxLines;
        this.maxBytes = maxBytes;
    }

    public PatchDocument parse(String unifiedDiff) {
        if (unifiedDiff == null || unifiedDiff.isEmpty()) throw new IllegalArgumentException("patch must not be empty");
        byte[] bytes = unifiedDiff.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes || unifiedDiff.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("patch exceeds budget or contains NUL");
        }
        String normalized = unifiedDiff.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        if (lines.length > maxLines) throw new IllegalArgumentException("patch line budget exceeded");
        List<FilePatch> files = new ArrayList<>();
        Set<ProjectPath> paths = new HashSet<>();
        int index = 0;
        while (index < lines.length && lines[index].isEmpty()) index++;
        while (index < lines.length && !lines[index].isEmpty()) {
            if (!lines[index].startsWith("--- ")) throw new IllegalArgumentException("expected old file header");
            ProjectPath oldPath = parsePath(lines[index++].substring(4), "a/");
            if (index >= lines.length || !lines[index].startsWith("+++ ")) {
                throw new IllegalArgumentException("expected new file header");
            }
            ProjectPath newPath = parsePath(lines[index++].substring(4), "b/");
            if (oldPath != null && newPath != null && !oldPath.equals(newPath)) {
                throw new IllegalArgumentException("rename patches are unsupported");
            }
            ProjectPath target = newPath == null ? oldPath : newPath;
            if (!paths.add(target)) throw new IllegalArgumentException("duplicate logical patch path");
            List<PatchHunk> hunks = new ArrayList<>();
            boolean oldNewline = true;
            boolean newNewline = true;
            while (index < lines.length && lines[index].startsWith("@@ ")) {
                Matcher matcher = HUNK.matcher(lines[index++]);
                if (!matcher.matches()) throw new IllegalArgumentException("invalid hunk header");
                int oldStart = Integer.parseInt(matcher.group(1));
                int oldCount = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
                int newStart = Integer.parseInt(matcher.group(3));
                int newCount = matcher.group(4) == null ? 1 : Integer.parseInt(matcher.group(4));
                List<PatchLine> hunkLines = new ArrayList<>();
                PatchLineType previous = null;
                while (index < lines.length
                        && !lines[index].startsWith("@@ ")
                        && !lines[index].startsWith("--- ")
                        && !lines[index].isEmpty()) {
                    String line = lines[index++];
                    if (line.equals("\\ No newline at end of file")) {
                        if (previous == PatchLineType.REMOVE) oldNewline = false;
                        else if (previous == PatchLineType.ADD) newNewline = false;
                        else {
                            oldNewline = false;
                            newNewline = false;
                        }
                        continue;
                    }
                    previous = switch (line.charAt(0)) {
                        case ' ' -> PatchLineType.CONTEXT;
                        case '+' -> PatchLineType.ADD;
                        case '-' -> PatchLineType.REMOVE;
                        default -> throw new IllegalArgumentException("invalid hunk line prefix");
                    };
                    hunkLines.add(new PatchLine(previous, line.substring(1)));
                }
                hunks.add(new PatchHunk(oldStart, oldCount, newStart, newCount, hunkLines));
            }
            files.add(new FilePatch(oldPath, newPath, hunks, oldNewline, newNewline));
            if (files.size() > maxFiles) throw new IllegalArgumentException("patch file budget exceeded");
            while (index < lines.length && lines[index].isEmpty()) index++;
        }
        return new PatchDocument(files, "sha256:" + hash(bytes));
    }

    private static ProjectPath parsePath(String header, String prefix) {
        String value = header.split("\\t", 2)[0].trim();
        if (value.equals("/dev/null")) return null;
        if (value.startsWith("GIT binary patch") || value.startsWith("Binary files")) {
            throw new IllegalArgumentException("binary patches are unsupported");
        }
        if (!value.startsWith(prefix)) throw new IllegalArgumentException("patch path prefix is invalid");
        return ProjectPath.of(value.substring(prefix.length()));
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
