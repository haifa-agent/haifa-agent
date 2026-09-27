package io.haifa.agent.runtime.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.run.AgentRunId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LocalExecutionSchedulerTest {
    private static final AgentRunId RUN_ID = new AgentRunId("run-1");

    @Test
    void strictSubmitStillRejectsDuplicatesWhileOneContinuationIsAccepted() throws Exception {
        CountDownLatch currentStarted = new CountDownLatch(1);
        CountDownLatch releaseCurrent = new CountDownLatch(1);
        CountDownLatch successorFinished = new CountDownLatch(1);
        AtomicInteger successorCalls = new AtomicInteger();
        try (LocalExecutionScheduler scheduler = new LocalExecutionScheduler()) {
            scheduler.submit(RUN_ID, () -> {
                currentStarted.countDown();
                await(releaseCurrent);
            });
            assertThat(currentStarted.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> scheduler.submit(RUN_ID, () -> {}))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already has a process-local execution task");
            scheduler.submitAfterCurrent(RUN_ID, () -> {
                successorCalls.incrementAndGet();
                successorFinished.countDown();
            });
            assertThatThrownBy(() -> scheduler.submitAfterCurrent(RUN_ID, () -> {}))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already has a process-local execution successor");

            releaseCurrent.countDown();
            assertThat(successorFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(successorCalls).hasValue(1);
        }
    }

    @Test
    void closeDrainsAnAcceptedContinuationAndThenRejectsNewSubmissions() throws Exception {
        CountDownLatch currentStarted = new CountDownLatch(1);
        CountDownLatch releaseCurrent = new CountDownLatch(1);
        CountDownLatch successorFinished = new CountDownLatch(1);
        AtomicInteger successorCalls = new AtomicInteger();
        LocalExecutionScheduler scheduler = new LocalExecutionScheduler();
        scheduler.submit(RUN_ID, () -> {
            currentStarted.countDown();
            await(releaseCurrent);
        });
        assertThat(currentStarted.await(2, TimeUnit.SECONDS)).isTrue();
        scheduler.submitAfterCurrent(RUN_ID, () -> {
            successorCalls.incrementAndGet();
            successorFinished.countDown();
        });

        try (var closer = Executors.newSingleThreadExecutor()) {
            CountDownLatch closeInvoked = new CountDownLatch(1);
            var closed = closer.submit(() -> {
                closeInvoked.countDown();
                scheduler.close();
            });
            assertThat(closeInvoked.await(2, TimeUnit.SECONDS)).isTrue();
            releaseCurrent.countDown();
            closed.get(2, TimeUnit.SECONDS);
        }

        assertThat(successorFinished.getCount()).isZero();
        assertThat(successorCalls).hasValue(1);
        assertThatThrownBy(() -> scheduler.submit(new AgentRunId("run-after-close"), () -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scheduler is closed");
    }

    @Test
    void cancellationAtTheHandoffInterruptsBothTasksWithoutDuplicatingTheSuccessor() throws Exception {
        CountDownLatch currentStarted = new CountDownLatch(1);
        CountDownLatch currentInterrupted = new CountDownLatch(1);
        CountDownLatch successorFinished = new CountDownLatch(1);
        AtomicBoolean successorInterrupted = new AtomicBoolean();
        AtomicInteger successorCalls = new AtomicInteger();
        try (LocalExecutionScheduler scheduler = new LocalExecutionScheduler()) {
            scheduler.submit(RUN_ID, () -> {
                currentStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    currentInterrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(currentStarted.await(2, TimeUnit.SECONDS)).isTrue();
            scheduler.submitAfterCurrent(RUN_ID, () -> {
                successorCalls.incrementAndGet();
                successorInterrupted.set(Thread.currentThread().isInterrupted());
                successorFinished.countDown();
            });

            scheduler.cancel(RUN_ID);

            assertThat(currentInterrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(successorFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(successorInterrupted).isTrue();
            assertThat(successorCalls).hasValue(1);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting in test task", exception);
        }
    }
}
