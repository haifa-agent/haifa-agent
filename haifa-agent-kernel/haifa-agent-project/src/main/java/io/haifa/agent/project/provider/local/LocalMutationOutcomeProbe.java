package io.haifa.agent.project.provider.local;

import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.changeset.FileChange;
import io.haifa.agent.project.changeset.FileChangeSet;
import io.haifa.agent.project.changeset.FileVersion;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.reconciliation.MutationOutcomeProbe;
import io.haifa.agent.project.reconciliation.MutationProbeResult;
import io.haifa.agent.project.reconciliation.MutationProbeStatus;
import io.haifa.agent.project.store.WorkspaceBindingStore;
import io.haifa.agent.project.store.WorkspaceStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class LocalMutationOutcomeProbe implements MutationOutcomeProbe {
    private final WorkspaceStore workspaces;
    private final WorkspaceBindingStore bindings;
    private final LocalWorkspaceLocationStore locations;

    public LocalMutationOutcomeProbe(
            WorkspaceStore workspaces, WorkspaceBindingStore bindings, LocalWorkspaceLocationStore locations) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.locations = Objects.requireNonNull(locations, "locations must not be null");
    }

    @Override
    public MutationProbeResult probe(FileChangeSet changeSet) {
        try {
            var workspace = workspaces.find(changeSet.workspaceId()).orElseThrow();
            WorkspaceBinding binding =
                    bindings.find(workspace.root().bindingId()).orElseThrow();
            Path root = locations.resolve(binding.locationRef()).toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!LocalWorkspaceLocationStore.fingerprintFor(root).equals(binding.rootFingerprint())
                    || isLinkOrReparse(root)) {
                return indeterminate("workspace root identity is unavailable");
            }
            if (!cleanupManagedTemporaries(root)) {
                return indeterminate("managed temporary files require manual inspection");
            }
            boolean allApplied = changeSet.changes().stream().allMatch(change -> applied(root, change));
            if (allApplied)
                return new MutationProbeResult(MutationProbeStatus.CONFIRMED, "post-state hashes confirmed");
            boolean noneApplied = changeSet.changes().stream().allMatch(change -> original(root, change));
            if (noneApplied) return new MutationProbeResult(MutationProbeStatus.NOT_APPLIED, "original state remains");
            return indeterminate("physical state is mixed or changed externally");
        } catch (IOException | RuntimeException exception) {
            return indeterminate("physical state could not be determined safely");
        }
    }

    private static boolean applied(Path root, FileChange change) {
        return switch (change.type()) {
            case CREATE, REPLACE -> matches(resolve(root, change.path()), change.after());
            case DELETE -> absent(resolve(root, change.path()));
            case MOVE ->
                absent(resolve(root, change.path())) && matches(resolve(root, change.destination()), change.after());
        };
    }

    private static boolean original(Path root, FileChange change) {
        return switch (change.type()) {
            case CREATE -> absent(resolve(root, change.path()));
            case REPLACE, DELETE -> matches(resolve(root, change.path()), change.before());
            case MOVE ->
                matches(resolve(root, change.path()), change.before()) && absent(resolve(root, change.destination()));
        };
    }

    private static Path resolve(Path root, io.haifa.agent.project.path.ProjectPath logical) {
        Path target = root;
        for (String segment : logical.segments()) {
            target = target.resolve(segment).normalize();
            if (!target.startsWith(root)) throw new IllegalArgumentException("logical path escaped root");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && isLinkOrReparse(target)) {
                throw new IllegalArgumentException("logical path contains a link");
            }
        }
        return target;
    }

    private static boolean matches(Path path, FileVersion expected) {
        if (expected == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS) || isLinkOrReparse(path)) return false;
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (expected.type() == FileType.FILE && attributes.isRegularFile()) {
                byte[] bytes = Files.readAllBytes(path);
                return bytes.length == expected.size() && ("sha256:" + hash(bytes)).equals(expected.contentHash());
            }
            if (expected.type() == FileType.DIRECTORY && attributes.isDirectory()) {
                try (var entries = Files.list(path)) {
                    return entries.findAny().isEmpty() && expected.contentHash().equals("directory:empty");
                }
            }
            return false;
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean absent(Path path) {
        return !Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean cleanupManagedTemporaries(Path root) throws IOException {
        boolean clean = true;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(candidate -> candidate.getFileName() != null
                            && candidate.getFileName().toString().startsWith(".haifa-write-"))
                    .toList()) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !isLinkOrReparse(path)) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        clean = false;
                    }
                } else {
                    clean = false;
                }
            }
        }
        return clean;
    }

    private static boolean isLinkOrReparse(Path path) {
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

    private static MutationProbeResult indeterminate(String detail) {
        return new MutationProbeResult(MutationProbeStatus.INDETERMINATE, detail);
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
