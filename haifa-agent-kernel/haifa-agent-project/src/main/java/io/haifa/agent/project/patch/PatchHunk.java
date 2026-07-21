package io.haifa.agent.project.patch;

import java.util.List;
import java.util.Objects;

public record PatchHunk(int oldStart, int oldCount, int newStart, int newCount, List<PatchLine> lines) {
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
    }
}
