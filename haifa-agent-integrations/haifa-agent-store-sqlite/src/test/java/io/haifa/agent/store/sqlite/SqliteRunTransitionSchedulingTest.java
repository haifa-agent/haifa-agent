package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.run.AgentRunResult;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.core.lifecycle.RunAwaiter;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import io.haifa.agent.runtime.core.storage.RuntimeUnitOfWork;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A child JVM is needed to fix the virtual-thread scheduler at one carrier before its first use.
 * One parked SQLite writer and one contending Run transition must both finish on that carrier.
 */
class SqliteRunTransitionSchedulingTest {
    @Test
    void contendingRunTransitionsLetTheSqliteWriterResume(@TempDir Path directory) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String java =
                Path.of(System.getProperty("java.home"), "bin", executable).toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String operation : List.of("started", "completed", "failed")) {
            Process child = new ProcessBuilder(
                            java,
                            "-Xmx128m",
                            "-Djdk.virtualThreadScheduler.parallelism=1",
                            "-Djdk.virtualThreadScheduler.maxPoolSize=1",
                            "-cp",
                            classpath,
                            SqliteRunTransitionSchedulingTest.class.getName(),
                            directory.resolve(operation).toString(),
                            operation)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            try {
                assertThat(child.waitFor(20, TimeUnit.SECONDS))
                        .as("%s writer/transition completes", operation)
                        .isTrue();
                assertThat(child.exitValue())
                        .as("%s authoritative transition result", operation)
                        .isZero();
            } finally {
                if (child.isAlive()) {
                    child.destroyForcibly();
                    child.waitFor(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    public static void main(String[] arguments) throws Exception {
        Path directory = Files.createDirectories(Path.of(arguments[0]));
        String operation = arguments[1];
        SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory);
        var run = SqliteAggregateTestData.prepareRun(foundation);
        var now = SqliteAggregateTestData.NOW;
        if (!operation.equals("started")) {
            run.start(now);
            foundation.runs().save(run, 0);
        }
        var protector =
                new AesGcmModelContinuationProtector(new SecretKeySpec(new byte[32], "AES"), new SecureRandom());
        var ports = foundation.persistencePorts(protector);
        CompletableFuture<Void> writerAcquired = new CompletableFuture<>();
        CompletableFuture<Void> resumeWriter = new CompletableFuture<>();
        CompletableFuture<Void> writerFinished = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                ports.unitOfWork().execute(() -> {
                    writerAcquired.complete(null);
                    resumeWriter.join();
                    return null;
                });
                writerFinished.complete(null);
            } catch (Throwable failure) {
                writerFinished.completeExceptionally(failure);
            }
        });
        writerAcquired.get(5, TimeUnit.SECONDS);
        RuntimeUnitOfWork contender = new RuntimeUnitOfWork() {
            @Override
            public <T> T execute(Supplier<T> work) {
                // Queue the writer's continuation on this same carrier, then contend for its SQLite slot.
                resumeWriter.complete(null);
                return ports.unitOfWork().execute(work);
            }

            @Override
            public void afterCommit(Runnable listener) {
                ports.unitOfWork().afterCommit(listener);
            }
        };
        AtomicInteger ids = new AtomicInteger();
        var transitions = new RunTransitionCoordinator(
                ports.runs(),
                ports.state(),
                ports.events(),
                ports.outbox(),
                () -> "transition-" + ids.incrementAndGet(),
                () -> now,
                new RunAwaiter(),
                contender,
                ports.runInputs());
        var message = new SessionMessageDraft(
                new AgentMessageId("final"),
                run.sessionId(),
                Optional.of(run.id()),
                Optional.empty(),
                MessageRole.ASSISTANT,
                MessageStatus.COMPLETED,
                MessageVisibility.USER_VISIBLE,
                List.of(new TextPart("done", "text/plain")),
                Map.of(),
                now);
        CompletableFuture<AgentRunSnapshot> finished = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                AgentRunSnapshot result =
                        switch (operation) {
                            case "started" -> transitions.started(run);
                            case "completed" ->
                                transitions
                                        .completedWithOutputUnlessInputPending(
                                                run,
                                                new AgentRunResult(
                                                        AgentRunOutcome.SUCCESS,
                                                        "done",
                                                        "result",
                                                        "1.0",
                                                        Map.of(),
                                                        List.of(),
                                                        List.of()),
                                                "done",
                                                message)
                                        .orElseThrow();
                            case "failed" ->
                                transitions.failedWithOutput(
                                        run,
                                        new AgentError(AgentErrorCode.RUNTIME_EXECUTION_FAILED, Map.of(), null, now),
                                        "done",
                                        message);
                            default -> throw new IllegalArgumentException("operation");
                        };
                finished.complete(result);
            } catch (Throwable failure) {
                finished.completeExceptionally(failure);
            }
        });
        AgentRunSnapshot result = finished.get(5, TimeUnit.SECONDS);
        writerFinished.get(5, TimeUnit.SECONDS);
        AgentRunStatus expected =
                switch (operation) {
                    case "started" -> AgentRunStatus.RUNNING;
                    case "completed" -> AgentRunStatus.COMPLETED;
                    default -> AgentRunStatus.FAILED;
                };
        assertThat(result.status()).isEqualTo(expected);
        if (!operation.equals("started")) assertThat(result.output()).contains("done");
        foundation.close();
    }
}
