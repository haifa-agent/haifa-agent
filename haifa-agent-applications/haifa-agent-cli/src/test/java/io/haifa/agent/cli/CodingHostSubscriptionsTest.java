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
        CodingSessionClient client = (CodingSessionClient) Proxy.newProxyInstance(
                CodingSessionClient.class.getClassLoader(),
                new Class<?>[] {CodingSessionClient.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("subscribe")) return subscription(closes);
                    throw new UnsupportedOperationException(method.getName());
                });
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
        CodingSessionClient client = (CodingSessionClient) Proxy.newProxyInstance(
                CodingSessionClient.class.getClassLoader(),
                new Class<?>[] {CodingSessionClient.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("subscribe")) return subscription(closes);
                    throw new UnsupportedOperationException(method.getName());
                });
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
