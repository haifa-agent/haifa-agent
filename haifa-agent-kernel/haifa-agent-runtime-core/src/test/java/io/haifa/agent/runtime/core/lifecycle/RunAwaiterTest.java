package io.haifa.agent.runtime.core.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RunAwaiterTest {
    private static final AgentRunId RUN_ID = new AgentRunId("run-await-lock-order");

    @Test
    void doesNotHoldAwaitMonitorWhileReadingSnapshot() throws Exception {
        var awaiter = new RunAwaiter();
        var storeLock = new Object();
        var snapshotReadStarted = new CountDownLatch(1);
        var allowSnapshotRead = new CountDownLatch(1);
        var terminal = new AtomicBoolean();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var awaited = executor.submit(() -> awaiter.await(
                    RUN_ID,
                    Duration.ofSeconds(2),
                    () -> {
                        snapshotReadStarted.countDown();
                        awaitLatch(allowSnapshotRead);
                        synchronized (storeLock) {
                            return terminal.get();
                        }
                    },
                    Boolean::booleanValue));

            assertThat(snapshotReadStarted.await(1, TimeUnit.SECONDS)).isTrue();
            var signalled = executor.submit(() -> {
                synchronized (storeLock) {
                    terminal.set(true);
                    allowSnapshotRead.countDown();
                    awaiter.signal(RUN_ID);
                }
            });

            assertThat(signalled.get(1, TimeUnit.SECONDS)).isNull();
            assertThat(awaited.get(1, TimeUnit.SECONDS)).contains(true);
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", exception);
        }
    }
}
