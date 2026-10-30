package io.haifa.agent.application.project.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-lifetime ownership of one Coding Agent SQLite database, independent of transaction locking. */
final class CodingDatabaseOwnership implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private final AtomicBoolean closed = new AtomicBoolean();

    private CodingDatabaseOwnership(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static CodingDatabaseOwnership acquire(Path database) {
        Objects.requireNonNull(database, "database must not be null");
        Path canonical = canonicalDatabase(database);
        Path lockFile = canonical.resolveSibling(canonical.getFileName() + ".owner.lock");
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException alreadyOwnedInThisJvm) {
                lock = null;
            }
            if (lock == null) {
                channel.close();
                throw new CodingDatabaseOwnershipException();
            }
            return new CodingDatabaseOwnership(channel, lock);
        } catch (CodingDatabaseOwnershipException exception) {
            throw exception;
        } catch (IOException exception) {
            closeQuietly(channel);
            throw new IllegalStateException("Unable to acquire Coding Agent database ownership", exception);
        }
    }

    private static Path canonicalDatabase(Path database) {
        Path absolute = database.toAbsolutePath().normalize();
        try {
            if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) return absolute.toRealPath();
            Path parent = absolute.getParent();
            if (parent == null) throw new IllegalArgumentException("database path must have a parent directory");
            return parent.toRealPath().resolve(absolute.getFileName()).normalize();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to resolve Coding Agent database ownership path", exception);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) return;
        try {
            channel.close();
        } catch (IOException ignored) {
            // The original acquire failure is the actionable error.
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        RuntimeException failure = null;
        try {
            lock.release();
        } catch (IOException exception) {
            failure = new IllegalStateException("Unable to release Coding Agent database ownership", exception);
        }
        try {
            channel.close();
        } catch (IOException exception) {
            if (failure == null) failure = new IllegalStateException("Unable to close database ownership lock", exception);
            else failure.addSuppressed(exception);
        }
        if (failure != null) throw failure;
    }
}
