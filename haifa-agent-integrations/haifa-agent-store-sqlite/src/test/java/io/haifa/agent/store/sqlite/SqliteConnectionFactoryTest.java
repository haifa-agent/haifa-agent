package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.io.SecureFilePermissions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteConnectionFactoryTest {
    @AfterEach
    void closeStores() throws Exception {
        SqliteTestSupport.closeOpenedStores();
    }

    @TempDir
    Path directory;

    @Test
    void requiresControlledAbsoluteDatabasePathAndUsableParent() throws Exception {
        assertThatThrownBy(() -> SqliteStoreConfiguration.defaults(Path.of("relative.db")))
                .isInstanceOf(SqliteStoreException.class)
                .extracting(exception -> ((SqliteStoreException) exception).failure())
                .isEqualTo(SqliteStoreFailure.INVALID_CONFIGURATION);

        assertThatThrownBy(() -> SqliteStoreConfiguration.defaults(
                        directory.resolve("missing").resolve("runtime.db").toAbsolutePath()))
                .isInstanceOf(SqliteStoreException.class)
                .hasMessageContaining("existing directory");

        Path nonDirectoryParent = Files.writeString(directory.resolve("not-a-directory"), "blocked");
        assertThatThrownBy(() -> SqliteStoreConfiguration.defaults(
                        nonDirectoryParent.resolve("runtime.db").toAbsolutePath()))
                .isInstanceOf(SqliteStoreException.class)
                .hasMessageContaining("existing directory");

        assertThatThrownBy(() -> SqliteStoreConfiguration.defaults(directory.toAbsolutePath()))
                .isInstanceOf(SqliteStoreException.class)
                .hasMessageContaining("regular file");

        assertThatThrownBy(() -> new SqliteStoreConfiguration(
                        directory.resolve("runtime.db").toAbsolutePath(), 1_250, 8_192, 0))
                .isInstanceOf(SqliteStoreException.class)
                .hasMessageContaining("writerQueueTimeoutMillis");
    }

    @Test
    void initializesWalAndConfiguresEveryConnection() throws Exception {
        SqliteStoreConfiguration configuration = SqliteTestSupport.configuration(directory);
        SqliteConnectionFactory factory = SqliteTestSupport.closeAfterTest(new SqliteConnectionFactory(configuration));
        factory.initialize();
        factory.initialize();

        try (Connection first = factory.openConnection();
                Connection second = factory.openConnection()) {
            assertThat(query(first, "PRAGMA journal_mode")).isEqualToIgnoringCase("wal");
            assertThat(query(first, "PRAGMA foreign_keys")).isEqualTo("1");
            assertThat(query(first, "PRAGMA busy_timeout"))
                    .isEqualTo(Integer.toString(configuration.busyTimeoutMillis()));
            assertThat(query(second, "PRAGMA foreign_keys")).isEqualTo("1");
            assertThat(query(second, "PRAGMA busy_timeout"))
                    .isEqualTo(Integer.toString(configuration.busyTimeoutMillis()));
        }
    }

    @Test
    void detectsThePermissionStrategyOnceAndRevalidatesExistingFilesOnEveryOpen() throws Exception {
        SqliteStoreConfiguration configuration = SqliteTestSupport.configuration(directory);
        AtomicInteger detections = new AtomicInteger();
        AtomicInteger standaloneRootValidations = new AtomicInteger();
        AtomicInteger securedBatches = new AtomicInteger();
        Map<Path, AtomicInteger> securedFiles = new HashMap<>();
        SqliteConnectionFactory.PermissionStrategyDetector detector = root -> {
            detections.incrementAndGet();
            var delegate = SecureFilePermissions.strategyForDirectory(root);
            return new SecureFilePermissions.PermissionStrategy() {
                @Override
                public void validateRoot() throws java.io.IOException {
                    standaloneRootValidations.incrementAndGet();
                    delegate.validateRoot();
                }

                @Override
                public void secureDirectory(Path value) throws java.io.IOException {
                    delegate.secureDirectory(value);
                }

                @Override
                public void secureFile(Path value) throws java.io.IOException {
                    securedFiles
                            .computeIfAbsent(value, ignored -> new AtomicInteger())
                            .incrementAndGet();
                    delegate.secureFile(value);
                }

                @Override
                public void secureExistingFiles(java.util.List<Path> values) throws java.io.IOException {
                    securedBatches.incrementAndGet();
                    values.stream().filter(Files::exists).forEach(value -> securedFiles
                            .computeIfAbsent(value, ignored -> new AtomicInteger())
                            .incrementAndGet());
                    delegate.secureExistingFiles(values);
                }
            };
        };
        SqliteConnectionFactory factory =
                SqliteTestSupport.closeAfterTest(new SqliteConnectionFactory(configuration, detector));
        factory.initialize();

        try (Connection first = factory.openConnection()) {
            int afterFirst = total(securedFiles);
            try (Connection second = factory.openConnection()) {
                assertThat(total(securedFiles)).isGreaterThan(afterFirst);
            }
        }

        // Pooled connections keep the database file itself open, so the changed storage identity is
        // staged on the journal sibling, which the store also revalidates on every open.
        Path database = configuration.databasePath().toAbsolutePath().normalize();
        Path journal = database.resolveSibling(database.getFileName() + "-journal");
        Files.writeString(journal, "first");
        try (Connection ignored = factory.openConnection()) {
            assertThat(securedFiles).containsKey(journal);
        }
        int securedBeforeReplacement = securedFiles.get(journal).get();
        Path replacement = Files.writeString(directory.resolve("replacement-journal"), "second");
        Files.move(replacement, journal, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (Connection ignored = factory.openConnection()) {
            assertThat(securedFiles.get(journal).get()).isEqualTo(securedBeforeReplacement + 1);
        }

        assertThat(detections).hasValue(1);
        assertThat(standaloneRootValidations).hasValue(0);
        assertThat(securedBatches).hasValueGreaterThanOrEqualTo(4);

        // A write unit of work verifies the files before it queues for the writer slot, so the lease that follows
        // must not repeat the file-system work while the slot is held.
        int batchesBeforeAheadOfWriter = securedBatches.get();
        factory.verifyDatabaseFiles();
        assertThat(securedBatches).hasValue(batchesBeforeAheadOfWriter + 1);
        try (Connection ignored = factory.openConnectionAfterFileVerification()) {
            assertThat(securedBatches)
                    .as("a lease after an explicit verification does not verify the files again")
                    .hasValue(batchesBeforeAheadOfWriter + 1);
        }
        factory.close();
    }

    @Test
    void rejectsAnExistingSymbolicDatabaseBeforeJdbcCanFollowIt() throws Exception {
        Path target = Files.writeString(directory.resolve("target.db"), "not a database");
        Path link = directory.resolve("linked.db");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException exception) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links are unavailable on this host");
        }

        assertThatThrownBy(() -> SqliteStoreConfiguration.defaults(link.toAbsolutePath()))
                .isInstanceOf(SqliteStoreException.class)
                .hasMessageContaining("regular file");
    }

    @Test
    void inProcessWritersQueueBehindTheSingleWriteSlot() throws Exception {
        SqliteConnectionFactory factory = SqliteTestSupport.closeAfterTest(
                new SqliteConnectionFactory(SqliteTestSupport.configuration(directory)));
        CountDownLatch queuedWriterAcquired = new CountDownLatch(1);
        factory.acquireWriter();
        boolean released = false;
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> queuedWriter = executor.submit(() -> {
                factory.acquireWriter();
                queuedWriterAcquired.countDown();
                factory.releaseWriter();
                return null;
            });

            assertThat(queuedWriterAcquired.await(200, TimeUnit.MILLISECONDS))
                    .as("a second in-process writer waits for the held write slot")
                    .isFalse();

            factory.releaseWriter();
            released = true;

            assertThat(queuedWriterAcquired.await(10, TimeUnit.SECONDS))
                    .as("the queued writer takes the slot as soon as it is released")
                    .isTrue();
            queuedWriter.get();
        } finally {
            if (!released) {
                factory.releaseWriter();
            }
        }
    }

    @Test
    void queuedWriterKeepsWaitingBeyondTheBusyTimeoutItIsNotBoundedBy() throws Exception {
        SqliteStoreConfiguration configuration =
                new SqliteStoreConfiguration(directory.resolve("queued.db").toAbsolutePath(), 50, 8_192);
        SqliteConnectionFactory factory = SqliteTestSupport.closeAfterTest(new SqliteConnectionFactory(configuration));
        CountDownLatch queuedWriterStarted = new CountDownLatch(1);
        CountDownLatch queuedWriterAcquired = new CountDownLatch(1);
        factory.acquireWriter();
        boolean released = false;
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> queuedWriter = executor.submit(() -> {
                queuedWriterStarted.countDown();
                factory.acquireWriter();
                queuedWriterAcquired.countDown();
                factory.releaseWriter();
                return null;
            });
            assertThat(queuedWriterStarted.await(10, TimeUnit.SECONDS)).isTrue();

            // The holder keeps the slot while the queued writer is already asking for it; that is the window in
            // which the busy timeout used to fail the queued writer instead of letting it wait.
            assertThat(queuedWriterAcquired.await(300, TimeUnit.MILLISECONDS))
                    .as("the queued writer keeps waiting for the held write slot")
                    .isFalse();

            factory.releaseWriter();
            released = true;

            assertThat(queuedWriterAcquired.await(10, TimeUnit.SECONDS))
                    .as("the queued writer takes the slot once it is released")
                    .isTrue();
            queuedWriter.get();
        } finally {
            if (!released) {
                factory.releaseWriter();
            }
        }
    }

    @Test
    void writerQueueFallbackBoundNamesTheThreadThatStillHoldsTheSlot() throws Exception {
        SqliteStoreConfiguration configured = new SqliteStoreConfiguration(
                SqliteTestSupport.configuration(directory).databasePath(), 1_250, 8_192, 150);
        SqliteConnectionFactory factory = SqliteTestSupport.closeAfterTest(
                new SqliteConnectionFactory(configured, SecureFilePermissions::strategyForDirectory));
        String holderThreadName = Thread.currentThread().getName();
        factory.acquireWriter();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Long> queuedWriter = executor.submit(factory::acquireWriter);

            assertThatThrownBy(() -> queuedWriter.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(SqliteStoreException.class)
                    .satisfies(failure -> {
                        SqliteStoreException storeFailure = (SqliteStoreException) failure.getCause();
                        assertThat(storeFailure.failure()).isEqualTo(SqliteStoreFailure.WRITER_SLOT_TIMEOUT);
                        assertThat(storeFailure.getMessage())
                                .contains(holderThreadName, "waitedMs=", "writerLocked=true");
                    });
        } finally {
            factory.releaseWriter();
        }
    }

    @Test
    void nestedWriterSlotAcquisitionIsRejectedInsteadOfOverwritingTheHolder() {
        SqliteConnectionFactory factory = SqliteTestSupport.closeAfterTest(
                new SqliteConnectionFactory(SqliteTestSupport.configuration(directory)));
        factory.acquireWriter();
        try {
            assertThatThrownBy(factory::acquireWriter)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("nested");
        } finally {
            factory.releaseWriter();
        }
    }

    @Test
    void writerTimeoutDescriptionDistinguishesAnUnheldSlotFromALeakedOne() {
        SqliteConnectionFactory factory = SqliteTestSupport.closeAfterTest(
                new SqliteConnectionFactory(SqliteTestSupport.configuration(directory)));

        assertThat(factory.writerTimeoutDescription(60_001, 3, null))
                .contains(
                        "waitedMs=60001",
                        "holderThread=none",
                        "holderHeldMs=unknown",
                        "ownership=none",
                        "writerLocked=false",
                        "queuedWriters=3");

        factory.acquireWriter();
        try {
            assertThat(factory.writerTimeoutDescription(60_001, 3, Thread.currentThread()))
                    .contains(
                            "holderThread=" + Thread.currentThread().getName(),
                            "ownership=consistent",
                            "writerLocked=true",
                            "queuedWriters=3");
        } finally {
            factory.releaseWriter();
        }
    }

    @Test
    void writerTimeoutDescriptionMarksAChangedOwnerDuringHandoff() throws Exception {
        SqliteConnectionFactory factory = SqliteTestSupport.closeAfterTest(
                new SqliteConnectionFactory(SqliteTestSupport.configuration(directory)));
        factory.acquireWriter();
        factory.releaseWriter();
        Thread previousOwner = Thread.currentThread();
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> holder = executor.submit(() -> {
                factory.acquireWriter();
                acquired.countDown();
                await(release);
                factory.releaseWriter();
                return null;
            });
            try {
                assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(factory.writerTimeoutDescription(60_001, 3, previousOwner))
                        .contains(
                                "holderThread=" + previousOwner.getName(),
                                "holderHeldMs=unknown",
                                "ownership=changed",
                                "writerLocked=true");
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", exception);
        }
    }

    private static int total(Map<Path, AtomicInteger> values) {
        return values.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    private static String query(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
