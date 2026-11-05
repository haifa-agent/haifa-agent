package io.haifa.agent.application.project.product.coding;

import static org.assertj.core.api.Assertions.*;

import io.haifa.agent.core.session.AgentSessionId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CodingSessionChangesTest {
    @Test
    void isolatesScopesAndOwnershipAndNeverRejectsCommandsBecauseAnObserverFailed() {
        var source = new CodingSessionChanges();
        var session = new AgentSessionId("session");
        var other = new AgentSessionId("other");
        var calls = new AtomicInteger();
        Runnable listener = calls::incrementAndGet;
        var first = source.observe(session, listener);
        var second = source.observe(session, listener);
        var broken = source.observe(session, () -> {
            throw new IllegalStateException("observer failed");
        });
        source.changed(other);
        assertThat(calls).hasValue(0);
        source.changed(session);
        assertThat(calls).hasValue(2);
        first.close();
        first.close();
        source.changed(session);
        assertThat(calls).hasValue(3);
        second.close();
        broken.close();
        source.changed(session);
        assertThat(calls).hasValue(3);
    }
}
