package io.haifa.agent.project.hostworkspace;

import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingStatus;
import io.haifa.agent.project.filesystem.FileContent;
import io.haifa.agent.project.filesystem.FileEntry;
import io.haifa.agent.project.filesystem.FileListPage;
import io.haifa.agent.project.filesystem.FileListRequest;
import io.haifa.agent.project.filesystem.FileMetadata;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.filesystem.ReadOptions;
import io.haifa.agent.project.filesystem.SearchRequest;
import io.haifa.agent.project.filesystem.SearchResult;
import io.haifa.agent.project.filesystem.WorkspaceFileErrorCode;
import io.haifa.agent.project.filesystem.WorkspaceFileException;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.spi.WorkspaceProvider;
import io.haifa.agent.project.store.WorkspaceBindingStore;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspacePermission;
import io.haifa.agent.project.workspace.WorkspaceStatus;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class HostWorkspaceFileService implements WorkspaceProvider {
    private final WorkspaceStore workspaces;
    private final WorkspaceBindingStore bindings;
    private final HostWorkspaceLocationStore locations;
    private final SensitivePathPolicy sensitivePaths;

    public HostWorkspaceFileService(
            WorkspaceStore workspaces,
            WorkspaceBindingStore bindings,
            HostWorkspaceLocationStore locations,
            SensitivePathPolicy sensitivePaths) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.locations = Objects.requireNonNull(locations, "locations must not be null");
        this.sensitivePaths = Objects.requireNonNull(sensitivePaths, "sensitivePaths must not be null");
    }

    @Override
    public String providerId() {
        return "local-guarded";
    }

    @Override
    public Set<io.haifa.agent.project.binding.WorkspaceBindingMode> supportedBindingModes() {
        return Set.of(
                io.haifa.agent.project.binding.WorkspaceBindingMode.DIRECT,
                io.haifa.agent.project.binding.WorkspaceBindingMode.READ_ONLY);
    }

    @Override
    public FileListPage list(FileListRequest request) {
        WorkspacePath directory = request.directory();
        Access access = access(directory, WorkspacePermission.LIST);
        Path hostDirectory = resolveExisting(access, directory);
        if (!Files.isDirectory(hostDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(WorkspaceFileErrorCode.WRONG_FILE_TYPE, directory, "logical path is not a directory");
        }
        try (Stream<Path> children = Files.list(hostDirectory)) {
            List<FileEntry> ordered = children.map(child -> new Candidate(child, logical(access, child)))
                    .filter(candidate -> sensitivePaths.mayRead(candidate.logical.projectPath()))
                    .limit(10_001)
                    .map(candidate -> metadata(access, candidate.logical, candidate.hostPath, false))
                    .sorted(Comparator.comparing(value -> value.path().projectPath()))
                    .map(FileEntry::new)
                    .toList();
            if (ordered.size() > 10_000) {
                throw failure(
                        WorkspaceFileErrorCode.LIST_BUDGET_EXCEEDED,
                        directory,
                        "logical directory exceeds the listing budget");
            }
            List<FileEntry> page = ordered.stream()
                    .skip(request.offset())
                    .limit(request.limit())
                    .toList();
            int nextOffset = request.offset() + page.size();
            return new FileListPage(page, nextOffset, nextOffset < ordered.size());
        } catch (IOException exception) {
            throw failure(WorkspaceFileErrorCode.IO_FAILURE, directory, "unable to list logical directory");
        }
    }

    @Override
    public FileMetadata stat(WorkspacePath path, boolean includeHash) {
        Access access = access(path, WorkspacePermission.STAT);
        Path hostPath = resolveExisting(access, path);
        return metadata(access, path, hostPath, includeHash);
    }

    @Override
    public FileContent read(WorkspacePath path, ReadOptions options) {
        Objects.requireNonNull(options, "options must not be null");
        Access access = access(path, WorkspacePermission.READ);
        Path hostPath = resolveExisting(access, path);
        if (!Files.isRegularFile(hostPath, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(WorkspaceFileErrorCode.WRONG_FILE_TYPE, path, "logical path is not a regular file");
        }
        try {
            BasicFileAttributes before =
                    Files.readAttributes(hostPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            long size = before.size();
            if (options.offset() > size) {
                throw failure(WorkspaceFileErrorCode.FILE_CURSOR_STALE, path, "read cursor is beyond the file end");
            }
            long remaining = size - options.offset();
            if (remaining > options.maxBytes() && !options.allowTruncation()) {
                throw failure(WorkspaceFileErrorCode.FILE_TOO_LARGE, path, "file exceeds the configured read budget");
            }
            int readLimit = Math.toIntExact(Math.min(remaining, options.maxBytes()));
            ByteBuffer buffer = ByteBuffer.allocate(readLimit);
            try (SeekableByteChannel channel = Files.newByteChannel(hostPath, StandardOpenOption.READ)) {
                channel.position(options.offset());
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                    // Bounded channel read; no allocation is proportional to the complete file.
                }
            }
            byte[] bytes = java.util.Arrays.copyOf(buffer.array(), buffer.position());
            if (looksBinary(bytes)) {
                throw failure(WorkspaceFileErrorCode.BINARY_CONTENT, path, "binary content is not readable as text");
            }
            Decoded decoded = decode(bytes, options, path);
            String text = decoded.text();
            int consumedBytes = decoded.byteCount();
            boolean truncated = options.offset() + consumedBytes < size || text.length() > options.maxCharacters();
            if (text.length() > options.maxCharacters()) {
                if (!options.allowTruncation()) {
                    throw failure(WorkspaceFileErrorCode.FILE_TOO_LARGE, path, "text exceeds the character budget");
                }
                int characters = options.maxCharacters();
                if (characters < text.length()
                        && characters > 0
                        && Character.isHighSurrogate(text.charAt(characters - 1))) {
                    characters--;
                }
                text = text.substring(0, characters);
                consumedBytes = text.getBytes(options.charset()).length;
            }
            BasicFileAttributes after =
                    Files.readAttributes(hostPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            verifyUnchanged(access, path, hostPath, before, after);
            byte[] consumed = java.util.Arrays.copyOf(bytes, consumedBytes);
            return new FileContent(
                    path,
                    text,
                    options.charset(),
                    options.offset(),
                    consumedBytes,
                    size,
                    sourceVersion(before),
                    (truncated || options.offset() > 0 ? "sha256-partial:" : "sha256:") + hash(consumed),
                    truncated);
        } catch (WorkspaceFileException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(WorkspaceFileErrorCode.IO_FAILURE, path, "unable to read logical file");
        }
    }

    @Override
    public List<SearchResult> search(SearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Access access = access(request.root(), WorkspacePermission.SEARCH);
        Path hostRoot = resolveExisting(access, request.root());
        if (!Files.isDirectory(hostRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(WorkspaceFileErrorCode.WRONG_FILE_TYPE, request.root(), "search root is not a directory");
        }
        List<SearchResult> results = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> stream = Files.walk(hostRoot)) {
            List<Path> candidates = stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(path -> logical(access, path).projectPath()))
                    .toList();
            for (Path candidate : candidates) {
                if (scanned >= request.maxScannedFiles() || results.size() >= request.maxResults()) break;
                WorkspacePath logical = logical(access, candidate);
                if (!sensitivePaths.mayRead(logical.projectPath())) continue;
                if (Files.size(candidate) > request.maxFileBytes()) continue;
                scanned++;
                FileContent content = read(
                        logical,
                        new ReadOptions(
                                request.maxFileBytes(), 1_000_000, java.nio.charset.StandardCharsets.UTF_8, false));
                match(content.text(), logical, request, results);
            }
            return results.stream().sorted().limit(request.maxResults()).toList();
        } catch (WorkspaceFileException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(WorkspaceFileErrorCode.IO_FAILURE, request.root(), "unable to search logical directory");
        }
    }

    private Access access(WorkspacePath path, WorkspacePermission permission) {
        Workspace workspace = workspaces
                .find(path.workspaceId())
                .orElseThrow(() -> failure(WorkspaceFileErrorCode.WORKSPACE_NOT_FOUND, path, "workspace not found"));
        if (workspace.status() != WorkspaceStatus.ACTIVE) {
            throw failure(WorkspaceFileErrorCode.WORKSPACE_INACTIVE, path, "workspace is not active");
        }
        WorkspaceBinding binding = bindings.find(workspace.root().bindingId())
                .orElseThrow(
                        () -> failure(WorkspaceFileErrorCode.BINDING_NOT_FOUND, path, "workspace binding not found"));
        if (binding.status() != WorkspaceBindingStatus.ACTIVE) {
            throw failure(WorkspaceFileErrorCode.BINDING_INACTIVE, path, "workspace binding is not active");
        }
        if (!binding.permissions().allows(permission)) {
            throw failure(WorkspaceFileErrorCode.PERMISSION_DENIED, path, "workspace permission denied");
        }
        if (!sensitivePaths.mayRead(path.projectPath())) {
            throw failure(WorkspaceFileErrorCode.SENSITIVE_PATH, path, "sensitive logical path is not readable");
        }
        Path root;
        try {
            root = locations.resolve(binding.locationRef()).toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException exception) {
            throw failure(WorkspaceFileErrorCode.BINDING_INACTIVE, path, "workspace location is unavailable");
        }
        if (!HostWorkspaceLocationStore.fingerprintFor(root).equals(binding.rootFingerprint())) {
            throw failure(WorkspaceFileErrorCode.BINDING_INACTIVE, path, "workspace root fingerprint changed");
        }
        if (isLinkOrReparse(root)) {
            throw failure(WorkspaceFileErrorCode.LINK_REJECTED, path, "workspace root cannot be a link");
        }
        return new Access(workspace, binding, root);
    }

    private Path resolveExisting(Access access, WorkspacePath logical) {
        Path target = access.root;
        for (String segment : logical.projectPath().segments()) {
            target = target.resolve(segment).normalize();
            if (!target.startsWith(access.root)) {
                throw failure(WorkspaceFileErrorCode.PATH_ESCAPE, logical, "logical path escapes workspace root");
            }
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(WorkspaceFileErrorCode.PATH_NOT_FOUND, logical, "logical path does not exist");
            }
            if (isLinkOrReparse(target)) {
                throw failure(WorkspaceFileErrorCode.LINK_REJECTED, logical, "links and reparse points are denied");
            }
        }
        try {
            Path real = target.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.startsWith(access.root)) {
                throw failure(WorkspaceFileErrorCode.PATH_ESCAPE, logical, "logical path escapes workspace root");
            }
            return real;
        } catch (IOException exception) {
            throw failure(WorkspaceFileErrorCode.IO_FAILURE, logical, "unable to resolve logical path");
        }
    }

    private void verifyUnchanged(
            Access access, WorkspacePath logical, Path opened, BasicFileAttributes before, BasicFileAttributes after) {
        Path current = resolveExisting(access, logical);
        if (!current.equals(opened)) {
            throw failure(WorkspaceFileErrorCode.PATH_ESCAPE, logical, "logical path changed during read");
        }
        if (before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) {
            throw failure(WorkspaceFileErrorCode.FILE_CURSOR_STALE, logical, "file changed during read");
        }
    }

    private FileMetadata metadata(Access access, WorkspacePath logical, Path hostPath, boolean includeHash) {
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(hostPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            boolean link = isLinkOrReparse(hostPath);
            FileType type = link
                    ? FileType.LINK
                    : attributes.isRegularFile()
                            ? FileType.FILE
                            : attributes.isDirectory() ? FileType.DIRECTORY : FileType.OTHER;
            Optional<String> hash = Optional.empty();
            if (includeHash && type == FileType.FILE) {
                hash = Optional.of("sha256:" + hashFile(hostPath));
                BasicFileAttributes after =
                        Files.readAttributes(hostPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                verifyUnchanged(access, logical, hostPath, attributes, after);
            }
            return new FileMetadata(
                    logical,
                    type,
                    attributes.size(),
                    Optional.of(attributes.lastModifiedTime().toInstant()),
                    hash,
                    link);
        } catch (WorkspaceFileException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(WorkspaceFileErrorCode.IO_FAILURE, logical, "unable to read logical metadata");
        }
    }

    private WorkspacePath logical(Access access, Path hostPath) {
        Path relative = access.root.relativize(hostPath.toAbsolutePath().normalize());
        String value = relative.toString().replace(hostPath.getFileSystem().getSeparator(), "/");
        return new WorkspacePath(access.workspace.id(), ProjectPath.of(value));
    }

    private static void match(String text, WorkspacePath path, SearchRequest request, List<SearchResult> results) {
        String needle =
                request.caseSensitive() ? request.query() : request.query().toLowerCase(Locale.ROOT);
        String[] lines = text.split("\\R", -1);
        for (int index = 0; index < lines.length && results.size() < request.maxResults(); index++) {
            String haystack = request.caseSensitive() ? lines[index] : lines[index].toLowerCase(Locale.ROOT);
            int column = haystack.indexOf(needle);
            if (column >= 0) {
                String excerpt = lines[index].length() <= 512 ? lines[index] : lines[index].substring(0, 512);
                results.add(new SearchResult(path, index + 1, column + 1, excerpt));
            }
        }
    }

    private static Decoded decode(byte[] bytes, ReadOptions options, WorkspacePath path) {
        try {
            var decoder = options.charset()
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return new Decoded(decoder.decode(ByteBuffer.wrap(bytes)).toString(), bytes.length);
        } catch (CharacterCodingException exception) {
            if (options.allowTruncation()) {
                for (int trim = 1; trim <= Math.min(4, bytes.length); trim++) {
                    try {
                        var decoder = options.charset()
                                .newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT);
                        return new Decoded(
                                decoder.decode(ByteBuffer.wrap(bytes, 0, bytes.length - trim))
                                        .toString(),
                                bytes.length - trim);
                    } catch (CharacterCodingException ignored) {
                        // Try the next possible incomplete trailing sequence.
                    }
                }
            }
            throw failure(
                    WorkspaceFileErrorCode.UNSUPPORTED_ENCODING, path, "file is not valid in the requested encoding");
        }
    }

    private static boolean looksBinary(byte[] bytes) {
        int sample = Math.min(bytes.length, 8192);
        for (int index = 0; index < sample; index++) {
            if (bytes[index] == 0) return true;
        }
        return false;
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
            Object value = Files.getAttribute(path, "dos:reparsePoint", LinkOption.NOFOLLOW_LINKS);
            return Boolean.TRUE.equals(value);
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static String hashFile(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path, java.nio.file.StandardOpenOption.READ)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static String sourceVersion(BasicFileAttributes attributes) {
        String identity = attributes.size()
                + ":"
                + attributes.lastModifiedTime().toMillis()
                + ":"
                + Objects.toString(attributes.fileKey(), "");
        return "sha256:" + hash(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static WorkspaceFileException failure(WorkspaceFileErrorCode code, WorkspacePath path, String message) {
        return new WorkspaceFileException(code, path, message);
    }

    private record Access(Workspace workspace, WorkspaceBinding binding, Path root) {}

    private record Candidate(Path hostPath, WorkspacePath logical) {}

    private record Decoded(String text, int byteCount) {}
}
