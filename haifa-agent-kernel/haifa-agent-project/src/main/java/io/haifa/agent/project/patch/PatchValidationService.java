package io.haifa.agent.project.patch;

import io.haifa.agent.project.path.ProjectPath;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class PatchValidationService {
    private final int maxFiles;
    private final int maxHunks;
    private final int maxLines;

    public PatchValidationService(int maxFiles, int maxHunks, int maxLines) {
        if (maxFiles < 1 || maxHunks < 1 || maxLines < 1) {
            throw new IllegalArgumentException("patch budgets must be positive");
        }
        this.maxFiles = maxFiles;
        this.maxHunks = maxHunks;
        this.maxLines = maxLines;
    }

    public void validate(PatchDocument document) {
        Objects.requireNonNull(document, "document must not be null");
        if (document.files().size() > maxFiles) throw new IllegalArgumentException("patch file budget exceeded");
        int hunks = 0;
        int lines = 0;
        Set<ProjectPath> paths = new HashSet<>();
        for (FilePatch file : document.files()) {
            if (!paths.add(file.targetPath())) throw new IllegalArgumentException("duplicate logical patch path");
            hunks += file.hunks().size();
            for (PatchHunk hunk : file.hunks()) lines += hunk.lines().size();
        }
        if (hunks > maxHunks) throw new IllegalArgumentException("patch hunk budget exceeded");
        if (lines > maxLines) throw new IllegalArgumentException("patch line budget exceeded");
    }
}
