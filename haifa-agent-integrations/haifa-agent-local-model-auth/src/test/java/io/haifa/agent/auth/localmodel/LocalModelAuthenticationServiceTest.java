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
    void savesApiKeyWithAttributesAndFindsCredential() {
        InMemoryStore store = new InMemoryStore();
        var service = service(store, Map.of());
        char[] secret = "test-secret-dashscope".toCharArray();

        LocalModelConnectionView saved = service.saveApiKey(
                "aliyun-bailian", secret, Map.of("workspace_id", "ws-prod-01", "region", "cn-beijing"));

        assertThat(secret).containsOnly('\0');
        assertThat(saved.connectionId().value()).isEqualTo("model-auth://aliyun-bailian/default");
        var found = service.findApiKeyCredential("aliyun-bailian");
        assertThat(found).isPresent();
        assertThat(found.get().apiKey()).isEqualTo("test-secret-dashscope");
        assertThat(found.get().workspaceId()).contains("ws-prod-01");
        assertThat(found.get().region()).contains("cn-beijing");
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
    void checksOsReferencesViaOsStore() {
        var osMap = Map.of("STORED_KEY", "secret-value");
        var service = new LocalModelAuthenticationService(
                new InMemoryStore(),
                Optional.empty(),
                reference -> {
                    throw new AssertionError("credential resolution is not expected");
                },
                name -> null,
                name -> Optional.ofNullable(osMap.get(name)));

        assertThat(service.connectionRequired(new CredentialRef("os://STORED_KEY")))
                .isFalse();
        assertThat(service.connectionRequired(new CredentialRef("os://MISSING_KEY")))
                .isTrue();

        assertThatThrownBy(() -> service.connectionRequired(new CredentialRef("os://invalid/name")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AUTH_OS_CREDENTIAL_REFERENCE_INVALID");
    }

    @Test
    void findsCodexAccountIdForValidatedExternalCredential() {
        InMemoryStore store = new InMemoryStore();
        var service = service(store, Map.of());
        LocalModelAuthReference ref = LocalModelAuthReference.parse("model-auth://openai-codex/default");
        StoredExternalCredential cred = new StoredExternalCredential(
                ref,
                new ExternalLoginMethodId("openai-codex"),
                "reg-1",
                "access-token",
                "refresh-token",
                System.currentTimeMillis() + 100000,
                System.currentTimeMillis(),
                "account-12345");
        store.save(cred);

        assertThat(service.findExternalAccountId(
                        new CredentialRef(ref.value()), new ExternalLoginMethodId("openai-codex")))
                .contains("account-12345");
        assertThat(service.findExternalAccountId(
                        new CredentialRef(ref.value()), new ExternalLoginMethodId("other-method")))
                .isEmpty();
        assertThat(service.findExternalAccountId(
                        new CredentialRef("env://OTHER"), new ExternalLoginMethodId("openai-codex")))
                .isEmpty();
        assertThat(service.findExternalAccountId(
                        new CredentialRef("model-auth://openai-codex/nonexistent"),
                        new ExternalLoginMethodId("openai-codex")))
                .isEmpty();
        assertThat(service.findExternalCredential(new CredentialRef(ref.value())))
                .contains(cred);
    }

    @Test
    void updatesAttributesWhileRetainingExistingApiKeyOnEmptySecret() {
        InMemoryStore store = new InMemoryStore();
        var service = service(store, Map.of());
        service.saveApiKey(
                "aliyun-bailian",
                "sk-original-key".toCharArray(),
                Map.of("workspace_id", "ws-old", "region", "cn-beijing"));

        var updated = service.saveApiKey(
                "aliyun-bailian", new char[0], Map.of("workspace_id", "ws-new", "region", "cn-hangzhou"));
        assertThat(updated.providerId()).isEqualTo("aliyun-bailian");

        var cred = service.findApiKeyCredential("aliyun-bailian").orElseThrow();
        assertThat(cred.apiKey()).isEqualTo("sk-original-key");
        assertThat(cred.workspaceId()).contains("ws-new");
        assertThat(cred.region()).contains("cn-hangzhou");
    }

    @Test
    void rejectsEmptySecretWithoutAttributesEvenWhenKeyIsStored() {
        InMemoryStore store = new InMemoryStore();
        var service = service(store, Map.of());
        service.saveApiKey("deepseek", "sk-original-key".toCharArray());

        assertThatThrownBy(() -> service.saveApiKey("deepseek", new char[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AUTH_SECRET_INVALID");
        assertThat(service.findApiKeyCredential("deepseek").orElseThrow().apiKey())
                .isEqualTo("sk-original-key");
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
