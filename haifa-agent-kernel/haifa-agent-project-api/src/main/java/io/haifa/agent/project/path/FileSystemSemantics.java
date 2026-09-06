package io.haifa.agent.project.path;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

public record FileSystemSemantics(String id, boolean caseSensitive, Normalizer.Form normalization) {
    public FileSystemSemantics {
        id = Objects.requireNonNull(id, "id must not be null").trim();
        if (id.isEmpty()) throw new IllegalArgumentException("id must not be blank");
        normalization = Objects.requireNonNull(normalization, "normalization must not be null");
    }

    public static FileSystemSemantics windowsDefault() {
        return new FileSystemSemantics("windows-default", false, Normalizer.Form.NFC);
    }

    public static FileSystemSemantics linuxDefault() {
        return new FileSystemSemantics("linux-default", true, Normalizer.Form.NFC);
    }

    public static FileSystemSemantics macDefault() {
        return new FileSystemSemantics("mac-default", false, Normalizer.Form.NFD);
    }

    public String comparisonKey(String logicalPath) {
        String normalized = Normalizer.normalize(logicalPath, normalization);
        return caseSensitive ? normalized : normalized.toLowerCase(Locale.ROOT);
    }
}
