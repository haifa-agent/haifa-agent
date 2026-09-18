package io.haifa.agent.project.patch;

import java.util.List;
import java.util.Objects;

public record PatchHunk(
        int oldStart,
        int oldCount,
        int newStart,
        int newCount,
        List<PatchLine> lines,
        String changeContext,
        boolean locateByContent,
        boolean endOfFile) {
    public PatchHunk(int oldStart, int oldCount, int newStart, int newCount, List<PatchLine> lines) {
        this(oldStart, oldCount, newStart, newCount, lines, null, false, false);
    }

    public static PatchHunk contextual(String changeContext, List<PatchLine> lines, boolean endOfFile) {
        Objects.requireNonNull(lines, "lines must not be null");
        int oldCount = Math.toIntExact(
                lines.stream().filter(line -> line.type() != PatchLineType.ADD).count());
        int newCount = Math.toIntExact(lines.stream()
                .filter(line -> line.type() != PatchLineType.REMOVE)
                .count());
        return new PatchHunk(0, oldCount, 0, newCount, lines, changeContext, true, endOfFile);
    }

    public PatchHunk {
        if (oldStart < 0 || oldCount < 0 || newStart < 0 || newCount < 0) {
            throw new IllegalArgumentException("hunk coordinates must not be negative");
        }
        lines = List.copyOf(Objects.requireNonNull(lines, "lines must not be null"));
        long oldLines =
                lines.stream().filter(line -> line.type() != PatchLineType.ADD).count();
        long newLines = lines.stream()
                .filter(line -> line.type() != PatchLineType.REMOVE)
                .count();
        if (oldLines != oldCount || newLines != newCount) {
            throw new IllegalArgumentException("hunk line counts do not match header");
        }
        if (!locateByContent && (changeContext != null || endOfFile)) {
            throw new IllegalArgumentException("positional hunk cannot declare contextual matching");
        }
        if (changeContext != null && changeContext.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("patch context contains NUL");
        }
    }
}
