package io.haifa.agent.execution.core;

import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ProcessOutputChunk;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps presentation latency and failures out of the process pipe readers. */
final class BoundedAsyncExecutionOutputObserver implements ExecutionOutputObserver, AutoCloseable {
    private static final int QUEUE_CAPACITY = 256;

    private final ExecutionOutputObserver delegate;
    private final ArrayBlockingQueue<ProcessOutputChunk> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean dropped = new AtomicBoolean();
    private final Thread worker;

    BoundedAsyncExecutionOutputObserver(ExecutionOutputObserver delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        worker = Thread.ofVirtual().name("haifa-execution-output").start(this::dispatch);
    }

    @Override
    public void onOutput(ProcessOutputChunk chunk) {
        if (!accepting.get()) return;
        if (!queue.offer(chunk)) dropped.set(true);
    }

    @Override
    public void close() {
        accepting.set(false);
        try {
            worker.join(Duration.ofSeconds(2));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) worker.interrupt();
    }

    private void dispatch() {
        EnumSet<ExecutionOutputChannel> ended = EnumSet.noneOf(ExecutionOutputChannel.class);
        try {
            while (accepting.get() || !queue.isEmpty()) {
                ProcessOutputChunk chunk = queue.poll(25, TimeUnit.MILLISECONDS);
                if (chunk == null) continue;
                boolean truncated = chunk.truncated() || dropped.getAndSet(false);
                notifyDelegate(new ProcessOutputChunk(chunk.channel(), chunk.bytes(), chunk.endOfStream(), truncated));
                if (chunk.endOfStream()) ended.add(chunk.channel());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            dropped.set(true);
        } finally {
            for (ExecutionOutputChannel channel : ExecutionOutputChannel.values()) {
                if (!ended.contains(channel)) {
                    notifyDelegate(new ProcessOutputChunk(channel, new byte[0], true, true));
                }
            }
        }
    }

    private void notifyDelegate(ProcessOutputChunk chunk) {
        try {
            delegate.onOutput(chunk);
        } catch (RuntimeException ignored) {
            // Streaming output is presentation-only; ExecutionResult remains authoritative.
        }
    }
}
