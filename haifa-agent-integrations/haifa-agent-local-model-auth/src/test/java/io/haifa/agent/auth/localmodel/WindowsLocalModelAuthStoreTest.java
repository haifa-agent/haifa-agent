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

    @Test
    void payloadNearBlobLimitSucceeds() {
        var ref = LocalModelAuthReference.parse("model-auth://openai/large");
        // Create an API key that results in a total payload near 2500 bytes (limit is 2560)
        String largeKey = "k".repeat(2450);
        var credential = new StoredApiKeyCredential(ref, largeKey);

        store.save(credential);

        var found = store.find(ref);
        assertThat(found).isPresent();
        assertThat(((StoredApiKeyCredential) found.get()).apiKey()).isEqualTo(largeKey);
    }

    @Test
    void payloadExceedingBlobLimitThrowsItemTooLargeException() {
        var ref = LocalModelAuthReference.parse("model-auth://openai/oversized");
        // Create an API key that exceeds 2560 bytes
        String oversizedKey = "k".repeat(3000);
        var credential = new StoredApiKeyCredential(ref, oversizedKey);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.save(credential))
                .isInstanceOf(WindowsCredentialManagerException.class)
                .satisfies(exception -> {
                    var winEx = (WindowsCredentialManagerException) exception;
                    assertThat(winEx.reason()).isEqualTo(WindowsCredentialManagerException.Reason.ITEM_TOO_LARGE);
                });
    }

    @Test
    void mapsWindowsErrorCodesToStableProductReasons() {
        assertThat(WindowsCredentialManagerException.mapErrorCode(1168))
                .isEqualTo(WindowsCredentialManagerException.Reason.MISSING);
        assertThat(WindowsCredentialManagerException.mapErrorCode(5))
                .isEqualTo(WindowsCredentialManagerException.Reason.ACCESS_DENIED);
        assertThat(WindowsCredentialManagerException.mapErrorCode(87))
                .isEqualTo(WindowsCredentialManagerException.Reason.ITEM_TOO_LARGE);
        assertThat(WindowsCredentialManagerException.mapErrorCode(111))
                .isEqualTo(WindowsCredentialManagerException.Reason.ITEM_TOO_LARGE);
        assertThat(WindowsCredentialManagerException.mapErrorCode(1312))
                .isEqualTo(WindowsCredentialManagerException.Reason.UNAVAILABLE);
        assertThat(WindowsCredentialManagerException.mapErrorCode(99999))
                .isEqualTo(WindowsCredentialManagerException.Reason.STORE_FAILURE);
    }

    @Test
    void listSafeReturnsEmptyWhenStoreUnavailable() {
        var unavailableStore =
                new WindowsLocalModelAuthStore(UnavailableWindowsCredentialManagerClient.INSTANCE, new ObjectMapper());
        assertThat(unavailableStore.listSafe()).isEmpty();
    }
}
