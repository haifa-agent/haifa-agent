package io.haifa.agent.auth.localmodel.antigravity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AntigravityOAuthClientRegistrationTest {
    @Test
    void officialEndpointsAndInjectedRegistrationPassValidation() {
        AntigravityOAuthClientRegistration registration = AntigravityLocalCompatibilityRegistrationFactory.create(
                        Map.of(
                                "HAIFA_ANTIGRAVITY_LOCAL_COMPAT_TEST",
                                "true",
                                "HAIFA_ANTIGRAVITY_OAUTH_CLIENT_ID",
                                "injected-client",
                                "HAIFA_ANTIGRAVITY_OAUTH_CLIENT_SECRET",
                                "injected-secret"))
                .orElseThrow();

        assertThat(registration.clientId()).isEqualTo("injected-client");
        assertThat(registration.scopes()).containsAll(AntigravityOAuthClientRegistration.DEFAULT_SCOPES);
        assertThat(registration.toString()).doesNotContain("injected-secret");
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
                        false,
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
                true,
                true);

        assertThat(stub.reference()).isEqualTo("stub-reg");
        assertThat(stub.allowLoopbackStub()).isTrue();
    }
}
