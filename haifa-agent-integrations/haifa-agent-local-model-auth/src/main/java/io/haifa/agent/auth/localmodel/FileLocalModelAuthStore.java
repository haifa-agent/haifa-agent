package io.haifa.agent.auth.localmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/** Plaintext current-user auth store with strict permissions, locking, and atomic replacement. */
public final class FileLocalModelAuthStore implements LocalModelAuthStore {
    private final Path file;
    private final Path directory;
    private final Path lockFile;
    private final LocalModelAuthFileCodec codec;
    private final LocalModelAuthFilePermissions permissions;
    private final ReentrantLock processLock = new ReentrantLock();

    public FileLocalModelAuthStore(Path file, ObjectMapper json) {
        Path configured = Objects.requireNonNull(file, "file must not be null")
                .toAbsolutePath()
                .normalize();
        if (configured.getParent() == null
                || !"auth.json".equals(configured.getFileName().toString())) {
            throw new IllegalArgumentException("Local model auth store must target an auth.json file");
        }
        this.file = configured;
        this.directory = configured.getParent();
        this.lockFile = directory.resolve("auth.json.lock");
        this.codec = new LocalModelAuthFileCodec(json);
        this.permissions = new LocalModelAuthFilePermissions();
    }

    public static FileLocalModelAuthStore defaultStore(ObjectMapper json) {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) throw new IllegalStateException("user.home is unavailable");
        return new FileLocalModelAuthStore(Path.of(userHome, ".haifa-agent", "auth.json"), json);
    }

    public Path file() {
        return file;
    }

    @Override
    public Optional<StoredModelCredential> find(LocalModelAuthReference reference) {
        LocalModelAuthReference checked = Objects.requireNonNull(reference, "reference must not be null");
        return withFileLock(() -> Optional.ofNullable(readAll().get(checked)));
    }

    @Override
    public List<LocalModelConnectionView> listSafe() {
        return withFileLock(() -> readAll().values().stream()
                .map(credential -> credential.safeView(false))
                .toList());
    }

    @Override
    public void save(StoredModelCredential credential) {
        StoredModelCredential checked = Objects.requireNonNull(credential, "credential must not be null");
        withFileLock(() -> {
            Map<LocalModelAuthReference, StoredModelCredential> credentials = readAll();
            credentials.put(checked.reference(), checked);
            writeAll(credentials);
            return null;
        });
    }

    @Override
    public boolean delete(LocalModelAuthReference reference) {
        LocalModelAuthReference checked = Objects.requireNonNull(reference, "reference must not be null");
        return withFileLock(() -> {
            Map<LocalModelAuthReference, StoredModelCredential> credentials = readAll();
            if (credentials.remove(checked) == null) return false;
            if (credentials.isEmpty()) Files.deleteIfExists(file);
            else writeAll(credentials);
            return true;
        });
    }

    private <T> T withFileLock(IoSupplier<T> operation) {
        processLock.lock();
        try {
            prepareDirectory();
            permissions.rejectSymbolicLink(lockFile);
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = tryFileLock(channel)) {
                permissions.secure(lockFile, false);
                return operation.get();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Local model auth store operation failed", exception);
        } finally {
            processLock.unlock();
        }
    }

    private FileLock tryFileLock(FileChannel channel) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IllegalStateException("Local model auth store is locked by another process");
            return lock;
        } catch (OverlappingFileLockException exception) {
            throw new IllegalStateException("Local model auth store is already locked in this process", exception);
        }
    }

    private void prepareDirectory() throws IOException {
        permissions.rejectSymbolicLink(directory);
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Local model auth path is not a directory");
            }
        } else {
            Files.createDirectories(directory);
        }
        permissions.secure(directory, true);
    }

    private Map<LocalModelAuthReference, StoredModelCredential> readAll() throws IOException {
        permissions.rejectSymbolicLink(file);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new LinkedHashMap<>();
        permissions.secure(file, false);
        long size = Files.size(file);
        if (size < 1 || size > LocalModelAuthFileCodec.MAX_FILE_BYTES) {
            throw new IllegalStateException("Local model auth file size is invalid");
        }
        return new LinkedHashMap<>(codec.decode(Files.readAllBytes(file)));
    }

    private void writeAll(Map<LocalModelAuthReference, StoredModelCredential> credentials) throws IOException {
        byte[] bytes = codec.encode(credentials);
        Path temporary = Files.createTempFile(directory, ".auth-", ".tmp");
        boolean moved = false;
        try {
            permissions.secure(temporary, false);
            try (FileChannel channel =
                    FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IllegalStateException(
                        "Local model auth filesystem does not support atomic replacement", exception);
            }
            moved = true;
            permissions.secure(file, false);
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
