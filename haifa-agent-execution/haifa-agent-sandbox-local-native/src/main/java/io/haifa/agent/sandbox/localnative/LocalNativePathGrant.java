package io.haifa.agent.sandbox.localnative;

import java.nio.file.Path;
import java.util.Objects;

public record LocalNativePathGrant(Path path, boolean readOnly) {
    public LocalNativePathGrant {
        path = Objects.requireNonNull(path, "path must not be null")
                .toAbsolutePath()
                .normalize();
        if (path.getParent() == null) {
            throw new IllegalArgumentException("path grant cannot target a filesystem root");
        }
    }
}
