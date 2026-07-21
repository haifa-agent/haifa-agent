package io.haifa.agent.project.patch;

import io.haifa.agent.project.path.ProjectPath;
import java.util.Objects;

public record PatchConflict(ProjectPath path, PatchConflictCode code, int hunkIndex, String safeDetail) {
    public PatchConflict {
        path = Objects.requireNonNull(path, "path must not be null");
        code = Objects.requireNonNull(code, "code must not be null");
        if (hunkIndex < -1) throw new IllegalArgumentException("hunkIndex must be -1 or greater");
        safeDetail = Objects.requireNonNull(safeDetail, "safeDetail must not be null")
                .trim();
        if (safeDetail.isEmpty()) throw new IllegalArgumentException("safeDetail must not be blank");
    }
}
