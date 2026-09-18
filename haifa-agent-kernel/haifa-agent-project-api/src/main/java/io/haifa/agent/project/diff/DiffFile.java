package io.haifa.agent.project.diff;

import io.haifa.agent.project.path.ProjectPath;
import java.util.Objects;

public record DiffFile(ProjectPath path, String before, String after) {
    public DiffFile {
        path = Objects.requireNonNull(path, "path must not be null");
        if (before == null && after == null) throw new IllegalArgumentException("before and after cannot both be null");
        if (Objects.equals(before, after)) throw new IllegalArgumentException("unchanged content is not a diff input");
    }

    @Override
    public String toString() {
        return "DiffFile[path=" + path + ", beforeCharacters=" + length(before) + ", afterCharacters=" + length(after)
                + "]";
    }

    private static int length(String value) {
        return value == null ? -1 : value.length();
    }
}
