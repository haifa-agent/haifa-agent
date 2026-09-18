package io.haifa.agent.runtime.core.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetryExecutorTest {
    @Test
    void exposesAttemptAndRetryLifecycleWithoutChangingTheWorkIdentity() {
        List<Duration> sleeps = new ArrayList<>();
        List<String> lifecycle = new ArrayList<>();
        List<Integer> attempts = new ArrayList<>();
        RetryExecutor executor = new RetryExecutor(sleeps::add);

        String value = executor.execute(
                attempt -> {
                    attempts.add(attempt);
                    if (attempt == 1) throw new IllegalStateException("temporary");
                    return "ok";
                },
                new RetryPolicy(2, ignored -> true, BackoffStrategy.none()),
                (attempt, ignored) -> Duration.ofMillis(125),
                () -> {},
                new RetryListener() {
                    @Override
                    public void attemptScheduled(int attempt) {
                        lifecycle.add("attempt:" + attempt);
                    }

                    @Override
                    public void retryScheduled(int failedAttempt, RuntimeException failure, Duration delay) {
                        lifecycle.add("retry:" + failedAttempt + ":" + delay.toMillis());
                    }
                });

        assertThat(value).isEqualTo("ok");
        assertThat(attempts).containsExactly(1, 2);
        assertThat(sleeps).containsExactly(Duration.ofMillis(100), Duration.ofMillis(25));
        assertThat(lifecycle).containsExactly("attempt:1", "retry:1:125", "attempt:2");
    }

    @Test
    void cancellationDuringBackoffPreventsTheNextAttempt() {
        AtomicInteger workCalls = new AtomicInteger();
        AtomicInteger controlChecks = new AtomicInteger();
        List<Duration> sleeps = new ArrayList<>();
        RetryExecutor executor = new RetryExecutor(sleeps::add);

        assertThatThrownBy(() -> executor.execute(
                        ignored -> {
                            workCalls.incrementAndGet();
                            throw new IllegalStateException("temporary");
                        },
                        new RetryPolicy(3, ignored -> true, BackoffStrategy.none()),
                        (attempt, ignored) -> Duration.ofMillis(250),
                        () -> {
                            if (controlChecks.incrementAndGet() >= 3) {
                                throw new RetryCancelledException();
                            }
                        },
                        RetryListener.noop()))
                .isInstanceOf(RetryCancelledException.class);

        assertThat(workCalls).hasValue(1);
        assertThat(sleeps).containsExactly(Duration.ofMillis(100));
    }

    @Test
    void recordsExhaustionOnceForTheLastPhysicalAttempt() {
        List<Integer> exhausted = new ArrayList<>();
        RetryExecutor executor = new RetryExecutor(ignored -> {});

        assertThatThrownBy(() -> executor.execute(
                        attempt -> {
                            throw new IllegalStateException("temporary");
                        },
                        new RetryPolicy(2, ignored -> true, BackoffStrategy.none()),
                        (attempt, ignored) -> Duration.ZERO,
                        () -> {},
                        new RetryListener() {
                            @Override
                            public void exhausted(int finalAttempt, RuntimeException failure) {
                                exhausted.add(finalAttempt);
                            }
                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(exhausted).containsExactly(2);
    }

    @Test
    void appliesTheFailureSpecificAttemptLimitWithoutChangingThePolicyCeiling() {
        AtomicInteger calls = new AtomicInteger();
        List<Integer> exhausted = new ArrayList<>();
        RetryExecutor executor = new RetryExecutor(ignored -> {});

        assertThatThrownBy(() -> executor.execute(
                        attempt -> {
                            calls.incrementAndGet();
                            throw new IllegalStateException("temporary");
                        },
                        new RetryPolicy(4, ignored -> true, BackoffStrategy.none()),
                        (attempt, ignored) -> Duration.ZERO,
                        () -> {},
                        new RetryListener() {
                            @Override
                            public void exhausted(int finalAttempt, RuntimeException failure) {
                                exhausted.add(finalAttempt);
                            }
                        },
                        ignored -> 2))
                .isInstanceOf(IllegalStateException.class);

        assertThat(calls).hasValue(2);
        assertThat(exhausted).containsExactly(2);
    }

    @Test
    void nonRetryableFailureDoesNotMasqueradeAsRetryExhaustion() {
        List<Integer> exhausted = new ArrayList<>();
        RetryExecutor executor = new RetryExecutor(ignored -> {});

        assertThatThrownBy(() -> executor.execute(
                        attempt -> {
                            throw new IllegalArgumentException("invalid request");
                        },
                        new RetryPolicy(3, ignored -> false, BackoffStrategy.none()),
                        (attempt, ignored) -> Duration.ZERO,
                        () -> {},
                        new RetryListener() {
                            @Override
                            public void exhausted(int finalAttempt, RuntimeException failure) {
                                exhausted.add(finalAttempt);
                            }
                        }))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(exhausted).isEmpty();
    }

    @Test
    void interruptedBackoffRechecksCancellationBeforeClassifyingAnInfrastructureFailure() {
        AtomicInteger controlChecks = new AtomicInteger();
        RetryExecutor executor = new RetryExecutor(ignored -> {
            throw new InterruptedException("cancel signal");
        });

        try {
            assertThatThrownBy(() -> executor.execute(
                            ignored -> {
                                throw new IllegalStateException("temporary");
                            },
                            new RetryPolicy(2, ignored -> true, BackoffStrategy.none()),
                            (attempt, ignored) -> Duration.ofMillis(100),
                            () -> {
                                if (controlChecks.incrementAndGet() >= 3) {
                                    throw new RetryCancelledException();
                                }
                            },
                            RetryListener.noop()))
                    .isInstanceOf(RetryCancelledException.class);
        } finally {
            Thread.interrupted();
        }
    }

    private static final class RetryCancelledException extends RuntimeException {}
}
