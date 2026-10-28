package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.SessionScope;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Repository reads must be served by WAL's read concurrency instead of queueing for the process-wide write slot.
 *
 * <p>Each case holds the writer slot on another thread until the read has finished, so a read that queued behind the
 * writer could not complete: the case would end in the writer-queue fallback instead of passing.
 */
class SqliteReadScopeTest {
    private static final Instant NOW = Instant.parse("2026-07-25T00:00:00Z");

    @AfterEach
    void closeStores() throws Exception {
        SqliteTestSupport.closeOpenedStores();
    }

    @TempDir
    Path directory;

    @Test
    void sessionReadIsServedWhileAWriteUnitOfWorkHoldsTheWriterSlot() throws Exception {
        SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory);
        AgentSession session = AgentSession.open(
                new AgentSessionId("session"),
                new TenantRef("tenant"),
                new PrincipalRef("owner", "user"),
                null,
                SessionScope.USER,
                NOW,
                Map.of());
        foundation.agentSessions().insert(session);

        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch readFinished = new CountDownLatch(1);
        try (ExecutorService writer = Executors.newSingleThreadExecutor()) {
            Future<?> holdingWrite = writer.submit(() -> foundation.unitOfWork().execute(() -> {
                writeStarted.countDown();
                awaitQuietly(readFinished);
                return null;
            }));

            assertThat(writeStarted.await(20, TimeUnit.SECONDS))
                    .as("the write unit of work holds the writer slot")
                    .isTrue();

            Optional<AgentSession> found;
            try {
                found = foundation.agentSessions().find(session.id());
            } finally {
                readFinished.countDown();
            }
            holdingWrite.get(20, TimeUnit.SECONDS);

            assertThat(found).isPresent();
            assertThat(found.orElseThrow().id()).isEqualTo(session.id());
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the concurrent read did not finish");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while the writer slot was held", interrupted);
        }
    }
}
