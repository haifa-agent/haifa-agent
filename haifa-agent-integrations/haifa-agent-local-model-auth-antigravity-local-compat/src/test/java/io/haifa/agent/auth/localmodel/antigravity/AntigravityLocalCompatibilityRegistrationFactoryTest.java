package io.haifa.agent.auth.localmodel.antigravity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AntigravityLocalCompatibilityRegistrationFactoryTest {
    @Test
    void requiresExplicitGateAndExternallySuppliedClientRegistration() {
        assertThat(AntigravityLocalCompatibilityRegistrationFactory.create(Map.of()))
                .isEmpty();
        assertThatThrownBy(() -> AntigravityLocalCompatibilityRegistrationFactory.create(
                        Map.of("HAIFA_ANTIGRAVITY_LOCAL_COMPAT_TEST", "true")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HAIFA_ANTIGRAVITY_OAUTH_CLIENT_ID");
    }

    @Test
    void appliesEnvironmentOverrides() {
        Optional<AntigravityOAuthClientRegistration> reg =
                AntigravityLocalCompatibilityRegistrationFactory.create(Map.of(
                        "HAIFA_ANTIGRAVITY_LOCAL_COMPAT_TEST",
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
        assertThat(reg.get().allowOnboarding()).isFalse();
    }

    @Test
    void onboardingRequiresASeparateExplicitGate() {
        AntigravityOAuthClientRegistration registration = AntigravityLocalCompatibilityRegistrationFactory.create(
                        Map.of(
                                "HAIFA_ANTIGRAVITY_LOCAL_COMPAT_TEST",
                                "true",
                                "HAIFA_ANTIGRAVITY_OAUTH_CLIENT_ID",
                                "custom-client-id",
                                "HAIFA_ANTIGRAVITY_OAUTH_CLIENT_SECRET",
                                "custom-secret",
                                "HAIFA_ANTIGRAVITY_ALLOW_ONBOARDING",
                                "true"))
                .orElseThrow();

        assertThat(registration.allowOnboarding()).isTrue();
    }
}
