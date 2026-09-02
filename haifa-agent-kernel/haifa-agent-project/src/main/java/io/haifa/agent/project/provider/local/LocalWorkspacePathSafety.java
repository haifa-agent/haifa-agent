package io.haifa.agent.project.provider.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Safety checks shared by local workspace roots before an operation can traverse or mutate a node.
 */
public final class LocalWorkspacePathSafety {
    private LocalWorkspacePathSafety() {}

    public static boolean isUnsafeNode(Path path) {
        if (Files.isSymbolicLink(path)) return true;
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isOther()) return true;
        } catch (IOException exception) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(Files.getAttribute(path, "dos:reparsePoint", LinkOption.NOFOLLOW_LINKS));
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException ignored) {
            return false;
        }
    }
}
