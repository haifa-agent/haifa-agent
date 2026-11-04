package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.product.coding.CodingShellExecution;
import io.haifa.agent.application.project.product.coding.CodingShellResult;
import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CliShellExecutionsTest {
    private static final AgentSessionId SESSION = new AgentSessionId("shell-session");
    private static final CodingShellResult RESULT =
            new CodingShellResult("EXITED", Optional.of(0), "safe result", Optional.empty(), false, false);

    @Test
    void acceptsOnceAndReturnsTheSameOutcomeAfterTheCallerStopsObserving() {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger dispatches = new AtomicInteger();
        AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
        try (var executions = new CliShellExecutions(now::get)) {
            var receipt = executions.start(
                    "plan",
                    () -> new CliShellExecutions.Work(SESSION, cancellation -> {
                        dispatches.incrementAndGet();
                        entered.countDown();
                        CodingHostTestFixture.await(release);
                        return RESULT;
                    }));
            CodingHostTestFixture.await(entered);
            now.set(Instant.ofEpochMilli(35_000));
            assertThat(receipt.executionId()).isEqualTo("plan");
            assertThat(executions.get(SESSION, "plan").state()).isEqualTo(CodingShellExecution.State.RUNNING);
            executions.start("plan", () -> {
                throw new AssertionError("one-shot plan consumed twice");
            });
            assertThatThrownBy(() -> executions.get(new AgentSessionId("other"), "plan"))
                    .hasMessageContaining("unavailable");
            release.countDown();
            assertThat(executions.await("plan")).isEqualTo(RESULT);
            assertThat(executions.get(SESSION, "plan").result()).contains(RESULT);
            assertThat(dispatches).hasValue(1);
            now.set(Instant.ofEpochMilli(35_000 + 30 * 60 * 1000));
            assertThat(executions.list(SESSION)).isEmpty();
        } finally {
            release.countDown();
        }
    }

    @Test
    void cancellationTargetsOnlyThatShellAndCanWinBeforeStart() {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        try (var executions = new CliShellExecutions(() -> Instant.EPOCH)) {
            executions.start(
                    "running",
                    () -> new CliShellExecutions.Work(SESSION, cancellation -> {
                        entered.countDown();
                        while (!cancellation.isCancellationRequested()) {
                            try {
                                if (cancelled.await(10, TimeUnit.MILLISECONDS)) break;
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                        cancelled.countDown();
                        return new CodingShellResult(
                                "CANCELLED", Optional.empty(), "cancelled", Optional.empty(), false, false);
                    }));
            CodingHostTestFixture.await(entered);
            assertThat(executions
                            .cancel(SESSION, "running", () -> {
                                throw new AssertionError();
                            })
                            .cancellationRequested())
                    .isTrue();
            assertThat(executions.await("running").processState()).isEqualTo("CANCELLED");
            executions.cancel(
                    SESSION,
                    "before-start",
                    () -> new CliShellExecutions.Work(SESSION, ignored -> {
                        throw new AssertionError("cancelled plan dispatched");
                    }));
            assertThat(executions
                            .start("before-start", () -> {
                                throw new AssertionError();
                            })
                            .result())
                    .get()
                    .extracting(CodingShellResult::processState)
                    .isEqualTo("CANCELLED");
        }
    }

    @Test
    void rejectsCapacityBeforeConsumingAnotherPlanAndRetainsUncertainFailures() {
        CountDownLatch release = new CountDownLatch(1);
        try (var executions = new CliShellExecutions(() -> Instant.EPOCH)) {
            for (int i = 0; i < 8; i++)
                executions.start(
                        "active-" + i,
                        () -> new CliShellExecutions.Work(SESSION, cancellation -> {
                            CodingHostTestFixture.await(release);
                            return RESULT;
                        }));
            assertThatThrownBy(() -> executions.start("ninth", () -> {
                        throw new AssertionError("plan consumed");
                    }))
                    .hasMessageContaining("Too many active");
            release.countDown();
            for (int i = 0; i < 8; i++) executions.await("active-" + i);
            executions.start(
                    "uncertain",
                    () -> new CliShellExecutions.Work(SESSION, ignored -> {
                        throw new IllegalStateException("private detail");
                    }));
            assertThatThrownBy(() -> executions.await("uncertain")).hasMessageContaining("could not be confirmed");
            assertThat(executions.get(SESSION, "uncertain").errorCode()).contains("SHELL_OUTCOME_UNKNOWN");
            for (int i = 0; i < 130; i++) {
                String id = "finished-" + i;
                executions.start(id, () -> new CliShellExecutions.Work(SESSION, ignored -> RESULT));
                executions.await(id);
            }
            assertThat(executions.list(SESSION)).hasSize(128);
            var retained = executions.list(SESSION);
            assertThatThrownBy(() -> executions.start("missing", () -> {
                        throw new IllegalStateException("missing");
                    }))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> executions.cancel(SESSION, "missing", () -> {
                        throw new IllegalStateException("missing");
                    }))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(executions.list(SESSION)).isEqualTo(retained);
        } finally {
            release.countDown();
        }
    }
}
