package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.product.coding.client.CodingSessionClient;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunEventListener;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.RunEventSubscription;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CodingHostSubscriptionsTest {
    @Test
    void previewRedactsCredentialsSplitAcrossSourceFrames() throws Exception {
        var run = new AgentRunId("run-split");
        var session = new AgentSessionId("session-split");
        var publisher = new RecordingPreviewPublisher();
        var subscriptions = new CodingHostSubscriptions(
                eventClient(new AtomicInteger()),
                1,
                8,
                () -> Instant.EPOCH,
                Duration.ofSeconds(30),
                new io.haifa.agent.common.id.UuidV7IdentifierGenerator(),
                publisher);
        String id = subscriptions.subscribePreview("client-split", session, run);
        var attached = subscriptions.attach("client-split", id);
        var tool = new io.haifa.agent.core.tool.ToolCallId("call-split");
        for (String text : java.util.List.of("Bearer ", "synthetic-split-value ")) {
            publisher
                    .receiver
                    .get()
                    .accept(new io.haifa.agent.execution.api.ToolOutputPreview(
                            run, tool, io.haifa.agent.execution.api.ExecutionOutputChannel.STDOUT, text, false, false));
        }
        var first = attached.poll(1, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        var second = attached.poll(0, java.util.concurrent.TimeUnit.SECONDS);
        String visible = first.get("text").toString()
                + (second == null ? "" : second.get("text").toString());
        assertThat(visible).contains("[REDACTED]").doesNotContain("synthetic-split-value");
        subscriptions.closeAll();
    }

    @Test
    void previewOverflowAndDetachCloseOnlyTheirObservation() throws Exception {
        var run = new AgentRunId("run-preview");
        var session = new AgentSessionId("session-preview");
        var publisher = new RecordingPreviewPublisher();
        var closes = publisher.closes;
        var receiver = publisher.receiver;
        var subscriptions = new CodingHostSubscriptions(
                eventClient(new AtomicInteger()),
                1,
                1,
                () -> Instant.EPOCH,
                Duration.ofSeconds(30),
                new io.haifa.agent.common.id.UuidV7IdentifierGenerator(),
                publisher);
        var preview = new io.haifa.agent.execution.api.ToolOutputPreview(
                run,
                new io.haifa.agent.core.tool.ToolCallId("call-preview"),
                io.haifa.agent.execution.api.ExecutionOutputChannel.STDOUT,
                "chunk",
                false,
                false);
        String first = subscriptions.subscribePreview("client-preview", session, run);
        var attached = subscriptions.attach("client-preview", first);
        receiver.get().accept(preview);
        assertThat(attached.poll(1, java.util.concurrent.TimeUnit.SECONDS)).containsEntry("text", "chunk");
        receiver.get().accept(preview);
        receiver.get().accept(preview);
        assertThat(attached.closed()).isTrue();
        assertThat(closes).hasValue(1);
        String second = subscriptions.subscribePreview("client-preview", session, run);
        assertThat(subscriptions.unsubscribe("other-client", second)).isFalse();
        assertThat(subscriptions.unsubscribe("client-preview", second)).isTrue();
        assertThat(closes).hasValue(2);
    }

    @Test
    void overflowDuringSubscribeClosesTheReturnedHandleExactlyOnce() {
        AgentRunId runId = new AgentRunId("run-subscription");
        AgentSessionId sessionId = new AgentSessionId("session-subscription");
        AtomicInteger closes = new AtomicInteger();
        CodingSessionClient client = (CodingSessionClient) Proxy.newProxyInstance(
                CodingSessionClient.class.getClassLoader(),
                new Class<?>[] {CodingSessionClient.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("subscribe")) {
                        AgentRunEventListener listener = (AgentRunEventListener) arguments[2];
                        listener.onEvent(event(runId, sessionId, 1));
                        listener.onEvent(event(runId, sessionId, 2));
                        listener.onEvent(event(runId, sessionId, 3));
                        return subscription(closes);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        CodingHostSubscriptions subscriptions = new CodingHostSubscriptions(client, 4, 2);
        String id = subscriptions.subscribeEvents("client-1", sessionId, runId, RunEventCursor.beforeFirst(runId));

        assertThat(closes).hasValue(1);
        assertThat(subscriptions.unsubscribe("client-1", id)).isFalse();
    }

    @Test
    void closePreventsNewRegistrationsAndOtherClientsCannotUnsubscribe() {
        AgentRunId runId = new AgentRunId("run-subscription-2");
        AgentSessionId sessionId = new AgentSessionId("session-subscription-2");
        AtomicInteger closes = new AtomicInteger();
        CodingSessionClient client = eventClient(closes);
        CodingHostSubscriptions subscriptions = new CodingHostSubscriptions(client, 4, 2);
        String id = subscriptions.subscribeEvents("client-1", sessionId, runId, RunEventCursor.beforeFirst(runId));

        assertThat(subscriptions.unsubscribe("client-2", id)).isFalse();
        assertThat(subscriptions.unsubscribe("client-1", id)).isTrue();
        subscriptions.closeAll();
        assertThatThrownBy(() ->
                        subscriptions.subscribeEvents("client-1", sessionId, runId, RunEventCursor.beforeFirst(runId)))
                .isInstanceOf(CodingHostException.class)
                .hasMessageContaining("shutting down");
    }

    @Test
    void unattachedSubscriptionExpiresAndReleasesCapacity() {
        AgentRunId runId = new AgentRunId("run-expiry");
        AgentSessionId sessionId = new AgentSessionId("session-expiry");
        AtomicInteger closes = new AtomicInteger();
        CodingSessionClient client = eventClient(closes);
        AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
        CodingHostSubscriptions subscriptions = new CodingHostSubscriptions(
                client,
                4,
                2,
                now::get,
                Duration.ofSeconds(1),
                new io.haifa.agent.common.id.UuidV7IdentifierGenerator());
        String id = subscriptions.subscribeEvents("client-1", sessionId, runId, RunEventCursor.beforeFirst(runId));
        now.set(Instant.EPOCH.plusSeconds(2));

        assertThatThrownBy(() -> subscriptions.attach("client-1", id))
                .isInstanceOf(CodingHostException.class)
                .hasMessageContaining("unavailable");
        assertThat(subscriptions.subscribeEvents("client-1", sessionId, runId, RunEventCursor.beforeFirst(runId)))
                .isNotBlank();
    }

    private static RunEventSubscription subscription(AtomicInteger closes) {
        return new RunEventSubscription() {
            @Override
            public boolean closed() {
                return false;
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
    }

    private static final class RecordingPreviewPublisher
            implements io.haifa.agent.execution.api.ToolOutputPreviewPublisher {
        final AtomicInteger closes = new AtomicInteger();
        final AtomicReference<java.util.function.Consumer<io.haifa.agent.execution.api.ToolOutputPreview>> receiver =
                new AtomicReference<>();

        public ToolOutputPreviewSubscription subscribe(
                AgentRunId id, java.util.function.Consumer<io.haifa.agent.execution.api.ToolOutputPreview> consumer) {
            receiver.set(consumer);
            return closes::incrementAndGet;
        }

        public ToolOutputPreviewSink open(AgentRunId id, io.haifa.agent.core.tool.ToolCallId tool) {
            throw new AssertionError("Observation must not open execution output");
        }
    }

    private static CodingSessionClient eventClient(AtomicInteger closes) {
        return (CodingSessionClient) Proxy.newProxyInstance(
                CodingSessionClient.class.getClassLoader(),
                new Class<?>[] {CodingSessionClient.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("subscribe")) return subscription(closes);
                    throw new AssertionError("Unexpected product operation: " + method.getName());
                });
    }

    private static AgentRunEvent event(AgentRunId runId, AgentSessionId sessionId, long sequence) {
        RunEventCursor cursor = new RunEventCursor(runId, "1", OptionalLong.of(sequence));
        return new AgentRunEvent(
                "event-" + sequence,
                "run.lifecycle",
                "1",
                runId,
                sessionId,
                sequence,
                cursor,
                Instant.EPOCH,
                Optional.empty(),
                Optional.empty(),
                new RunEventPayloads.RunLifecycle("RUNNING", sequence, "TEST"));
    }
}
