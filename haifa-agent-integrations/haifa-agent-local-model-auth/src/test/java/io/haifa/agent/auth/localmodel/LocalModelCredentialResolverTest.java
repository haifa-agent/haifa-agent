package io.haifa.agent.auth.localmodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.model.api.CredentialRef;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LocalModelCredentialResolverTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC);

    @Test
    void resolvesEnvironmentAndApiKeyWithoutLeakingThroughValueObjectString() {
        InMemoryStore store = new InMemoryStore();
        LocalModelAuthReference reference = LocalModelAuthReference.parse("model-auth://deepseek/default");
        store.save(new StoredApiKeyCredential(reference, "api-key-canary"));
        LocalModelCredentialResolver resolver =
                resolver(store, new ExternalLoginRegistry(List.of()), Map.of("KEY", "env-secret"));

        assertThat(resolver.resolve(new CredentialRef("env://KEY")).value()).isEqualTo("env-secret");
        assertThat(resolver.resolve(new CredentialRef(reference.value())).value())
                .isEqualTo("api-key-canary");
        assertThat(resolver.resolve(new CredentialRef(reference.value())).toString())
                .doesNotContain("api-key-canary");
        assertThatThrownBy(() -> resolver.resolve(new CredentialRef("coding-auth://deepseek/default")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AUTH_CREDENTIAL_SCHEME_UNSUPPORTED");
    }

    @Test
    void oneHundredConcurrentResolutionsShareOneRefreshAndPersistRotation() throws Exception {
        InMemoryStore store = new InMemoryStore();
        LocalModelAuthReference reference = LocalModelAuthReference.parse("model-auth://future/default");
        ExternalLoginMethodId methodId = new ExternalLoginMethodId("future-login");
        store.save(new StoredExternalCredential(
                reference, methodId, "registration", "old-access", "old-refresh", 2_000, 500, "account"));
        RefreshMethod method = new RefreshMethod(methodId, "registration");
        LocalModelCredentialResolver resolver = resolver(store, new ExternalLoginRegistry(List.of(method)), Map.of());
        List<Callable<String>> tasks = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            tasks.add(
                    () -> resolver.resolve(new CredentialRef(reference.value())).value());
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(tasks);
            for (var result : results) assertThat(result.get()).isEqualTo("new-access");
        }
        assertThat(method.refreshes).hasValue(1);
        assertThat(store.find(reference)).get().isInstanceOfSatisfying(StoredExternalCredential.class, credential -> {
            assertThat(credential.refreshToken()).isEqualTo("new-refresh");
            assertThat(credential.accessToken()).isEqualTo("new-access");
        });
    }

    @Test
    void routesByMethodAndFailsClosedForUnknownOrChangedRegistration() {
        InMemoryStore store = new InMemoryStore();
        LocalModelAuthReference reference = LocalModelAuthReference.parse("model-auth://future/default");
        ExternalLoginMethodId methodId = new ExternalLoginMethodId("future-login");
        store.save(new StoredExternalCredential(
                reference, methodId, "wrong-registration", "old-access", "old-refresh", 2_000, 500, "account"));
        LocalModelCredentialResolver resolver = resolver(
                store, new ExternalLoginRegistry(List.of(new RefreshMethod(methodId, "registration"))), Map.of());

        assertThatThrownBy(() -> resolver.resolve(new CredentialRef(reference.value())))
                .isInstanceOf(ExternalLoginMethodUnavailableException.class)
                .hasMessage("AUTH_REAUTH_REQUIRED");

        InMemoryStore unknownStore = new InMemoryStore();
        unknownStore.save(new StoredExternalCredential(
                reference, methodId, "registration", "old-access", "old-refresh", 2_000, 500, "account"));
        assertThatThrownBy(() -> resolver(unknownStore, new ExternalLoginRegistry(List.of()), Map.of())
                        .resolve(new CredentialRef(reference.value())))
                .isInstanceOf(ExternalLoginMethodUnavailableException.class)
                .hasMessage("AUTH_LOGIN_METHOD_UNAVAILABLE");
    }

    @Test
    void preparesAValidPersistedExternalCredentialBeforeReturningItsToken() {
        InMemoryStore store = new InMemoryStore();
        LocalModelAuthReference reference = LocalModelAuthReference.parse("model-auth://future/default");
        ExternalLoginMethodId methodId = new ExternalLoginMethodId("future-login");
        store.save(new StoredExternalCredential(
                reference, methodId, "registration", "old-access", "old-refresh", 10_000, 500, "account"));
        RefreshMethod method = new RefreshMethod(methodId, "registration");
        LocalModelCredentialResolver resolver = resolver(store, new ExternalLoginRegistry(List.of(method)), Map.of());

        assertThat(resolver.resolve(new CredentialRef(reference.value())).value())
                .isEqualTo("old-access");
        assertThat(method.preparations).hasValue(1);
        assertThat(method.refreshes).hasValue(0);
    }

    private static LocalModelCredentialResolver resolver(
            InMemoryStore store, ExternalLoginRegistry registry, Map<String, String> environment) {
        return new LocalModelCredentialResolver(environment::get, store, registry, CLOCK, Duration.ofSeconds(5));
    }

    private static final class RefreshMethod implements ExternalLoginMethod {
        private final ExternalLoginMethodDescriptor descriptor;
        private final String registration;
        private final AtomicInteger refreshes = new AtomicInteger();
        private final AtomicInteger preparations = new AtomicInteger();

        private RefreshMethod(ExternalLoginMethodId id, String registration) {
            this.descriptor = new ExternalLoginMethodDescriptor(
                    id, id.value(), Set.of(ExternalLoginMode.BROWSER), false, Optional.empty());
            this.registration = registration;
        }

        @Override
        public ExternalLoginMethodDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public ExternalLoginOperation create(ExternalLoginMode mode, ExternalLoginOperationContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void prepare(StoredExternalCredential credential) {
            preparations.incrementAndGet();
        }

        @Override
        public StoredExternalCredential refresh(StoredExternalCredential credential, Instant refreshBefore) {
            refreshes.incrementAndGet();
            if (!registration.equals(credential.clientRegistrationRef())) {
                throw new ExternalLoginMethodUnavailableException("AUTH_REAUTH_REQUIRED");
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ExternalLoginMethodUnavailableException("AUTH_CANCELLED");
            }
            return new StoredExternalCredential(
                    credential.reference(),
                    credential.methodId(),
                    credential.clientRegistrationRef(),
                    "new-access",
                    "new-refresh",
                    10_000,
                    1_000,
                    credential.accountId());
        }

        @Override
        public void revoke(StoredExternalCredential credential) {}
    }

    private static final class InMemoryStore implements LocalModelAuthStore {
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
