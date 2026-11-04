package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.id.UuidV7IdentifierGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CodingHostClientsTest {
    @Test
    void authenticatedActivityRenewsTheContextWithInjectedTime() {
        var now = new AtomicReference<>(Instant.EPOCH);
        var clients = new CodingHostClients(new UuidV7IdentifierGenerator(), now::get, Duration.ofMinutes(30));
        String client = clients.initialize();
        now.set(Instant.EPOCH.plusSeconds(1200));
        clients.require(client);
        now.set(Instant.EPOCH.plusSeconds(1801));
        clients.require(client);
        now.set(Instant.EPOCH.plusSeconds(3602));
        assertThatThrownBy(() -> clients.require(client))
                .isInstanceOfSatisfying(CodingHostException.class, failure -> assertThat(failure.reasonCode())
                        .isEqualTo("CLIENT_CONTEXT_REQUIRED"));
    }

    @Test
    void boundsClientsExpiresTransportContextsAndNeverResurrectsAfterClose() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
        AtomicInteger expired = new AtomicInteger();
        CodingHostClients clients = new CodingHostClients(
                new UuidV7IdentifierGenerator(),
                now::get,
                Duration.ofMinutes(1),
                2,
                ignored -> expired.incrementAndGet());

        String first = clients.initialize();
        clients.initialize();
        assertThatThrownBy(clients::initialize)
                .isInstanceOf(CodingHostException.class)
                .satisfies(exception -> assertThat(((CodingHostException) exception).reasonCode())
                        .isEqualTo("HOST_CLIENT_LIMIT"));

        now.set(Instant.EPOCH.plusSeconds(120));
        assertThatThrownBy(() -> clients.require(first))
                .isInstanceOf(CodingHostException.class)
                .satisfies(exception -> assertThat(((CodingHostException) exception).reasonCode())
                        .isEqualTo("CLIENT_CONTEXT_REQUIRED"));
        assertThat(expired).hasValue(2);

        clients.close();
        assertThatThrownBy(clients::initialize)
                .isInstanceOf(CodingHostException.class)
                .satisfies(exception -> assertThat(((CodingHostException) exception).reasonCode())
                        .isEqualTo("HOST_SHUTTING_DOWN"));
    }
}
