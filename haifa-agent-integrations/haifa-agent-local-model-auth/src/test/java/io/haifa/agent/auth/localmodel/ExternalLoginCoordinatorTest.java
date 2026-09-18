package io.haifa.agent.auth.localmodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExternalLoginCoordinatorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC);

    @Test
    void completesStoresAndClosesExactlyOnce() throws Exception {
        InMemoryStore store = new InMemoryStore();
        FakeMethod method = new FakeMethod("future-login", false);
        try (ExternalLoginCoordinator coordinator = coordinator(method, store)) {
            ExternalLoginAttemptSnapshot started =
                    coordinator.start(method.descriptor().methodId(), ExternalLoginMode.BROWSER);
            ExternalLoginAttemptSnapshot completed = awaitTerminal(coordinator, started.attemptId());

            assertThat(completed.state()).isEqualTo(ExternalLoginAttemptState.SUCCEEDED);
            assertThat(coordinator.completedConnection(started.attemptId())).isPresent();
            assertThat(store.values).hasSize(1);
            assertThat(method.closedLatch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(method.closed).hasValue(1);
        }
        assertThat(method.closed).hasValue(1);
    }

    @Test
    void enforcesSingleActiveAttemptAndCancelsWithOneClose() throws Exception {
        InMemoryStore store = new InMemoryStore();
        FakeMethod method = new FakeMethod("future-login", true);
        try (ExternalLoginCoordinator coordinator = coordinator(method, store)) {
            ExternalLoginAttemptSnapshot started =
                    coordinator.start(method.descriptor().methodId(), ExternalLoginMode.BROWSER);
            assertThat(method.entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> coordinator.start(method.descriptor().methodId(), ExternalLoginMode.BROWSER))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("AUTH_LOGIN_IN_PROGRESS");

            assertThat(coordinator.cancel(started.attemptId())).isTrue();
            assertThat(coordinator.find(started.attemptId()).state()).isEqualTo(ExternalLoginAttemptState.CANCELLED);
            assertThat(method.closed).hasValue(1);
        }
    }

    @Test
    void driverFailureIsProjectedWithoutSecretText() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ExternalLoginMethod failing = new FakeMethod("future-login", false) {
            @Override
            StoredExternalCredential execute(ExternalLoginOperationContext context) {
                throw new IllegalStateException("provider secret-token-canary");
            }
        };
        try (ExternalLoginCoordinator coordinator = coordinator(failing, store)) {
            ExternalLoginAttemptSnapshot started =
                    coordinator.start(failing.descriptor().methodId(), ExternalLoginMode.BROWSER);
            ExternalLoginAttemptSnapshot completed = awaitTerminal(coordinator, started.attemptId());

            assertThat(completed.state()).isEqualTo(ExternalLoginAttemptState.FAILED);
            assertThat(completed.toString()).doesNotContain("secret-token-canary");
            assertThat(completed.reasonCode()).contains("AUTH_LOGIN_FAILED");
        }
    }

    @Test
    void exposesCredentialPersistenceAndProjectsStoreFailuresWithAStableReason() throws Exception {
        CountDownLatch storeEntered = new CountDownLatch(1);
        CountDownLatch releaseStore = new CountDownLatch(1);
        InMemoryStore store = new InMemoryStore() {
            @Override
            public synchronized void save(StoredModelCredential credential) {
                storeEntered.countDown();
                try {
                    releaseStore.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted secret detail", exception);
                }
                throw new IllegalStateException("filesystem secret detail");
            }
        };
        FakeMethod method = new FakeMethod("future-login", false);
        try (ExternalLoginCoordinator coordinator = coordinator(method, store)) {
            ExternalLoginAttemptSnapshot started =
                    coordinator.start(method.descriptor().methodId(), ExternalLoginMode.BROWSER);
            assertThat(storeEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(coordinator.find(started.attemptId()).state()).isEqualTo(ExternalLoginAttemptState.STORING);

            releaseStore.countDown();
            ExternalLoginAttemptSnapshot failed = awaitTerminal(coordinator, started.attemptId());
            assertThat(failed.state()).isEqualTo(ExternalLoginAttemptState.FAILED);
            assertThat(failed.reasonCode()).contains("AUTH_STORE_FAILED");
            assertThat(failed.toString()).doesNotContain("filesystem secret detail");
        } finally {
            releaseStore.countDown();
        }
    }

    @Test
    void cancelFailureStillPublishesTerminalStateAndClosesExactlyOnce() throws Exception {
        InMemoryStore store = new InMemoryStore();
        FakeMethod method = new FakeMethod("future-login", true, true);
        try (ExternalLoginCoordinator coordinator = coordinator(method, store)) {
            ExternalLoginAttemptSnapshot started =
                    coordinator.start(method.descriptor().methodId(), ExternalLoginMode.BROWSER);
            assertThat(method.entered.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> coordinator.cancel(started.attemptId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("AUTH_CANCEL_FAILED");
            assertThat(coordinator.find(started.attemptId()).state()).isEqualTo(ExternalLoginAttemptState.CANCELLED);
            assertThat(method.closed).hasValue(1);
        }
    }

    @Test
    void browserAuthorizationUrlUsesAOneTimeChannelOutsideTheAttemptSnapshot() throws Exception {
        InMemoryStore store = new InMemoryStore();
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExternalLoginMethod method = new FakeMethod("future-login", false) {
            @Override
            StoredExternalCredential execute(ExternalLoginOperationContext context) {
                context.progressSink()
                        .accept(new ExternalLoginAttemptSnapshot(
                                context.attemptId(),
                                descriptor().methodId(),
                                ExternalLoginMode.BROWSER,
                                ExternalLoginAttemptState.WAITING_USER,
                                Optional.empty(),
                                Optional.empty(),
                                5_000,
                                Optional.empty()));
                context.browserAuthorizationSink()
                        .accept(URI.create("https://auth.example/oauth/authorize?client_id=test&state=state"));
                published.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("AUTH_CANCELLED");
                }
                return super.execute(context);
            }
        };
        try (ExternalLoginCoordinator coordinator = coordinator(method, store)) {
            ExternalLoginAttemptSnapshot started =
                    coordinator.start(method.descriptor().methodId(), ExternalLoginMode.BROWSER);
            assertThat(published.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(coordinator.find(started.attemptId()).verificationUri()).isEmpty();
            assertThat(coordinator.takeBrowserAuthorizationUri(started.attemptId()))
                    .contains(URI.create("https://auth.example/oauth/authorize?client_id=test&state=state"));
            assertThat(coordinator.takeBrowserAuthorizationUri(started.attemptId()))
                    .isEmpty();

            release.countDown();
            assertThat(awaitTerminal(coordinator, started.attemptId()).state())
                    .isEqualTo(ExternalLoginAttemptState.SUCCEEDED);
        } finally {
            release.countDown();
        }
    }

    private static ExternalLoginCoordinator coordinator(ExternalLoginMethod method, InMemoryStore store) {
        List<String> ids = new ArrayList<>(
                List.of("01890f6c-7b2a-7cc0-8000-000000000001", "01890f6c-7b2a-7cc0-8000-000000000002"));
        return new ExternalLoginCoordinator(
                new ExternalLoginRegistry(List.of(method)),
                store,
                () -> new ExternalLoginAttemptId(ids.removeFirst()),
                CLOCK,
                Executors.newFixedThreadPool(2),
                uri -> true,
                4);
    }

    private static ExternalLoginAttemptSnapshot awaitTerminal(
            ExternalLoginCoordinator coordinator, ExternalLoginAttemptId attemptId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            ExternalLoginAttemptSnapshot snapshot = coordinator.find(attemptId);
            if (Set.of(
                            ExternalLoginAttemptState.SUCCEEDED,
                            ExternalLoginAttemptState.FAILED,
                            ExternalLoginAttemptState.CANCELLED,
                            ExternalLoginAttemptState.EXPIRED)
                    .contains(snapshot.state())) {
                return snapshot;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("attempt did not complete");
    }

    private static class FakeMethod implements ExternalLoginMethod {
        private final ExternalLoginMethodDescriptor descriptor;
        private final boolean block;
        private final boolean cancelFails;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private final AtomicInteger closed = new AtomicInteger();

        private FakeMethod(String id, boolean block) {
            this(id, block, false);
        }

        private FakeMethod(String id, boolean block, boolean cancelFails) {
            this.descriptor = new ExternalLoginMethodDescriptor(
                    new ExternalLoginMethodId(id), id, Set.of(ExternalLoginMode.BROWSER), false, Optional.empty());
            this.block = block;
            this.cancelFails = cancelFails;
        }

        @Override
        public ExternalLoginMethodDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public ExternalLoginOperation create(ExternalLoginMode mode, ExternalLoginOperationContext context) {
            return new ExternalLoginOperation() {
                @Override
                public ExternalLoginAttemptSnapshot snapshot() {
                    return new ExternalLoginAttemptSnapshot(
                            context.attemptId(),
                            descriptor.methodId(),
                            mode,
                            ExternalLoginAttemptState.CREATED,
                            Optional.empty(),
                            Optional.empty(),
                            5_000,
                            Optional.empty());
                }

                @Override
                public StoredExternalCredential execute() {
                    return FakeMethod.this.execute(context);
                }

                @Override
                public void cancel() {
                    released.countDown();
                    if (cancelFails) throw new IllegalStateException("AUTH_CANCEL_FAILED");
                }

                @Override
                public void close() {
                    closed.incrementAndGet();
                    closedLatch.countDown();
                    released.countDown();
                }
            };
        }

        StoredExternalCredential execute(ExternalLoginOperationContext context) {
            entered.countDown();
            if (block) {
                try {
                    released.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("AUTH_CANCELLED");
                }
            }
            return new StoredExternalCredential(
                    LocalModelAuthReference.parse("model-auth://future/default"),
                    descriptor.methodId(),
                    "registration",
                    "access",
                    "refresh",
                    4_000,
                    1_000,
                    "account");
        }

        @Override
        public StoredExternalCredential refresh(StoredExternalCredential credential, Instant refreshBefore) {
            return credential;
        }

        @Override
        public void revoke(StoredExternalCredential credential) {}
    }

    private static class InMemoryStore implements LocalModelAuthStore {
        private final Map<LocalModelAuthReference, StoredModelCredential> values = new LinkedHashMap<>();

        @Override
        public synchronized Optional<StoredModelCredential> find(LocalModelAuthReference reference) {
            return Optional.ofNullable(values.get(reference));
        }

        @Override
        public synchronized List<LocalModelConnectionView> listSafe() {
            return values.values().stream().map(value -> value.safeView(false)).toList();
        }

        @Override
        public synchronized void save(StoredModelCredential credential) {
            values.put(credential.reference(), credential);
        }

        @Override
        public synchronized boolean delete(LocalModelAuthReference reference) {
            return values.remove(reference) != null;
        }
    }
}
