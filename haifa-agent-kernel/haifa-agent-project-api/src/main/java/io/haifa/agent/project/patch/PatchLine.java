package io.haifa.agent.project.patch;

import java.util.Objects;

public record PatchLine(PatchLineType type, String text) {
    public PatchLine {
        type = Objects.requireNonNull(type, "type must not be null");
        text = Objects.requireNonNull(text, "text must not be null");
        if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("patch line contains NUL");
    }

    @Override
    public String toString() {
        return "PatchLine[type=" + type + ", characters=" + text.length() + "]";
    }
}
