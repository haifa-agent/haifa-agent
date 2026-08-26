package io.haifa.agent.auth.localmodel.antigravity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.auth.localmodel.ExternalLoginMethodId;
import io.haifa.agent.auth.localmodel.ExternalLoginMethodUnavailableException;
import io.haifa.agent.auth.localmodel.ExternalLoginMode;
import io.haifa.agent.auth.localmodel.LocalModelAuthReference;
import io.haifa.agent.auth.localmodel.StoredExternalCredential;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AntigravityExternalLoginMethodTest {
    @Test
    void descriptorIsSafeAndRefreshRejectsAnotherRegistrationBeforeNetwork() {
        AntigravityOAuthClientRegistration registration =
                AntigravityLocalCompatibilityRegistrationFactory.createDefault();
        AntigravityTokenClient tokens = new AntigravityTokenClient(
                HttpClient.newHttpClient(), new ObjectMapper(), Clock.systemUTC(), Duration.ofSeconds(5), registration);
        AntigravityExternalLoginMethod method =
                new AntigravityExternalLoginMethod(registration, tokens, SecureRandom::new, Duration.ofMinutes(5));

        assertThat(method.descriptor().methodId()).isEqualTo(ExternalLoginMethodId.GOOGLE_ANTIGRAVITY);
        assertThat(method.descriptor().supportedModes()).containsExactly(ExternalLoginMode.BROWSER);

        StoredExternalCredential foreign = new StoredExternalCredential(
                LocalModelAuthReference.parse("model-auth://google-antigravity/foreign"),
                ExternalLoginMethodId.GOOGLE_ANTIGRAVITY,
                "other-registration",
                "access",
                "refresh",
                5_000,
                1_000,
                "user@example.com");

        assertThatThrownBy(() -> method.refresh(foreign, Instant.now()))
                .isInstanceOf(ExternalLoginMethodUnavailableException.class)
                .hasMessage("AUTH_REAUTH_REQUIRED");
    }
}
