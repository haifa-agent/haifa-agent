package io.haifa.agent.auth.localmodel.antigravity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AntigravityLocalCompatibilityRegistrationFactoryTest {
    @Test
    void createsDefaultWhenNoOverride() {
        Optional<AntigravityOAuthClientRegistration> reg =
                AntigravityLocalCompatibilityRegistrationFactory.create(Map.of());

        assertThat(reg).isPresent();
        assertThat(reg.get().clientId()).isEqualTo(AntigravityLocalCompatibilityRegistrationFactory.DEFAULT_CLIENT_ID);
        assertThat(reg.get().userAgent()).isEqualTo("Antigravity");
    }

    @Test
    void appliesEnvironmentOverrides() {
        Optional<AntigravityOAuthClientRegistration> reg =
                AntigravityLocalCompatibilityRegistrationFactory.create(Map.of(
                        "HAIFA_ANTIGRAVITY_AUTH_ENABLED",
                        "true",
                        "HAIFA_ANTIGRAVITY_OAUTH_CLIENT_ID",
                        "custom-client-id",
                        "HAIFA_ANTIGRAVITY_OAUTH_CLIENT_SECRET",
                        "custom-secret",
                        "HAIFA_ANTIGRAVITY_USER_AGENT",
                        "CustomUserAgent/1.0"));

        assertThat(reg).isPresent();
        assertThat(reg.get().clientId()).isEqualTo("custom-client-id");
        assertThat(reg.get().userAgent()).isEqualTo("CustomUserAgent/1.0");
    }
}
