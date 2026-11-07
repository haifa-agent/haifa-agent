package io.haifa.coding.host.internal;

import static org.assertj.core.api.Assertions.*;

import io.haifa.coding.host.BrowserOptions;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class CodingBrowserAccessTest {
    private static final String ORIGIN = "http://127.0.0.1:17861";

    @Test
    void boundedChallengesExpireAndExhaustAttemptsWithoutBecomingReusable() {
        try (var f = new Fixture("host-one")) {
            var first = f.access.challenge();
            f.access.challenge();
            assertCode(() -> f.access.challenge(), "PAIRING_LIMIT");
            String bad = first.value().substring(0, first.value().indexOf('.') + 1) + "wrong-proof";
            assertCode(() -> f.access.exchange(bad, "host-one", ORIGIN), "PAIRING_REJECTED");
            assertCode(() -> f.access.exchange(bad, "host-one", ORIGIN), "PAIRING_REJECTED");
            assertCode(() -> f.access.exchange(first.value(), "host-one", ORIGIN), "PAIRING_REJECTED");
            var fresh = f.access.challenge();
            f.clock.millis.set(2000);
            assertCode(() -> f.access.exchange(fresh.value(), "host-one", ORIGIN), "PAIRING_REJECTED");
        }
    }

    @Test
    void pairingValidatesOriginAndIncarnationAndConcurrentExchangeSucceedsOnce() throws Exception {
        try (var f = new Fixture("host-one");
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var challenge = f.access.challenge();
            assertCode(() -> f.access.exchange(challenge.value(), "host-one", null), "BROWSER_ORIGIN_REJECTED");
            assertCode(() -> f.access.exchange(challenge.value(), "host-one", "null"), "BROWSER_ORIGIN_REJECTED");
            assertCode(
                    () -> f.access.exchange(challenge.value(), "host-one", "http://foreign.example"),
                    "BROWSER_ORIGIN_REJECTED");
            var start = new CountDownLatch(1);
            var accepted = new AtomicInteger();
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++)
                tasks.add(workers.submit(() -> {
                    start.await();
                    try {
                        f.access.exchange(challenge.value(), "host-one", ORIGIN);
                        accepted.incrementAndGet();
                    } catch (CodingHostException rejected) {
                        assertThat(rejected.reasonCode()).isEqualTo("PAIRING_REJECTED");
                    }
                    return null;
                }));
            start.countDown();
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
            assertThat(accepted).hasValue(1);
            var next = f.access.challenge();
            assertCode(() -> f.access.exchange(next.value(), "old-host", ORIGIN), "PAIRING_REJECTED");
        }
    }

    @Test
    void grantDoesNotPermitClientSelectionOrMissingProofAndExpiryClosesOwnedResources() throws Exception {
        try (var f = new Fixture("host-one")) {
            var a = f.pair();
            var b = f.pair();
            var context = f.access.admit(a.grant(), null, a.clientId());
            assertCode(() -> f.access.admit(null, null, a.clientId()), "BROWSER_AUTH_REQUIRED");
            assertCode(() -> f.access.admit("wrong-proof", null, a.clientId()), "BROWSER_AUTH_REQUIRED");
            assertCode(() -> f.access.admit(a.grant(), null, b.clientId()), "BROWSER_AUTH_REQUIRED");
            assertCode(() -> f.access.admit(a.grant(), "null", a.clientId()), "BROWSER_ORIGIN_REJECTED");
            var closed = new AtomicInteger();
            context.own("view", closed::incrementAndGet);
            context.own("sse", closed::incrementAndGet);
            f.clock.millis.set(1000);
            context.renew();
            f.clock.millis.set(5000);
            f.access.expire();
            assertThat(closed).hasValue(2);
            assertCode(() -> f.access.admit(a.grant(), null, null), "BROWSER_AUTH_REQUIRED");
            assertCode(context::renew, "BROWSER_AUTH_EXPIRED");
            assertThat(f.closedClients).hasValue(2);
        }
    }

    @Test
    void revocationSerializesAdmissionButCannotRetractAnAlreadyAcceptedCommand() {
        try (var f = new Fixture("host-one")) {
            var pair = f.pair();
            var admitted = f.access.admit(pair.grant(), ORIGIN, null);
            admitted.negotiate(Set.of("coding-product-v1"));
            admitted.requireCapability("coding-product-v1");
            var sideEffects = new AtomicInteger();
            Runnable accepted = sideEffects::incrementAndGet;
            f.access.revoke(pair.clientId());
            accepted.run();
            assertThat(sideEffects).hasValue(1);
            assertCode(() -> f.access.admit(pair.grant(), ORIGIN, null), "BROWSER_AUTH_REQUIRED");
            assertThat(sideEffects).hasValue(1);
            var closed = new AtomicInteger();
            assertCode(() -> admitted.own("late-view", closed::incrementAndGet), "BROWSER_OBSERVATION_UNAVAILABLE");
            assertThat(closed).hasValue(1);
        }
    }

    @Test
    void restartAndAbsoluteGrantDeadlineCannotBeRenewedIntoAnotherIdentity() {
        try (var old = new Fixture("old-host");
                var restarted = new Fixture("new-host")) {
            var previous = old.pair();
            restarted.pair();
            assertCode(() -> restarted.access.admit(previous.grant(), null, null), "BROWSER_AUTH_REQUIRED");
            var context = old.access.admit(previous.grant(), null, null);
            old.clock.millis.set(4500);
            context.renew();
            old.clock.millis.set(5000);
            assertCode(context::renew, "BROWSER_AUTH_EXPIRED");
            old.access.expire();
            var next = old.pair();
            assertThat(next.clientId().equals(previous.clientId())).isFalse();
        }
    }

    private static void assertCode(Runnable action, String reason) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(CodingHostException.class, e -> assertThat(e.reasonCode())
                        .isEqualTo(reason));
    }

    private static final class TestClock extends Clock {
        final AtomicLong millis = new AtomicLong();

        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        public long millis() {
            return millis.get();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final TestClock clock = new TestClock();
        final AtomicInteger tokens = new AtomicInteger(),
                ids = new AtomicInteger(),
                closedClients = new AtomicInteger();
        final CodingBrowserAccess access;
        final String incarnation;

        Fixture(String incarnation) {
            this.incarnation = incarnation;
            access = new CodingBrowserAccess(
                    new BrowserOptions(
                            Path.of("assets"),
                            "index.html",
                            Duration.ofSeconds(2),
                            Duration.ofSeconds(5),
                            Duration.ofSeconds(5),
                            2,
                            2),
                    incarnation,
                    ORIGIN,
                    () -> "client-" + ids.incrementAndGet(),
                    id -> closedClients.incrementAndGet(),
                    clock,
                    () -> "synthetic-proof-" + tokens.incrementAndGet());
        }

        io.haifa.coding.api.CodingProtocol.BrowserGrant pair() {
            var proof = access.challenge();
            return access.exchange(proof.value(), incarnation, ORIGIN);
        }

        public void close() {
            access.close();
        }
    }
}
