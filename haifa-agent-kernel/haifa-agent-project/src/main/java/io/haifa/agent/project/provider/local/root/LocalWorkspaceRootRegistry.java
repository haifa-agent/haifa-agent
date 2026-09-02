package io.haifa.agent.project.provider.local.root;

import io.haifa.agent.project.root.WorkspaceRootAlias;
import io.haifa.agent.project.root.WorkspaceRootErrorCode;
import io.haifa.agent.project.root.WorkspaceRootException;
import io.haifa.agent.project.root.WorkspaceRootPermission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Session-scoped registry of authorized workspace roots.
 */
public final class LocalWorkspaceRootRegistry {
    private final Map<WorkspaceRootAlias, LocalWorkspaceRoot> roots;
    private final LocalWorkspaceRoot mainRoot;

    private LocalWorkspaceRootRegistry(Map<WorkspaceRootAlias, LocalWorkspaceRoot> roots) {
        this.roots = new LinkedHashMap<>(Objects.requireNonNull(roots, "roots must not be null"));
        LocalWorkspaceRoot main = roots.get(WorkspaceRootAlias.MAIN);
        if (main == null) {
            throw new IllegalArgumentException("registry must contain a 'main' root");
        }
        this.mainRoot = main;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static LocalWorkspaceRootRegistry singleMain(LocalWorkspaceRoot mainRoot) {
        if (!mainRoot.alias().isMain()) {
            throw new IllegalArgumentException("mainRoot must have alias 'main', was: " + mainRoot.alias());
        }
        return new LocalWorkspaceRootRegistry(Map.of(WorkspaceRootAlias.MAIN, mainRoot));
    }

    public static LocalWorkspaceRootRegistry of(List<LocalWorkspaceRoot> roots) {
        Objects.requireNonNull(roots, "roots must not be null");
        Builder builder = builder();
        for (LocalWorkspaceRoot root : roots) {
            builder.addRoot(root);
        }
        return builder.build();
    }

    public LocalWorkspaceRoot mainRoot() {
        return mainRoot;
    }

    public synchronized Optional<LocalWorkspaceRoot> find(WorkspaceRootAlias alias) {
        return Optional.ofNullable(roots.get(alias));
    }

    public Optional<LocalWorkspaceRoot> find(String alias) {
        if (alias == null || alias.isBlank()) return Optional.empty();
        try {
            return find(WorkspaceRootAlias.of(alias));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public synchronized LocalWorkspaceRoot require(WorkspaceRootAlias alias) {
        LocalWorkspaceRoot root = roots.get(alias);
        if (root == null) {
            throw new WorkspaceRootException(
                    WorkspaceRootErrorCode.ROOT_ALIAS_NOT_FOUND,
                    alias.value(),
                    null,
                    "Workspace root not found for alias: " + alias.value());
        }
        return root;
    }

    public synchronized boolean contains(WorkspaceRootAlias alias) {
        return roots.containsKey(alias);
    }

    public synchronized List<LocalWorkspaceRoot> allRoots() {
        return List.copyOf(roots.values());
    }

    /** Adds one user-approved root for the lifetime of this local agent process. */
    public synchronized void attach(LocalWorkspaceRoot root) {
        Objects.requireNonNull(root, "root must not be null");
        if (root.alias().isMain()) {
            throw new WorkspaceRootException(
                    WorkspaceRootErrorCode.DUPLICATE_ROOT_ALIAS,
                    root.alias().value(),
                    root.hostPath().toString(),
                    "The main root cannot be attached again");
        }
        if (roots.containsKey(root.alias())) {
            throw new WorkspaceRootException(
                    WorkspaceRootErrorCode.DUPLICATE_ROOT_ALIAS,
                    root.alias().value(),
                    root.hostPath().toString(),
                    "Workspace root alias is already attached: " + root.alias().value());
        }
        roots.put(root.alias(), root);
    }

    public synchronized void checkPermission(WorkspaceRootAlias alias, WorkspaceRootPermission required) {
        LocalWorkspaceRoot root = require(alias);
        if (required == WorkspaceRootPermission.READ_WRITE && !root.permission().canWrite()) {
            throw new WorkspaceRootException(
                    WorkspaceRootErrorCode.ROOT_READ_ONLY,
                    alias.value(),
                    null,
                    "Workspace root '" + alias.value() + "' is READ_ONLY; WRITE permission is denied");
        }
    }

    public static final class Builder {
        private final Map<WorkspaceRootAlias, LocalWorkspaceRoot> roots = new LinkedHashMap<>();

        public Builder addRoot(LocalWorkspaceRoot root) {
            Objects.requireNonNull(root, "root must not be null");
            if (roots.containsKey(root.alias())) {
                throw new WorkspaceRootException(
                        WorkspaceRootErrorCode.DUPLICATE_ROOT_ALIAS,
                        root.alias().value(),
                        root.hostPath().toString(),
                        "Duplicate root alias: " + root.alias().value());
            }
            roots.put(root.alias(), root);
            return this;
        }

        public LocalWorkspaceRootRegistry build() {
            return new LocalWorkspaceRootRegistry(roots);
        }
    }
}
