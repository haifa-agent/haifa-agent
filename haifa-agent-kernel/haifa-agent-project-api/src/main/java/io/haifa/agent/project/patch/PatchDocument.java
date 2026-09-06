package io.haifa.agent.project.patch;

import java.util.List;
import java.util.Objects;

public record PatchDocument(List<FilePatch> files, String sha256) {
    public PatchDocument {
        files = List.copyOf(Objects.requireNonNull(files, "files must not be null"));
        if (files.isEmpty()) throw new IllegalArgumentException("patch document must not be empty");
        sha256 = Objects.requireNonNull(sha256, "sha256 must not be null").trim();
        if (sha256.isEmpty()) throw new IllegalArgumentException("sha256 must not be blank");
    }
}
