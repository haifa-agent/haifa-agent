package io.haifa.agent.execution.core;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ProcessOutputChunk;
import io.haifa.agent.execution.api.ToolOutputPreview;
import io.haifa.agent.execution.api.ToolOutputPreviewPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A small process-local multicast publisher. Producers only batch and enqueue best-effort previews;
 * bounded asynchronous subscriber slots isolate execution output from presentation consumers.
 */
public final class TransientToolOutputPreviewPublisher implements ToolOutputPreviewPublisher {
    public static final int MAX_BATCH_BYTES = 4 * 1024;
    static final Duration FLUSH_INTERVAL = Duration.ofMillis(100);
    private static final int SUBSCRIBER_QUEUE_CAPACITY = 8;
    private static final ScheduledExecutorService FLUSH_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "haifa-tool-preview-flush");
                thread.setDaemon(true);
                return thread;
            });
    private final ConcurrentHashMap<AgentRunId, CopyOnWriteArrayList<Subscription>> subscribers =
            new ConcurrentHashMap<>();

    @Override
    public ToolOutputPreviewSubscription subscribe(AgentRunId runId, Consumer<ToolOutputPreview> consumer) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(consumer, "consumer must not be null");
        Subscription subscription = new Subscription(consumer);
        subscribers.compute(runId, (ignored, registered) -> {
            CopyOnWriteArrayList<Subscription> current = registered == null ? new CopyOnWriteArrayList<>() : registered;
            current.add(subscription);
            return current;
        });
        return () -> unsubscribe(runId, subscription);
    }

    @Override
    public ToolOutputPreviewSink open(AgentRunId runId, ToolCallId toolCallId) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(toolCallId, "toolCallId must not be null");
        return new Sink(runId, toolCallId);
    }

    private void unsubscribe(AgentRunId runId, Subscription subscription) {
        subscription.close();
        subscribers.computeIfPresent(runId, (ignored, registered) -> {
            registered.remove(subscription);
            return registered.isEmpty() ? null : registered;
        });
    }

    private void publish(ToolOutputPreview preview) {
        CopyOnWriteArrayList<Subscription> registered = subscribers.get(preview.runId());
        if (registered == null) return;
        for (Subscription subscription : registered) subscription.offer(preview);
    }

    private final class Sink implements ToolOutputPreviewSink {
        private final AgentRunId runId;
        private final ToolCallId toolCallId;
        private final EnumMap<ExecutionOutputChannel, Utf8Decoder> decoders =
                new EnumMap<>(ExecutionOutputChannel.class);
        private final StringBuilder pending = new StringBuilder();
        private int pendingBytes;
        private ExecutionOutputChannel pendingChannel;
        private boolean pendingOutputTruncated;
        private ScheduledFuture<?> scheduledFlush;
        private long flushGeneration;
        private boolean closed;

        private Sink(AgentRunId runId, ToolCallId toolCallId) {
            this.runId = runId;
            this.toolCallId = toolCallId;
        }

        @Override
        public synchronized void onOutput(ProcessOutputChunk chunk) {
            if (closed) return;
            Objects.requireNonNull(chunk, "chunk must not be null");
            Utf8Decoder decoder = decoders.computeIfAbsent(chunk.channel(), ignored -> new Utf8Decoder());
            append(chunk.channel(), decoder.decode(chunk.bytes(), chunk.endOfStream()), chunk.truncated());
            if (chunk.endOfStream()) flush();
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            cancelScheduledFlush();
            for (var entry : decoders.entrySet()) {
                append(entry.getKey(), entry.getValue().finish(), false);
            }
            flush();
        }

        private void append(ExecutionOutputChannel channel, String text, boolean truncated) {
            if (pendingChannel != null && pendingChannel != channel) flush();
            if (text.isEmpty()) {
                if (truncated && pendingChannel == null) pendingChannel = channel;
                pendingOutputTruncated |= truncated;
                return;
            }
            pendingOutputTruncated |= truncated;
            for (int offset = 0; offset < text.length(); ) {
                int codePoint = text.codePointAt(offset);
                int width = utf8Width(codePoint);
                if (pendingBytes + width > MAX_BATCH_BYTES) flush();
                if (pendingChannel == null) pendingChannel = channel;
                pending.appendCodePoint(codePoint);
                pendingBytes += width;
                offset += Character.charCount(codePoint);
            }
            scheduleFlush();
        }

        private void flush() {
            cancelScheduledFlush();
            if (pending.length() == 0 && !pendingOutputTruncated) return;
            String text = pending.toString();
            pending.setLength(0);
            pendingBytes = 0;
            ExecutionOutputChannel outputChannel = pendingChannel;
            pendingChannel = null;
            boolean outputTruncated = pendingOutputTruncated;
            pendingOutputTruncated = false;
            publish(new ToolOutputPreview(runId, toolCallId, outputChannel, text, outputTruncated, false));
        }

        private void scheduleFlush() {
            if (scheduledFlush != null || pending.length() == 0 || closed) return;
            long generation = ++flushGeneration;
            scheduledFlush = FLUSH_EXECUTOR.schedule(
                    () -> flushFromTimer(generation), FLUSH_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void flushFromTimer(long generation) {
            synchronized (this) {
                if (generation != flushGeneration) return;
                scheduledFlush = null;
                if (!closed) flush();
            }
        }

        private void cancelScheduledFlush() {
            if (scheduledFlush == null) return;
            flushGeneration++;
            scheduledFlush.cancel(false);
            scheduledFlush = null;
        }
    }

    private static final class Subscription implements ToolOutputPreviewSubscription {
        private final Consumer<ToolOutputPreview> consumer;
        private final ArrayDeque<ToolOutputPreview> pending = new ArrayDeque<>(SUBSCRIBER_QUEUE_CAPACITY);
        private final LinkedHashSet<PreviewKey> droppedKeys = new LinkedHashSet<>();
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final AtomicBoolean active = new AtomicBoolean(true);

        private Subscription(Consumer<ToolOutputPreview> consumer) {
            this.consumer = consumer;
        }

        private void offer(ToolOutputPreview preview) {
            if (!active.get()) return;
            synchronized (pending) {
                if (!active.get()) return;
                if (pending.size() == SUBSCRIBER_QUEUE_CAPACITY) {
                    markDropped(pending.removeFirst());
                }
                pending.addLast(preview);
            }
            schedule();
        }

        private void schedule() {
            if (!active.get() || !scheduled.compareAndSet(false, true)) return;
            Thread.ofVirtual().name("haifa-tool-preview-dispatch").start(this::drain);
        }

        private void drain() {
            try {
                while (active.get()) {
                    ToolOutputPreview preview;
                    boolean previewDropped;
                    synchronized (pending) {
                        preview = pending.pollFirst();
                        previewDropped = preview != null && droppedKeys.remove(PreviewKey.from(preview));
                    }
                    if (preview == null) return;
                    if (previewDropped && !preview.previewDropped()) {
                        preview = new ToolOutputPreview(
                                preview.runId(),
                                preview.toolCallId(),
                                preview.channel(),
                                preview.text(),
                                preview.outputTruncated(),
                                true);
                    }
                    try {
                        consumer.accept(preview);
                    } catch (RuntimeException ignored) {
                        // Presentation failures must never affect command execution.
                    }
                }
            } finally {
                scheduled.set(false);
                synchronized (pending) {
                    if (active.get() && !pending.isEmpty()) schedule();
                }
            }
        }

        @Override
        public void close() {
            active.set(false);
            synchronized (pending) {
                pending.clear();
                droppedKeys.clear();
            }
        }

        private void markDropped(ToolOutputPreview preview) {
            PreviewKey key = PreviewKey.from(preview);
            if (droppedKeys.contains(key)) return;
            if (droppedKeys.size() == SUBSCRIBER_QUEUE_CAPACITY) {
                droppedKeys.remove(droppedKeys.iterator().next());
            }
            droppedKeys.add(key);
        }
    }

    private record PreviewKey(ToolCallId toolCallId, ExecutionOutputChannel channel) {
        private static PreviewKey from(ToolOutputPreview preview) {
            return new PreviewKey(preview.toolCallId(), preview.channel());
        }
    }

    private static final class Utf8Decoder {
        private byte[] carry = new byte[0];

        private String decode(byte[] bytes, boolean endOfStream) {
            byte[] combined = new byte[carry.length + bytes.length];
            System.arraycopy(carry, 0, combined, 0, carry.length);
            System.arraycopy(bytes, 0, combined, carry.length, bytes.length);
            int safeEnd = endOfStream ? combined.length : completeUtf8Prefix(combined);
            carry = Arrays.copyOfRange(combined, safeEnd, combined.length);
            return new String(combined, 0, safeEnd, StandardCharsets.UTF_8);
        }

        private String finish() {
            String value = new String(carry, StandardCharsets.UTF_8);
            carry = new byte[0];
            return value;
        }
    }

    private static int completeUtf8Prefix(byte[] bytes) {
        int continuationBytes = 0;
        int lead = bytes.length - 1;
        while (lead >= 0 && continuationBytes < 3 && (bytes[lead] & 0xC0) == 0x80) {
            continuationBytes++;
            lead--;
        }
        if (lead < 0) return bytes.length;
        int expectedWidth = utf8SequenceWidth(bytes[lead] & 0xFF);
        return expectedWidth > continuationBytes + 1 ? lead : bytes.length;
    }

    private static int utf8SequenceWidth(int unsignedByte) {
        if ((unsignedByte & 0x80) == 0) return 1;
        if ((unsignedByte & 0xE0) == 0xC0) return 2;
        if ((unsignedByte & 0xF0) == 0xE0) return 3;
        if ((unsignedByte & 0xF8) == 0xF0) return 4;
        return 1;
    }

    private static int utf8Width(int value) {
        if (value <= 0x7F) return 1;
        if (value <= 0x7FF) return 2;
        if (value <= 0xFFFF) return 3;
        return 4;
    }
}
