package io.haifa.agent.auth.localmodel.antigravity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class AntigravityOAuthClientRegistrationTest {
    @Test
    void officialEndpointsAndDefaultsPassValidation() {
        AntigravityOAuthClientRegistration registration =
                AntigravityLocalCompatibilityRegistrationFactory.createDefault();

        assertThat(registration.clientId())
                .isEqualTo(AntigravityLocalCompatibilityRegistrationFactory.DEFAULT_CLIENT_ID);
        assertThat(registration.scopes()).containsAll(AntigravityOAuthClientRegistration.DEFAULT_SCOPES);
        assertThat(registration.toString())
                .doesNotContain(AntigravityLocalCompatibilityRegistrationFactory.DEFAULT_CLIENT_SECRET);
        assertThat(registration.toString()).contains("<redacted>");
    }

    @Test
    void rejectsDisallowedEndpointsWhenLoopbackStubDisabled() {
        assertThatThrownBy(() -> new AntigravityOAuthClientRegistration(
                        "custom-reg",
                        "client-id",
                        "client-secret",
                        URI.create("https://malicious.com/oauth/auth"),
                        AntigravityOAuthClientRegistration.OFFICIAL_TOKEN_ENDPOINT,
                        AntigravityOAuthClientRegistration.OFFICIAL_USER_INFO_ENDPOINT,
                        AntigravityOAuthClientRegistration.OFFICIAL_CLOUDCODE_ENDPOINT,
                        AntigravityOAuthClientRegistration.OFFICIAL_DAILY_CLOUDCODE_ENDPOINT,
                        AntigravityOAuthClientRegistration.OFFICIAL_REDIRECT_URI,
                        List.of("scope1"),
                        "Antigravity",
                        true,
                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Antigravity OAuth endpoints are not approved");
    }

    @Test
    void allowsLoopbackStubWhenFlagEnabled() {
        AntigravityOAuthClientRegistration stub = new AntigravityOAuthClientRegistration(
                "stub-reg",
                "client-id",
                "client-secret",
                URI.create("http://127.0.0.1:51120/auth"),
                URI.create("http://127.0.0.1:51120/token"),
                URI.create("http://127.0.0.1:51120/userinfo"),
                URI.create("http://127.0.0.1:51120/cloudcode"),
                URI.create("http://127.0.0.1:51120/daily"),
                URI.create("http://127.0.0.1:51121/oauth-callback"),
                List.of("scope1"),
                "Antigravity",
                true,
                true);

        assertThat(stub.reference()).isEqualTo("stub-reg");
        assertThat(stub.allowLoopbackStub()).isTrue();
    }
}
