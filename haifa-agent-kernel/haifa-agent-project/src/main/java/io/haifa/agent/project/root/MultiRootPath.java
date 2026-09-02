package io.haifa.agent.project.root;

import java.util.Objects;

/**
 * Represents a parsed path comprising a root alias and a normalized relative path.
 */
public record MultiRootPath(WorkspaceRootAlias rootAlias, String relativePath) {
    public MultiRootPath {
        rootAlias = Objects.requireNonNull(rootAlias, "rootAlias must not be null");
        Objects.requireNonNull(relativePath, "relativePath must not be null");
        relativePath = normalize(relativePath);
    }

    public static MultiRootPath of(WorkspaceRootAlias alias, String relativePath) {
        return new MultiRootPath(alias, relativePath);
    }

    public static MultiRootPath ofMain(String relativePath) {
        return new MultiRootPath(WorkspaceRootAlias.MAIN, relativePath);
    }

    private static String normalize(String path) {
        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.equals(".") || normalized.isEmpty()) {
            return "";
        }
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    public boolean isRoot() {
        return relativePath.isEmpty();
    }

    @Override
    public String toString() {
        return rootAlias.value() + ":" + (relativePath.isEmpty() ? "." : relativePath);
    }
}
