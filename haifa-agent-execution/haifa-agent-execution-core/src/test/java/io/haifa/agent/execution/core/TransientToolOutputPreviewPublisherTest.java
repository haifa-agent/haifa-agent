package io.haifa.agent.execution.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ProcessOutputChunk;
import io.haifa.agent.execution.api.ToolOutputPreview;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TransientToolOutputPreviewPublisherTest {
    private final AgentRunId run = new AgentRunId("run");
    private final ToolCallId tool = new ToolCallId("call");

    @Test
    void batchesOutputWithoutReplayAndKeepsChannelsSeparate() {
        var publisher = new TransientToolOutputPreviewPublisher();
        List<ToolOutputPreview> received = new CopyOnWriteArrayList<>();
        try (var subscription = publisher.subscribe(run, received::add)) {
            try (var sink = publisher.open(run, tool)) {
                sink.onOutput(chunk(ExecutionOutputChannel.STDOUT, "hello", false));
                sink.onOutput(chunk(ExecutionOutputChannel.STDOUT, " world", false));
                sink.onOutput(chunk(ExecutionOutputChannel.STDERR, "error", false));
            }
            awaitSize(received, 2);
        }

        assertThat(received)
                .extracting(ToolOutputPreview::channel)
                .containsExactly(ExecutionOutputChannel.STDOUT, ExecutionOutputChannel.STDERR);
        assertThat(received).extracting(ToolOutputPreview::text).containsExactly("hello world", "error");

        List<ToolOutputPreview> late = new CopyOnWriteArrayList<>();
        try (var ignored = publisher.subscribe(run, late::add)) {
            assertThat(late).isEmpty();
        }
    }

    @Test
    void boundsPreviewBatchesAndPreservesTheTail() {
        var publisher = new TransientToolOutputPreviewPublisher();
        List<ToolOutputPreview> received = new CopyOnWriteArrayList<>();
        String output = "x".repeat(TransientToolOutputPreviewPublisher.MAX_BATCH_BYTES + 100);
        try (var subscription = publisher.subscribe(run, received::add)) {
            try (var sink = publisher.open(run, tool)) {
                sink.onOutput(chunk(ExecutionOutputChannel.STDOUT, output, false));
            }
            awaitNotEmpty(received);
        }

        assertThat(received.getLast().text()).endsWith("x".repeat(100));
        assertThat(received).allSatisfy(preview -> assertThat(preview.text().getBytes(StandardCharsets.UTF_8))
                .hasSizeLessThanOrEqualTo(TransientToolOutputPreviewPublisher.MAX_BATCH_BYTES));
    }

    @Test
    void flushesOneLineOnTheTimerBeforeTheToolCompletes() {
        var publisher = new TransientToolOutputPreviewPublisher();
        List<ToolOutputPreview> received = new CopyOnWriteArrayList<>();
        try (var subscription = publisher.subscribe(run, received::add)) {
            try (var sink = publisher.open(run, tool)) {
                sink.onOutput(chunk(ExecutionOutputChannel.STDOUT, "first line\n", false));
                awaitNotEmpty(received);
                assertThat(received.getFirst().text()).isEqualTo("first line\n");
            }
        }
    }

    @Test
    void keepsUtf8CharactersSplitAcrossChunksIntact() {
        var publisher = new TransientToolOutputPreviewPublisher();
        List<ToolOutputPreview> received = new CopyOnWriteArrayList<>();
        byte[] value = "你好\r\n".getBytes(StandardCharsets.UTF_8);
        try (var subscription = publisher.subscribe(run, received::add)) {
            try (var sink = publisher.open(run, tool)) {
                sink.onOutput(new ProcessOutputChunk(
                        ExecutionOutputChannel.STDOUT, java.util.Arrays.copyOfRange(value, 0, 2), false, false));
                sink.onOutput(new ProcessOutputChunk(
                        ExecutionOutputChannel.STDOUT,
                        java.util.Arrays.copyOfRange(value, 2, value.length),
                        true,
                        false));
            }
            awaitNotEmpty(received);
        }

        assertThat(received.stream().map(ToolOutputPreview::text).reduce("", String::concat))
                .isEqualTo("你好\r\n");
    }

    @Test
    void removingOneSubscriberKeepsTheOtherRegistered() {
        var publisher = new TransientToolOutputPreviewPublisher();
        List<ToolOutputPreview> first = new CopyOnWriteArrayList<>();
        List<ToolOutputPreview> second = new CopyOnWriteArrayList<>();
        var firstSubscription = publisher.subscribe(run, first::add);
        try (var secondSubscription = publisher.subscribe(run, second::add)) {
            firstSubscription.close();
            try (var sink = publisher.open(run, tool)) {
                sink.onOutput(chunk(ExecutionOutputChannel.STDOUT, "still visible", true));
            }
            awaitNotEmpty(second);
        }

        assertThat(first).isEmpty();
        assertThat(second).extracting(ToolOutputPreview::text).containsExactly("still visible");
    }

    @Test
    void slowConsumerDoesNotBlockProducerAndLosesOnlyPreviewData() throws Exception {
        var publisher = new TransientToolOutputPreviewPublisher();
        CountDownLatch consumerEntered = new CountDownLatch(1);
        CountDownLatch releaseConsumer = new CountDownLatch(1);
        List<ToolOutputPreview> received = new CopyOnWriteArrayList<>();
        try (var subscription = publisher.subscribe(run, preview -> {
                    consumerEntered.countDown();
                    try {
                        releaseConsumer.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    received.add(preview);
                });
                var sink = publisher.open(run, tool)) {
            sink.onOutput(chunk(ExecutionOutputChannel.STDOUT, "first", true));
            assertThat(consumerEntered.await(1, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            for (int index = 0; index < 100; index++) {
                sink.onOutput(chunk(ExecutionOutputChannel.STDOUT, "line-" + index, true));
            }
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(200));
            releaseConsumer.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((received.isEmpty() || !received.getLast().text().equals("line-99")) && System.nanoTime() < deadline)
                Thread.sleep(10);
        }

        awaitSize(received, 2);
        assertThat(received.getLast().text()).isEqualTo("line-99");
        assertThat(received).anyMatch(ToolOutputPreview::previewDropped);
        assertThat(received).noneMatch(ToolOutputPreview::outputTruncated);
    }

    @Test
    void attributesDroppedPreviewsOnlyToTheAffectedToolAndChannel() throws Exception {
        var publisher = new TransientToolOutputPreviewPublisher();
        ToolCallId secondTool = new ToolCallId("call-2");
        CountDownLatch consumerEntered = new CountDownLatch(1);
        CountDownLatch releaseConsumer = new CountDownLatch(1);
        List<ToolOutputPreview> received = new CopyOnWriteArrayList<>();
        try (var subscription = publisher.subscribe(run, preview -> {
                    consumerEntered.countDown();
                    try {
                        releaseConsumer.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    received.add(preview);
                });
                var firstSink = publisher.open(run, tool);
                var secondSink = publisher.open(run, secondTool)) {
            firstSink.onOutput(chunk(ExecutionOutputChannel.STDOUT, "blocker", true));
            assertThat(consumerEntered.await(1, TimeUnit.SECONDS)).isTrue();
            for (int index = 0; index < 10; index++) {
                firstSink.onOutput(chunk(ExecutionOutputChannel.STDOUT, "first-" + index, true));
            }
            secondSink.onOutput(chunk(ExecutionOutputChannel.STDOUT, "second", true));
            releaseConsumer.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (received.stream().noneMatch(value -> value.toolCallId().equals(secondTool))
                    && System.nanoTime() < deadline) Thread.sleep(10);
        }

        assertThat(received)
                .filteredOn(value -> value.toolCallId().equals(secondTool))
                .singleElement()
                .satisfies(value -> assertThat(value.previewDropped()).isFalse());
        assertThat(received)
                .filteredOn(value -> value.toolCallId().equals(tool))
                .anyMatch(ToolOutputPreview::previewDropped);
    }

    @Test
    void reportsSourceOutputTruncationSeparatelyFromPreviewDrops() {
        var publisher = new TransientToolOutputPreviewPublisher();
        List<ToolOutputPreview> received = new CopyOnWriteArrayList<>();
        try (var subscription = publisher.subscribe(run, received::add);
                var sink = publisher.open(run, tool)) {
            sink.onOutput(chunk(ExecutionOutputChannel.STDOUT, "visible", false));
            sink.onOutput(new ProcessOutputChunk(ExecutionOutputChannel.STDERR, new byte[0], true, true));
            awaitSize(received, 2);
        }

        assertThat(received.getFirst().channel()).isEqualTo(ExecutionOutputChannel.STDOUT);
        assertThat(received.getFirst().outputTruncated()).isFalse();
        assertThat(received.getLast()).satisfies(preview -> {
            assertThat(preview.channel()).isEqualTo(ExecutionOutputChannel.STDERR);
            assertThat(preview.text()).isEmpty();
            assertThat(preview.outputTruncated()).isTrue();
            assertThat(preview.previewDropped()).isFalse();
        });
    }

    @Test
    void consumerFailureDoesNotStopOtherSubscribers() {
        var publisher = new TransientToolOutputPreviewPublisher();
        List<ToolOutputPreview> received = new CopyOnWriteArrayList<>();
        try (var ignored = publisher.subscribe(run, value -> {
                    throw new IllegalStateException("presentation failed");
                });
                var second = publisher.subscribe(run, received::add)) {
            try (var sink = publisher.open(run, tool)) {
                sink.onOutput(chunk(ExecutionOutputChannel.STDOUT, "visible", true));
            }
            awaitNotEmpty(received);
        }

        assertThat(received).extracting(ToolOutputPreview::text).containsExactly("visible");
    }

    @Test
    void keepsConcurrentToolOutputAssociatedWithItsOwnToolCall() throws Exception {
        var publisher = new TransientToolOutputPreviewPublisher();
        ToolCallId secondTool = new ToolCallId("call-2");
        List<ToolOutputPreview> received = new CopyOnWriteArrayList<>();
        try (var subscription = publisher.subscribe(run, received::add);
                var firstSink = publisher.open(run, tool);
                var secondSink = publisher.open(run, secondTool)) {
            Thread first = Thread.ofVirtual()
                    .start(() -> firstSink.onOutput(chunk(ExecutionOutputChannel.STDOUT, "first", true)));
            Thread second = Thread.ofVirtual()
                    .start(() -> secondSink.onOutput(chunk(ExecutionOutputChannel.STDOUT, "second", true)));
            first.join();
            second.join();
            awaitSize(received, 2);
        }

        assertThat(received)
                .extracting(preview -> preview.toolCallId().value() + ":" + preview.text())
                .containsExactlyInAnyOrder("call:first", "call-2:second");
    }

    private static ProcessOutputChunk chunk(ExecutionOutputChannel channel, String text, boolean end) {
        return new ProcessOutputChunk(channel, text.getBytes(StandardCharsets.UTF_8), end, false);
    }

    private static void awaitNotEmpty(List<?> values) {
        awaitSize(values, 1);
    }

    private static void awaitSize(List<?> values, int minimum) {
        org.assertj.core.api.Assertions.assertThatCode(() -> {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (values.size() < minimum && System.nanoTime() < deadline) Thread.sleep(10);
                    assertThat(new ArrayList<>(values)).hasSizeGreaterThanOrEqualTo(minimum);
                })
                .doesNotThrowAnyException();
    }
}
