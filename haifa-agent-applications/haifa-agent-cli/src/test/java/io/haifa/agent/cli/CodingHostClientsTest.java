package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.id.UuidV7IdentifierGenerator;
import io.haifa.coding.host.internal.CodingHostClients;
import io.haifa.coding.host.internal.CodingHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CodingHostClientsTest {
    @Test
    void observationChecksDoNotRenewAnExpiredLogicalClient() {
        var now = new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.EPOCH);
        var expired = new java.util.ArrayList<String>();
        var clients = new CodingHostClients(
                new io.haifa.agent.common.id.UuidV7IdentifierGenerator(),
                now::get,
                java.time.Duration.ofMinutes(1),
                4,
                expired::add);
        String id = clients.initialize();
        now.set(java.time.Instant.EPOCH.plusSeconds(59));
        clients.requireObservation(id);
        now.set(java.time.Instant.EPOCH.plusSeconds(61));
        assertThatThrownBy(() -> clients.requireObservation(id)).isInstanceOf(CodingHostException.class);
        assertThat(expired).containsExactly(id);
    }

    @Test
    void optionalPreviewIsBoundToTheLogicalClientAndDoesNotTransferOnRenewal() {
        var now = new AtomicReference<>(Instant.EPOCH);
        var clients = new CodingHostClients(new UuidV7IdentifierGenerator(), now::get, Duration.ofMinutes(1));
        String enabled = clients.initialize(java.util.Set.of("preview-v1"));
        String legacy = clients.initialize();
        clients.require(enabled);
        clients.requireOptional(enabled, "preview-v1");
        assertThatThrownBy(() -> clients.requireOptional(legacy, "preview-v1")).isInstanceOf(CodingHostException.class);
        now.set(Instant.EPOCH.plusSeconds(61));
        assertThatThrownBy(() -> clients.requireOptional(enabled, "preview-v1"))
                .isInstanceOf(CodingHostException.class);
        String renewed = clients.initialize();
        assertThatThrownBy(() -> clients.requireOptional(renewed, "preview-v1"))
                .isInstanceOf(CodingHostException.class);
    }

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
