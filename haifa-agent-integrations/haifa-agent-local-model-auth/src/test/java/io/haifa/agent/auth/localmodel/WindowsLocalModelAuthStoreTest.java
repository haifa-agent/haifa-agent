package io.haifa.agent.auth.localmodel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WindowsLocalModelAuthStoreTest {
    private InMemoryWindowsCredentialManagerClient client;
    private WindowsLocalModelAuthStore store;

    @BeforeEach
    void setUp() {
        client = new InMemoryWindowsCredentialManagerClient();
        store = new WindowsLocalModelAuthStore(client, new ObjectMapper());
    }

    @Test
    void saveAndFindApiKeyCredential() {
        var reference = LocalModelAuthReference.parse("model-auth://deepseek/default");
        var credential = new StoredApiKeyCredential(reference, "test-api-key");

        store.save(credential);

        var found = store.find(reference);
        assertThat(found).isPresent();
        assertThat(found.get()).isInstanceOf(StoredApiKeyCredential.class);
        var apiKeyCred = (StoredApiKeyCredential) found.get();
        assertThat(apiKeyCred.apiKey()).isEqualTo("test-api-key");
        assertThat(apiKeyCred.reference()).isEqualTo(reference);

        // Verify storage format in Windows Credential Manager target
        assertThat(client.snapshot())
                .containsKey("haifa:model-auth:deepseek/default")
                .hasEntrySatisfying("haifa:model-auth:deepseek/default", payload -> {
                    assertThat(payload).contains("\"kind\":\"API_KEY\"");
                    assertThat(payload).contains("\"api_key\":\"test-api-key\"");
                });
    }

    @Test
    void saveAndFindExternalCredential() {
        var reference = LocalModelAuthReference.parse("model-auth://openai-codex/default");
        var credential = new StoredExternalCredential(
                reference,
                new ExternalLoginMethodId("openai-codex"),
                "client-ref-1",
                "access-token-123",
                "refresh-token-456",
                1700000000000L,
                1699990000000L,
                "user-account-1",
                Optional.of("AUTH_OK"));

        store.save(credential);

        var found = store.find(reference);
        assertThat(found).isPresent();
        assertThat(found.get()).isInstanceOf(StoredExternalCredential.class);
        var externalCred = (StoredExternalCredential) found.get();
        assertThat(externalCred.accessToken()).isEqualTo("access-token-123");
        assertThat(externalCred.refreshToken()).isEqualTo("refresh-token-456");
        assertThat(externalCred.accountId()).isEqualTo("user-account-1");
        assertThat(externalCred.reasonCode()).contains("AUTH_OK");
    }

    @Test
    void listSafeReturnsSafeViews() {
        var ref1 = LocalModelAuthReference.parse("model-auth://deepseek/default");
        var ref2 = LocalModelAuthReference.parse("model-auth://openai-codex/default");

        store.save(new StoredApiKeyCredential(ref1, "key-1"));
        store.save(new StoredExternalCredential(
                ref2,
                new ExternalLoginMethodId("openai-codex"),
                "client-ref",
                "secret-token",
                "secret-refresh",
                1700000000000L,
                1699990000000L,
                "account-2",
                Optional.empty()));

        List<LocalModelConnectionView> views = store.listSafe();
        assertThat(views).hasSize(2);
        assertThat(views)
                .extracting(view -> view.connectionId().value())
                .containsExactlyInAnyOrder("model-auth://deepseek/default", "model-auth://openai-codex/default");

        // Verify that secrets are not in the safe views
        for (var view : views) {
            assertThat(view.toString()).doesNotContain("key-1", "secret-token", "secret-refresh");
        }
    }

    @Test
    void deleteRemovesTarget() {
        var ref = LocalModelAuthReference.parse("model-auth://deepseek/default");
        store.save(new StoredApiKeyCredential(ref, "key-1"));

        assertThat(store.delete(ref)).isTrue();
        assertThat(store.find(ref)).isEmpty();
        assertThat(store.delete(ref)).isFalse();
    }

    @Test
    void toAndFromTargetName() {
        var ref = LocalModelAuthReference.parse("model-auth://google-antigravity/default");
        String target = WindowsLocalModelAuthStore.toTargetName(ref);
        assertThat(target).isEqualTo("haifa:model-auth:google-antigravity/default");
        assertThat(WindowsLocalModelAuthStore.fromTargetName(target)).isEqualTo(ref);
    }
}
