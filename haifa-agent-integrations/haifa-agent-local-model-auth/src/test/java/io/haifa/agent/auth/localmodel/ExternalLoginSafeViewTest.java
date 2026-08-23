package io.haifa.agent.auth.localmodel;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExternalLoginSafeViewTest {
    private static final String ATTEMPT_ID = "01890f6c-7b2a-7cc0-8000-000000000001";

    @Test
    void safeViewsAndStoredCredentialStringsNeverContainCanaries() {
        String access = "access-token-canary";
        String refresh = "refresh-token-canary";
        String client = "client-id-canary";
        String account = "account-canary";
        StoredExternalCredential credential = new StoredExternalCredential(
                LocalModelAuthReference.parse("model-auth://openai-codex/default"),
                ExternalLoginMethodId.OPENAI_CODEX,
                client,
                access,
                refresh,
                2_000,
                1_000,
                account);
        LocalModelConnectionView connection = credential.safeView(true);
        ExternalLoginMethodDescriptor descriptor = new ExternalLoginMethodDescriptor(
                ExternalLoginMethodId.OPENAI_CODEX,
                "ChatGPT sign-in",
                Set.of(ExternalLoginMode.BROWSER),
                true,
                Optional.empty());
        ExternalLoginAttemptSnapshot attempt = new ExternalLoginAttemptSnapshot(
                new ExternalLoginAttemptId(ATTEMPT_ID),
                ExternalLoginMethodId.OPENAI_CODEX,
                ExternalLoginMode.DEVICE_CODE,
                ExternalLoginAttemptState.WAITING_USER,
                Optional.of(URI.create("https://auth.openai.com/codex/device")),
                Optional.of("ABCD-1234"),
                2_000,
                Optional.empty());

        String combined = credential + " " + connection + " " + descriptor + " " + attempt;
        assertThat(combined)
                .doesNotContain(
                        access, refresh, client, account, "authorization_code", "code_verifier", "oauth/authorize?");
    }

    @Test
    void apiKeyStringIsRedacted() {
        StoredApiKeyCredential key = new StoredApiKeyCredential(
                LocalModelAuthReference.parse("model-auth://deepseek/default"), "api-key-canary");

        assertThat(key.toString()).doesNotContain("api-key-canary");
        assertThat(key.safeView(false).expiresAtEpochMillis()).isEqualTo(OptionalLong.empty());
    }
}
