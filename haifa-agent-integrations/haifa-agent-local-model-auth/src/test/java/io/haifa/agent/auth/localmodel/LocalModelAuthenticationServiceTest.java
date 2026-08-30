package io.haifa.agent.auth.localmodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.model.api.CredentialRef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LocalModelAuthenticationServiceTest {
    @Test
    void savesListsAndDeletesApiKeyWhileClearingCallerBuffer() {
        InMemoryStore store = new InMemoryStore();
        var service = service(store, Map.of());
        char[] secret = "test-secret-canary".toCharArray();

        LocalModelConnectionView saved = service.saveApiKey("DeepSeek", secret);

        assertThat(secret).containsOnly('\0');
        assertThat(saved.connectionId().value()).isEqualTo("model-auth://deepseek/default");
        assertThat(service.connections()).containsExactly(saved);
        assertThat(service.connectionRequired(
                        new CredentialRef(saved.connectionId().value())))
                .isFalse();
        assertThat(service.logout(saved.connectionId().value())).isTrue();
        assertThat(service.connectionRequired(
                        new CredentialRef(saved.connectionId().value())))
                .isTrue();
        assertThat(saved.toString()).doesNotContain("test-secret-canary");
    }

    @Test
    void rejectsInvalidSecretAndAlwaysClearsIt() {
        InMemoryStore store = new InMemoryStore();
        var service = service(store, Map.of());
        char[] empty = new char[0];

        assertThatThrownBy(() -> service.saveApiKey("deepseek", empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AUTH_SECRET_INVALID");
        assertThat(empty).isEmpty();
        assertThat(store.values).isEmpty();
    }

    @Test
    void checksEnvironmentReferencesWithoutPersistingSecrets() {
        var service = service(new InMemoryStore(), Map.of("DEEPSEEK_API_KEY", "present"));

        assertThat(service.connectionRequired(new CredentialRef("env://DEEPSEEK_API_KEY")))
                .isFalse();
        assertThat(service.connectionRequired(new CredentialRef("env://MISSING")))
                .isTrue();
        assertThatThrownBy(() -> service.connectionRequired(new CredentialRef("vault://model")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AUTH_CREDENTIAL_REFERENCE_UNSUPPORTED");
        assertThatThrownBy(() -> service.connectionRequired(new CredentialRef("env://BAD-NAME")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AUTH_ENVIRONMENT_REFERENCE_INVALID");
    }

    @Test
    void findsCodexAccountIdForValidatedExternalCredential() {
        InMemoryStore store = new InMemoryStore();
        var service = service(store, Map.of());
        LocalModelAuthReference ref = LocalModelAuthReference.parse("model-auth://openai-codex/default");
        StoredExternalCredential cred = new StoredExternalCredential(
                ref,
                ExternalLoginMethodId.OPENAI_CODEX,
                "reg-1",
                "access-token",
                "refresh-token",
                System.currentTimeMillis() + 100000,
                System.currentTimeMillis(),
                "account-12345");
        store.save(cred);

        assertThat(service.findCodexAccountId(new CredentialRef(ref.value()))).contains("account-12345");
        assertThat(service.findCodexAccountId(new CredentialRef("env://OTHER"))).isEmpty();
        assertThat(service.findCodexAccountId(new CredentialRef("model-auth://openai-codex/nonexistent")))
                .isEmpty();
    }

    private static LocalModelAuthenticationService service(InMemoryStore store, Map<String, String> environment) {
        return new LocalModelAuthenticationService(
                store,
                Optional.empty(),
                reference -> {
                    throw new AssertionError("credential resolution is not expected");
                },
                environment::get);
    }

    private static final class InMemoryStore implements LocalModelAuthStore {
        private final Map<LocalModelAuthReference, StoredModelCredential> values = new LinkedHashMap<>();

        @Override
        public Optional<StoredModelCredential> find(LocalModelAuthReference reference) {
            return Optional.ofNullable(values.get(reference));
        }

        @Override
        public List<LocalModelConnectionView> listSafe() {
            return values.values().stream().map(value -> value.safeView(false)).toList();
        }

        @Override
        public void save(StoredModelCredential credential) {
            values.put(credential.reference(), credential);
        }

        @Override
        public boolean delete(LocalModelAuthReference reference) {
            return values.remove(reference) != null;
        }
    }
}
