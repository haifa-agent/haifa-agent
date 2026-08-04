package io.haifa.agent.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

/** Best-effort, process-free Git branch discovery for terminal presentation only. */
final class LocalGitBranchResolver {
    private static final int MAX_METADATA_CHARS = 8_192;

    private LocalGitBranchResolver() {}

    static Optional<String> resolve(Path workspace) {
        Path metadata = workspace.toAbsolutePath().normalize().resolve(".git");
        try {
            Path gitDirectory =
                    Files.isDirectory(metadata, LinkOption.NOFOLLOW_LINKS) ? metadata : linkedGitDirectory(metadata);
            if (gitDirectory == null || !Files.isDirectory(gitDirectory, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            String head = boundedRead(gitDirectory.resolve("HEAD")).strip();
            String prefix = "ref: refs/heads/";
            if (!head.startsWith(prefix)) return Optional.empty();
            String branch = head.substring(prefix.length()).strip();
            return branch.isEmpty()
                            || branch.length() > 512
                            || branch.codePoints().anyMatch(Character::isISOControl)
                    ? Optional.empty()
                    : Optional.of(branch);
        } catch (IOException | IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static Path linkedGitDirectory(Path metadata) throws IOException {
        if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(metadata)) return null;
        String pointer = boundedRead(metadata).strip();
        String prefix = "gitdir:";
        if (!pointer.regionMatches(true, 0, prefix, 0, prefix.length())) return null;
        String value = pointer.substring(prefix.length()).strip();
        if (value.isEmpty()) return null;
        Path target = Path.of(value);
        return (target.isAbsolute() ? target : metadata.getParent().resolve(target)).normalize();
    }

    private static String boundedRead(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)
                || Files.size(path) > MAX_METADATA_CHARS) {
            throw new IOException("Git metadata is unavailable");
        }
        return Files.readString(path);
    }
}
