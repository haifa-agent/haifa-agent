package io.haifa.agent.auth.localmodel.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.auth.localmodel.ExternalLoginMethodUnavailableException;
import io.haifa.agent.auth.localmodel.ExternalLoginMode;
import io.haifa.agent.auth.localmodel.LocalModelAuthReference;
import io.haifa.agent.auth.localmodel.StoredExternalCredential;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CodexExternalLoginMethodTest {
    @Test
    void descriptorIsSafeAndRefreshRejectsAnotherRegistrationBeforeNetwork() {
        CodexOAuthClientRegistration registration = registration();
        CodexExternalLoginMethod method = new CodexExternalLoginMethod(
                registration,
                new CodexTokenClient(
                        HttpClient.newHttpClient(),
                        new ObjectMapper(),
                        Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
                        Duration.ofSeconds(1),
                        registration),
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                SecureRandom::new,
                Duration.ofMinutes(5),
                duration -> {});
        StoredExternalCredential credential = new StoredExternalCredential(
                LocalModelAuthReference.parse("model-auth://openai-codex/default"),
                CodexExternalLoginMethod.METHOD_ID,
                "another-registration",
                "access",
                "refresh",
                2_000,
                1_000,
                "account");

        assertThat(method.descriptor().supportedModes())
                .containsExactlyInAnyOrder(ExternalLoginMode.BROWSER, ExternalLoginMode.DEVICE_CODE);
        assertThat(method.descriptor().toString())
                .doesNotContain(
                        registration.clientId(), registration.tokenEndpoint().toString());
        assertThatThrownBy(() -> method.refresh(credential, Instant.ofEpochMilli(1_500)))
                .isInstanceOf(ExternalLoginMethodUnavailableException.class)
                .hasMessage("AUTH_REAUTH_REQUIRED");
    }

    private static CodexOAuthClientRegistration registration() {
        return new CodexOAuthClientRegistration(
                "stub-registration",
                "client-canary",
                URI.create("http://127.0.0.1:34561/oauth/authorize"),
                URI.create("http://127.0.0.1:34561/oauth/token"),
                URI.create("http://127.0.0.1:34562/auth/callback"),
                URI.create("http://127.0.0.1:34561/backend-api/codex"),
                "haifa-stub",
                "haifa-test/1",
                true,
                true);
    }
}
